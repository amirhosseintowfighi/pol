import Foundation

// Wire models, matching core/src/main/kotlin/studio/virgule/pol/core/domain/Models.kt and proto/Messages.kt.
// Unknown fields are ignored; missing optional fields decode as nil, so newer gateways keep working.

struct SimInfo: Codable, Hashable, Sendable {
    var slot: Int
    var subId: Int
    var label: String
    var carrier: String?
    var signal: Int?
    var network: String?
    var service: String?
    var roaming: Bool?
    var displayLabel: String { label.isEmpty ? "SIM \(slot + 1)" : label }
}

struct Capabilities: Codable, Hashable, Sendable {
    var smsSend = false, smsRead = false, smsReceive = false, callPlace = false, callControl = false
    var callLog = false, contacts = false, ussd = false, simChoice = false
    var callAudio = false, hold = false, mute = false, speaker = false, dtmf = false

    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        func b(_ k: CodingKeys) -> Bool { (try? c.decodeIfPresent(Bool.self, forKey: k)) ?? false }
        smsSend = b(.smsSend); smsRead = b(.smsRead); smsReceive = b(.smsReceive); callPlace = b(.callPlace)
        callControl = b(.callControl); callLog = b(.callLog); contacts = b(.contacts); ussd = b(.ussd)
        simChoice = b(.simChoice); callAudio = b(.callAudio); hold = b(.hold); mute = b(.mute); speaker = b(.speaker)
        dtmf = b(.dtmf)
    }
}

struct GatewayStatus: Codable, Hashable, Sendable {
    var name: String
    var appVersion: String?
    var battery: Int?
    var charging: Bool?
    var sims: [SimInfo]
    var defaultVoiceSub: Int?
    var defaultSmsSub: Int?
    var caps: Capabilities
    var sdk: Int?
    /// Call-audio bridge on the gateway: "ready" | "needs_setup" | "android_version" | "not_interceptable" | "off".
    var audio: String?
}

enum Direction: String, Codable, Sendable { case incoming = "in", outgoing = "out" }

enum DeliveryState: String, Codable, Sendable {
    case pending, sent, delivered, failed, received
    init(from d: Decoder) throws { self = DeliveryState(rawValue: try d.singleValueContainer().decode(String.self)) ?? .received }
}

struct Sms: Codable, Hashable, Identifiable, Sendable {
    var id: Int64
    var thread: Int64
    var address: String
    var body: String
    var ts: Int64
    var dir: Direction
    var read: Bool?
    var subId: Int?
    var state: DeliveryState?
    var cmid: String?

    var key: String { id > 0 ? "m:\(id)" : "p:\(cmid ?? String(ts))" }
    var isUnread: Bool { dir == .incoming && read == false }
    var date: Date { Date(timeIntervalSince1970: TimeInterval(ts) / 1000) }
}

enum CallType: String, Codable, Sendable {
    case incoming, outgoing, missed, rejected, blocked, other
    init(from d: Decoder) throws { self = CallType(rawValue: try d.singleValueContainer().decode(String.self)) ?? .other }
}

struct CallEntry: Codable, Hashable, Identifiable, Sendable {
    var id: Int64
    var number: String
    var ts: Int64
    var durationS: Int64?
    var type: CallType
    var subId: Int?
    var name: String?
    var date: Date { Date(timeIntervalSince1970: TimeInterval(ts) / 1000) }
}

struct PhoneNumber: Codable, Hashable, Sendable { var number: String; var label: String? }

struct Contact: Codable, Hashable, Identifiable, Sendable {
    var id: Int64
    var name: String
    var phones: [PhoneNumber]
    var starred: Bool?
}

enum LiveCallState: String, Codable, Sendable {
    case dialing, ringing, active, holding, ended
    init(from d: Decoder) throws { self = LiveCallState(rawValue: try d.singleValueContainer().decode(String.self)) ?? .active }
}

struct LiveCall: Codable, Hashable, Identifiable, Sendable {
    var callId: String
    var number: String
    var name: String?
    var state: LiveCallState
    var incoming: Bool
    var activeSince: Int64?
    var subId: Int?
    var id: String { callId }
}

struct Conversation: Identifiable, Hashable {
    let thread: Int64
    let address: String
    let last: Sms
    let unread: Int
    var id: Int64 { thread }
}

// Message bodies.
struct SyncReq: Codable { var smsSince: Int64; var callsSince: Int64; var contacts: Bool; var client: String }
struct SmsPage: Codable { var items: [Sms]; var more: Bool }
struct SmsNew: Codable { var item: Sms }
struct SmsSend: Codable { var cmid: String; var to: String; var body: String; var subId: Int? }
struct SmsAck: Codable { var cmid: String; var state: DeliveryState; var err: String? }
struct SmsRead: Codable { var thread: Int64 }
struct CallsPage: Codable { var items: [CallEntry]; var more: Bool }
struct CallsNew: Codable { var item: CallEntry }
struct ContactsPage: Codable { var items: [Contact]; var more: Bool; var first: Bool }
struct CallDial: Codable { var cmid: String; var number: String; var subId: Int? }
struct CallAck: Codable { var cmid: String; var ok: Bool; var err: String? }
struct CallStates: Codable { var calls: [LiveCall] }
struct CallAction: Codable { var callId: String; var action: String }
struct CallDtmf: Codable { var callId: String; var digits: String }
struct UssdSend: Codable { var cmid: String; var code: String; var subId: Int? }
struct UssdResult: Codable { var cmid: String; var ok: Bool; var text: String?; var err: String? }
struct Ping: Codable { var ts: Int64 }
struct AudioStart: Codable { var callId: String; var codec: String = "pcmu"; var codecs: [String] = AudioSpec.codecs }
struct AudioStop: Codable { var callId: String }
struct AudioState: Codable { var callId: String; var state: String; var reason: String?; var codec: String?; var rate: Int? }
struct ErrorBody: Codable { var code: String; var msg: String? }
struct Empty: Codable {}

enum MsgType {
    static let status = "status", sync = "sync", smsPage = "sms.page", smsNew = "sms.new", smsSend = "sms.send"
    static let smsAck = "sms.ack", smsRead = "sms.read", callsPage = "calls.page", callsNew = "calls.new"
    static let contactsPage = "contacts.page", callDial = "call.dial", callAck = "call.ack", callState = "call.state"
    static let callAction = "call.action", callDtmf = "call.dtmf", ussdSend = "ussd.send", ussdResult = "ussd.result"
    static let ping = "ping", pong = "pong", unpair = "unpair", error = "error"
    static let audioStart = "audio.start", audioStop = "audio.stop", audioState = "audio.state"
}

/// JSON envelope `{"t","id","re","b"}` (protocol/PROTOCOL.md §5).
enum Wire {
    private struct Header: Decodable { let t: String; let id: Int64?; let re: Int64? }
    private struct Body<B: Decodable>: Decodable { let b: B }
    private struct Out<B: Encodable>: Encodable { let t: String; let id: Int64?; let re: Int64?; let b: B }

    struct Envelope { let type: String; let id: Int64?; let re: Int64?; let raw: Data }

    static func encode<B: Encodable>(_ type: String, _ body: B, id: Int64? = nil, re: Int64? = nil) -> String? {
        guard let d = try? JSONEncoder().encode(Out(t: type, id: id, re: re, b: body)), d.count <= LinkLimits.maxMessage else { return nil }
        return String(decoding: d, as: UTF8.self)
    }

    static func decode(_ json: String) -> Envelope? {
        let d = Data(json.utf8)
        guard d.count <= LinkLimits.maxMessage, let h = try? JSONDecoder().decode(Header.self, from: d), (1...32).contains(h.t.count) else { return nil }
        return Envelope(type: h.t, id: h.id, re: h.re, raw: d)
    }

    static func body<B: Decodable>(_ e: Envelope, _ type: B.Type) -> B? { try? JSONDecoder().decode(Body<B>.self, from: e.raw).b }
}

/// Input checks, identical to core Validate.kt.
enum Validate {
    static let maxSmsChars = 1600

    static func normalizeNumber(_ input: String) -> String? {
        let t = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.count <= 64 else { return nil }
        var out = ""
        for (i, ch) in t.enumerated() {
            if let v = ch.unicodeScalars.first?.value, ch.unicodeScalars.count == 1 {
                switch v {
                case 0x30...0x39: out.append(ch)
                case 0x06F0...0x06F9: out.append(Character(UnicodeScalar(v - 0x06F0 + 0x30)!))
                case 0x0660...0x0669: out.append(Character(UnicodeScalar(v - 0x0660 + 0x30)!))
                case 0x2A, 0x23: out.append(ch)
                case 0x2B where i == 0: out.append(ch)
                case 0x20, 0x2D, 0x28, 0x29, 0x2E, 0xA0: continue
                default: return nil
                }
            } else { return nil }
        }
        guard out.range(of: #"^\+?[0-9*#]{1,32}$"#, options: .regularExpression) != nil else { return nil }
        return out
    }

    static func dialNumber(_ s: String) -> String? {
        guard let n = normalizeNumber(s), !n.contains("*"), !n.contains("#") else { return nil }
        return n
    }

    static func smsRecipient(_ s: String) -> String? { dialNumber(s) }

    static func smsBody(_ s: String) -> String? {
        guard !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, s.count <= maxSmsChars else { return nil }
        return String(s.unicodeScalars.filter { $0 == "\n" || $0 == "\t" || !CharacterSet.controlCharacters.contains($0) })
    }

    static func ussdCode(_ s: String) -> String? {
        guard let n = normalizeNumber(s), n.range(of: #"^[*#][0-9*#]{1,30}#$"#, options: .regularExpression) != nil else { return nil }
        return n
    }

    static func display(_ s: String?, max: Int = 200) -> String {
        String(String((s ?? "").unicodeScalars.filter { $0 == "\n" || !CharacterSet.controlCharacters.contains($0) }).prefix(max))
    }
}

/// Last 9 digits, so "+98 912 …", "0912 …" and "912 …" match the same person.
func numberKey(_ n: String) -> String { String(n.filter(\.isNumber).suffix(9)) }
