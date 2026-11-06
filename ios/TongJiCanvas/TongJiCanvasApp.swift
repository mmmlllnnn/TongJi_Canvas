import SwiftUI

@main
struct TongJiCanvasApp: App {
    @StateObject private var accountStore = AccountStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(accountStore)
        }
    }
}
