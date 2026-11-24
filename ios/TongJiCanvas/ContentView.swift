import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var accountStore: AccountStore
    @EnvironmentObject private var historyStore: SignHistoryStore
    @State private var storeError: String?
    @State private var isAddingManualAccount = false
    @State private var isCreatingCourse = false
    @State private var editingCourse: CourseGroup?
    @State private var exportDocument: CanvasBackupDocument?
    @State private var isExportingFile = false
    @State private var isImportingFile = false
    @State private var pendingFileExportMessage = ""
    @State private var noticeTitle = "提示"
    @State private var noticeMessage: String?

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

                    NavigationLink {
                        SignHistoryView()
                    } label: {
                        HStack {
                            Label("签到历史", systemImage: "clock.arrow.circlepath")
                            Spacer()
                            if !historyStore.runs.isEmpty {
                                Text("\(historyStore.runs.count)")
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                Section {
                    if accountStore.courses.isEmpty {
                        Button {
                            isCreatingCourse = true
                        } label: {
                            Label("新建课程分组", systemImage: "folder.badge.plus")
                        }
                    } else {
                        ForEach(accountStore.courses) { course in
                            let memberCount = accountStore.validMemberIDs(for: course).count
                            let isSelected = accountStore.isCourseSelected(course)
                            HStack(spacing: 12) {
                                Button {
                                    accountStore.toggleCourseSelection(course)
                                } label: {
                                    Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                                        .font(.title3)
                                        .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                                        .frame(width: 30, height: 44)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .disabled(memberCount == 0)
                                .accessibilityLabel(isSelected ? "取消选择\(course.name)" : "选择\(course.name)")

                                Button {
                                    editingCourse = course
                                } label: {
                                    HStack(spacing: 12) {
                                        Image(systemName: "folder.fill")
                                            .foregroundStyle(.tint)
                                        Text(course.name)
                                            .foregroundStyle(.primary)
                                        Spacer()
                                        Text("\(memberCount) 名成员")
                                            .font(.caption.monospacedDigit())
                                            .foregroundStyle(.secondary)
                                        Image(systemName: "chevron.right")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(.tertiary)
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            }
                            .swipeActions {
                                Button(role: .destructive) {
                                    accountStore.removeCourse(course)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text("课程分组")
                        Spacer()
                        if !accountStore.courses.isEmpty {
                            Button("新建") {
                                isCreatingCourse = true
                            }
                            .font(.caption)
                        }
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
                        Button {
                            exportToClipboard()
                        } label: {
                            Label("一键导出到剪贴板", systemImage: "doc.on.doc")
                        }
                        Button {
                            importFromClipboard()
                        } label: {
                            Label("一键从剪贴板导入", systemImage: "doc.on.clipboard")
                        }
                        Divider()
                        Button {
                            prepareFileExport()
                        } label: {
                            Label("导出 JSON 文件…", systemImage: "square.and.arrow.up")
                        }
                        Button {
                            isImportingFile = true
                        } label: {
                            Label("导入 JSON 文件…", systemImage: "square.and.arrow.down")
                        }
                    } label: {
                        Image(systemName: "arrow.up.arrow.down.circle")
                    }
                    .accessibilityLabel("导入与导出")

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
            .sheet(isPresented: $isCreatingCourse) {
                CourseEditorView()
            }
            .sheet(item: $editingCourse) { course in
                CourseEditorView(course: course)
            }
            .fileExporter(
                isPresented: $isExportingFile,
                document: exportDocument,
                contentType: .json,
                defaultFilename: "tongji_canvas_backup.json"
            ) { result in
                exportDocument = nil
                switch result {
                case .success:
                    showNotice(title: "导出成功", message: pendingFileExportMessage)
                case let .failure(error):
                    if (error as? CocoaError)?.code != .userCancelled {
                        showNotice(title: "导出失败", message: error.localizedDescription)
                    }
                }
            }
            .fileImporter(
                isPresented: $isImportingFile,
                allowedContentTypes: [.json],
                allowsMultipleSelection: false
            ) { result in
                handleFileImport(result)
            }
            .alert("账号操作失败", isPresented: Binding(
                get: { storeError != nil },
                set: { if !$0 { storeError = nil } }
            )) {
                Button("好", role: .cancel) { storeError = nil }
            } message: {
                Text(storeError ?? "")
            }
            .alert(noticeTitle, isPresented: Binding(
                get: { noticeMessage != nil },
                set: { if !$0 { noticeMessage = nil } }
            )) {
                Button("好", role: .cancel) { noticeMessage = nil }
            } message: {
                Text(noticeMessage ?? "")
            }
        }
    }

    private func exportToClipboard() {
        do {
            let result = try accountStore.exportBackupJSON()
            UIPasteboard.general.string = result.json
            showNotice(title: "导出成功", message: "\(result.statusMessage)，已复制到剪贴板。")
        } catch {
            showNotice(title: "导出失败", message: error.localizedDescription)
        }
    }

    private func importFromClipboard() {
        guard let raw = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else {
            showNotice(title: "导入失败", message: AccountTransferError.emptyClipboard.localizedDescription)
            return
        }
        do {
            let summary = try accountStore.importBackupJSON(raw)
            showNotice(title: "导入完成", message: summary.statusMessage)
        } catch {
            showNotice(title: "导入失败", message: error.localizedDescription)
        }
    }

    private func prepareFileExport() {
        do {
            let result = try accountStore.exportBackupJSON()
            pendingFileExportMessage = result.statusMessage
            exportDocument = CanvasBackupDocument(text: result.json)
            isExportingFile = true
        } catch {
            showNotice(title: "导出失败", message: error.localizedDescription)
        }
    }

    private func handleFileImport(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let hasAccess = url.startAccessingSecurityScopedResource()
            defer {
                if hasAccess { url.stopAccessingSecurityScopedResource() }
            }
            let data = try Data(contentsOf: url)
            guard let raw = String(data: data, encoding: .utf8) else {
                throw AccountTransferError.invalidFormat
            }
            let summary = try accountStore.importBackupJSON(raw)
            showNotice(title: "导入完成", message: summary.statusMessage)
        } catch {
            if (error as? CocoaError)?.code != .userCancelled {
                showNotice(title: "导入失败", message: error.localizedDescription)
            }
        }
    }

    private func showNotice(title: String, message: String) {
        noticeTitle = title
        noticeMessage = message
    }
}

private struct CanvasBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var text: String

    init(text: String = "") {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents,
              let text = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.text = text
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
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

    private var assignedCourseIDs: Set<UUID> {
        accountStore.courseIDs(containing: account)
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

            if !accountStore.courses.isEmpty {
                Section("所属课程") {
                    ForEach(accountStore.courses) { course in
                        let isAssigned = assignedCourseIDs.contains(course.id)
                        Button {
                            var updated = assignedCourseIDs
                            if isAssigned {
                                updated.remove(course.id)
                            } else {
                                updated.insert(course.id)
                            }
                            accountStore.setCourses(updated, for: account)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: isAssigned ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(isAssigned ? Color.accentColor : Color.secondary)
                                Text(course.name)
                                    .foregroundStyle(.primary)
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
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
