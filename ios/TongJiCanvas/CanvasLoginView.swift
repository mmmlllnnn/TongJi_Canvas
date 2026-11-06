import SwiftUI
import WebKit

struct CanvasLoginView: View {
    @StateObject private var controller = CanvasLoginController()
    @EnvironmentObject private var accountStore: AccountStore
    @Environment(\.dismiss) private var dismiss
    @State private var saveMessage: String?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: controller.pendingSession == nil ? "person.badge.key" : "checkmark.circle.fill")
                    .foregroundStyle(controller.pendingSession == nil ? Color.secondary : Color.green)
                Text(controller.captureMessage)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if !controller.pageHost.isEmpty {
                    Text(controller.pageHost)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 10)
            .background(.bar)

            CanvasLoginWebView(controller: controller)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            if let saveMessage {
                HStack(spacing: 12) {
                    Text(saveMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let session = controller.pendingSession {
                        Button("重试保存") { Task { await saveSession(session) } }
                            .font(.caption.weight(.semibold))
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
            }
        }
        .navigationTitle("统一认证登录")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    saveMessage = nil
                    controller.restartLogin()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("重新登录")
            }
        }
        .onAppear { controller.start() }
        .onChange(of: controller.pendingSession) { _, session in
            guard let session else { return }
            Task { await saveSession(session) }
        }
    }

    @MainActor
    private func saveSession(_ session: CapturedCanvasSession) async {
        do {
            try accountStore.addSession(displayName: session.displayName, cookieValue: session.cookieValue)
            controller.markSessionSaved()
            dismiss()
        } catch {
            saveMessage = error.localizedDescription
        }
    }
}

private struct CanvasLoginWebView: UIViewRepresentable {
    let controller: CanvasLoginController

    func makeUIView(context: Context) -> WKWebView {
        controller.webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
