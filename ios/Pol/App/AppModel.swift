import CoreBluetooth
import AVFoundation
import Foundation
import Observation
import UIKit
import UserNotifications

enum LinkState: Equatable { case notPaired, bluetoothOff, bluetoothDenied, searching, connecting, connected, reconnecting }

enum PairingState: Equatable {
    case idle
    case searching(several: Bool)
    case connecting
    case compare(sas: String, gatewayName: String, fingerprint: String, waitingForGateway: Bool)
    case done(String)
    case failed(CloseReason)
}

enum ActionResult { case ok, notConnected, invalid, unsupported }

struct UssdReply: Equatable { let cmid: String; let code: String; var pending: Bool; var ok = false; var text: String?; var err: String? }

/// The iPhone client's state and logic: the same behaviour as core ClientModel.kt, plus Bluetooth and pairing.
/// Message, call and contact data is held in memory only; it is gone when the app is terminated.
@Observable
@MainActor
final class AppModel {
    // Connection
    private(set) var link: LinkState = .notPaired
    private(set) var pairing: PairingState = .idle
    private(set) var demo = false
    private(set) var gateway: TrustedGateway?

    // Data from the gateway
    private(set) var status: GatewayStatus?
    private(set) var messages: [String: Sms] = [:]
    private(set) var calls: [CallEntry] = []
    private(set) var contacts: [Contact] = []
    private(set) var liveCalls: [LiveCall] = []
    private(set) var ussd: UssdReply?
    var lastError: String?
    private(set) var pingMs: Int?

    // Settings (non-sensitive, on-device only)
    private(set) var notificationsEnabled: Bool
    private(set) var showPreviews: Bool
    /// "Take calls on this iPhone": ring through CallKit and carry call audio (needs the gateway's audio helper).
    private(set) var callsOnThisPhone: Bool

    var inBackground = false

    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private var identity: DeviceIdentity?
    @ObservationIgnored private var ble: BleCentral?
    @ObservationIgnored private var linkEngine: ClientLink?
    @ObservationIgnored private var connection: BleConnection?
    @ObservationIgnored private var demoGateway: DemoGateway?
    @ObservationIgnored private var running = false
    @ObservationIgnored private var attempt = 0
    @ObservationIgnored private var notOurs: [UUID: Date] = [:]
    @ObservationIgnored private var pairingCandidates: [UUID: CBPeripheral] = [:]
    @ObservationIgnored private var nextId: Int64 = 1
    @ObservationIgnored private var pendingSends: [String: (SmsSend, Date)] = [:]
    @ObservationIgnored private var contactsBuffer: [Contact] = []
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var ticks = 0
    @ObservationIgnored private var retryWork: DispatchWorkItem?
    @ObservationIgnored private var localThread: Int64 = -1
    @ObservationIgnored private(set) var callKit: CallCoordinator!
    /// Debug screenshots only: which tab and conversation to open on launch.
    var screenshotTab: Int?
    /// Gateway status and settings, opened from the toolbar of each tab.
    var showGateway = false
    var screenshotThread: ThreadRoute?

    init() {
        notificationsEnabled = defaults.bool(forKey: "notify")
        showPreviews = defaults.bool(forKey: "previews")
        callsOnThisPhone = defaults.bool(forKey: "callsHere")
        callKit = CallCoordinator()
        callKit.model = self
        if let raw = Keychain.get("gateway.v1") { gateway = try? JSONDecoder().decode(TrustedGateway.self, from: raw) }
        if defaults.bool(forKey: "demo") { enterDemo() }
        else if gateway != nil { link = .searching; startLink() }
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.tick() } }
    }

    // MARK: Derived

    var conversations: [Conversation] {
        Dictionary(grouping: messages.values, by: \.thread).map { thread, items in
            let last = items.max(by: { $0.ts < $1.ts })!
            return Conversation(thread: thread, address: last.address, last: last, unread: items.filter(\.isUnread).count)
        }.sorted { $0.last.ts > $1.last.ts }
    }

    func searchConversations(_ q: String) -> [Conversation] {
        let query = q.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return conversations }
        let hits = Set(messages.values.filter { m in
            m.body.lowercased().contains(query) || m.address.contains(query) || (name(for: m.address)?.lowercased().contains(query) ?? false)
        }.map(\.thread))
        return conversations.filter { hits.contains($0.thread) }
    }

    func thread(_ id: Int64, address: String) -> [Sms] {
        messages.values.filter { id > 0 ? ($0.thread == id || ($0.id == 0 && numberKey($0.address) == numberKey(address))) : numberKey($0.address) == numberKey(address) }
            .sorted { $0.ts < $1.ts }
    }

    func threadId(for number: String) -> Int64 { messages.values.first { numberKey($0.address) == numberKey(number) }?.thread ?? 0 }

    func name(for number: String) -> String? {
        let k = numberKey(number)
        guard !k.isEmpty else { return nil }
        return contacts.first { $0.phones.contains { numberKey($0.number) == k } }?.name
    }

    /// Calls ring and are talked through on this iPhone (CallKit + audio), not just controlled.
    var bridged: Bool { callsOnThisPhone && caps?.callAudio == true && !demo && link == .connected }

    var missedCount: Int { calls.filter { $0.type == .missed }.count }
    var unreadCount: Int { messages.values.filter(\.isUnread).count }
    var caps: Capabilities? { status?.caps }
    var defaultVoiceSub: Int? { status?.defaultVoiceSub ?? status?.sims.first?.subId }
    var defaultSmsSub: Int? { status?.defaultSmsSub ?? status?.sims.first?.subId }
    func simLabel(_ sub: Int?) -> String? {
        guard let s = status, s.sims.count > 1 else { return nil }
        return s.sims.first { $0.subId == sub }?.displayLabel
    }

    // MARK: Demo

    func enterDemo() {
        stopLink()
        resetData()
        demo = true
        defaults.set(true, forKey: "demo")
        demoGateway = DemoGateway { [weak self] json in self?.handle(json) }
        link = .connected
        sessionUp()
    }

    func exitDemo() {
        demoGateway = nil
        demo = false
        defaults.set(false, forKey: "demo")
        resetData()
        link = gateway == nil ? .notPaired : .searching
        if gateway != nil { startLink() }
    }

    func simulateIncomingCall() { demoGateway?.simulateIncomingCall() }
    func simulateIncomingSms() { demoGateway?.simulateIncomingSms() }

    // MARK: Connection

    private func loadIdentity() -> DeviceIdentity? {
        if identity == nil { identity = try? DeviceIdentity.load() }
        return identity
    }

    private func central() -> BleCentral {
        if let b = ble { return b }
        let b = BleCentral(); b.delegate = self; ble = b
        return b
    }

    func startLink() {
        guard !demo, gateway != nil, !running else { return }
        running = true
        attempt = 0
        let b = central()
        if b.poweredOn { scanForGateway() }
    }

    private func stopLink() {
        running = false
        retryWork?.cancel()
        ble?.stopScan()
        linkEngine?.close(); linkEngine = nil
        connection?.close(); connection = nil
    }

    private func scheduleRetry() {
        let delays: [TimeInterval] = [1, 2, 5, 10, 30]
        let d = delays[min(attempt, delays.count - 1)]
        attempt += 1
        retryWork?.cancel()
        let w = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { if self?.running == true { self?.scanForGateway() } } }
        retryWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + d, execute: w)
    }

    private func scanForGateway() {
        guard running, connection == nil, let b = ble else { return }
        guard b.poweredOn else { link = .bluetoothOff; return }
        link = status == nil ? .searching : .reconnecting
        b.scan()
    }

    private func sessionUp() {
        let smsSince = messages.values.filter { $0.id > 0 }.map(\.id).max() ?? 0
        let callsSince = calls.map(\.id).max() ?? 0
        link = .connected
        attempt = 0
        lastError = nil
        send(Wire.encode(MsgType.sync, SyncReq(smsSince: smsSince, callsSince: callsSince, contacts: true, client: UIDevice.current.name), id: takeId()))
        for (req, _) in pendingSends.values { send(Wire.encode(MsgType.smsSend, req, id: takeId())) }
    }

    @discardableResult
    private func send(_ json: String?) -> Bool {
        guard let json else { return false }
        if let d = demoGateway { DispatchQueue.main.async { MainActor.assumeIsolated { d.receive(json) } }; return true }
        guard let l = linkEngine, l.isEstablished else { return false }
        return (try? l.send(json)) != nil
    }

    private func takeId() -> Int64 { defer { nextId += 1 }; return nextId }

    private func tick() {
        linkEngine?.tick()
        expirePending()
        ticks += 1
        if ticks % 5 == 0, !inBackground, link == .connected, !demo {
            send(Wire.encode(MsgType.ping, Ping(ts: Int64(Date().timeIntervalSince1970 * 1000)), id: takeId()))
        }
    }

    // MARK: Pairing

    func startPairing() {
        stopLink()
        pairingCandidates = [:]
        pairing = .searching(several: false)
        let b = central()
        if b.authorizationDenied { link = .bluetoothDenied; pairing = .failed(.transport); return }
        if b.poweredOn { b.scan() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, case .searching = self.pairing else { return }
                self.ble?.stopScan(); self.pairing = .failed(.timeout)
            }
        }
    }

    func confirmSas() {
        linkEngine?.confirmSas()
        if case let .compare(sas, name, fp, _) = pairing { pairing = .compare(sas: sas, gatewayName: name, fingerprint: fp, waitingForGateway: true) }
    }

    func declineSas() { linkEngine?.declineSas() }

    func cancelPairing() {
        ble?.stopScan()
        if case .done = pairing {} else { linkEngine?.close() }
        pairing = .idle
        if gateway != nil { startLink() }
    }

    private func saveGateway(_ g: TrustedGateway) {
        if let d = try? JSONEncoder().encode(g) { try? Keychain.set(d, for: "gateway.v1") }
        gateway = g
    }

    /// Unpair: ask the gateway to drop this iPhone (if connected) and forget it here.
    func forgetGateway(notifyGateway: Bool = true) {
        if notifyGateway, let l = linkEngine, l.isEstablished { try? l.send(Wire.encode(MsgType.unpair, Empty()) ?? "") }
        stopLink()
        Keychain.remove("gateway.v1")
        gateway = nil
        resetData()
        link = .notPaired
    }

    /// Deletes the pairing, this iPhone's identity key, settings and all in-memory data.
    func eraseAll() {
        forgetGateway()
        demoGateway = nil; demo = false
        Keychain.removeAll()
        DeviceIdentity.delete(); identity = nil
        if let bundle = Bundle.main.bundleIdentifier { defaults.removePersistentDomain(forName: bundle) }
        notificationsEnabled = false; showPreviews = false; callsOnThisPhone = false
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    private func resetData() {
        status = nil; messages = [:]; calls = []; contacts = []; liveCalls = []; ussd = nil; lastError = nil
        pendingSends = [:]; contactsBuffer = []
    }

    // MARK: Messages from the gateway

    private func handle(_ json: String) {
        guard let e = Wire.decode(json) else { return }
        switch e.type {
        case MsgType.status: if let s = Wire.body(e, GatewayStatus.self) { status = s }
        case MsgType.smsPage:
            if let p = Wire.body(e, SmsPage.self) { for m in p.items.map(clean) { messages[m.key] = m } }
        case MsgType.smsNew:
            if let n = Wire.body(e, SmsNew.self) {
                let m = clean(n.item)
                if let c = m.cmid { messages["p:\(c)"] = nil; pendingSends[c] = nil }
                messages[m.key] = m
                if m.dir == .incoming { notifyMessage(m) }
            }
        case MsgType.smsAck:
            if let a = Wire.body(e, SmsAck.self) {
                if a.state != .pending { pendingSends[a.cmid] = nil }
                if var m = messages["p:\(a.cmid)"] { m.state = a.state; messages["p:\(a.cmid)"] = m; if a.err != nil { lastError = "sms:\(a.err!)" } }
                else if let hit = messages.first(where: { $0.value.cmid == a.cmid }) { var mm = hit.value; mm.state = a.state; messages[hit.key] = mm }
            }
        case MsgType.callsPage:
            if let p = Wire.body(e, CallsPage.self) {
                var byId = Dictionary(calls.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
                for c in p.items { var c = c; c.number = Validate.display(c.number, max: 40); byId[c.id] = c }
                calls = Array(byId.values.sorted { $0.ts > $1.ts }.prefix(1000))
            }
        case MsgType.callsNew:
            if let n = Wire.body(e, CallsNew.self) { calls = Array(([n.item] + calls.filter { $0.id != n.item.id }).prefix(1000)) }
        case MsgType.contactsPage:
            if let p = Wire.body(e, ContactsPage.self) {
                if p.first { contactsBuffer = [] }
                contactsBuffer += p.items.prefix(max(0, 5000 - contactsBuffer.count)).map { c in
                    var c = c; c.name = Validate.display(c.name, max: 100); c.phones = Array(c.phones.prefix(10)); return c
                }
                if !p.more { contacts = contactsBuffer.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending } }
            }
        case MsgType.callState:
            if let c = Wire.body(e, CallStates.self) {
                let before = liveCalls.first { $0.state == .ringing }?.callId
                liveCalls = Array(c.calls.filter { $0.state != .ended }.prefix(4))
                callKit.update(liveCalls, bridged: bridged)
                if !bridged, let ring = liveCalls.first(where: { $0.state == .ringing }), ring.callId != before { notifyCall(ring) }
                if liveCalls.isEmpty { UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call"]) }
            }
        case MsgType.callAck: if let a = Wire.body(e, CallAck.self), !a.ok { lastError = "call:\(a.err ?? "failed")" }
        case MsgType.ussdResult:
            if let r = Wire.body(e, UssdResult.self), var u = ussd, u.cmid == r.cmid {
                u.pending = false; u.ok = r.ok; u.text = r.text.map { Validate.display($0, max: 1000) }; u.err = r.err; ussd = u
            }
        case MsgType.ping: if let p = Wire.body(e, Ping.self) { send(Wire.encode(MsgType.pong, p, re: e.id)) }
        case MsgType.pong: if let p = Wire.body(e, Ping.self) { pingMs = max(0, Int(Int64(Date().timeIntervalSince1970 * 1000) - p.ts)) }
        case MsgType.error: if let b = Wire.body(e, ErrorBody.self) { lastError = b.code }
        case MsgType.unpair: forgetGateway(notifyGateway: false)
        case MsgType.audioState: if let a = Wire.body(e, AudioState.self) { callKit.audioState(a) }
        default: break
        }
    }

    private func clean(_ m: Sms) -> Sms {
        var m = m; m.address = Validate.display(m.address, max: 40); m.body = Validate.display(m.body, max: 3200); return m
    }

    // MARK: Actions

    private var connected: Bool { link == .connected }

    @discardableResult
    func sendSms(to: String, body: String, subId: Int?) -> ActionResult {
        guard let number = Validate.smsRecipient(to), let text = Validate.smsBody(body) else { return .invalid }
        if caps?.smsSend == false { return .unsupported }
        let cmid = UUID().uuidString
        let req = SmsSend(cmid: cmid, to: number, body: text, subId: subId)
        var thread = threadId(for: number)
        if thread == 0 { thread = localThread; localThread -= 1 }
        messages["p:\(cmid)"] = Sms(id: 0, thread: thread, address: number, body: text, ts: Int64(Date().timeIntervalSince1970 * 1000),
                                    dir: .outgoing, read: true, subId: subId, state: .pending, cmid: cmid)
        pendingSends[cmid] = (req, Date())
        guard connected else { return .notConnected }
        send(Wire.encode(MsgType.smsSend, req, id: takeId()))
        return .ok
    }

    func retry(cmid: String) -> ActionResult {
        guard var m = messages["p:\(cmid)"] else { return .invalid }
        let req = SmsSend(cmid: cmid, to: m.address, body: m.body, subId: m.subId)
        m.state = .pending; messages["p:\(cmid)"] = m
        pendingSends[cmid] = (req, Date())
        guard connected else { return .notConnected }
        send(Wire.encode(MsgType.smsSend, req, id: takeId()))
        return .ok
    }

    private func expirePending() {
        let expired = pendingSends.filter { Date().timeIntervalSince($0.value.1) > 90 }.map(\.key)
        for c in expired {
            pendingSends[c] = nil
            if var m = messages["p:\(c)"], m.state == .pending { m.state = .failed; messages["p:\(c)"] = m }
        }
    }

    func markRead(thread: Int64) {
        for (k, m) in messages where m.thread == thread && m.isUnread { var mm = m; mm.read = true; messages[k] = mm }
        if connected, thread > 0 { send(Wire.encode(MsgType.smsRead, SmsRead(thread: thread), id: takeId())) }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["sms-\(thread)"])
    }

    /// Places a call through the gateway. With call audio, it goes through CallKit so it is a real call here.
    @discardableResult
    func dial(_ number: String, subId: Int?) -> ActionResult {
        guard let n = Validate.dialNumber(number) else { return .invalid }
        guard connected else { return .notConnected }
        guard caps?.callPlace == true else { return .unsupported }
        if bridged { callKit.startOutgoing(number: n, subId: subId); return .ok }
        return placeCall(n, subId: subId)
    }

    /// Sends the dial request itself (CallKit's start action calls this).
    @discardableResult
    func placeCall(_ number: String, subId: Int?) -> ActionResult {
        guard let n = Validate.dialNumber(number) else { return .invalid }
        guard connected else { return .notConnected }
        send(Wire.encode(MsgType.callDial, CallDial(cmid: UUID().uuidString, number: n, subId: subId), id: takeId()))
        return .ok
    }

    /// Answer / decline / end from the app's own call screen: through CallKit when the call is bridged.
    func answerCall(_ callId: String) -> ActionResult {
        if callKit.isBridged(callId) { callKit.answer(callId); return .ok }
        return callAction(callId, "answer")
    }

    func endCall(_ callId: String, ringing: Bool) -> ActionResult {
        if callKit.isBridged(callId) { callKit.end(callId); return .ok }
        return callAction(callId, ringing ? "reject" : "end")
    }

    /// Keypad tones played into the call by the gateway's phone app (full call control).
    @discardableResult
    func sendDtmf(_ callId: String, _ digits: String) -> ActionResult {
        guard !digits.isEmpty, digits.count <= 20, digits.allSatisfy({ "0123456789*#".contains($0) }) else { return .invalid }
        guard connected else { return .notConnected }
        guard caps?.dtmf == true else { return .unsupported }
        send(Wire.encode(MsgType.callDtmf, CallDtmf(callId: callId, digits: digits), id: takeId()))
        return .ok
    }

    // MARK: Call audio

    func sendAudioStart(_ callId: String) { send(Wire.encode(MsgType.audioStart, AudioStart(callId: callId), id: takeId())) }
    func sendAudioStop(_ callId: String) { send(Wire.encode(MsgType.audioStop, AudioStop(callId: callId), id: takeId())) }

    /// One sealed audio frame to the gateway. Dropped silently when not connected (calls end on link loss).
    func sendAudioFrame(_ frame: Data) {
        guard let l = linkEngine, l.isEstablished else { return }
        try? l.sendAudio(frame)
    }

    /// Turning "Take calls on this iPhone" on asks for the microphone, the only moment it is requested.
    func setCallsOnThisPhone(_ on: Bool) async {
        var value = false
        if on { value = await AVAudioApplication.requestRecordPermission() }
        callsOnThisPhone = value
        defaults.set(value, forKey: "callsHere")
        if !value { lastError = on ? "audio:microphone" : nil }
    }

    @discardableResult
    func callAction(_ callId: String, _ action: String) -> ActionResult {
        guard ["answer", "reject", "end", "hold", "unhold"].contains(action) else { return .invalid }
        guard connected else { return .notConnected }
        guard caps?.callControl == true else { return .unsupported }
        if (action == "hold" || action == "unhold") && caps?.hold != true { return .unsupported }
        send(Wire.encode(MsgType.callAction, CallAction(callId: callId, action: action), id: takeId()))
        return .ok
    }

    @discardableResult
    func sendUssd(_ code: String, subId: Int?) -> ActionResult {
        guard let c = Validate.ussdCode(code) else { return .invalid }
        guard connected else { return .notConnected }
        guard caps?.ussd == true else { return .unsupported }
        let cmid = UUID().uuidString
        ussd = UssdReply(cmid: cmid, code: c, pending: true)
        send(Wire.encode(MsgType.ussdSend, UssdSend(cmid: cmid, code: c, subId: subId), id: takeId()))
        return .ok
    }

    // MARK: Notifications (opt-in)

    /// Asks for notification permission only when the user turns notifications on.
    func setNotifications(_ on: Bool) async {
        var value = false
        if on { value = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false }
        notificationsEnabled = value
        defaults.set(value, forKey: "notify")
    }

    func setShowPreviews(_ on: Bool) { showPreviews = on; defaults.set(on, forKey: "previews") }

    private func notifyMessage(_ m: Sms) {
        guard notificationsEnabled, inBackground else { return }
        let c = UNMutableNotificationContent()
        c.title = showPreviews ? (name(for: m.address) ?? m.address) : String(localized: "notify.newMessage")
        c.body = showPreviews ? String(m.body.prefix(200)) : String(localized: "notify.openToRead")
        c.sound = .default
        c.threadIdentifier = "sms-\(m.thread)"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "sms-\(m.thread)", content: c, trigger: nil))
    }

    private func notifyCall(_ call: LiveCall) {
        guard notificationsEnabled, inBackground else { return }
        let c = UNMutableNotificationContent()
        c.title = String(localized: "incall.ringing")
        c.body = showPreviews ? (call.name ?? name(for: call.number) ?? call.number) : String(localized: "notify.openToAnswer")
        c.sound = .default
        c.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "call", content: c, trigger: nil))
    }
}

// MARK: - Bluetooth and link events

extension AppModel: BleCentralDelegate, ClientLinkDelegate {
    func bleStateChanged(poweredOn: Bool, unauthorized: Bool) {
        if unauthorized { link = .bluetoothDenied; return }
        if !poweredOn {
            if !demo && gateway != nil { link = .bluetoothOff }
            linkEngine?.transportClosed(); linkEngine = nil; connection = nil
            return
        }
        if case .searching = pairing { ble?.scan() } else if running { scanForGateway() }
    }

    func bleFound(_ p: CBPeripheral, pairing isPairing: Bool) {
        if case .searching = pairing {
            guard isPairing else { return }
            pairingCandidates[p.identifier] = p
            pairing = .searching(several: pairingCandidates.count > 1)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, case .searching = self.pairing, self.pairingCandidates.count == 1, let only = self.pairingCandidates.values.first else { return }
                    self.ble?.stopScan()
                    self.pairing = .connecting
                    self.ble?.connect(only)
                }
            }
            return
        }
        guard running, connection == nil, (notOurs[p.identifier] ?? .distantPast) < Date() else { return }
        ble?.stopScan()
        link = .connecting
        ble?.connect(p)
    }

    func bleReady(_ c: BleConnection) {
        guard let id = loadIdentity() else { c.close(); return }
        connection = c
        let mode: ClientLink.Mode
        if case .connecting = pairing { mode = .pair(clientName: UIDevice.current.name) }
        else if let g = gateway { mode = .resume(g) }
        else { c.close(); return }
        let l = ClientLink(identity: id, transport: c, mode: mode)
        l.delegate = self
        linkEngine = l
        l.start()
    }

    func bleBytes(_ data: Data) { linkEngine?.receive(data) }

    func bleDisconnected(_ p: CBPeripheral) {
        linkEngine?.transportClosed(); linkEngine = nil; connection = nil
        callKit.linkLost()
        if running && !demo && gateway != nil { link = .reconnecting; scheduleRetry() }
    }

    func linkSasReady(sas: String, gatewayName: String, fingerprint: String) {
        pairing = .compare(sas: sas, gatewayName: gatewayName, fingerprint: fingerprint, waitingForGateway: false)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    func linkPaired(_ g: TrustedGateway) {
        saveGateway(g)
        resetData()
        pairing = .done(g.name)
        running = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    func linkEstablished() { sessionUp() }

    func linkMessage(_ json: String) { handle(json) }

    func linkAudio(_ frame: Data) { callKit.receiveAudio(frame) }

    func linkClosed(_ reason: CloseReason, byPeer: Bool) {
        if let p = connection?.peripheral, reason == .unknownDevice || reason == .authFailed {
            notOurs[p.identifier] = Date().addingTimeInterval(600) // someone else's gateway, or ours after it removed us
        }
        switch pairing {
        case .connecting, .compare: pairing = .failed(reason); UINotificationFeedbackGenerator().notificationOccurred(.error)
        default: break
        }
    }
}

#if DEBUG
// MARK: - Screenshots (debug builds only)

/// Opens one screen with demo data, for the automated screenshot workflow
/// (`xcrun simctl launch … -polScreenshot <screen>`). Compiled out of release builds.
extension AppModel {
    static var screenshotArgument: String? {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "-polScreenshot"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    func prepareScreenshot(_ screen: String) {
        if screen == "pair" {
            demoGateway = nil; demo = false; defaults.set(false, forKey: "demo")
            pairing = .compare(sas: "974705", gatewayName: "Pixel 8", fingerprint: "6BCD-A99D-58E5", waitingForGateway: false)
            return
        }
        enterDemo()
        switch screen {
        case "contacts": screenshotTab = 1
        case "keypad": screenshotTab = 2
        case "messages": screenshotTab = 3
        case "thread": screenshotTab = 3; screenshotThread = ThreadRoute(thread: 1, address: "+15555550101")
        case "gateway": screenshotTab = 0; showGateway = true
        case "search": screenshotTab = 4
        case "incall": screenshotTab = 0; DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in MainActor.assumeIsolated { self?.simulateIncomingCall() } }
        default: screenshotTab = 0
        }
    }
}
#endif
