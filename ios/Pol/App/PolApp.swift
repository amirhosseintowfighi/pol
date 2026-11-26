import SwiftUI

@main
struct PolApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .onChange(of: phase) { _, p in model.inBackground = (p != .active) }
        }
    }
}
