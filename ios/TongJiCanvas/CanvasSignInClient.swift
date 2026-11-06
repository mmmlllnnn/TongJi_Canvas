import Foundation

struct CanvasSignTarget: Equatable {
    let url: URL
    let courseCode: String

    init?(rawValue: String) {
        guard let components = URLComponents(string: rawValue),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == "canvas.tongji.edu.cn",
              components.port == nil || components.port == 443,
              // Classroom QR codes use this endpoint both with and without a trailing slash.
              ["/lms/mobile/forscan", "/lms/mobile/forscan/"].contains(components.percentEncodedPath),
              components.user == nil,
              components.password == nil else { return nil }

        func parameter(_ name: String) -> String? {
            let values = components.queryItems?.filter { $0.name == name } ?? []
            guard values.count == 1,
                  let value = values[0].value?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !value.isEmpty else { return nil }
            return value
        }

        guard let courseCode = parameter("courseCode"),
              parameter("rollCallToken") != nil,
              let url = components.url else { return nil }
        self.url = url
        self.courseCode = courseCode
    }
}

enum CanvasSignStatus {
    case success
    case alreadySigned
    case notEnrolled
    case expiredQR
    case expiredSession
    case failed
}

struct CanvasSignResult {
    let status: CanvasSignStatus
    let message: String
    let httpCode: Int?
    let courseName: String?
}

enum CanvasSignInClient {
    static func sign(
        target: CanvasSignTarget,
        cookieValue: String,
        configuration: URLSessionConfiguration = .ephemeral
    ) async -> CanvasSignResult {
        guard !cookieValue.isEmpty,
              !cookieValue.contains(";"),
              !cookieValue.contains("\r"),
              !cookieValue.contains("\n") else {
            return result(.expiredSession, "账号缺少有效的 Canvas 会话", code: nil)
        }

        var request = URLRequest(url: target.url)
        request.httpMethod = "GET"
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("_canvas_middle_session=\(cookieValue)", forHTTPHeaderField: "Cookie")

        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }

        do {
            let (data, response) = try await session.data(for: request, delegate: RedirectBlocker())
            guard let response = response as? HTTPURLResponse else {
                return result(.failed, "无法读取 Canvas 响应", code: nil)
            }
            if (300..<400).contains(response.statusCode) || [401, 403].contains(response.statusCode) {
                return result(.expiredSession, "Canvas 登录状态已失效，请重新登录", code: response.statusCode)
            }
            guard (200..<300).contains(response.statusCode) else {
                return result(.failed, "请求失败（HTTP \(response.statusCode)）", code: response.statusCode)
            }

            let text = responseText(from: data)
            let course = capture(#"签到课程\s*[:：]\s*([^\s]+)"#, in: text)
                .flatMap { $0 == "无" ? nil : $0 }
            if ["已签过", "已签到过", "已经签到", "重复签到"].contains(where: text.contains) {
                return result(.alreadySigned, "该课程已签到", code: response.statusCode, course: course)
            }
            if text.contains("二维码已过期") || text.contains("二维码已失效") {
                return result(.expiredQR, "签到二维码已过期", code: response.statusCode, course: course)
            }
            if text.contains("未加入此课程") {
                return result(.notEnrolled, "当前账号未加入此课程", code: response.statusCode, course: course)
            }
            if text.contains("签到失败") {
                let reason = capture(#"签到失败\s*[:：]\s*([^\s<]+)"#, in: text)
                return result(.failed, reason.map { "签到失败：\($0)" } ?? "签到失败", code: response.statusCode, course: course)
            }
            if text.contains("签到成功") {
                return result(.success, "签到成功", code: response.statusCode, course: course)
            }
            if text.contains("签到超时") || text.contains("未知错误") {
                return result(.failed, text.contains("签到超时") ? "签到超时" : "Canvas 返回未知错误", code: response.statusCode, course: course)
            }
            return result(.failed, "无法识别 Canvas 返回结果", code: response.statusCode, course: course)
        } catch {
            return result(.failed, "网络异常，签到结果未知；请先在 Canvas 核对状态", code: nil)
        }
    }

    private static func result(
        _ status: CanvasSignStatus,
        _ message: String,
        code: Int?,
        course: String? = nil
    ) -> CanvasSignResult {
        CanvasSignResult(status: status, message: message, httpCode: code, courseName: course)
    }

    private static func responseText(from data: Data) -> String {
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
}

private final class RedirectBlocker: NSObject, URLSessionTaskDelegate {
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
