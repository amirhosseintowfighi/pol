import SwiftUI

struct MessagesView: View {
    @Environment(AppModel.self) private var model
    @State private var compose = false
    @State private var unreadOnly = false
    @State private var path: [ThreadRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            let _ = openScreenshotThread()
            List(unreadOnly ? model.conversations.filter { $0.unread > 0 } : model.conversations) { c in
                NavigationLink(value: ThreadRoute(thread: c.thread, address: c.address)) { ConversationRow(c: c) }
                    .swipeActions { Button { model.markRead(thread: c.thread) } label: { Label(L("messages.markRead"), systemImage: "envelope.open") }.tint(.blue) }
            }
            .listStyle(.plain)
            .safeAreaInset(edge: .top, spacing: 0) { LinkBanner() }
            .overlay {
                if model.caps?.smsRead == false {
                    ContentUnavailableView(L("messages.off.title"), systemImage: "message.badge", description: Text(L("messages.off.body")))
                } else if model.conversations.isEmpty {
                    ContentUnavailableView(L("messages.empty.title"), systemImage: "message", description: Text(L("messages.empty.body")))
                }
            }
            .navigationTitle(L("tab.messages"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { GatewayButton() }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker(L("calls.filter"), selection: $unreadOnly) {
                            Label(L("messages.filter.all"), systemImage: "message").tag(false)
                            Label(L("messages.filter.unread"), systemImage: "message.badge").tag(true)
                        }
                    } label: { Image(systemName: "line.3.horizontal.decrease") }
                    .accessibilityLabel(L("calls.filter"))
                }
                ToolbarItem(placement: .topBarTrailing) {
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

/// A conversation in the list, as in Messages: unread dot, picture, name, date and a two-line preview.
struct ConversationRow: View {
    @Environment(AppModel.self) private var model
    let c: Conversation
    var body: some View {
        let name = model.name(for: c.address)
        HStack(alignment: .center, spacing: 10) {
            Circle().fill(c.unread > 0 ? Color.blue : .clear).frame(width: 10, height: 10)
                .accessibilityHidden(true)
            Avatar(name: name ?? "#", size: 50)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(verbatim: name ?? ltr(c.address)).font(.body.weight(.semibold)).lineLimit(1)
                    Spacer()
                    Text(verbatim: Fmt.relative(c.last.date)).font(.subheadline).foregroundStyle(.secondary)
                    Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                }
                Text(verbatim: (c.last.dir == .outgoing ? L("messages.you") : "") + c.last.body)
                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(2, reservesSpace: true)
            }
        }
        .padding(.vertical, 2)
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

    var body: some View {
        let items = model.thread(threadId, address: address)
        let canSend = model.caps?.smsSend != false && Validate.smsRecipient(address) != nil
        let lastOut: String? = items.last(where: { $0.dir == .outgoing })?.key
        let name = model.name(for: address)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 3) {
                    ForEach(Array(items.enumerated()), id: \.element.key) { i, _ in
                        threadRow(items, i, lastOut: lastOut)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: items.count) { _, _ in if let last = items.last { withAnimation { proxy.scrollTo(last.key, anchor: .bottom) } } }
        }
        .safeAreaInset(edge: .bottom) {
            SmsComposer(text: $text, sub: $sub, enabled: canSend) {
                let r = model.sendSms(to: address, body: text, subId: sub)
                if r == .ok || r == .notConnected { text = "" }
                alert = resultMessage(r)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 2) {
                    Avatar(name: name ?? "#", size: 38)
                    HStack(spacing: 2) {
                        Text(verbatim: name ?? ltr(address)).font(.caption.weight(.semibold)).lineLimit(1)
                        Image(systemName: "chevron.forward").font(.system(size: 8, weight: .bold)).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 9).padding(.vertical, 3)
                    .glassPanel()
                }
                .accessibilityElement(children: .combine)
            }
            if Validate.dialNumber(address) != nil {
                ToolbarItem(placement: .topBarTrailing) {
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

    /// One message, with the date header above it when a new group starts and the tail on the last of a run.
    @ViewBuilder private func threadRow(_ items: [Sms], _ i: Int, lastOut: String?) -> some View {
        let m: Sms = items[i]
        let newGroup: Bool = i == 0 || m.date.timeIntervalSince(items[i - 1].date) > 3600
        let tail: Bool = isLastOfRun(items, i)
        let status: Bool = m.key == lastOut || m.state == .failed
        if newGroup {
            VStack(spacing: 1) {
                if i == 0 { Text(L("thread.smsPlaceholder")) }
                Text(verbatim: header(m))
            }
            .font(.caption2.weight(.medium)).foregroundStyle(.secondary)
            .padding(.top, i == 0 ? 4 : 14).padding(.bottom, 6)
        }
        Bubble(m: m, tail: tail, showStatus: status)
            .padding(.bottom, tail ? 6 : 0)
            .id(m.key)
    }

    private func isLastOfRun(_ items: [Sms], _ i: Int) -> Bool {
        guard i + 1 < items.count else { return true }
        let next = items[i + 1]
        if next.dir != items[i].dir { return true }
        return next.date.timeIntervalSince(items[i].date) > 3600
    }

    /// "Today 12:15 · Personal", as Messages shows above a group of texts.
    private func header(_ m: Sms) -> String {
        let cal = Calendar.current
        let day = cal.isDateInToday(m.date) ? L("thread.today") : (cal.isDateInYesterday(m.date) ? L("time.yesterday") : m.date.formatted(.dateTime.weekday(.abbreviated).day().month()))
        return [day + " " + m.date.formatted(date: .omitted, time: .shortened), model.simLabel(m.subId)].compactMap { $0 }.joined(separator: " · ")
    }
}

private func placeholder(enabled: Bool, line: String?) -> String {
    if !enabled { return L("thread.cannotReply") }
    if let line { return line + " · SMS" }
    return L("thread.smsPlaceholder")
}

/// The Messages compose bar: a round + button (the SIM to send from) and a glass text field with the send button.
struct SmsComposer: View {
    @Environment(AppModel.self) private var model
    @Binding var text: String
    @Binding var sub: Int?
    let enabled: Bool
    let send: () -> Void

    var body: some View {
        let sims = model.status?.sims ?? []
        let several = sims.count > 1 && model.caps?.simChoice == true
        let line = sims.first { $0.subId == sub }?.displayLabel
        VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 8) {
                Menu {
                    if several {
                        Picker(L("sim.choose"), selection: $sub) {
                            ForEach(sims, id: \.subId) { s in Label(s.displayLabel, systemImage: "simcard").tag(Optional(s.subId)) }
                        }
                    }
                } label: {
                    Image(systemName: "plus").font(.system(size: 18, weight: .medium)).foregroundStyle(.primary)
                        .frame(width: 40, height: 40).glassCircle()
                }
                .disabled(!several)
                .accessibilityLabel(L("sim.choose"))
                HStack(alignment: .bottom, spacing: 4) {
                    TextField(placeholder(enabled: enabled, line: several ? line : nil), text: $text, axis: .vertical)
                        .lineLimit(1...5)
                        .padding(.leading, 16).padding(.vertical, 10)
                        .disabled(!enabled)
                    if Validate.smsBody(text) != nil {
                        Button(action: send) {
                            Image(systemName: "arrow.up").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 30, height: 30).background(Circle().fill(Color.smsGreen))
                        }
                        .disabled(!enabled)
                        .padding(5)
                        .accessibilityLabel(L("thread.send"))
                    }
                }
                .frame(minHeight: 40)
                .glassPanel(cornerRadius: 20, interactive: true)
            }
            if text.count > 140 { Text(verbatim: "\(text.count)/\(Validate.maxSmsChars)").font(.caption2).foregroundStyle(.secondary) }
        }
        .padding(.horizontal, 14).padding(.top, 6).padding(.bottom, 8)
    }
}

extension Color {
    /// The green of text-message (SMS) bubbles on iPhone.
    static let smsGreen = Color(red: 0.20, green: 0.78, blue: 0.35)
}

private struct Bubble: View {
    @Environment(AppModel.self) private var model
    let m: Sms
    let tail: Bool
    let showStatus: Bool

    var body: some View {
        let out = m.dir == .outgoing
        let fill = out ? Color.smsGreen : Color(.systemGray5)
        VStack(alignment: out ? .trailing : .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(verbatim: m.body)
                    .padding(.horizontal, 13).padding(.vertical, 8)
                    .foregroundStyle(out ? .white : .primary)
                    .background {
                        ZStack {
                            RoundedRectangle(cornerRadius: 18, style: .continuous).fill(fill)
                            if tail { BubbleTail(outgoing: out).fill(fill).flipsForRightToLeftLayoutDirection(true) }
                        }
                    }
                    .textSelection(.enabled)
                    .contextMenu { Button { UIPasteboard.general.string = m.body } label: { Label(L("copy"), systemImage: "doc.on.doc") } }
                if m.state == .failed {
                    Image(systemName: "exclamationmark.circle").font(.title3).foregroundStyle(.red)
                        .accessibilityLabel(L("thread.notSent"))
                }
            }
            if showStatus, let stateText {
                HStack(spacing: 4) {
                    Text(verbatim: stateText).font(.caption2.weight(.semibold)).foregroundStyle(m.state == .failed ? .red : .secondary)
                    if m.state == .failed, let c = m.cmid {
                        Button(L("thread.retry")) { _ = model.retry(cmid: c) }.font(.caption2.weight(.semibold))
                    }
                }
                .padding(.horizontal, 6)
            }
        }
        .frame(maxWidth: .infinity, alignment: out ? .trailing : .leading)
        .padding(out ? .leading : .trailing, 60)
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

/// The small curl at the bottom corner of the last bubble in a group, as in Messages. Drawn for the right side
/// (outgoing in left-to-right layouts) and mirrored for incoming.
struct BubbleTail: Shape {
    let outgoing: Bool
    func path(in r: CGRect) -> Path {
        let w = r.width, h = r.height
        func x(_ v: CGFloat) -> CGFloat { outgoing ? v : w - v }
        var p = Path()
        p.move(to: CGPoint(x: x(w - 3), y: h - 18))
        p.addQuadCurve(to: CGPoint(x: x(w + 5), y: h), control: CGPoint(x: x(w - 1), y: h - 4))
        p.addQuadCurve(to: CGPoint(x: x(w - 16), y: h - 3), control: CGPoint(x: x(w - 6), y: h + 1))
        p.closeSubpath()
        return p
    }
}

/// New Message: a "To:" field in glass, suggestions from contacts, and the compose bar. Sending opens the conversation.
private struct NewMessageSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let open: (String) -> Void
    @State private var to = ""
    @State private var text = ""
    @State private var sub: Int?
    @State private var alert: String?
    @FocusState private var toFocused: Bool

    private func suggestions() -> [Contact] {
        let q = to.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return [] }
        let digits = q.filter(\.isNumber)
        var out: [Contact] = []
        for c in model.contacts {
            let byName = c.name.localizedCaseInsensitiveContains(q)
            let byNumber = !digits.isEmpty && c.phones.contains { $0.number.contains(digits) }
            if byName || byNumber { out.append(c) }
            if out.count == 12 { break }
        }
        return out
    }

    var body: some View {
        let recipient = Validate.smsRecipient(to)
        let matches: [Contact] = suggestions()
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 6) {
                    Text(L("messages.to")).foregroundStyle(.secondary)
                    TextField("", text: $to).keyboardType(.phonePad).textContentType(.telephoneNumber)
                        .foregroundStyle(recipient != nil ? Color.smsGreen : .primary)
                        .focused($toFocused)
                    Image(systemName: "plus.circle.fill").font(.title3).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 16).padding(.vertical, 11)
                .glassPanel(cornerRadius: 22)
                .padding(.horizontal, 14).padding(.top, 8)
                if recipient != nil && matches.isEmpty {
                    Text(L("thread.smsPlaceholder")).font(.caption2.weight(.medium)).foregroundStyle(.secondary).padding(.top, 10)
                }
                List {
                    ForEach(matches) { c in
                        ForEach(c.phones, id: \.number) { p in
                            Button { to = p.number; toFocused = false } label: {
                                HStack(spacing: 12) {
                                    Avatar(name: c.name, size: 36)
                                    VStack(alignment: .leading) {
                                        Text(verbatim: c.name).font(.body.weight(.semibold))
                                        Text(verbatim: ltr(p.number)).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
            .safeAreaInset(edge: .bottom) {
                SmsComposer(text: $text, sub: $sub, enabled: recipient != nil && model.caps?.smsSend != false) {
                    guard let n = recipient else { return }
                    let r = model.sendSms(to: n, body: text, subId: sub)
                    alert = resultMessage(r)
                    if r == .ok || r == .notConnected { text = ""; open(n) }
                }
            }
            .navigationTitle(L("messages.new"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }.accessibilityLabel(L("cancel"))
                }
            }
            .onAppear { sub = model.defaultSmsSub; toFocused = true }
            .alert(alert ?? "", isPresented: .init(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button(L("ok")) {} }
        }
    }
}
