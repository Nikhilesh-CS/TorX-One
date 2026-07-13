import SwiftUI

@main
struct TorX OneApp: App {
    @StateObject private var chatViewModel = ChatViewModel()
    
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(chatViewModel)
        }
    }
}
