import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var tab = 0
    @State private var showError = false

    var body: some View {
        Group {
            if model.gateway == nil && !model.demo {
                OnboardingView()
            } else {
                TabView(selection: $tab) {
                    Tab(L("tab.calls"), systemImage: "clock", value: 0) { RecentsView() }
                        .badge(model.missedCount)
                    Tab(L("tab.contacts"), systemImage: "person.crop.circle", value: 1) { ContactsView() }
                    Tab(L("tab.keypad"), systemImage: "circle.grid.3x3", value: 2) { KeypadView() }
                    Tab(L("tab.messages"), systemImage: "message", value: 3) { MessagesView() }
                        .badge(model.unreadCount)
                    Tab(L("tab.gateway"), systemImage: "iphone.gen3.radiowaves.left.and.right", value: 4) { GatewayView() }
                }
                .fullScreenCover(isPresented: .constant(!model.liveCalls.isEmpty)) { InCallView() }
            }
        }
        .onChange(of: model.lastError) { _, e in showError = e != nil }
        .onChange(of: model.screenshotTab) { _, t in if let t { tab = t } }
        .onAppear {
            #if DEBUG
            if let s = AppModel.screenshotArgument { model.prepareScreenshot(s) }
            #endif
        }
        .alert(describeError(model.lastError ?? ""), isPresented: $showError) {
            Button(L("ok")) { model.lastError = nil }
        }
    }
}
