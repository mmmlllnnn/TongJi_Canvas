import SwiftUI

struct CourseEditorView: View {
    let course: CourseGroup?

    @EnvironmentObject private var accountStore: AccountStore
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var selectedMemberIDs: Set<UUID>

    init(course: CourseGroup? = nil) {
        self.course = course
        _name = State(initialValue: course?.name ?? "")
        _selectedMemberIDs = State(initialValue: Set(course?.memberIDs ?? []))
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("课程名称") {
                    TextField("例如：高等数学", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section {
                    if accountStore.accounts.isEmpty {
                        Text("尚未添加账号")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(accountStore.accounts) { account in
                            let isMember = selectedMemberIDs.contains(account.id)
                            Button {
                                if isMember {
                                    selectedMemberIDs.remove(account.id)
                                } else {
                                    selectedMemberIDs.insert(account.id)
                                }
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: isMember ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(isMember ? Color.accentColor : Color.secondary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(account.displayName)
                                            .foregroundStyle(.primary)
                                        if accountStore.cookieValue(for: account) == nil {
                                            Text("待填写 Cookie")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } header: {
                    HStack {
                        Text("选择成员")
                        Spacer()
                        if !accountStore.accounts.isEmpty {
                            Text("已选 \(selectedMemberIDs.intersection(Set(accountStore.accounts.map(\.id))).count) 人")
                                .font(.caption)
                                .monospacedDigit()
                        }
                    }
                }

                if let course {
                    Section {
                        Button("删除课程分组", role: .destructive) {
                            accountStore.removeCourse(course)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(course == nil ? "新建课程分组" : "编辑课程分组")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(course == nil ? "创建" : "保存") {
                        if let course {
                            accountStore.updateCourse(course, name: trimmedName, memberIDs: selectedMemberIDs)
                        } else {
                            accountStore.addCourse(name: trimmedName, memberIDs: selectedMemberIDs)
                        }
                        dismiss()
                    }
                    .disabled(trimmedName.isEmpty)
                }
            }
        }
    }
}
