import SwiftUI

struct CanvasScannerView: View {
    @StateObject private var scanner = CameraScannerModel()
    @EnvironmentObject private var accountStore: AccountStore
    @State private var submittedScanID: UUID?
    @State private var submittedAccounts: [CanvasAccount] = []
    @State private var isSubmitting = false
    @State private var results: [UUID: CanvasSignResult] = [:]
    @ScaledMetric(relativeTo: .subheadline) private var resultRowHeight = 72.0

    private var selectedAccounts: [CanvasAccount] {
        accountStore.accounts.filter { accountStore.selectedAccountIDs.contains($0.id) }
    }

    private var scannedTarget: CanvasSignTarget? {
        scanner.scannedValue.flatMap(CanvasSignTarget.init(rawValue:))
    }

    private var zoomBinding: Binding<Double> {
        Binding(
            get: { scanner.zoomFactor },
            set: { scanner.setZoom($0) }
        )
    }

    var body: some View {
        ZStack {
            NativeCameraPreview(scanner: scanner)
                .ignoresSafeArea()

            VStack(spacing: 16) {
                if scanner.status != "对准二维码", scanner.scannedValue == nil {
                    Text(scanner.status)
                        .font(.subheadline)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .modifier(ScannerGlassSurface(cornerRadius: 28))
                }

                Spacer(minLength: 16)

                if scanner.scannedValue != nil {
                    signInCard
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                zoomControls
            }
            .padding()
        }
        .background(.black)
        .navigationTitle("扫码签到")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .animation(.easeInOut(duration: 0.25), value: scanner.scannedValue != nil)
        .onAppear { scanner.start() }
        .onDisappear { scanner.stop() }
        .task(id: scanner.scanID) { [scanID = scanner.scanID] in
            await submitSignIn(scanID: scanID)
        }
    }

    private var signInCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: scannedTarget == nil ? "qrcode" : "qrcode.viewfinder")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(scannedTarget == nil ? Color.orange : Color.accentColor)
                    .frame(width: 44, height: 44)
                    .background(.primary.opacity(0.06), in: Circle())

                VStack(alignment: .leading, spacing: 4) {
                    Text(cardTitle)
                        .font(.headline)
                    if !submittedAccounts.isEmpty {
                        Text("已完成 \(results.count) / \(submittedAccounts.count) 个账号")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                }

                Spacer(minLength: 8)

                Button(action: rescan) {
                    Image(systemName: "arrow.clockwise")
                        .font(.subheadline.weight(.semibold))
                        .frame(width: 40, height: 40)
                        .background(.primary.opacity(0.06), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("重新扫描")
                .disabled(isSubmitting)
            }

            if let scannedTarget {
                LabeledContent("课程代码") {
                    Text(scannedTarget.courseCode)
                        .monospacedDigit()
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)

                if submittedAccounts.isEmpty {
                    Text(accountStore.accounts.isEmpty
                         ? "请先添加 Canvas 账号。"
                         : "请先在账号列表勾选参与签到的账号。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    Divider()

                    ScrollView {
                        VStack(spacing: 14) {
                            ForEach(submittedAccounts) { account in
                                resultRow(for: account)
                            }
                        }
                    }
                    .frame(height: min(Double(submittedAccounts.count) * resultRowHeight, resultRowHeight * 3))
                    .accessibilityIdentifier("signInResults")
                }
            } else {
                Text("这不是有效的 Canvas 签到二维码。")
                    .font(.subheadline)
                    .foregroundStyle(.orange)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .modifier(ScannerGlassSurface(cornerRadius: 28))
    }

    private var cardTitle: String {
        if scannedTarget == nil { return "无法识别二维码" }
        if isSubmitting { return "正在并发签到" }
        return submittedAccounts.isEmpty ? "二维码已识别" : "签到结果"
    }

    private var zoomControls: some View {
        HStack(spacing: 12) {
            Button {
                scanner.setZoom(scanner.zoomFactor - 0.5)
            } label: {
                Image(systemName: "minus.magnifyingglass")
            }
            .accessibilityLabel("缩小")

            Slider(value: zoomBinding, in: scanner.minimumZoom...max(scanner.maximumZoom, scanner.minimumZoom))
                .disabled(scanner.maximumZoom <= scanner.minimumZoom)

            Button {
                scanner.setZoom(scanner.zoomFactor + 0.5)
            } label: {
                Image(systemName: "plus.magnifyingglass")
            }
            .accessibilityLabel("放大")

            Text(String(format: "%.1f×", scanner.zoomFactor))
                .font(.caption.monospacedDigit())
                .frame(width: 42, alignment: .trailing)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .modifier(ScannerGlassSurface(cornerRadius: 28))
    }

    private func resultRow(for account: CanvasAccount) -> some View {
        let result = results[account.id]
        let signed = result?.status == .success || result?.status == .alreadySigned

        return HStack(alignment: .top, spacing: 12) {
            Group {
                if result != nil {
                    Image(systemName: signed ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(signed ? Color.green : Color.orange)
                } else {
                    ProgressView()
                        .controlSize(.small)
                }
            }
            .frame(width: 24, height: 24)

            VStack(alignment: .leading, spacing: 4) {
                Text(account.displayName)
                    .font(.subheadline.weight(.semibold))
                Text(result?.message ?? "正在签到…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let courseName = result?.courseName {
                    Text(courseName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func rescan() {
        guard !isSubmitting else { return }
        results = [:]
        submittedAccounts = []
        submittedScanID = nil
        scanner.resumeScanning()
    }

    @MainActor
    private func submitSignIn(scanID: UUID?) async {
        guard let scanID,
              scanID == scanner.scanID,
              scanID != submittedScanID,
              let target = scannedTarget,
              !isSubmitting else { return }
        let requests = selectedAccounts.map { account in
            (account: account, cookie: accountStore.cookieValue(for: account))
        }
        guard !requests.isEmpty else { return }

        submittedScanID = scanID
        submittedAccounts = requests.map(\.account)
        isSubmitting = true
        results = [:]
        defer { isSubmitting = false }

        await withTaskGroup(of: (UUID, CanvasSignResult).self) { group in
            for request in requests {
                group.addTask {
                    let result: CanvasSignResult
                    if let cookie = request.cookie {
                        result = await CanvasSignInClient.sign(target: target, cookieValue: cookie)
                    } else {
                        result = CanvasSignResult(
                            status: .expiredSession,
                            message: "尚未填写 Cookie，请到账号详情填写或重新登录。",
                            httpCode: nil,
                            courseName: nil
                        )
                    }
                    return (request.account.id, result)
                }
            }

            for await (accountID, result) in group {
                results[accountID] = result
            }
        }
    }
}

private struct ScannerGlassSurface: ViewModifier {
    let cornerRadius: CGFloat

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: shape)
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .overlay(shape.strokeBorder(.white.opacity(0.16), lineWidth: 1))
        }
    }
}
