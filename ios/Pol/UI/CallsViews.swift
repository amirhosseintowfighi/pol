import SwiftUI

// MARK: - Recents

struct RecentsView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var detail: CallEntry?
    @State private var alert: String?

    private var filtered: [CallEntry] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return model.calls }
        return model.calls.filter { ($0.name ?? model.name(for: $0.number) ?? "").lowercased().contains(q) || $0.number.contains(q) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                LinkBanner()
                List {
                    let favourites = model.contacts.filter { $0.starred == true }
                    if !favourites.isEmpty && query.isEmpty {
                        Section(L("calls.favourites")) {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 16) {
                                    ForEach(favourites) { c in
                                        Button { call(c.phones.first?.number ?? "", nil) } label: {
                                            VStack { Avatar(name: c.name, size: 56); Text(verbatim: c.name.components(separatedBy: " ").first ?? c.name).font(.caption).lineLimit(1) }
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel(L("call.x %@", c.name))
                                    }
                                }.padding(.vertical, 4)
                            }
                        }
                    }
                    Section(model.calls.isEmpty ? "" : L("calls.recents")) {
                        ForEach(filtered) { e in
                            RecentRow(entry: e) { detail = e }
                                .contentShape(Rectangle())
                                .onTapGesture { call(e.number, e.subId) }
                                .swipeActions(edge: .leading) {
                                    Button { call(e.number, e.subId) } label: { Label(L("call"), systemImage: "phone.fill") }.tint(.green)
                                }
                                .contextMenu {
                                    Button { call(e.number, e.subId) } label: { Label(L("call"), systemImage: "phone") }
                                    Button { UIPasteboard.general.string = e.number } label: { Label(L("copy"), systemImage: "doc.on.doc") }
                                }
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .overlay {
                    if model.caps?.callLog == false {
                        ContentUnavailableView(L("calls.off.title"), systemImage: "clock.badge.xmark", description: Text(L("calls.off.body")))
                    } else if model.calls.isEmpty {
                        ContentUnavailableView(L("calls.empty.title"), systemImage: "clock", description: Text(L("calls.empty.body")))
                    } else if filtered.isEmpty {
                        ContentUnavailableView.search(text: query)
                    }
                }
            }
            .navigationTitle(L("tab.calls"))
            .searchable(text: $query, prompt: L("search.calls"))
            .sheet(item: $detail) { e in CallDetailSheet(entry: e) }
            .alert(alert ?? "", isPresented: .init(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button(L("ok")) {} }
        }
    }

    private func call(_ number: String, _ sub: Int?) {
        alert = resultMessage(model.dial(number, subId: sub ?? model.defaultVoiceSub))
    }
}

private struct RecentRow: View {
    @Environment(AppModel.self) private var model
    let entry: CallEntry
    let info: () -> Void

    var body: some View {
        let name = entry.name ?? model.name(for: entry.number)
        let missed = entry.type == .missed || entry.type == .rejected
        HStack(spacing: 12) {
            Avatar(name: name ?? "#")
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: name ?? ltr(entry.number.isEmpty ? L("unknownNumber") : entry.number))
                    .font(.body.weight(.medium)).foregroundStyle(missed ? .red : .primary).lineLimit(1)
                HStack(spacing: 4) {
                    Image(systemName: icon).font(.caption2).foregroundStyle(.secondary).accessibilityLabel(typeLabel)
                    if let sim = model.simLabel(entry.subId) { Text(verbatim: sim).font(.caption2).padding(.horizontal, 5).background(.quaternary, in: Capsule()) }
                    Text(verbatim: typeLabel).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(verbatim: Fmt.relative(entry.date)).font(.subheadline).foregroundStyle(.secondary)
            Button(action: info) { Image(systemName: "info.circle").imageScale(.large) }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("details"))
        }
        .accessibilityElement(children: .combine)
    }

    private var icon: String {
        switch entry.type {
        case .outgoing: return "phone.arrow.up.right"
        case .missed, .rejected: return "phone.arrow.down.left"
        case .blocked: return "nosign"
        default: return "phone.arrow.down.left"
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
    @State private var composeTo: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 8) {
                        Avatar(name: entry.name ?? model.name(for: entry.number) ?? "#", size: 72)
                        Text(verbatim: entry.name ?? model.name(for: entry.number) ?? ltr(entry.number)).font(.title2.weight(.semibold))
                        if entry.name != nil || model.name(for: entry.number) != nil { Text(verbatim: ltr(entry.number)).foregroundStyle(.secondary) }
                    }.frame(maxWidth: .infinity).listRowBackground(Color.clear)
                }
                Section {
                    LabeledContent(L("details.when"), value: entry.date.formatted(date: .abbreviated, time: .shortened))
                    if let d = entry.durationS, d > 0 { LabeledContent(L("details.duration"), value: Fmt.duration(d)) }
                    if let sim = model.simLabel(entry.subId) { LabeledContent(L("details.line"), value: sim) }
                }
                Section {
                    SimMenu(subId: $sub)
                    Button { model.dial(entry.number, subId: sub ?? entry.subId ?? model.defaultVoiceSub); dismiss() } label: { Label(L("call"), systemImage: "phone") }
                        .disabled(Validate.dialNumber(entry.number) == nil)
                    NavigationLink { ThreadView(threadId: model.threadId(for: entry.number), address: entry.number) } label: { Label(L("message"), systemImage: "message") }
                        .disabled(Validate.smsRecipient(entry.number) == nil)
                }
            }
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } } }
            .onAppear { sub = entry.subId ?? model.defaultVoiceSub }
        }
        .presentationDetents([.medium, .large])
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
            VStack(spacing: 12) {
                LinkBanner()
                Spacer(minLength: 8)
                Text(verbatim: number.isEmpty ? " " : number)
                    .font(.system(size: 36, weight: .regular)).lineLimit(1).minimumScaleFactor(0.5)
                    .environment(\.layoutDirection, .leftToRight)
                    .padding(.horizontal)
                    .accessibilityLabel(number.isEmpty ? L("keypad.empty") : number)
                Text(verbatim: number.count >= 4 ? (model.name(for: number) ?? " ") : " ").foregroundStyle(.secondary)
                SimMenu(subId: $sub)
                // The keypad keeps its standard layout in right-to-left languages, like the system Phone app.
                Grid(horizontalSpacing: 24, verticalSpacing: 14) {
                    ForEach(0..<4) { r in
                        GridRow {
                            ForEach(0..<3) { c in
                                let k = keys[r * 3 + c]
                                Button { if number.count < 32 { number += k.0 } } label: {
                                    VStack(spacing: 0) {
                                        Text(verbatim: k.0).font(.system(size: 32))
                                        Text(verbatim: k.1).font(.system(size: 10, weight: .semibold)).tracking(1.5).foregroundStyle(.secondary)
                                    }
                                    .frame(width: 78, height: 78)
                                    .background(Circle().fill(Color(.secondarySystemFill)))
                                }
                                .buttonStyle(.plain)
                                .sensoryFeedback(.impact(weight: .light), trigger: number)
                                .accessibilityLabel(k.0)
                            }
                        }
                    }
                }
                .environment(\.layoutDirection, .leftToRight)
                HStack(spacing: 24) {
                    Color.clear.frame(width: 78, height: 78)
                    Button { primary() } label: {
                        Image(systemName: isCode ? "arrow.up" : "phone.fill").font(.system(size: 30)).foregroundStyle(.white)
                            .frame(width: 78, height: 78).background(Circle().fill(Color.green))
                    }
                    .disabled(isCode ? Validate.ussdCode(number) == nil || model.caps?.ussd != true : Validate.dialNumber(number) == nil)
                    .accessibilityLabel(isCode ? L("keypad.sendCode") : L("call"))
                    Button { number = String(number.dropLast()) } label: { Image(systemName: "delete.left").font(.title2).frame(width: 78, height: 78) }
                        .opacity(number.isEmpty ? 0 : 1).disabled(number.isEmpty)
                        .accessibilityLabel(L("keypad.delete"))
                }
                .environment(\.layoutDirection, .leftToRight)
                if isCode {
                    Text(model.caps?.ussd == true ? L("keypad.codeHint") : L("keypad.codeUnavailable")).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal)
                }
                if let u = model.ussd {
                    Text(verbatim: u.pending ? L("keypad.codeWaiting") : (u.ok ? (u.text ?? "") : describeError(u.err ?? "failed")))
                        .font(.callout).padding().frame(maxWidth: .infinity, alignment: .leading)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
                        .textSelection(.enabled)
                }
                Spacer(minLength: 8)
            }
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

/// Ringing or active call on the gateway. Voice stays on the gateway phone: see docs/COMPATIBILITY.md.
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
            VStack(spacing: 12) {
                Spacer().frame(height: 40)
                Avatar(name: name ?? "#", size: 96)
                Text(verbatim: name ?? ltr(call.number.isEmpty ? L("unknownNumber") : call.number)).font(.largeTitle.weight(.semibold)).multilineTextAlignment(.center)
                if name != nil { Text(verbatim: ltr(call.number)).foregroundStyle(.secondary) }
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    Text(verbatim: stateText(call, now: ctx.date)).font(.title3).foregroundStyle(.secondary)
                }
                if let sim = model.simLabel(call.subId) { Text(verbatim: sim).font(.caption).padding(.horizontal, 8).padding(.vertical, 2).background(.quaternary, in: Capsule()) }
                if model.liveCalls.count > 1 { Text(L("incall.waiting")).foregroundStyle(.orange) }
                if !bridged {
                    Label(L(model.status?.audio == "ready" ? "incall.audioOff" : "incall.audioNote"), systemImage: "speaker.wave.2")
                        .font(.callout).multilineTextAlignment(.center).padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20)).padding(.horizontal)
                }
                Spacer()
                if bridged && call.state != .ringing {
                    HStack(spacing: 36) {
                        ToggleCircle(symbol: muted ? "mic.slash.fill" : "mic.fill", label: L("incall.mute"), on: muted) {
                            muted.toggle(); model.callKit.setMuted(call.callId, muted)
                        }
                        ToggleCircle(symbol: "circle.grid.3x3.fill", label: L("tab.keypad"), on: keypad) { keypad.toggle() }
                        ToggleCircle(symbol: "speaker.wave.3.fill", label: L("incall.speaker"), on: speaker) {
                            speaker.toggle(); model.callKit.setSpeaker(speaker)
                        }
                    }
                    if keypad { DtmfPad { d in model.callKit.dtmf(call.callId, String(d)) }.padding(.top, 8) }
                }
                if model.caps?.hold == true && call.state != .ringing {
                    let other = model.liveCalls.first { $0.callId != call.callId && $0.state != .ringing }
                    HStack(spacing: 36) {
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
                    .padding(.top, 8)
                }
                if !control { Text(L("incall.noControl")).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal) }
                HStack(spacing: 64) {
                    if call.state == .ringing {
                        RoundButton(symbol: "phone.down.fill", label: L("incall.decline"), color: .red, enabled: control) { _ = model.endCall(call.callId, ringing: true) }
                        RoundButton(symbol: "phone.fill", label: L("incall.answer"), color: .green, enabled: control) { _ = model.answerCall(call.callId) }
                    } else {
                        RoundButton(symbol: "phone.down.fill", label: L("incall.end"), color: .red, enabled: control) { _ = model.endCall(call.callId, ringing: false) }
                    }
                }
                .padding(.bottom, 40)
                if model.demo { Text(L("demo.callNote")).font(.caption).foregroundStyle(.secondary) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemBackground))
            .sensoryFeedback(.warning, trigger: call.state == .ringing)
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
                Image(systemName: symbol).font(.system(size: 30)).foregroundStyle(.white).frame(width: 76, height: 76).background(Circle().fill(color))
            }
            .disabled(!enabled).opacity(enabled ? 1 : 0.4)
            .accessibilityLabel(label)
            Text(verbatim: label).font(.footnote)
        }
    }
}

private struct ToggleCircle: View {
    let symbol: String, label: String, on: Bool, action: () -> Void
    var body: some View {
        VStack(spacing: 6) {
            Button(action: action) {
                Image(systemName: symbol).font(.title2).frame(width: 64, height: 64)
                    .foregroundStyle(on ? Color(.systemBackground) : .primary)
                    .background(Circle().fill(on ? Color.primary : Color(.secondarySystemFill)))
            }
            .accessibilityLabel(label)
            .accessibilityAddTraits(on ? .isSelected : [])
            Text(verbatim: label).font(.caption)
        }
    }
}

/// Keypad for menus during a call; tones are generated into the call audio on this iPhone.
private struct DtmfPad: View {
    let tap: (Character) -> Void
    private let keys: [Character] = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"]
    var body: some View {
        Grid(horizontalSpacing: 18, verticalSpacing: 10) {
            ForEach(0..<4) { r in
                GridRow {
                    ForEach(0..<3) { c in
                        let k = keys[r * 3 + c]
                        Button { UIImpactFeedbackGenerator(style: .light).impactOccurred(); tap(k) } label: {
                            Text(String(k)).font(.title2).frame(width: 58, height: 58).background(Circle().fill(Color(.secondarySystemFill)))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }
}
