import Foundation

enum AccountTransferError: LocalizedError {
    case emptyClipboard
    case noExportableAccounts
    case invalidFormat
    case noImportableData

    var errorDescription: String? {
        switch self {
        case .emptyClipboard: "剪贴板为空"
        case .noExportableAccounts: "暂无可导出的有效账号（请先登录或填写 Cookie）"
        case .invalidFormat: "备份内容格式有误，请检查 JSON 数据"
        case .noImportableData: "未识别到可导入的账号或课程分组"
        }
    }
}

struct AccountExportResult {
    let json: String
    let accountCount: Int
    let courseCount: Int
    let skippedEmptyAccounts: Int

    var statusMessage: String {
        var parts = ["已导出 \(accountCount) 个账号"]
        if courseCount > 0 {
            parts.append("、\(courseCount) 个课程分组")
        }
        if skippedEmptyAccounts > 0 {
            parts.append("（已跳过 \(skippedEmptyAccounts) 个未填写 Cookie 的空账号）")
        }
        return parts.joined()
    }
}

struct AccountImportSummary {
    let importedAccounts: Int
    let updatedAccounts: Int
    let skippedAccounts: Int
    let importedCourses: Int

    var statusMessage: String {
        var parts: [String] = []
        if importedAccounts > 0 {
            parts.append("新增 \(importedAccounts) 个账号")
        }
        if updatedAccounts > 0 {
            parts.append("更新 \(updatedAccounts) 个已有账号")
        }
        if importedCourses > 0 {
            parts.append("导入 \(importedCourses) 个课程分组")
        }
        if skippedAccounts > 0 {
            parts.append("跳过 \(skippedAccounts) 个无效凭据")
        }
        return parts.isEmpty ? "未识别到可导入的数据" : parts.joined(separator: "，")
    }
}

enum AccountTransferCodec {
    private struct ExportPayload: Encodable {
        let users: [ExportUser]
        let courses: [ExportCourse]?
    }

    private struct ExportUser: Encodable {
        let displayName: String
        let accessToken: String
    }

    private struct ExportCourse: Encodable {
        let name: String
        let members: [String]
    }

    private struct ImportPayload: Decodable {
        let users: [ImportUser]?
        let courses: [ImportCourse]?
    }

    private struct ImportUser: Decodable {
        let displayName: String?
        let name: String?
        let accessToken: String?
        let token: String?
    }

    private struct ImportCourse: Decodable {
        let name: String?
        let members: [String?]?
    }

    struct DecodedBackup {
        let users: [(displayName: String, cookieValue: String)]
        let courses: [(name: String, memberNames: [String])]
        let skippedAccounts: Int
    }

    static func encode(
        accounts: [CanvasAccount],
        courses: [CourseGroup],
        cookieLookup: (CanvasAccount) -> String?
    ) throws -> AccountExportResult {
        var exportUsers: [ExportUser] = []
        var exportedNamesByID: [UUID: String] = [:]
        var skippedEmptyAccounts = 0

        for account in accounts {
            guard let rawCookie = cookieLookup(account)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !rawCookie.isEmpty else {
                skippedEmptyAccounts += 1
                continue
            }
            let normalized = (try? CanvasSessionCookie.normalize(rawCookie)) ?? rawCookie
            let token = normalized.hasPrefix("\(CanvasSessionCookie.name)=")
                ? normalized
                : "\(CanvasSessionCookie.name)=\(normalized)"
            exportUsers.append(ExportUser(displayName: account.displayName, accessToken: token))
            exportedNamesByID[account.id] = account.displayName
        }

        guard !exportUsers.isEmpty else {
            throw AccountTransferError.noExportableAccounts
        }

        let exportCourses: [ExportCourse] = courses.map { course in
            let members = course.memberIDs.compactMap { exportedNamesByID[$0] }
            return ExportCourse(name: course.name, members: members)
        }

        let payload = ExportPayload(
            users: exportUsers,
            courses: exportCourses.isEmpty ? nil : exportCourses
        )

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(payload)
        guard let json = String(data: data, encoding: .utf8) else {
            throw AccountTransferError.invalidFormat
        }

        return AccountExportResult(
            json: json,
            accountCount: exportUsers.count,
            courseCount: exportCourses.count,
            skippedEmptyAccounts: skippedEmptyAccounts
        )
    }

    static func decode(_ raw: String) throws -> DecodedBackup {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else {
            throw AccountTransferError.invalidFormat
        }

        let rawUsers: [ImportUser]
        let rawCourses: [ImportCourse]

        if trimmed.hasPrefix("{") {
            let payload: ImportPayload
            do {
                payload = try JSONDecoder().decode(ImportPayload.self, from: data)
            } catch {
                throw AccountTransferError.invalidFormat
            }
            guard payload.users != nil || payload.courses != nil else {
                throw AccountTransferError.invalidFormat
            }
            rawUsers = payload.users ?? []
            rawCourses = payload.courses ?? []
        } else if trimmed.hasPrefix("[") {
            do {
                rawUsers = try JSONDecoder().decode([ImportUser].self, from: data)
            } catch {
                throw AccountTransferError.invalidFormat
            }
            rawCourses = []
        } else {
            throw AccountTransferError.invalidFormat
        }

        var validUsers: [(displayName: String, cookieValue: String)] = []
        var skippedAccounts = 0

        for user in rawUsers {
            let name = (user.displayName ?? user.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let token = (user.accessToken ?? user.token ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if token.isEmpty {
                if !name.isEmpty {
                    skippedAccounts += 1
                }
                continue
            }
            guard let normalized = try? CanvasSessionCookie.normalize(token) else {
                skippedAccounts += 1
                continue
            }
            validUsers.append((displayName: name, cookieValue: normalized))
        }

        let validCourses: [(name: String, memberNames: [String])] = rawCourses.compactMap { course in
            guard let name = course.name?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !name.isEmpty else {
                return nil
            }
            let members = (course.members ?? []).compactMap { member -> String? in
                let trimmedMember = member?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                return trimmedMember.isEmpty ? nil : trimmedMember
            }
            return (name: name, memberNames: members)
        }

        return DecodedBackup(
            users: validUsers,
            courses: validCourses,
            skippedAccounts: skippedAccounts
        )
    }
}

@MainActor
extension AccountStore {
    func exportBackupJSON() throws -> AccountExportResult {
        try AccountTransferCodec.encode(accounts: accounts, courses: courses) { account in
            cookieValue(for: account)
        }
    }

    func importBackupJSON(_ raw: String) throws -> AccountImportSummary {
        let decoded = try AccountTransferCodec.decode(raw)
        let applied = try applyBackupImport(users: decoded.users, courses: decoded.courses)

        if applied.importedAccounts == 0
            && applied.updatedAccounts == 0
            && applied.importedCourses == 0
            && decoded.skippedAccounts == 0 {
            throw AccountTransferError.noImportableData
        }

        return AccountImportSummary(
            importedAccounts: applied.importedAccounts,
            updatedAccounts: applied.updatedAccounts,
            skippedAccounts: decoded.skippedAccounts,
            importedCourses: applied.importedCourses
        )
    }
}
