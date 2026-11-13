import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var accountStore: AccountStore
    @State private var storeError: String?
    @State private var isAddingManualAccount = false

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
                                            if accountStore.cookieValue(for: account) == nil {
                                                Text("待填写 Cookie")
                                                    .font(.caption)
                                                    .foregroundStyle(.secondary)
                                            }
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
                    Menu {
                        NavigationLink {
                            CanvasLoginView()
                        } label: {
                            Label("统一认证登录", systemImage: "person.badge.key")
                        }
                        Button {
                            isAddingManualAccount = true
                        } label: {
                            Label("手动添加账号", systemImage: "person.crop.circle.badge.plus")
                        }
                    } label: {
                        Image(systemName: "person.crop.circle.badge.plus")
                    }
                    .accessibilityLabel("添加 Canvas 账号")
                }
            }
            .sheet(isPresented: $isAddingManualAccount) {
                ManualCanvasAccountView()
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
    @State private var isEditingCookie = false
    @State private var cookieDraft = ""

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

                    Button {
                        copyShareLink()
                    } label: {
                        Label("复制网页签到链接", systemImage: "link")
                    }
                    .disabled(sessionCookie.isEmpty)
                } else {
                    Text("尚未填写 Cookie，可手动填写或通过统一认证添加账号。")
                        .foregroundStyle(.secondary)
                }
                Button {
                    cookieDraft = sessionCookie ?? ""
                    isEditingCookie = true
                } label: {
                    Label(sessionCookie == nil ? "填写 Cookie" : "修改 Cookie", systemImage: "pencil")
                }
            }
        }
        .navigationTitle(account.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    copyShareLink()
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(sessionCookie?.isEmpty != false)
                .accessibilityLabel("复制网页签到链接")
            }
        }
        .task {
            sessionCookie = accountStore.cookieValue(for: account)
        }
        .sheet(isPresented: $isEditingCookie) {
            CanvasCookieEditorView(value: cookieDraft) { input in
                sessionCookie = try accountStore.updateCookie(for: account, input: input)
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

    private func copyShareLink() {
        guard let sessionCookie, !sessionCookie.isEmpty else {
            alertMessage = "请先填写 Canvas 会话 Cookie。"
            return
        }
        do {
            let url = try CanvasSessionCookie.shareURL(cookieValue: sessionCookie)
            UIPasteboard.general.string = url.absoluteString
            alertMessage = "分享链接已复制到剪切板。"
        } catch {
            alertMessage = error.localizedDescription
        }
    }
}

private struct ManualCanvasAccountView: View {
    @EnvironmentObject private var accountStore: AccountStore
    @Environment(\.dismiss) private var dismiss
    @State private var displayName = ""
    @State private var cookieInput = ""
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("账号名称") {
                    TextField("账号显示名称", text: $displayName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                CanvasCookieInputSection(value: $cookieInput)
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(.red) }
                }
            }
            .navigationTitle("手动添加账号")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        do {
                            try accountStore.addManualAccount(displayName: displayName, cookieInput: cookieInput)
                            dismiss()
                        } catch { errorMessage = error.localizedDescription }
                    }
                }
            }
        }
    }
}

private struct CanvasCookieEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var value: String
    @State private var errorMessage: String?
    let save: (String) throws -> Void

    init(value: String, save: @escaping (String) throws -> Void) {
        _value = State(initialValue: value)
        self.save = save
    }

    var body: some View {
        NavigationStack {
            Form {
                CanvasCookieInputSection(value: $value)
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(.red) }
                }
            }
            .navigationTitle("修改 Cookie")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        do {
                            try save(value)
                            dismiss()
                        } catch { errorMessage = error.localizedDescription }
                    }
                }
            }
        }
    }
}

private struct CanvasCookieInputSection: View {
    @Binding var value: String

    var body: some View {
        Section {
            TextEditor(text: $value)
                .font(.caption.monospaced())
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .frame(minHeight: 150)
                .accessibilityLabel("Canvas 会话 Cookie")
        } header: {
            Text("_canvas_middle_session")
        } footer: {
            Text("可粘贴 Cookie 值、完整 Cookie 或网页签到链接。留空可创建空账号，或清除现有会话。")
        }
    }
}
