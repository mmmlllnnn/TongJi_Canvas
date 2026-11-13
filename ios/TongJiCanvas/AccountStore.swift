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

    private let defaults: UserDefaults
    private let keychain: any CanvasCredentialStore
    private let accountsKey = "canvas.accounts.v1"
    private let selectedAccountsKey = "canvas.selectedAccounts.v1"

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

        if let savedIDs = defaults.stringArray(forKey: selectedAccountsKey) {
            let validIDs = Set(savedAccounts.map(\.id))
            selectedAccountIDs = Set(savedIDs.compactMap(UUID.init(uuidString:))).intersection(validIDs)
        } else {
            selectedAccountIDs = Set(savedAccounts.map(\.id))
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
        persistAccounts()
        persistSelectedAccounts()
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

    private func persistAccounts() {
        guard let data = try? JSONEncoder().encode(accounts) else { return }
        defaults.set(data, forKey: accountsKey)
    }

    private func persistSelectedAccounts() {
        defaults.set(selectedAccountIDs.map(\.uuidString), forKey: selectedAccountsKey)
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
