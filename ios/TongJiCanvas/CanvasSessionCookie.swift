import Foundation

enum CanvasCookieError: LocalizedError {
    case missingValue
    case invalidValue

    var errorDescription: String? {
        switch self {
        case .missingValue: "请先填写 Canvas 会话 Cookie。"
        case .invalidValue: "Cookie 格式无效，请粘贴会话值或 _canvas_middle_session=值。"
        }
    }
}

enum CanvasSessionCookie {
    static let name = "_canvas_middle_session"

    /// Manual input may be a value, a Cookie header, or a previously shared URL.
    /// Only unwrap percent escapes for Canvas's Base64 characters; never decode
    /// the session's canonical %2B/%2F/%3D escapes into header characters.
    static func normalize(_ input: String) throws -> String {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { throw CanvasCookieError.missingValue }
        if value.hasPrefix("https://") {
            guard let url = URLComponents(string: value),
                  url.host == "handsome.eu.org",
                  url.path == "/canvas/index.html",
                  let query = url.percentEncodedQuery else { throw CanvasCookieError.invalidValue }
            let matches = query.split(separator: "&").filter { $0.hasPrefix(name + "=") }
            guard matches.count == 1 else { throw CanvasCookieError.invalidValue }
            value = String(matches[0].dropFirst(name.count + 1))
        }
        if value.lowercased().hasPrefix("cookie:") {
            value = String(value.dropFirst(7)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let fields = value.split(separator: ";", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        let sessions = fields.filter { $0.hasPrefix(name + "=") }
        if !sessions.isEmpty {
            guard sessions.count == 1 else { throw CanvasCookieError.invalidValue }
            value = String(sessions[0].dropFirst(name.count + 1))
        } else if fields.count != 1 {
            throw CanvasCookieError.invalidValue
        }
        while true {
            let unwrapped = value.replacingOccurrences(
                of: #"%25(?=(?:25)*(?:2[bBfF]|3[dD]))"#, with: "%", options: .regularExpression
            )
            if unwrapped == value { break }
            value = unwrapped
        }
        guard !value.isEmpty,
              value.range(of: #"^(?:[A-Za-z0-9_+/=-]|%(?:2[BbFf]|3[Dd]))+$"#, options: .regularExpression) != nil else {
            throw CanvasCookieError.invalidValue
        }
        return value.replacingOccurrences(of: "+", with: "%2B")
            .replacingOccurrences(of: "/", with: "%2F")
            .replacingOccurrences(of: "=", with: "%3D")
    }

    static func shareURL(cookieValue: String) throws -> URL {
        let value = try normalize(cookieValue)
        var components = URLComponents(string: "https://handsome.eu.org/canvas/index.html")!
        // The deployed website reads the raw query, without URL decoding.
        components.percentEncodedQuery = name + "=" + value
        guard let url = components.url else { throw CanvasCookieError.invalidValue }
        return url
    }
}
