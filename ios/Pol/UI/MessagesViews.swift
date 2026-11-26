import SwiftUI

struct MessagesView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var compose = false
    @State private var path: [ThreadRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            let _ = openScreenshotThread()
            VStack(spacing: 0) {
                LinkBanner()
                List(model.searchConversations(query)) { c in
                    NavigationLink(value: ThreadRoute(thread: c.thread, address: c.address)) { ConversationRow(c: c) }
                        .swipeActions { Button { model.markRead(thread: c.thread) } label: { Label(L("messages.markRead"), systemImage: "envelope.open") }.tint(.blue) }
                }
                .listStyle(.plain)
                .overlay {
                    if model.caps?.smsRead == false {
                        ContentUnavailableView(L("messages.off.title"), systemImage: "message.badge", description: Text(L("messages.off.body")))
                    } else if model.conversations.isEmpty {
                        ContentUnavailableView(L("messages.empty.title"), systemImage: "message", description: Text(L("messages.empty.body")))
                    } else if !query.isEmpty && model.searchConversations(query).isEmpty {
                        ContentUnavailableView.search(text: query)
                    }
                }
            }
            .navigationTitle(L("tab.messages"))
            .searchable(text: $query, prompt: L("search.messages"))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { compose = true } label: { Image(systemName: "square.and.pencil") }
                        .disabled(model.caps?.smsSend == false)
                        .accessibilityLabel(L("messages.new"))
                }
            }
            .navigationDestination(for: ThreadRoute.self) { r in ThreadView(threadId: r.thread, address: r.address) }
            .sheet(isPresented: $compose) {
                NewMessageSheet { number in compose = false; path.append(ThreadRoute(thread: model.threadId(for: number), address: number)) }
            }
        }
    }
}

struct ThreadRoute: Hashable { let thread: Int64; let address: String }

extension MessagesView {
    /// Debug screenshots: open the requested conversation once.
    fileprivate func openScreenshotThread() {
        guard let r = model.screenshotThread else { return }
        DispatchQueue.main.async { MainActor.assumeIsolated { path = [r]; model.screenshotThread = nil } }
    }
}

private struct ConversationRow: View {
    @Environment(AppModel.self) private var model
    let c: Conversation
    var body: some View {
        let name = model.name(for: c.address)
        HStack(alignment: .top, spacing: 12) {
            Circle().fill(c.unread > 0 ? Color.accentColor : .clear).frame(width: 8, height: 8).padding(.top, 18)
                .accessibilityHidden(true)
            Avatar(name: name ?? "#", size: 44)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(verbatim: name ?? ltr(c.address)).font(.body.weight(.semibold)).lineLimit(1)
                    Spacer()
                    Text(verbatim: Fmt.relative(c.last.date)).font(.subheadline).foregroundStyle(.secondary)
                }
                Text(verbatim: (c.last.dir == .outgoing ? L("messages.you") : "") + c.last.body).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(c.unread > 0 ? L("a11y.unread %d", c.unread) : "")
    }
}

struct ThreadView: View {
    @Environment(AppModel.self) private var model
    let threadId: Int64
    let address: String
    @State private var text = ""
    @State private var sub: Int?
    @State private var alert: String?
    @FocusState private var focused: Bool

    var body: some View {
        let items = model.thread(threadId, address: address)
        let canSend = model.caps?.smsSend != false && Validate.smsRecipient(address) != nil
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(items, id: \.key) { m in Bubble(m: m).id(m.key) }
                }.padding(.horizontal, 12).padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: items.count) { _, _ in if let last = items.last { withAnimation { proxy.scrollTo(last.key, anchor: .bottom) } } }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 6) {
                SimMenu(subId: $sub)
                HStack(alignment: .bottom, spacing: 8) {
                    TextField(canSend ? L("thread.placeholder") : L("thread.cannotReply"), text: $text, axis: .vertical)
                        .lineLimit(1...5)
                        .focused($focused)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(RoundedRectangle(cornerRadius: 20).strokeBorder(Color(.separator)))
                        .disabled(!canSend)
                    Button {
                        let r = model.sendSms(to: address, body: text, subId: sub)
                        if r == .ok || r == .notConnected { text = "" }
                        alert = resultMessage(r)
                    } label: { Image(systemName: "arrow.up.circle.fill").font(.system(size: 32)) }
                        .disabled(!canSend || Validate.smsBody(text) == nil)
                        .accessibilityLabel(L("thread.send"))
                }
                if text.count > 140 { Text(verbatim: "\(text.count)/\(Validate.maxSmsChars)").font(.caption2).foregroundStyle(.secondary) }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(.bar)
        }
        .navigationTitle(model.name(for: address) ?? ltr(address))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if Validate.dialNumber(address) != nil {
                ToolbarItem(placement: .primaryAction) {
                    Button { alert = resultMessage(model.dial(address, subId: model.defaultVoiceSub)) } label: { Image(systemName: "phone") }
                        .accessibilityLabel(L("call"))
                }
            }
        }
        .onAppear {
            sub = items.last?.subId ?? model.defaultSmsSub
            if threadId > 0 { model.markRead(thread: threadId) }
        }
        .onChange(of: items.filter(\.isUnread).count) { _, n in if n > 0, threadId > 0 { model.markRead(thread: threadId) } }
        .alert(alert ?? "", isPresented: .init(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button(L("ok")) {} }
    }
}

private struct Bubble: View {
    @Environment(AppModel.self) private var model
    let m: Sms

    var body: some View {
        let out = m.dir == .outgoing
        VStack(alignment: out ? .trailing : .leading, spacing: 2) {
            Text(verbatim: m.body)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .foregroundStyle(out ? .white : .primary)
                .background(RoundedRectangle(cornerRadius: 18).fill(out ? Color.accentColor : Color(.secondarySystemBackground)))
                .textSelection(.enabled)
                .contextMenu { Button { UIPasteboard.general.string = m.body } label: { Label(L("copy"), systemImage: "doc.on.doc") } }
            HStack(spacing: 4) {
                if m.state == .failed { Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red) }
                Text(verbatim: [m.date.formatted(date: .omitted, time: .shortened), stateText, model.simLabel(m.subId)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption2).foregroundStyle(m.state == .failed ? .red : .secondary)
                if m.state == .failed, let c = m.cmid {
                    Button(L("thread.retry")) { _ = model.retry(cmid: c) }.font(.caption2.weight(.semibold))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: out ? .trailing : .leading)
        .padding(out ? .leading : .trailing, 48)
    }

    private var stateText: String? {
        switch m.state {
        case .pending: return L("thread.sending")
        case .sent: return L("thread.sent")
        case .delivered: return L("thread.delivered")
        case .failed: return L("thread.notSent")
        default: return nil
        }
    }
}

private struct NewMessageSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let open: (String) -> Void
    @State private var to = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField(L("messages.to"), text: $to).keyboardType(.phonePad).textContentType(.telephoneNumber)
                }
                let matches = model.contacts.filter { to.count >= 2 && $0.name.localizedCaseInsensitiveContains(to) }.prefix(8)
                if !matches.isEmpty {
                    Section {
                        ForEach(Array(matches)) { c in
                            ForEach(c.phones, id: \.number) { p in
                                Button { open(p.number) } label: {
                                    HStack { Avatar(name: c.name, size: 32); VStack(alignment: .leading) { Text(verbatim: c.name); Text(verbatim: ltr(p.number)).font(.caption).foregroundStyle(.secondary) } }
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .navigationTitle(L("messages.new"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("next")) { if let n = Validate.smsRecipient(to) { open(n) } }.disabled(Validate.smsRecipient(to) == nil)
                }
            }
        }
    }
}
