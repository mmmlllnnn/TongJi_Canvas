import AVFoundation
import Combine
import SwiftUI
import UIKit

final class CameraScannerModel: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate {
    let session = AVCaptureSession()

    @Published private(set) var status = "相机准备中"
    @Published private(set) var scannedValue: String?
    @Published private(set) var scanID: UUID?
    @Published private(set) var zoomFactor = 1.0
    @Published private(set) var minimumZoom = 1.0
    @Published private(set) var maximumZoom = 1.0

    private let sessionQueue = DispatchQueue(label: "com.mln.tongji-canvas.camera")
    private let metadataOutput = AVCaptureMetadataOutput()
    private var captureDevice: AVCaptureDevice?
    private var isConfigured = false

    func start() {
        guard scannedValue == nil else { return }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            status = "等待相机权限"
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if granted {
                        self.configureAndStart()
                    } else {
                        self.status = "需要相机权限"
                    }
                }
            }
        case .denied, .restricted:
            status = "请在系统设置中允许相机访问"
        @unknown default:
            status = "相机不可用"
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    func resumeScanning() {
        scannedValue = nil
        scanID = nil
        status = "对准二维码"
        start()
    }

    func setZoom(_ value: Double) {
        let requested = min(max(value, minimumZoom), maximumZoom)
        sessionQueue.async { [weak self] in
            guard let self, let device = self.captureDevice else { return }
            do {
                try device.lockForConfiguration()
                let applied = min(max(CGFloat(requested), device.minAvailableVideoZoomFactor), device.maxAvailableVideoZoomFactor)
                device.videoZoomFactor = applied
                device.unlockForConfiguration()
                DispatchQueue.main.async { self.zoomFactor = Double(applied) }
            } catch {
                DispatchQueue.main.async { self.status = "无法调整相机缩放" }
            }
        }
    }

    func zoom(by scale: CGFloat) {
        setZoom(zoomFactor * Double(scale))
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard scannedValue == nil,
              let code = metadataObjects
                .compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
                .first(where: { $0.type == .qr })?.stringValue else { return }

        scannedValue = code
        scanID = UUID()
        status = "已识别二维码"
        stop()
    }

    private func configureAndStart() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            if self.isConfigured {
                if !self.session.isRunning { self.session.startRunning() }
                DispatchQueue.main.async { self.status = "对准二维码" }
                return
            }

            self.session.beginConfiguration()
            self.session.sessionPreset = .high
            do {
                guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
                    throw ScannerSetupError.cameraUnavailable
                }
                let input = try AVCaptureDeviceInput(device: device)
                guard self.session.canAddInput(input) else { throw ScannerSetupError.setupFailed }
                self.session.addInput(input)
                guard self.session.canAddOutput(self.metadataOutput) else { throw ScannerSetupError.setupFailed }
                self.session.addOutput(self.metadataOutput)
                guard self.metadataOutput.availableMetadataObjectTypes.contains(.qr) else {
                    throw ScannerSetupError.qrUnavailable
                }
                self.metadataOutput.setMetadataObjectsDelegate(self, queue: .main)
                self.metadataOutput.metadataObjectTypes = [.qr]
                self.captureDevice = device
                self.session.commitConfiguration()
                self.isConfigured = true
                self.session.startRunning()

                let minZoom = Double(device.minAvailableVideoZoomFactor)
                let maxZoom = min(Double(device.maxAvailableVideoZoomFactor), 8.0)
                DispatchQueue.main.async {
                    self.minimumZoom = minZoom
                    self.maximumZoom = max(maxZoom, minZoom)
                    self.zoomFactor = min(max(1.0, minZoom), max(maxZoom, minZoom))
                    self.status = "对准二维码"
                }
            } catch let error as ScannerSetupError {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.status = error.localizedDescription }
            } catch {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.status = "相机初始化失败" }
            }
        }
    }
}

private enum ScannerSetupError: LocalizedError {
    case cameraUnavailable
    case qrUnavailable
    case setupFailed

    var errorDescription: String? {
        switch self {
        case .cameraUnavailable: "找不到后置相机"
        case .qrUnavailable: "当前设备不支持二维码识别"
        case .setupFailed: "相机启动失败"
        }
    }
}

struct NativeCameraPreview: UIViewRepresentable {
    @ObservedObject var scanner: CameraScannerModel

    func makeUIView(context: Context) -> CameraPreviewHostView {
        let view = CameraPreviewHostView()
        view.previewLayer.session = scanner.session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.onPinch = { [weak scanner] scale in scanner?.zoom(by: scale) }
        return view
    }

    func updateUIView(_ uiView: CameraPreviewHostView, context: Context) {
        if uiView.previewLayer.session !== scanner.session {
            uiView.previewLayer.session = scanner.session
        }
    }
}

final class CameraPreviewHostView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    var onPinch: ((CGFloat) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        let gesture = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        addGestureRecognizer(gesture)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    @objc private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        guard gesture.state == .changed else { return }
        onPinch?(gesture.scale)
        gesture.scale = 1
    }
}
