import Combine
import Foundation
import WebKit

struct CapturedCanvasSession: Equatable {
    let cookieValue: String
    let displayName: String
}

@MainActor
final class CanvasLoginController: NSObject, ObservableObject {
    @Published private(set) var pageHost = ""
    @Published private(set) var captureMessage = "等待统一认证登录"
    @Published private(set) var pendingSession: CapturedCanvasSession?

    let webView: WKWebView

    private let sessionBootstrapURL = URL(
        string: "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=2333&rollCallToken=2333"
    )!
    private let identityProbeURL = URL(
        string: "https://canvas.tongji.edu.cn/lms/mobile/forscan?courseCode=117029&rollCallToken=0000000000"
    )!
    private let sessionCookieName = "_canvas_middle_session"
    private var hasStarted = false
    private var hasVisitedAuthenticationProvider = false
    private var didRedirectToBootstrap = false
    private var isCapturingSession = false

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()

        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        webView.load(URLRequest(url: sessionBootstrapURL))
    }

    func restartLogin() {
        hasVisitedAuthenticationProvider = false
        didRedirectToBootstrap = false
        isCapturingSession = false
        pendingSession = nil
        captureMessage = "正在重新打开统一认证"
        let store = webView.configuration.websiteDataStore
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.webView.load(URLRequest(url: self.sessionBootstrapURL))
            }
        }
    }

    func sessionCookieValue() async -> String? {
        let cookies = await allCookies()
        return cookies.first {
            $0.name == sessionCookieName && cookieApplies($0, to: sessionBootstrapURL)
        }?.value
    }

    func pageText() async -> String {
        await withCheckedContinuation { continuation in
            webView.evaluateJavaScript("document.body ? document.body.innerText : ''") { value, _ in
                continuation.resume(returning: value as? String ?? "")
            }
        }
    }

    func markSessionSaved() {
        captureMessage = "账号已自动保存"
    }

    private func captureSession() {
        guard !isCapturingSession, pendingSession == nil else { return }
        isCapturingSession = true
        captureMessage = "正在获取并保存 Canvas 会话"

        Task {
            var cookie: String?
            for attempt in 0..<3 {
                cookie = await sessionCookieValue()
                if cookie != nil { break }
                if attempt < 2 { try? await Task.sleep(for: .seconds(1)) }
            }

            guard let cookie, !cookie.isEmpty else {
                captureMessage = "未获取到 Canvas 会话，请重新登录"
                isCapturingSession = false
                return
            }

            let displayName = await discoverDisplayName(cookieValue: cookie, pageText: pageText())
            pendingSession = CapturedCanvasSession(cookieValue: cookie, displayName: displayName)
            captureMessage = "正在保存账号"
            isCapturingSession = false
        }
    }

    private func discoverDisplayName(cookieValue: String, pageText: String) async -> String {
        if let displayName = Self.displayName(from: pageText) { return displayName }

        var request = URLRequest(url: identityProbeURL)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("_canvas_middle_session=\(cookieValue)", forHTTPHeaderField: "Cookie")
        request.setValue(
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0.0.0 Mobile Safari/537.36",
            forHTTPHeaderField: "User-Agent"
        )

        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }

        do {
            let (data, response) = try await session.data(for: request, delegate: IdentityProbeRedirectBlocker())
            guard let response = response as? HTTPURLResponse,
                  (200..<300).contains(response.statusCode) else { return "未命名" }
            return Self.displayName(from: Self.responseText(data)) ?? "未命名"
        } catch {
            return "未命名"
        }
    }

    private static func displayName(from pageText: String) -> String? {
        let name = capture(#"签到人员\s*[:：]\s*([^\s<&"'，,;；]+)"#, in: pageText)
        let studentID = capture(#"学号\s*/\s*工号\s*[:：]\s*([0-9A-Za-z]{4,20})"#, in: pageText)
        guard let name, let studentID else { return nil }
        return "\(studentID) \(name)"
    }

    private static func responseText(_ data: Data) -> String {
        var text = String(decoding: data, as: UTF8.self)
        for pattern in [#"(?is)<script\b[^>]*>.*?</script>"#, #"(?is)<style\b[^>]*>.*?</style>"#, #"<[^>]+>"#] {
            text = text.replacingOccurrences(of: pattern, with: " ", options: .regularExpression)
        }
        for (entity, value) in [("&nbsp;", " "), ("&#160;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\"")] {
            text = text.replacingOccurrences(of: entity, with: value)
        }
        return text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    }

    private static func capture(_ pattern: String, in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: text) else { return nil }
        return String(text[range])
    }

    private func allCookies() async -> [HTTPCookie] {
        await withCheckedContinuation { continuation in
            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                continuation.resume(returning: cookies)
            }
        }
    }

    private func cookieApplies(_ cookie: HTTPCookie, to url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard host == domain || host.hasSuffix("." + domain) else { return false }
        guard !cookie.isSecure || url.scheme?.lowercased() == "https" else { return false }
        if let expiry = cookie.expiresDate, expiry <= Date() { return false }

        let requestPath = url.path.isEmpty ? "/" : url.path
        let cookiePath = cookie.path.isEmpty ? "/" : cookie.path
        return requestPath == cookiePath
            || cookiePath == "/"
            || requestPath.hasPrefix(cookiePath.hasSuffix("/") ? cookiePath : cookiePath + "/")
    }

    private func isCanvasPage(_ url: URL) -> Bool {
        url.host?.lowercased() == sessionBootstrapURL.host?.lowercased()
    }

    private func isAuthenticationPage(_ url: URL) -> Bool {
        let path = url.path.lowercased()
        return path.contains("login") || path.contains("oauth") || path.contains("openid")
    }
}

private final class IdentityProbeRedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

extension CanvasLoginController: WKNavigationDelegate, WKUIDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let url = webView.url else { return }
        pageHost = url.host ?? ""

        guard hasVisitedAuthenticationProvider, isCanvasPage(url), !isAuthenticationPage(url) else { return }
        if url.path == sessionBootstrapURL.path {
            captureSession()
        } else if !didRedirectToBootstrap {
            didRedirectToBootstrap = true
            webView.load(URLRequest(url: sessionBootstrapURL))
        }
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        pageHost = "页面加载失败"
        captureMessage = "页面加载失败，请检查网络后重试"
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }

        if navigationAction.targetFrame?.isMainFrame != false,
           url.scheme?.lowercased() == "https",
           !isCanvasPage(url) {
            hasVisitedAuthenticationProvider = true
            captureMessage = "请完成统一认证"
        }

        if navigationAction.targetFrame == nil {
            guard url.scheme?.lowercased() == "https" else {
                decisionHandler(.cancel)
                return
            }
            webView.load(URLRequest(url: url))
            decisionHandler(.cancel)
            return
        }

        decisionHandler(url.scheme?.lowercased() == "https" ? .allow : .cancel)
    }
}
