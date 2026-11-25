import Combine
import Foundation

struct SignHistoryAccountRecord: Codable, Hashable, Identifiable {
    let id: UUID
    let accountName: String
    let status: CanvasSignStatus
    let message: String
    let httpCode: Int?
    let elapsedMs: Int

    var isSigned: Bool { status.isSigned }

    init(
        id: UUID = UUID(),
        accountName: String,
        status: CanvasSignStatus,
        message: String,
        httpCode: Int?,
        elapsedMs: Int
    ) {
        self.id = id
        self.accountName = accountName
        self.status = status
        self.message = message
        self.httpCode = httpCode
        self.elapsedMs = elapsedMs
    }
}

struct SignHistoryRun: Codable, Hashable, Identifiable {
    let id: UUID
    let timestamp: Date
    let courseCode: String
    let courseName: String?
    let accounts: [SignHistoryAccountRecord]

    var signedCount: Int { accounts.filter(\.isSigned).count }
    var failedCount: Int { accounts.count - signedCount }

    var displayTitle: String {
        if let courseName, !courseName.isEmpty {
            return courseName
        }
        return "课程 \(courseCode.isEmpty ? "未知" : courseCode)"
    }

    init(
        id: UUID = UUID(),
        timestamp: Date = Date(),
        courseCode: String,
        courseName: String?,
        accounts: [SignHistoryAccountRecord]
    ) {
        self.id = id
        self.timestamp = timestamp
        self.courseCode = courseCode
        self.courseName = courseName
        self.accounts = accounts
    }

    func summary() -> String {
        var lines = ["\(displayTitle)：已签到 \(signedCount)/\(accounts.count)"]
        lines.append(contentsOf: accounts.map { "\($0.accountName)：\($0.message)" })
        return lines.joined(separator: "\n")
    }

    func diagnostics() -> String {
        let timeMs = Int64((timestamp.timeIntervalSince1970 * 1000).rounded())
        var lines = ["courseCode=\(courseCode) · time=\(timeMs)"]
        for account in accounts {
            let httpText = account.httpCode.map(String.init) ?? "-"
            lines.append(
                "\(account.accountName) · status=\(account.status.diagnosticName) · HTTP=\(httpText) · elapsed=\(account.elapsedMs)ms · \(account.message)"
            )
        }
        return lines.joined(separator: "\n")
    }
}

@MainActor
final class SignHistoryStore: ObservableObject {
    static let maxRuns = 50

    @Published private(set) var runs: [SignHistoryRun]

    private let defaults: UserDefaults
    private let historyKey = "canvas.signHistory.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: historyKey),
           let saved = try? JSONDecoder().decode([SignHistoryRun].self, from: data) {
            runs = Array(saved.prefix(Self.maxRuns))
        } else {
            runs = []
        }
    }

    func record(target: CanvasSignTarget, outcomes: [(account: CanvasAccount, result: CanvasSignResult)]) {
        guard !outcomes.isEmpty else { return }

        let resolvedCourseName = outcomes.compactMap { outcome -> String? in
            guard let name = outcome.result.courseName, !name.isEmpty else { return nil }
            return name
        }.first

        let records = outcomes.map { outcome in
            SignHistoryAccountRecord(
                accountName: outcome.account.displayName,
                status: outcome.result.status,
                message: outcome.result.message,
                httpCode: outcome.result.httpCode,
                elapsedMs: outcome.result.elapsedMs
            )
        }

        let run = SignHistoryRun(
            courseCode: target.courseCode,
            courseName: resolvedCourseName,
            accounts: records
        )
        runs.insert(run, at: 0)
        if runs.count > Self.maxRuns {
            runs = Array(runs.prefix(Self.maxRuns))
        }
        persist()
    }

    func remove(_ run: SignHistoryRun) {
        runs.removeAll { $0.id == run.id }
        persist()
    }

    func clear() {
        runs.removeAll()
        defaults.removeObject(forKey: historyKey)
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(runs) else { return }
        defaults.set(data, forKey: historyKey)
    }
}
