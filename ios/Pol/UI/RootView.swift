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
                // On iOS 26 and later this is the floating Liquid Glass tab bar, with search as its own round button.
                TabView(selection: $tab) {
                    Tab(L("tab.calls"), systemImage: "clock.fill", value: 0) { RecentsView() }
                        .badge(model.missedCount)
                    Tab(L("tab.contacts"), systemImage: "person.crop.circle.fill", value: 1) { ContactsView() }
                    Tab(L("tab.keypad"), systemImage: "circle.grid.3x3.fill", value: 2) { KeypadView() }
                    Tab(L("tab.messages"), systemImage: "message.fill", value: 3) { MessagesView() }
                        .badge(model.unreadCount)
                    Tab(L("tab.search"), systemImage: "magnifyingglass", value: 4, role: .search) { SearchView() }
                }
                .sheet(isPresented: Binding(get: { model.showGateway }, set: { model.showGateway = $0 })) { GatewayView() }
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

/// One search across contacts, recent calls and messages, like the Phone app's search tab.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var alert: String?

    var body: some View {
        let q = query.trimmingCharacters(in: .whitespaces)
        let digits = q.filter(\.isNumber)
        let contacts = q.isEmpty ? [] : model.contacts.filter { c in
            c.name.localizedCaseInsensitiveContains(q) || (!digits.isEmpty && c.phones.contains { $0.number.contains(digits) })
        }
        let calls = q.isEmpty ? [] : model.calls.filter { e in
            (e.name ?? model.name(for: e.number) ?? "").localizedCaseInsensitiveContains(q) || (!digits.isEmpty && e.number.contains(digits))
        }
        let conversations = q.isEmpty ? [] : model.searchConversations(q)
        NavigationStack {
            List {
                if !contacts.isEmpty {
                    Section(L("tab.contacts")) {
                        ForEach(contacts.prefix(20)) { c in
                            NavigationLink { ContactDetail(contact: c) } label: {
                                HStack(spacing: 12) { Avatar(name: c.name, size: 40); Text(verbatim: c.name).font(.body.weight(.semibold)) }
                            }
                        }
                    }
                }
                if !calls.isEmpty {
                    Section(L("calls.recents")) {
                        ForEach(calls.prefix(20)) { e in
                            RecentRow(entry: e) { alert = resultMessage(model.dial(e.number, subId: e.subId ?? model.defaultVoiceSub)) }
                        }
                    }
                }
                if !conversations.isEmpty {
                    Section(L("tab.messages")) {
                        ForEach(conversations.prefix(20)) { c in
                            NavigationLink { ThreadView(threadId: c.thread, address: c.address) } label: { ConversationRow(c: c) }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if q.isEmpty {
                    ContentUnavailableView(L("search.empty.title"), systemImage: "magnifyingglass", description: Text(L("search.empty.body")))
                } else if contacts.isEmpty && calls.isEmpty && conversations.isEmpty {
                    ContentUnavailableView.search(text: q)
                }
            }
            .navigationTitle(L("tab.search"))
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: L("search.all"))
            .alert(alert ?? "", isPresented: .init(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button(L("ok")) {} }
        }
    }
}
