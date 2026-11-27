import SwiftUI

// MARK: - Recents

struct RecentsView: View {
    @Environment(AppModel.self) private var model
    @State private var detail: CallEntry?
    @State private var alert: String?
    @State private var missedOnly = false

    private var shown: [CallEntry] {
        missedOnly ? model.calls.filter { $0.type == .missed || $0.type == .rejected } : model.calls
    }

    var body: some View {
        NavigationStack {
            List {
                let favourites = model.contacts.filter { $0.starred == true }
                if !favourites.isEmpty && !missedOnly {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 18) {
                            ForEach(favourites) { c in
                                Button { call(c.phones.first?.number ?? "", nil) } label: {
                                    VStack(spacing: 6) {
                                        Avatar(name: c.name, size: 64)
                                        Text(verbatim: c.name.components(separatedBy: " ").first ?? c.name).font(.footnote.weight(.medium)).lineLimit(1)
                                    }
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(L("call.x %@", c.name))
                            }
                        }
                        .padding(.horizontal, 20).padding(.vertical, 6)
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                }
                ForEach(shown) { e in
                    RecentRow(entry: e) { call(e.number, e.subId) }
                        .contentShape(Rectangle())
                        .onTapGesture { detail = e }
                        .swipeActions(edge: .leading) {
                            Button { call(e.number, e.subId) } label: { Label(L("call"), systemImage: "phone.fill") }.tint(.green)
                        }
                        .contextMenu {
                            Button { call(e.number, e.subId) } label: { Label(L("call"), systemImage: "phone") }
                            Button { UIPasteboard.general.string = e.number } label: { Label(L("copy"), systemImage: "doc.on.doc") }
                            Button { detail = e } label: { Label(L("details"), systemImage: "info.circle") }
                        }
                }
            }
            .listStyle(.plain)
            .safeAreaInset(edge: .top, spacing: 0) { LinkBanner() }
            .overlay {
                if model.caps?.callLog == false {
                    ContentUnavailableView(L("calls.off.title"), systemImage: "clock.badge.xmark", description: Text(L("calls.off.body")))
                } else if model.calls.isEmpty {
                    ContentUnavailableView(L("calls.empty.title"), systemImage: "clock", description: Text(L("calls.empty.body")))
                }
            }
            .navigationTitle(L("tab.calls"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { GatewayButton() }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker(L("calls.filter"), selection: $missedOnly) {
                            Label(L("calls.filter.all"), systemImage: "phone").tag(false)
                            Label(L("calls.filter.missed"), systemImage: "phone.arrow.down.left").tag(true)
                        }
                    } label: { Image(systemName: "line.3.horizontal.decrease") }
                    .accessibilityLabel(L("calls.filter"))
                }
            }
            .sheet(item: $detail) { e in CallDetailSheet(entry: e) }
            .alert(alert ?? "", isPresented: .init(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button(L("ok")) {} }
        }
    }

    private func call(_ number: String, _ sub: Int?) {
        alert = resultMessage(model.dial(number, subId: sub ?? model.defaultVoiceSub))
    }
}

/// One recent call, laid out like the Phone app: picture, name, direction and line, date, and a round call button.
struct RecentRow: View {
    @Environment(AppModel.self) private var model
    let entry: CallEntry
    let call: () -> Void

    var body: some View {
        let name = entry.name ?? model.name(for: entry.number)
        let missed = entry.type == .missed || entry.type == .rejected
        HStack(spacing: 12) {
            Avatar(name: name ?? "#", size: 46)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: name ?? ltr(entry.number.isEmpty ? L("unknownNumber") : entry.number))
                    .font(.body.weight(.semibold)).foregroundStyle(missed ? .red : .primary).lineLimit(1)
                HStack(spacing: 4) {
                    if let arrow { Image(systemName: arrow).font(.caption2.weight(.semibold)) }
                    Text(verbatim: model.simLabel(entry.subId) ?? typeLabel).lineLimit(1)
                }
                .font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            Text(verbatim: Fmt.relative(entry.date)).font(.subheadline).foregroundStyle(.secondary)
            Button(action: call) {
                Image(systemName: "phone.fill").font(.system(size: 17)).foregroundStyle(.blue)
                    .frame(width: 40, height: 40)
                    .glassCircle()
            }
            .buttonStyle(.plain)
            .disabled(Validate.dialNumber(entry.number) == nil)
            .accessibilityLabel(L("call.x %@", name ?? entry.number))
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityValue(typeLabel)
    }

    private var arrow: String? {
        switch entry.type {
        case .outgoing: return "arrow.up.right"
        case .incoming: return "arrow.down.left"
        case .blocked: return "nosign"
        default: return nil
        }
    }

    private var typeLabel: String {
        switch entry.type {
        case .incoming: return L("call.incoming"); case .outgoing: return L("call.outgoing")
        case .missed: return L("call.missed"); case .rejected: return L("call.declined")
        case .blocked: return L("call.blocked"); case .other: return L("call")
        }
    }
}

private struct CallDetailSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let entry: CallEntry
    @State private var sub: Int?

    var body: some View {
        let name = entry.name ?? model.name(for: entry.number)
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 10) {
                        Avatar(name: name ?? "#", size: 96)
                        Text(verbatim: name ?? ltr(entry.number)).font(.title.weight(.semibold)).multilineTextAlignment(.center)
                        if name != nil { Text(verbatim: ltr(entry.number)).foregroundStyle(.secondary) }
                        HStack(spacing: 12) {
                            ActionTile(symbol: "phone.fill", title: L("call"), enabled: Validate.dialNumber(entry.number) != nil) {
                                model.dial(entry.number, subId: sub ?? entry.subId ?? model.defaultVoiceSub); dismiss()
                            }
                            NavigationLink { ThreadView(threadId: model.threadId(for: entry.number), address: entry.number) } label: {
                                ActionTileLabel(symbol: "message.fill", title: L("message"))
                            }
                            .buttonStyle(.plain)
                            .disabled(Validate.smsRecipient(entry.number) == nil)
                        }
                        .padding(.top, 6)
                        SimMenu(subId: $sub)
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }
                Section {
                    LabeledContent(L("details.when"), value: entry.date.formatted(date: .abbreviated, time: .shortened))
                    if let d = entry.durationS, d > 0 { LabeledContent(L("details.duration"), value: Fmt.duration(d)) }
                    if let sim = model.simLabel(entry.subId) { LabeledContent(L("details.line"), value: sim) }
                }
            }
            .listStyle(.insetGrouped)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } } }
            .onAppear { sub = entry.subId ?? model.defaultVoiceSub }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Wide glass action button under a contact's name (Call, Message), as on a contact card.
struct ActionTile: View {
    let symbol: String, title: String, enabled: Bool, action: () -> Void
    var body: some View {
        Button(action: action) { ActionTileLabel(symbol: symbol, title: title) }
            .buttonStyle(.plain)
            .disabled(!enabled).opacity(enabled ? 1 : 0.4)
    }
}

struct ActionTileLabel: View {
    let symbol: String, title: String
    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: symbol).font(.system(size: 19))
            Text(verbatim: title).font(.caption.weight(.medium))
        }
        .foregroundStyle(.blue)
        .frame(width: 84, height: 58)
        .glassPanel(cornerRadius: 16, interactive: true)
    }
}

// MARK: - Keypad

struct KeypadView: View {
    @Environment(AppModel.self) private var model
    @State private var number = ""
    @State private var sub: Int?
    @State private var alert: String?
    private let keys: [(String, String)] = [("1", ""), ("2", "ABC"), ("3", "DEF"), ("4", "GHI"), ("5", "JKL"), ("6", "MNO"),
                                            ("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ"), ("*", ""), ("0", "+"), ("#", "")]
    private var isCode: Bool { number.hasPrefix("*") || number.hasPrefix("#") }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                LinkBanner()
                Spacer(minLength: 4)
                Text(verbatim: number.isEmpty ? " " : number)
                    .font(.system(size: 38, weight: .regular)).lineLimit(1).minimumScaleFactor(0.5)
                    .environment(\.layoutDirection, .leftToRight)
                    .padding(.horizontal, 24)
                    .accessibilityLabel(number.isEmpty ? L("keypad.empty") : number)
                Text(verbatim: number.count >= 4 ? (model.name(for: number) ?? " ") : " ")
                    .font(.subheadline).foregroundStyle(.secondary).padding(.top, 2)
                SimMenu(subId: $sub).padding(.top, 6)
                Spacer(minLength: 12)
                // The keypad keeps its standard layout in right-to-left languages, like the system Phone app.
                Grid(horizontalSpacing: 26, verticalSpacing: 18) {
                    ForEach(0..<4) { r in
                        GridRow {
                            ForEach(0..<3) { c in
                                let k = keys[r * 3 + c]
                                Button { if number.count < 32 { number += k.0 } } label: {
                                    VStack(spacing: -2) {
                                        Text(verbatim: k.0).font(.system(size: k.0 == "*" ? 40 : 36, weight: .regular))
                                        Text(verbatim: k.1.isEmpty ? " " : k.1).font(.system(size: 10, weight: .bold)).tracking(2)
                                            .opacity(k.1.isEmpty ? 0 : 1)
                                    }
                                    .foregroundStyle(.primary)
                                    .frame(width: 80, height: 80)
                                    .glassCircle()
                                }
                                .buttonStyle(.plain)
                                .sensoryFeedback(.impact(weight: .light), trigger: number)
                                .accessibilityLabel(k.0)
                            }
                        }
                    }
                }
                .environment(\.layoutDirection, .leftToRight)
                HStack(spacing: 26) {
                    Color.clear.frame(width: 80, height: 80)
                    Button { primary() } label: {
                        Image(systemName: isCode ? "arrow.up" : "phone.fill").font(.system(size: 32)).foregroundStyle(.white)
                            .frame(width: 80, height: 80).background(Circle().fill(Color.green))
                    }
                    .disabled(isCode ? Validate.ussdCode(number) == nil || model.caps?.ussd != true : Validate.dialNumber(number) == nil)
                    .accessibilityLabel(isCode ? L("keypad.sendCode") : L("call"))
                    Button { number = String(number.dropLast()) } label: {
                        Image(systemName: "delete.left.fill").font(.system(size: 24)).foregroundStyle(.secondary).frame(width: 80, height: 80)
                    }
                    .opacity(number.isEmpty ? 0 : 1).disabled(number.isEmpty)
                    .accessibilityLabel(L("keypad.delete"))
                }
                .environment(\.layoutDirection, .leftToRight)
                .padding(.top, 18)
                if isCode {
                    Text(model.caps?.ussd == true ? L("keypad.codeHint") : L("keypad.codeUnavailable")).font(.footnote).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center).padding(.horizontal).padding(.top, 8)
                }
                if let u = model.ussd {
                    Text(verbatim: u.pending ? L("keypad.codeWaiting") : (u.ok ? (u.text ?? "") : describeError(u.err ?? "failed")))
                        .font(.callout).padding().frame(maxWidth: .infinity, alignment: .leading)
                        .glassPanel(cornerRadius: 20).padding(.horizontal).padding(.top, 8)
                        .textSelection(.enabled)
                }
                Spacer(minLength: 12)
            }
            .toolbar { ToolbarItem(placement: .topBarLeading) { GatewayButton() } }
            .onAppear { if sub == nil { sub = model.defaultVoiceSub } }
            .alert(alert ?? "", isPresented: .init(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button(L("ok")) {} }
        }
    }

    private func primary() {
        if isCode { alert = resultMessage(model.sendUssd(number, subId: sub)) }
        else { alert = resultMessage(model.dial(number, subId: sub)) }
    }
}

// MARK: - In call

/// Ringing or active call on the gateway, in the style of the iPhone call screen. When the call is bridged to this
/// iPhone, CallKit's own screen is shown instead; this view is for calls whose voice stays on the gateway.
struct InCallView: View {
    @Environment(AppModel.self) private var model
    @State private var muted = false
    @State private var speaker = false
    @State private var keypad = false

    var body: some View {
        if let call = model.liveCalls.first(where: { $0.state == .ringing }) ?? model.liveCalls.first {
            let name = call.name ?? model.name(for: call.number)
            let control = model.caps?.callControl == true
            let bridged = model.callKit.isBridged(call.callId)
            ZStack {
                LinearGradient(colors: [Color(red: 0.20, green: 0.24, blue: 0.33), Color(red: 0.07, green: 0.08, blue: 0.11)],
                               startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
                VStack(spacing: 10) {
                    if let sim = model.simLabel(call.subId) {
                        Label(sim, systemImage: "simcard").font(.footnote.weight(.medium)).foregroundStyle(.white.opacity(0.75))
                            .padding(.horizontal, 10).padding(.vertical, 4).glassPanel()
                            .padding(.top, 24)
                    }
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        Text(verbatim: stateText(call, now: ctx.date)).font(.title3).foregroundStyle(.white.opacity(0.7))
                    }
                    Text(verbatim: name ?? ltr(call.number.isEmpty ? L("unknownNumber") : call.number))
                        .font(.system(size: 38, weight: .regular)).foregroundStyle(.white)
                        .multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.6).padding(.horizontal)
                    if name != nil { Text(verbatim: ltr(call.number)).foregroundStyle(.white.opacity(0.7)) }
                    if model.liveCalls.count > 1 { Text(L("incall.waiting")).foregroundStyle(.orange) }
                    Avatar(name: name ?? "#", size: 120).padding(.top, 18)
                    if !bridged {
                        Label(L(model.status?.audio == "ready" ? "incall.audioOff" : "incall.audioNote"), systemImage: "speaker.wave.2")
                            .font(.callout).foregroundStyle(.white.opacity(0.9)).multilineTextAlignment(.center)
                            .padding(14).glassPanel(cornerRadius: 22).padding(.horizontal, 20).padding(.top, 12)
                    }
                    Spacer()
                    if call.state != .ringing {
                        controls(call, bridged: bridged)
                    }
                    if !control {
                        Text(L("incall.noControl")).font(.footnote).foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center).padding(.horizontal)
                    }
                    HStack(spacing: 90) {
                        if call.state == .ringing {
                            RoundButton(symbol: "phone.down.fill", label: L("incall.decline"), color: .red, enabled: control) { _ = model.endCall(call.callId, ringing: true) }
                            RoundButton(symbol: "phone.fill", label: L("incall.answer"), color: .green, enabled: control) { _ = model.answerCall(call.callId) }
                        } else {
                            RoundButton(symbol: "phone.down.fill", label: L("incall.end"), color: .red, enabled: control) { _ = model.endCall(call.callId, ringing: false) }
                        }
                    }
                    .padding(.top, 16)
                    .padding(.bottom, model.demo ? 8 : 36)
                    if model.demo { Text(L("demo.callNote")).font(.caption).foregroundStyle(.white.opacity(0.6)).padding(.bottom, 12) }
                }
            }
            .environment(\.colorScheme, .dark)
            .sensoryFeedback(.warning, trigger: call.state == .ringing)
        }
    }

    @ViewBuilder private func controls(_ call: LiveCall, bridged: Bool) -> some View {
        VStack(spacing: 22) {
            if keypad && bridged {
                DtmfPad { d in model.callKit.dtmf(call.callId, String(d)) }
            } else {
                HStack(spacing: 30) {
                    if bridged {
                        ToggleCircle(symbol: "speaker.wave.2.fill", label: L("incall.speaker"), on: speaker) {
                            speaker.toggle(); model.callKit.setSpeaker(speaker)
                        }
                        ToggleCircle(symbol: muted ? "mic.slash.fill" : "mic.fill", label: L("incall.mute"), on: muted) {
                            muted.toggle(); model.callKit.setMuted(call.callId, muted)
                        }
                    }
                    if model.caps?.hold == true {
                        let other = model.liveCalls.first { $0.callId != call.callId && $0.state != .ringing }
                        if let other {
                            // Two calls: swap puts the current one on hold and resumes the other.
                            ToggleCircle(symbol: "arrow.left.arrow.right", label: L("incall.swap"), on: false) {
                                if bridged { model.callKit.setHeld(other.callId, false) } else { model.callAction(other.callId, "unhold") }
                            }
                        } else {
                            let held = call.state == .holding
                            ToggleCircle(symbol: held ? "play.fill" : "pause.fill", label: L(held ? "incall.resume" : "incall.hold"), on: held) {
                                if bridged { model.callKit.setHeld(call.callId, !held) } else { model.callAction(call.callId, held ? "unhold" : "hold") }
                            }
                        }
                    }
                }
            }
            if bridged {
                ToggleCircle(symbol: "circle.grid.3x3.fill", label: L("tab.keypad"), on: keypad) { keypad.toggle() }
            }
        }
    }

    private func stateText(_ c: LiveCall, now: Date) -> String {
        switch c.state {
        case .ringing: return L("incall.ringing")
        case .dialing: return L("incall.dialing")
        case .holding: return L("incall.holding")
        default:
            guard let since = c.activeSince else { return L("incall.inProgress") }
            return Fmt.duration(Int64(now.timeIntervalSince1970) - since / 1000)
        }
    }
}

private struct RoundButton: View {
    let symbol: String, label: String, color: Color, enabled: Bool, action: () -> Void
    var body: some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: symbol).font(.system(size: 32)).foregroundStyle(.white).frame(width: 80, height: 80).background(Circle().fill(color))
            }
            .disabled(!enabled).opacity(enabled ? 1 : 0.4)
            .accessibilityLabel(label)
            Text(verbatim: label).font(.footnote.weight(.medium)).foregroundStyle(.white)
        }
    }
}

private struct ToggleCircle: View {
    let symbol: String, label: String, on: Bool, action: () -> Void
    var body: some View {
        VStack(spacing: 6) {
            Button(action: action) {
                Image(systemName: symbol).font(.system(size: 26)).frame(width: 76, height: 76)
                    .foregroundStyle(on ? .black : .white)
                    .glassCircle(tint: on ? .white : nil)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
            .accessibilityAddTraits(on ? .isSelected : [])
            Text(verbatim: label).font(.caption.weight(.medium)).foregroundStyle(.white)
        }
    }
}

/// Keypad for menus during a call; tones are generated into the call audio on this iPhone.
private struct DtmfPad: View {
    let tap: (Character) -> Void
    private let keys: [Character] = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"]
    var body: some View {
        Grid(horizontalSpacing: 20, verticalSpacing: 12) {
            ForEach(0..<4) { r in
                GridRow {
                    ForEach(0..<3) { c in
                        let k = keys[r * 3 + c]
                        Button { UIImpactFeedbackGenerator(style: .light).impactOccurred(); tap(k) } label: {
                            Text(String(k)).font(.system(size: 30)).foregroundStyle(.white).frame(width: 68, height: 68).glassCircle()
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }
}
