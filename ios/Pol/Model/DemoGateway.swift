import Foundation

/// Simulated gateway speaking the real control protocol in memory (same data as core DemoGateway.kt).
/// It has no access to Bluetooth, telephony or the network: nothing it "sends" or "dials" leaves the iPhone.
/// Numbers use the 555-01xx range reserved for fiction.
@MainActor
final class DemoGateway {
    private let reply: (String) -> Void
    private var nextSms: Int64 = 1000
    private var live: LiveCall?
    private var messages: [Sms]
    private var calls: [CallEntry]
    private let contacts: [Contact] = [
        Contact(id: 1, name: "Sara Ahmadi", phones: [PhoneNumber(number: "+15555550101", label: "Mobile")], starred: true),
        Contact(id: 2, name: "Daniel Reyes", phones: [PhoneNumber(number: "+15555550102", label: "Mobile")], starred: false),
        Contact(id: 3, name: "Neda Karimi", phones: [PhoneNumber(number: "+15555550103", label: "Mobile"), PhoneNumber(number: "+15555550113", label: "Work")], starred: true),
        Contact(id: 4, name: "Omid Rahimi", phones: [PhoneNumber(number: "+15555550104", label: "Home")], starred: false),
        Contact(id: 5, name: "Lena Fischer", phones: [PhoneNumber(number: "+15555550105", label: "Mobile")], starred: false),
    ]
    private var status: GatewayStatus {
        var caps = Capabilities()
        caps.smsSend = true; caps.smsRead = true; caps.smsReceive = true; caps.callPlace = true; caps.callControl = true
        caps.callLog = true; caps.contacts = true; caps.ussd = true; caps.simChoice = true
        return GatewayStatus(name: "Demo gateway", appVersion: "demo", battery: 76, charging: false, sims: [
            SimInfo(slot: 0, subId: 1, label: "Personal", carrier: "Demo Mobile", signal: 4, network: "LTE", service: "in", roaming: false),
            SimInfo(slot: 1, subId: 2, label: "Work", carrier: "Sample Telecom", signal: 2, network: "3G", service: "in", roaming: false),
        ], defaultVoiceSub: 1, defaultSmsSub: 1, caps: caps, sdk: 35)
    }

    init(reply: @escaping (String) -> Void) {
        self.reply = reply
        let now = Int64(Date().timeIntervalSince1970 * 1000), min: Int64 = 60_000, day = 1440 * min
        messages = [
            Sms(id: 1, thread: 1, address: "+15555550101", body: "Are we still on for lunch tomorrow?", ts: now - 35 * min, dir: .incoming, read: false, subId: 1, state: .received),
            Sms(id: 2, thread: 1, address: "+15555550101", body: "Yes, 1 pm works.", ts: now - 30 * min, dir: .outgoing, read: true, subId: 1, state: .delivered),
            Sms(id: 3, thread: 1, address: "+15555550101", body: "Great, see you there 🙂", ts: now - 28 * min, dir: .incoming, read: false, subId: 1, state: .received),
            Sms(id: 4, thread: 2, address: "+15555550102", body: "The documents are ready to sign.", ts: now - 180 * min, dir: .incoming, read: true, subId: 2, state: .received),
            Sms(id: 5, thread: 3, address: "DemoBank", body: "Your verification code is 482913. It expires in 2 minutes.", ts: now - day, dir: .incoming, read: true, subId: 1, state: .received),
            Sms(id: 6, thread: 4, address: "+15555550103", body: "سلام! فردا جلسه ساعت ۱۰ است.", ts: now - 2 * day, dir: .incoming, read: true, subId: 1, state: .received),
        ]
        calls = [
            CallEntry(id: 1, number: "+15555550102", ts: now - 50 * min, durationS: 0, type: .missed, subId: 2, name: "Daniel Reyes"),
            CallEntry(id: 2, number: "+15555550101", ts: now - 240 * min, durationS: 312, type: .outgoing, subId: 1, name: "Sara Ahmadi"),
            CallEntry(id: 3, number: "+15555550104", ts: now - day, durationS: 95, type: .incoming, subId: 1, name: "Omid Rahimi"),
            CallEntry(id: 4, number: "+15555550199", ts: now - 2 * day, durationS: 0, type: .rejected, subId: 1, name: nil),
        ]
    }

    private var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private func out<B: Encodable>(_ t: String, _ b: B, re: Int64? = nil) { if let s = Wire.encode(t, b, re: re) { reply(s) } }
    private func pushCalls() { out(MsgType.callState, CallStates(calls: live.map { [$0] } ?? [])) }

    func receive(_ json: String) {
        guard let e = Wire.decode(json) else { return }
        switch e.type {
        case MsgType.sync:
            out(MsgType.status, status)
            out(MsgType.smsPage, SmsPage(items: messages, more: false))
            out(MsgType.callsPage, CallsPage(items: calls, more: false))
            out(MsgType.contactsPage, ContactsPage(items: contacts, more: false, first: true))
            pushCalls()
        case MsgType.smsSend:
            guard let s = Wire.body(e, SmsSend.self) else { return }
            let thread = messages.first { $0.address == s.to }?.thread ?? ((messages.map(\.thread).max() ?? 0) + 1)
            let m = Sms(id: nextSms, thread: thread, address: s.to, body: s.body, ts: nowMs, dir: .outgoing, read: true, subId: s.subId, state: .sent, cmid: s.cmid)
            nextSms += 1; messages.append(m)
            out(MsgType.smsNew, SmsNew(item: m))
            out(MsgType.smsAck, SmsAck(cmid: s.cmid, state: .delivered, err: nil))
        case MsgType.callDial:
            guard let d = Wire.body(e, CallDial.self) else { return }
            live = LiveCall(callId: "demo-\(d.cmid.prefix(8))", number: d.number, name: contacts.first { $0.phones.contains { $0.number == d.number } }?.name,
                            state: .active, incoming: false, activeSince: nowMs, subId: d.subId ?? 1)
            out(MsgType.callAck, CallAck(cmid: d.cmid, ok: true, err: nil))
            pushCalls()
        case MsgType.callAction:
            guard let a = Wire.body(e, CallAction.self), let c = live else { return }
            if a.action == "answer" { live?.state = .active; live?.activeSince = nowMs } else {
                live = nil
                let entry = CallEntry(id: (calls.map(\.id).max() ?? 0) + 1, number: c.number, ts: c.activeSince ?? nowMs,
                                      durationS: (nowMs - (c.activeSince ?? nowMs)) / 1000, type: c.incoming ? .incoming : .outgoing, subId: c.subId, name: c.name)
                calls.insert(entry, at: 0)
                out(MsgType.callsPage, CallsPage(items: [entry], more: false))
            }
            pushCalls()
        case MsgType.ussdSend:
            guard let u = Wire.body(e, UssdSend.self) else { return }
            out(MsgType.ussdResult, UssdResult(cmid: u.cmid, ok: true, text: "Demo: balance 120,000 tomans. (Simulated reply — no code was sent to a carrier.)", err: nil))
        case MsgType.ping:
            if let p = Wire.body(e, Ping.self) { out(MsgType.pong, p, re: e.id) }
        default: break
        }
    }

    func simulateIncomingCall() {
        live = LiveCall(callId: "demo-in", number: "+15555550105", name: "Lena Fischer", state: .ringing, incoming: true, activeSince: nil, subId: 1)
        pushCalls()
    }

    func simulateIncomingSms() {
        let m = Sms(id: nextSms, thread: 2, address: "+15555550102", body: "This is a simulated message.", ts: nowMs, dir: .incoming, read: false, subId: 2, state: .received)
        nextSms += 1; messages.append(m)
        out(MsgType.smsNew, SmsNew(item: m))
    }
}
