import SwiftUI

@main
struct TongJiCanvasApp: App {
    @StateObject private var accountStore = AccountStore()
    @StateObject private var historyStore = SignHistoryStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(accountStore)
                .environmentObject(historyStore)
        }
    }
}
