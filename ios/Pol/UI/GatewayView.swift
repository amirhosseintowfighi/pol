import SwiftUI

struct GatewayView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmUnpair = false
    @State private var confirmErase = false

    var body: some View {
        NavigationStack {
            Form {
                Section(L("gateway.title")) {
                    LabeledContent(model.status?.name ?? model.gateway?.name ?? L("demo.gateway")) {
                        if let b = model.status?.battery {
                            Label(Fmt.number(Int64(b)) + "%", systemImage: model.status?.charging == true ? "battery.100.bolt" : "battery.75")
                                .labelStyle(.titleAndIcon)
                        }
                    }
                    LabeledContent(L("gateway.connection"), value: linkText)
                    ForEach(model.status?.sims ?? [], id: \.subId) { sim in
                        HStack {
                            Image(systemName: "simcard").foregroundStyle(.secondary)
                            VStack(alignment: .leading) {
                                Text(verbatim: sim.displayLabel)
                                Text(verbatim: [sim.carrier, sim.network, sim.roaming == true ? L("gateway.roaming") : nil].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if sim.subId == model.status?.defaultVoiceSub { Text(L("gateway.default")).font(.caption).foregroundStyle(.secondary) }
                            SignalBars(level: sim.signal)
                        }
                    }
                    if let s = model.status, s.sims.isEmpty { Text(L("gateway.noSimInfo")).foregroundStyle(.secondary) }
                }

                Section {
                    feature(L("f.sendSms"), model.caps?.smsSend)
                    feature(L("f.readSms"), model.caps?.smsRead)
                    feature(L("f.placeCalls"), model.caps?.callPlace)
                    feature(L("f.answerCalls"), model.caps?.callControl)
                    feature(L("f.recents"), model.caps?.callLog)
                    feature(L("f.contacts"), model.caps?.contacts)
                    feature(L("f.ussd"), model.caps?.ussd)
                } header: { Text(L("gateway.features")) } footer: { Text(L("gateway.unsupported")) }

                Section {
                    Toggle(L("audio.toggle"), isOn: Binding(get: { model.callsOnThisPhone }, set: { on in Task { await model.setCallsOnThisPhone(on) } }))
                    Text(audioStatusText).font(.callout).foregroundStyle(.secondary)
                } header: { Text(L("audio.title")) } footer: { Text(L("audio.footer")) }

                Section {
                    Toggle(L("notify.toggle"), isOn: Binding(get: { model.notificationsEnabled }, set: { on in Task { await model.setNotifications(on) } }))
                    Toggle(L("notify.previews"), isOn: Binding(get: { model.showPreviews }, set: { model.setShowPreviews($0) }))
                        .disabled(!model.notificationsEnabled)
                } header: { Text(L("notify.title")) } footer: { Text(L("notify.footer")) }

                if model.demo {
                    Section {
                        Button(L("demo.ring")) { model.simulateIncomingCall() }
                        Button(L("demo.text")) { model.simulateIncomingSms() }
                        Button(L("demo.exit"), role: .destructive) { model.exitDemo() }
                    } header: { Text(L("demo.title")) } footer: { Text(L("demo.footer")) }
                }

                Section(L("sub.title")) { NavigationLink(L("sub.plan")) { SubscriptionView() } }

                Section {
                    Label(L("privacy.body"), systemImage: "lock.shield").font(.callout)
                    if let g = model.gateway {
                        LabeledContent(L("privacy.pairedWith"), value: g.name)
                        LabeledContent(L("privacy.fingerprint")) { Text(verbatim: ltr(g.fingerprintText)).font(.callout.monospaced()) }
                        Button(L("privacy.unpair"), role: .destructive) { confirmUnpair = true }
                    }
                    Button(L("privacy.erase"), role: .destructive) { confirmErase = true }
                } header: { Text(L("privacy.title")) }

                Section {
                    LabeledContent("\(L("app.name"))", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                    // Credit with a link; opening it is the user's choice and happens in Safari.
                    HStack(spacing: 4) {
                        Text(L("about.creditPrefix"))
                        Link(L("about.studio"), destination: URL(string: "https://virgule.studio")!)
                    }
                    Link("virgule.studio", destination: URL(string: "https://virgule.studio")!)
                    Link(L("about.source"), destination: URL(string: "https://github.com/amirhosseintowfighi/pol")!)
                } header: { Text(L("about.title")) } footer: { Text(L("about.license")) }
            }
            .navigationTitle(L("tab.gateway"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Image(systemName: "checkmark") }.accessibilityLabel(L("done"))
                }
            }
            .confirmationDialog(L("privacy.unpairTitle"), isPresented: $confirmUnpair, titleVisibility: .visible) {
                Button(L("privacy.unpair"), role: .destructive) { model.forgetGateway() }
            } message: { Text(L("privacy.unpairBody")) }
            .confirmationDialog(L("privacy.eraseTitle"), isPresented: $confirmErase, titleVisibility: .visible) {
                Button(L("privacy.erase"), role: .destructive) { model.eraseAll() }
            } message: { Text(L("privacy.eraseBody")) }
        }
    }

    private var audioStatusText: String {
        switch model.status?.audio {
        case "ready": return model.callsOnThisPhone ? L("audio.status.ready") : L("audio.status.available")
        case "needs_setup": return L("audio.status.setup")
        case "android_version": return L("audio.err.android")
        case "not_interceptable": return L("audio.err.notSupported")
        case "off": return L("audio.status.off")
        default: return L("audio.status.unknown")
        }
    }

    private var linkText: String {
        switch model.link {
        case .connected: return L("link.connected") + (model.pingMs.map { " · " + Fmt.number(Int64($0)) + " ms" } ?? "")
        case .bluetoothOff: return L("banner.btOff")
        case .bluetoothDenied: return L("banner.btDenied")
        case .reconnecting: return L("banner.reconnecting")
        case .connecting: return L("banner.connecting")
        default: return L("banner.searching")
        }
    }

    private func feature(_ title: String, _ on: Bool?) -> some View {
        LabeledContent(title, value: on == true ? L("f.available") : (on == false ? L("f.off") : L("f.unknown")))
    }
}

/// Plan terms shown in full before any commitment. No payment provider is configured, so nothing can be bought
/// and nothing is locked. See docs/BILLING.md.
struct SubscriptionView: View {
    private let start = Date()
    private var trialEnd: Date { Calendar.current.date(byAdding: .month, value: 1, to: start) ?? start }
    static let priceTomans: Int64 = 500_000

    var body: some View {
        Form {
            Section {
                Text(L("sub.terms %@", Fmt.number(Self.priceTomans)))
                LabeledContent(L("sub.trialStart"), value: start.formatted(date: .long, time: .omitted))
                LabeledContent(L("sub.trialEnd"), value: trialEnd.formatted(date: .long, time: .omitted))
                LabeledContent(L("sub.renewal"), value: L("sub.renewalValue %@ %@", Fmt.number(Self.priceTomans), trialEnd.formatted(date: .long, time: .omitted)))
                Text(L("sub.cancel")).font(.callout).foregroundStyle(.secondary)
            } header: { Text(L("sub.plan")) }
            Section {
                Label(L("sub.unavailable"), systemImage: "creditcard.trianglebadge.exclamationmark")
                Text(L("sub.unavailableBody")).font(.callout).foregroundStyle(.secondary)
                Button(L("sub.payOnline")) {}.disabled(true)
                Button(L("sub.cardToCard")) {}.disabled(true)
            } footer: { Text(L("sub.footer")) }
        }
        .navigationTitle(L("sub.title"))
    }
}

// MARK: - Onboarding and pairing

struct OnboardingView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    switch model.pairing {
                    case .idle: welcome
                    case .searching(let several):
                        progress(L("pair.searching"), several ? L("pair.several") : L("pair.searchingBody"))
                    case .connecting:
                        progress(L("pair.connecting"), "")
                    case let .compare(sas, name, fp, waiting):
                        Image(systemName: "lock.shield").font(.system(size: 44)).foregroundStyle(.tint)
                        Text(L("pair.compareTitle")).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                        Text(L("pair.compareBody %@", name)).multilineTextAlignment(.center)
                        SasCodeView(sas: sas).padding(.vertical)
                        Text(L("pair.fingerprint %@", ltr(fp))).font(.footnote).monospacedDigit().foregroundStyle(.secondary)
                        if waiting {
                            ProgressView(); Text(L("pair.waiting")).multilineTextAlignment(.center)
                        } else {
                            Button { model.confirmSas() } label: { Text(L("pair.match")).frame(maxWidth: .infinity) }.prominentButton().controlSize(.large)
                            Button(role: .destructive) { model.declineSas() } label: { Text(L("pair.differ")).frame(maxWidth: .infinity) }.secondaryButton().controlSize(.large)
                        }
                    case .done(let name):
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 56)).foregroundStyle(.green)
                        Text(L("pair.done %@", name)).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                        Button { model.cancelPairing() } label: { Text(L("continue")).frame(maxWidth: .infinity) }.prominentButton().controlSize(.large)
                    case .failed(let reason):
                        Image(systemName: "exclamationmark.triangle").font(.system(size: 44)).foregroundStyle(.orange)
                        Text(L("pair.failed")).font(.title2.weight(.semibold))
                        Text(failure(reason)).multilineTextAlignment(.center)
                        Button { model.startPairing() } label: { Text(L("pair.tryAgain")).frame(maxWidth: .infinity) }.prominentButton().controlSize(.large)
                        Button(L("cancel")) { model.cancelPairing() }
                    }
                }
                .padding(24)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .toolbar {
                if model.pairing != .idle, !isDone {
                    ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { model.cancelPairing() } }
                }
            }
        }
        .sensoryFeedback(.success, trigger: isDone)
    }

    private var isDone: Bool { if case .done = model.pairing { return true }; return false }

    private var welcome: some View {
        VStack(spacing: 20) {
            Image(systemName: "iphone.gen3.radiowaves.left.and.right").font(.system(size: 56)).foregroundStyle(.tint).padding(.top, 40)
            Text(L("welcome.title")).font(.largeTitle.weight(.bold)).multilineTextAlignment(.center)
            Text(L("welcome.body")).font(.body).multilineTextAlignment(.center).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 12) {
                Text(L("setup.needs")).font(.headline)
                ForEach(["setup.1", "setup.2", "setup.3", "setup.4"], id: \.self) { k in
                    Label(L(k), systemImage: "checkmark.circle").font(.callout)
                }
            }
            .padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .glassPanel(cornerRadius: 26)
            if model.link == .bluetoothDenied { Text(L("pair.btDenied")).foregroundStyle(.red).multilineTextAlignment(.center) }
            Button { model.startPairing() } label: { Text(L("pair.start")).frame(maxWidth: .infinity) }.prominentButton().controlSize(.large)
            Button { model.enterDemo() } label: { Text(L("demo.try")).frame(maxWidth: .infinity) }.secondaryButton().controlSize(.large)
            Text(L("privacy.short")).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
    }

    private func progress(_ title: String, _ body: String) -> some View {
        VStack(spacing: 16) {
            ProgressView().controlSize(.large).padding(.top, 60)
            Text(title).font(.title2.weight(.semibold))
            if !body.isEmpty { Text(body).multilineTextAlignment(.center).foregroundStyle(.secondary) }
        }
    }

    private func failure(_ r: CloseReason) -> String {
        switch r {
        case .pairingClosed: return L("pair.fail.closed")
        case .tooManyAttempts: return L("pair.fail.attempts")
        case .declined: return L("pair.fail.declined")
        case .timeout: return L("pair.fail.timeout")
        case .busy: return L("pair.fail.busy")
        case .badVersion: return L("pair.fail.version")
        default: return model.link == .bluetoothDenied ? L("pair.btDenied") : L("pair.fail.generic")
        }
    }
}
