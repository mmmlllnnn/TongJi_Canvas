import Combine
import Foundation
import Security

struct CanvasAccount: Codable, Hashable, Identifiable {
    let id: UUID
    var displayName: String
    let addedAt: Date

    init(displayName: String) {
        id = UUID()
        self.displayName = displayName
        addedAt = Date()
    }
}

struct CourseGroup: Codable, Hashable, Identifiable {
    let id: UUID
    var name: String
    var memberIDs: [UUID]

    init(id: UUID = UUID(), name: String, memberIDs: [UUID] = []) {
        self.id = id
        self.name = name
        self.memberIDs = memberIDs
    }
}

enum AccountStoreError: LocalizedError {
    case missingCookie
    case keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .missingCookie: "没有可保存的 Canvas 会话 Cookie"
        case let .keychain(status) where status == errSecMissingEntitlement:
            "应用缺少 Keychain 签名权限，请从配置了开发团队的 Xcode 构建运行。"
        case let .keychain(status): "无法安全保存账号凭据（\(status)）"
        }
    }
}

@MainActor
final class AccountStore: ObservableObject {
    @Published private(set) var accounts: [CanvasAccount]
    @Published private(set) var selectedAccountIDs: Set<UUID>
    @Published private(set) var courses: [CourseGroup]

    private let defaults: UserDefaults
    private let keychain: any CanvasCredentialStore
    private let accountsKey = "canvas.accounts.v1"
    private let selectedAccountsKey = "canvas.selectedAccounts.v1"
    private let coursesKey = "canvas.courses.v1"

    init(defaults: UserDefaults = .standard, credentialStore: (any CanvasCredentialStore)? = nil) {
        self.defaults = defaults
        keychain = credentialStore ?? CanvasKeychain(service: Bundle.main.bundleIdentifier ?? "com.mln.tongjicanvas.ios")
        let savedAccounts: [CanvasAccount]
        if let data = defaults.data(forKey: accountsKey),
           let saved = try? JSONDecoder().decode([CanvasAccount].self, from: data) {
            savedAccounts = saved
        } else {
            savedAccounts = []
        }
        accounts = savedAccounts

        let validIDs = Set(savedAccounts.map(\.id))
        if let savedIDs = defaults.stringArray(forKey: selectedAccountsKey) {
            selectedAccountIDs = Set(savedIDs.compactMap(UUID.init(uuidString:))).intersection(validIDs)
        } else {
            selectedAccountIDs = validIDs
        }

        if let courseData = defaults.data(forKey: coursesKey),
           let savedCourses = try? JSONDecoder().decode([CourseGroup].self, from: courseData) {
            courses = savedCourses.map { course in
                CourseGroup(
                    id: course.id,
                    name: course.name,
                    memberIDs: Self.deduplicatedIDs(course.memberIDs.filter { validIDs.contains($0) })
                )
            }
        } else {
            courses = []
        }
    }

    func addSession(displayName: String, cookieValue: String) throws {
        guard !cookieValue.isEmpty else { throw AccountStoreError.missingCookie }
        try insertAccount(displayName: displayName, cookieValue: cookieValue)
    }

    @discardableResult
    func addManualAccount(displayName: String, cookieInput: String) throws -> CanvasAccount {
        let value = cookieInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? nil : try CanvasSessionCookie.normalize(cookieInput)
        return try insertAccount(displayName: displayName, cookieValue: value)
    }

    @discardableResult
    func updateCookie(for account: CanvasAccount, input: String) throws -> String? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? nil : try CanvasSessionCookie.normalize(input)
        if let value {
            try keychain.save(value, account: account.id.uuidString)
        } else {
            try keychain.remove(account: account.id.uuidString)
        }
        objectWillChange.send()
        return value
    }

    @discardableResult
    private func insertAccount(displayName: String, cookieValue: String?) throws -> CanvasAccount {
        let label = displayName.trimmingCharacters(in: .whitespacesAndNewlines)

        let account = CanvasAccount(displayName: label.isEmpty ? "未命名" : label)
        if let cookieValue { try keychain.save(cookieValue, account: account.id.uuidString) }
        accounts.insert(account, at: 0)
        selectedAccountIDs.insert(account.id)
        persistAccounts()
        persistSelectedAccounts()
        return account
    }

    func cookieValue(for account: CanvasAccount) -> String? {
        try? keychain.read(account: account.id.uuidString)
    }

    func remove(_ account: CanvasAccount) throws {
        try keychain.remove(account: account.id.uuidString)
        accounts.removeAll { $0.id == account.id }
        selectedAccountIDs.remove(account.id)
        var coursesChanged = false
        for index in courses.indices where courses[index].memberIDs.contains(account.id) {
            courses[index].memberIDs.removeAll { $0 == account.id }
            coursesChanged = true
        }
        persistAccounts()
        persistSelectedAccounts()
        if coursesChanged {
            persistCourses()
        }
    }

    func updateDisplayName(for account: CanvasAccount, to displayName: String) {
        guard let index = accounts.firstIndex(where: { $0.id == account.id }) else { return }
        let name = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        accounts[index].displayName = name.isEmpty ? "未命名" : name
        persistAccounts()
    }

    func setSelected(_ selected: Bool, for account: CanvasAccount) {
        if selected {
            selectedAccountIDs.insert(account.id)
        } else {
            selectedAccountIDs.remove(account.id)
        }
        persistSelectedAccounts()
    }

    func toggleSelection(for account: CanvasAccount) {
        setSelected(!selectedAccountIDs.contains(account.id), for: account)
    }

    func selectAllAccounts() {
        selectedAccountIDs = Set(accounts.map(\.id))
        persistSelectedAccounts()
    }

    func deselectAllAccounts() {
        selectedAccountIDs = []
        persistSelectedAccounts()
    }

    // MARK: - Course Groups

    func validMemberIDs(for course: CourseGroup) -> [UUID] {
        let validIDs = Set(accounts.map(\.id))
        return course.memberIDs.filter { validIDs.contains($0) }
    }

    func isCourseSelected(_ course: CourseGroup) -> Bool {
        let members = validMemberIDs(for: course)
        return !members.isEmpty && members.allSatisfy { selectedAccountIDs.contains($0) }
    }

    func toggleCourseSelection(_ course: CourseGroup) {
        let members = Set(validMemberIDs(for: course))
        guard !members.isEmpty else { return }
        if isCourseSelected(course) {
            selectedAccountIDs.subtract(members)
        } else {
            selectedAccountIDs.formUnion(members)
        }
        persistSelectedAccounts()
    }

    @discardableResult
    func addCourse(name: String, memberIDs: Set<UUID> = []) -> CourseGroup? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let orderedMembers = accounts.map(\.id).filter { memberIDs.contains($0) }
        let course = CourseGroup(name: trimmed, memberIDs: orderedMembers)
        courses.append(course)
        persistCourses()
        return course
    }

    func updateCourse(_ course: CourseGroup, name: String, memberIDs: Set<UUID>) {
        guard let index = courses.firstIndex(where: { $0.id == course.id }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let orderedMembers = accounts.map(\.id).filter { memberIDs.contains($0) }
        courses[index].name = trimmed
        courses[index].memberIDs = orderedMembers
        persistCourses()
    }

    func removeCourse(_ course: CourseGroup) {
        courses.removeAll { $0.id == course.id }
        persistCourses()
    }

    func courseIDs(containing account: CanvasAccount) -> Set<UUID> {
        Set(courses.filter { $0.memberIDs.contains(account.id) }.map(\.id))
    }

    func setCourses(_ courseIDs: Set<UUID>, for account: CanvasAccount) {
        var changed = false
        for index in courses.indices {
            let shouldContain = courseIDs.contains(courses[index].id)
            let currentlyContains = courses[index].memberIDs.contains(account.id)
            if shouldContain && !currentlyContains {
                courses[index].memberIDs.append(account.id)
                changed = true
            } else if !shouldContain && currentlyContains {
                courses[index].memberIDs.removeAll { $0 == account.id }
                changed = true
            }
        }
        if changed {
            persistCourses()
        }
    }

    func applyBackupImport(
        users: [(displayName: String, cookieValue: String)],
        courses importedCourses: [(name: String, memberNames: [String])]
    ) throws -> (importedAccounts: Int, updatedAccounts: Int, importedCourses: Int) {
        var nameToID: [String: UUID] = [:]
        var cookiesByID: [UUID: String] = [:]
        for account in accounts {
            nameToID[account.displayName] = account.id
            if let cookie = cookieValue(for: account) {
                cookiesByID[account.id] = cookie
            }
        }

        var importedAccounts = 0
        var updatedAccounts = 0
        var accountsChanged = false
        var selectionChanged = false
        var coursesChanged = false

        for user in users {
            let trimmedName = user.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            let existingIndex = accounts.firstIndex { cookiesByID[$0.id] == user.cookieValue }
                ?? accounts.firstIndex {
                    !trimmedName.isEmpty && trimmedName != "未命名" && $0.displayName == trimmedName
                }

            if let index = existingIndex {
                let accountID = accounts[index].id
                try keychain.save(user.cookieValue, account: accountID.uuidString)
                cookiesByID[accountID] = user.cookieValue
                if !trimmedName.isEmpty, accounts[index].displayName == "未命名" {
                    accounts[index].displayName = trimmedName
                    accountsChanged = true
                }
                if !trimmedName.isEmpty {
                    nameToID[trimmedName] = accountID
                }
                nameToID[accounts[index].displayName] = accountID
                updatedAccounts += 1
            } else {
                let label = trimmedName.isEmpty ? "未命名" : trimmedName
                let account = CanvasAccount(displayName: label)
                try keychain.save(user.cookieValue, account: account.id.uuidString)
                cookiesByID[account.id] = user.cookieValue
                accounts.append(account)
                selectedAccountIDs.insert(account.id)
                if !trimmedName.isEmpty {
                    nameToID[trimmedName] = account.id
                }
                nameToID[account.displayName] = account.id
                importedAccounts += 1
                accountsChanged = true
                selectionChanged = true
            }
        }

        var importedCourseCount = 0
        for importedCourse in importedCourses {
            let courseName = importedCourse.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !courseName.isEmpty else { continue }

            let resolvedMembers = Self.deduplicatedIDs(
                importedCourse.memberNames.compactMap { rawMember in
                    let memberName = rawMember.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !memberName.isEmpty else { return nil }
                    return nameToID[memberName]
                }
            )

            if let index = courses.firstIndex(where: { $0.name == courseName }) {
                let merged = Self.deduplicatedIDs(courses[index].memberIDs + resolvedMembers)
                if merged != courses[index].memberIDs {
                    courses[index].memberIDs = merged
                    coursesChanged = true
                }
            } else {
                courses.append(CourseGroup(name: courseName, memberIDs: resolvedMembers))
                coursesChanged = true
            }
            importedCourseCount += 1
        }

        if accountsChanged { persistAccounts() }
        if selectionChanged { persistSelectedAccounts() }
        if coursesChanged { persistCourses() }

        return (importedAccounts, updatedAccounts, importedCourseCount)
    }

    private func persistAccounts() {
        guard let data = try? JSONEncoder().encode(accounts) else { return }
        defaults.set(data, forKey: accountsKey)
    }

    private func persistSelectedAccounts() {
        defaults.set(selectedAccountIDs.map(\.uuidString), forKey: selectedAccountsKey)
    }

    private func persistCourses() {
        guard let data = try? JSONEncoder().encode(courses) else { return }
        defaults.set(data, forKey: coursesKey)
    }

    private static func deduplicatedIDs(_ ids: [UUID]) -> [UUID] {
        var seen: Set<UUID> = []
        return ids.filter { seen.insert($0).inserted }
    }
}

protocol CanvasCredentialStore {
    func save(_ value: String, account: String) throws
    func read(account: String) throws -> String?
    func remove(account: String) throws
}

private struct CanvasKeychain: CanvasCredentialStore {
    let service: String

    func save(_ value: String, account: String) throws {
        guard let data = value.data(using: .utf8) else { throw AccountStoreError.missingCookie }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecItemNotFound {
            var item = query
            attributes.forEach { item[$0.key] = $0.value }
            let status = SecItemAdd(item as CFDictionary, nil)
            guard status == errSecSuccess else { throw AccountStoreError.keychain(status) }
        } else if updateStatus != errSecSuccess {
            throw AccountStoreError.keychain(updateStatus)
        }
    }

    func read(account: String) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess,
              let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else {
            throw AccountStoreError.keychain(status)
        }
        return value
    }

    func remove(account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw AccountStoreError.keychain(status)
        }
    }
}
