import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var accountStore: AccountStore
    @State private var storeError: String?

    private var allAccountsSelected: Bool {
        !accountStore.accounts.isEmpty
            && accountStore.accounts.allSatisfy { accountStore.selectedAccountIDs.contains($0.id) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section("签到") {
                    NavigationLink {
                        CanvasScannerView()
                    } label: {
                        Label("扫码签到", systemImage: "qrcode.viewfinder")
                    }
                }

                Section {
                    if accountStore.accounts.isEmpty {
                        Text("尚未添加账号")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(accountStore.accounts) { account in
                            HStack(spacing: 12) {
                                Button {
                                    accountStore.toggleSelection(for: account)
                                } label: {
                                    Image(systemName: accountStore.selectedAccountIDs.contains(account.id)
                                          ? "checkmark.square.fill" : "square")
                                        .font(.title3)
                                        .foregroundStyle(accountStore.selectedAccountIDs.contains(account.id) ? Color.accentColor : Color.secondary)
                                        .frame(width: 30, height: 44)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(accountStore.selectedAccountIDs.contains(account.id) ? "取消选择\(account.displayName)" : "选择\(account.displayName)")

                                NavigationLink {
                                    CanvasAccountDetailView(account: account)
                                } label: {
                                    HStack(spacing: 12) {
                                        Image(systemName: "person.crop.circle.fill")
                                            .foregroundStyle(.tint)
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(account.displayName)
                                        }
                                    }
                                }
                            }
                            .swipeActions {
                                Button(role: .destructive) {
                                    do {
                                        try accountStore.remove(account)
                                    } catch {
                                        storeError = error.localizedDescription
                                    }
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text("Canvas 账号列表")
                        Spacer()
                        if !accountStore.accounts.isEmpty {
                            Button(allAccountsSelected ? "取消全选" : "全选") {
                                if allAccountsSelected {
                                    accountStore.deselectAllAccounts()
                                } else {
                                    accountStore.selectAllAccounts()
                                }
                            }
                            .font(.caption)
                        }
                    }
                }
            }
            .navigationTitle("Tongji Canvas")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    NavigationLink {
                        CanvasLoginView()
                    } label: {
                        Image(systemName: "person.crop.circle.badge.plus")
                    }
                    .accessibilityLabel("添加 Canvas 账号")
                }
            }
            .alert("账号操作失败", isPresented: Binding(
                get: { storeError != nil },
                set: { if !$0 { storeError = nil } }
            )) {
                Button("好", role: .cancel) { storeError = nil }
            } message: {
                Text(storeError ?? "")
            }
        }
    }
}

private struct CanvasAccountDetailView: View {
    let account: CanvasAccount

    @EnvironmentObject private var accountStore: AccountStore
    @State private var displayName: String
    @State private var sessionCookie: String?
    @State private var isCookieVisible = false
    @State private var alertMessage: String?

    init(account: CanvasAccount) {
        self.account = account
        _displayName = State(initialValue: account.displayName)
    }

    var body: some View {
        Form {
            Section("账号名称") {
                TextField("账号显示名称", text: $displayName)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("保存名称") {
                    accountStore.updateDisplayName(for: account, to: displayName)
                    if let updated = accountStore.accounts.first(where: { $0.id == account.id }) {
                        displayName = updated.displayName
                    }
                }
            }

            Section("Canvas 会话") {
                if let sessionCookie {
                    ScrollView {
                        Text(isCookieVisible ? sessionCookie : String(repeating: "•", count: min(sessionCookie.count, 32)))
                            .font(.caption.monospaced())
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 4)
                    }
                    .frame(height: 110)

                    HStack {
                        Text(isCookieVisible ? "会话已显示" : "会话已隐藏")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button {
                            isCookieVisible.toggle()
                        } label: {
                            Image(systemName: isCookieVisible ? "eye.slash" : "eye")
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(isCookieVisible ? "隐藏 Canvas 会话" : "显示 Canvas 会话")
                    }
                } else {
                    Text("正在读取安全保存的会话…")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(account.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            sessionCookie = accountStore.cookieValue(for: account)
            if sessionCookie == nil {
                alertMessage = "没有读取到此账号的 Canvas 会话，请重新登录添加。"
            }
        }
        .alert("提示", isPresented: Binding(
            get: { alertMessage != nil },
            set: { if !$0 { alertMessage = nil } }
        )) {
            Button("好", role: .cancel) { alertMessage = nil }
        } message: {
            Text(alertMessage ?? "")
        }
    }

}
