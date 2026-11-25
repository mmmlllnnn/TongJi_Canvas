// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CanvasSignInCore",
    platforms: [.macOS(.v12), .iOS(.v17)],
    targets: [
        .target(
            name: "CanvasSignInCore",
            path: "TongJiCanvas",
            exclude: [
                "Assets.xcassets",
                "CanvasLoginView.swift",
                "CanvasLoginController.swift",
                "ContentView.swift",
                "CourseEditorView.swift",
                "NativeQRScannerView.swift",
                "CanvasScannerView.swift",
                "SignHistoryView.swift",
                "TongJiCanvas.entitlements",
                "TongJiCanvasApp.swift"
            ],
            sources: [
                "AccountStore.swift",
                "AccountTransfer.swift",
                "CanvasSessionCookie.swift",
                "CanvasSignInClient.swift",
                "SignHistoryStore.swift"
            ]
        ),
        .testTarget(
            name: "CanvasSignInCoreTests",
            dependencies: ["CanvasSignInCore"],
            path: "Tests",
            resources: [.copy("Fixtures")]
        )
    ]
)
