import SwiftUI
import UIKit

struct SignHistoryView: View {
    @EnvironmentObject private var historyStore: SignHistoryStore
    @State private var isConfirmingClear = false

    var body: some View {
        Group {
            if historyStore.runs.isEmpty {
                ContentUnavailableView(
                    "暂无签到记录",
                    systemImage: "clock.arrow.circlepath",
                    description: Text("完成扫码批量签到后会自动保存在本机，最多保留 50 条。")
                )
            } else {
                List {
                    ForEach(historyStore.runs) { run in
                        NavigationLink {
                            SignHistoryDetailView(run: run)
                        } label: {
                            SignHistoryRow(run: run)
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                historyStore.remove(run)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("签到历史")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("清空", role: .destructive) {
                    isConfirmingClear = true
                }
                .disabled(historyStore.runs.isEmpty)
            }
        }
        .alert("清空签到历史？", isPresented: $isConfirmingClear) {
            Button("清空", role: .destructive) {
                historyStore.clear()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("此操作会删除本机保存的所有签到记录。")
        }
    }
}

private struct SignHistoryRow: View {
    let run: SignHistoryRun

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(run.displayTitle)
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                Text(run.timestamp.formatted(date: .abbreviated, time: .shortened))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text("共 \(run.accounts.count) 人 · 成功 \(run.signedCount) · 失败 \(run.failedCount)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            HistoryResultBar(signedCount: run.signedCount, totalCount: run.accounts.count)
        }
        .padding(.vertical, 4)
    }
}

private struct SignHistoryDetailView: View {
    let run: SignHistoryRun

    @State private var alertMessage: String?

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Text(run.displayTitle)
                        .font(.headline)
                    Text(run.timestamp.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("共 \(run.accounts.count) 人 · 成功 \(run.signedCount) · 失败 \(run.failedCount)")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                    HistoryResultBar(signedCount: run.signedCount, totalCount: run.accounts.count)
                }
                .padding(.vertical, 4)

                Button {
                    copyToPasteboard(run.summary(), notice: "签到结果已复制到剪贴板。")
                } label: {
                    Label("复制结果", systemImage: "doc.on.doc")
                }
            }

            Section("账号明细") {
                ForEach(run.accounts) { account in
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: account.isSigned ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(account.isSigned ? Color.green : Color.orange)
                            .frame(width: 24, height: 24)

                        VStack(alignment: .leading, spacing: 4) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(account.accountName)
                                    .font(.subheadline.weight(.semibold))
                                Spacer()
                                Text(account.status.statusLabel)
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(account.isSigned ? Color.green : Color.orange)
                            }
                            Text(account.message)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Text("HTTP \(account.httpCode.map(String.init) ?? "-") · \(account.elapsedMs) ms")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .navigationTitle("签到结果")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        copyToPasteboard(run.summary(), notice: "签到结果已复制到剪贴板。")
                    } label: {
                        Label("复制结果", systemImage: "doc.on.doc")
                    }
                    Button {
                        copyToPasteboard(run.diagnostics(), notice: "诊断信息已复制到剪贴板。")
                    } label: {
                        Label("复制诊断", systemImage: "ladybug")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel("更多操作")
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

    private func copyToPasteboard(_ text: String, notice: String) {
        UIPasteboard.general.string = text
        alertMessage = notice
    }
}

private struct HistoryResultBar: View {
    let signedCount: Int
    let totalCount: Int

    var body: some View {
        GeometryReader { geometry in
            let total = max(totalCount, 1)
            let width = geometry.size.width
            let signedWidth = width * CGFloat(signedCount) / CGFloat(total)
            let failedWidth = max(0, width - signedWidth)

            HStack(spacing: 0) {
                if signedCount > 0 {
                    Rectangle()
                        .fill(Color.green)
                        .frame(width: signedWidth)
                }
                if totalCount > signedCount {
                    Rectangle()
                        .fill(Color.orange)
                        .frame(width: failedWidth)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.secondary.opacity(0.2))
        }
        .frame(height: 6)
        .clipShape(Capsule())
    }
}
