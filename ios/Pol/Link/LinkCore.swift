import CryptoKit
import Foundation

// Client side of the Pol link protocol v1 (protocol/PROTOCOL.md). Mirrors core/…/link in Kotlin and is
// checked against protocol/vectors.json. Uses only CryptoKit primitives: P-256, SHA-256, HKDF, AES-GCM, ECDSA.

enum RecordType: UInt8 {
    case pairCommit = 0x01, pairRevealGateway = 0x02, pairRevealClient = 0x03
    case hello = 0x04, helloAck = 0x05, reject = 0x06, sealed = 0x10
}

enum LinkLimits {
    static let maxBody = 8192
    static let maxPlaintext = maxBody - 8 - 16
    static let maxMessage = maxPlaintext - 1
    static let maxNameBytes = 64
}

/// Why a link closed. Raw values are the REJECT codes on the wire.
enum CloseReason: UInt8, Error {
    case normal = 0, badVersion = 1, unknownDevice = 2, pairingClosed = 3, busy = 4, declined = 5
    case authFailed = 6, tooManyAttempts = 7, timeout = 8, malformed = 9, revoked = 10, transport = 11
}

struct LinkError: Error { let reason: CloseReason }

enum HandshakeConst {
    static let version: UInt8 = 1
    static let nonceSize = 16
    static let deviceIdSize = 16
    static let commit = Data("POL1-commit".utf8)
    static let pair = Data("POL1-pair".utf8)
    static let resume = Data("POL1-resume".utf8)
    static let sas = Data("POL1-sas".utf8)
    static let keys = Data("POL1 keys".utf8)
    static let sigGateway = Data("POL1-sig-gateway".utf8)
    static let sigClient = Data("POL1-sig-client".utf8)
}

enum Channel: UInt8 { case message = 0x00, audio = 0x01, pairConfirm = 0xFE, finish = 0xFF }

func sha256(_ parts: Data...) -> Data {
    var h = SHA256()
    for p in parts { h.update(data: p) }
    return Data(h.finalize())
}

func deviceId(_ identityPublicKey: Data) -> Data { sha256(identityPublicKey).prefix(HandshakeConst.deviceIdSize) }

/// Six-digit code compared by the user on both phones.
func shortAuthString(_ transcript: Data) -> String {
    let h = Array(sha256(HandshakeConst.sas, transcript))
    let v = (UInt32(h[0]) << 24) | (UInt32(h[1]) << 16) | (UInt32(h[2]) << 8) | UInt32(h[3])
    return String(format: "%06u", v % 1_000_000)
}

func sessionKeys(shared: Data, transcript: Data) -> (c2g: SymmetricKey, g2c: SymmetricKey) {
    let okm = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: shared), salt: transcript,
                                     info: HandshakeConst.keys, outputByteCount: 64)
    let bytes = okm.withUnsafeBytes { Data($0) }
    return (SymmetricKey(data: bytes.prefix(32)), SymmetricKey(data: bytes.suffix(32)))
}

/// "AB12-CD34-EF56", the same fingerprint the Android apps show.
func fingerprint(_ publicKey: Data) -> String {
    let hex = sha256(publicKey).prefix(6).map { String(format: "%02X", $0) }.joined()
    return stride(from: 0, to: hex.count, by: 4).map { i -> String in
        let s = hex.index(hex.startIndex, offsetBy: i)
        return String(hex[s..<hex.index(s, offsetBy: 4)])
    }.joined(separator: "-")
}

func encodeName(_ name: String) -> Data {
    var s = String(name.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }).trimmingCharacters(in: .whitespaces)
    while s.utf8.count > LinkLimits.maxNameBytes { s.removeLast() }
    return Data(s.utf8)
}

func decodeName(_ d: Data) -> String {
    let s = String(decoding: d, as: UTF8.self)
    let clean = String(s.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }).trimmingCharacters(in: .whitespaces)
    return clean.isEmpty ? "Unnamed device" : clean
}

func encodeRecord(_ type: RecordType, _ body: Data) -> Data {
    precondition(body.count <= LinkLimits.maxBody)
    let len = body.count + 1
    var out = Data([UInt8(len >> 8), UInt8(len & 0xFF), type.rawValue])
    out.append(body)
    return out
}

func u64(_ v: UInt64) -> Data { Data((0..<8).map { UInt8(truncatingIfNeeded: v >> (56 - 8 * UInt64($0))) }) }

/// Reassembles records from arbitrarily cut transport chunks, with a hard size bound.
struct RecordReader {
    private var buf = Data()

    mutating func feed(_ bytes: Data) throws -> [(RecordType, Data)] {
        guard buf.count + bytes.count <= 3 + LinkLimits.maxBody + 4096 else { throw LinkError(reason: .malformed) }
        buf.append(bytes)
        var out: [(RecordType, Data)] = []
        while buf.count >= 3 {
            let b = [UInt8](buf.prefix(3))
            let len = Int(b[0]) << 8 | Int(b[1])
            guard len >= 1, len - 1 <= LinkLimits.maxBody else { throw LinkError(reason: .malformed) }
            guard buf.count >= 2 + len else { break }
            guard let type = RecordType(rawValue: b[2]) else { throw LinkError(reason: .malformed) }
            let start = buf.startIndex
            out.append((type, Data(buf[(start + 3)..<(start + 2 + len)])))
            buf = Data(buf[(start + 2 + len)...])
        }
        return out
    }
}

/// Bounded reader for fixed-layout handshake bodies.
struct Cursor {
    private let b: [UInt8]
    private var p = 0
    init(_ d: Data) { b = [UInt8](d) }
    mutating func u8() throws -> UInt8 { guard p < b.count else { throw LinkError(reason: .malformed) }; defer { p += 1 }; return b[p] }
    mutating func bytes(_ n: Int) throws -> Data {
        guard n >= 0, p + n <= b.count else { throw LinkError(reason: .malformed) }
        defer { p += n }; return Data(b[p..<(p + n)])
    }
    mutating func u64() throws -> UInt64 { var v: UInt64 = 0; for _ in 0..<8 { v = v << 8 | UInt64(try u8()) }; return v }
    func end() throws { guard p == b.count else { throw LinkError(reason: .malformed) } }
}

/// AES-256-GCM with a strict per-direction counter as the nonce: replay, reordering or loss closes the session.
struct SecureChannel {
    let sendKey: SymmetricKey
    let receiveKey: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var receiveCounter: UInt64 = 0
    static let maxRecords: UInt64 = 1 << 31

    init(sendKey: SymmetricKey, receiveKey: SymmetricKey) { self.sendKey = sendKey; self.receiveKey = receiveKey }

    mutating func seal(_ channel: Channel, _ payload: Data) throws -> Data {
        guard payload.count + 1 <= LinkLimits.maxPlaintext, sendCounter < Self.maxRecords else { throw LinkError(reason: .normal) }
        let ctr = sendCounter; sendCounter += 1
        let nonce = try AES.GCM.Nonce(data: Data(count: 4) + u64(ctr))
        var pt = Data([channel.rawValue]); pt.append(payload)
        let box = try AES.GCM.seal(pt, using: sendKey, nonce: nonce, authenticating: Data([RecordType.sealed.rawValue]) + u64(ctr))
        return encodeRecord(.sealed, u64(ctr) + box.ciphertext + box.tag)
    }

    mutating func open(_ body: Data) throws -> (Channel, Data) {
        guard body.count >= 8 + 16 + 1 else { throw LinkError(reason: .malformed) }
        var c = Cursor(body)
        let ctr = try c.u64()
        guard ctr == receiveCounter else { throw LinkError(reason: .authFailed) }
        let rest = Data(body.dropFirst(8))
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: Data(count: 4) + u64(ctr)),
                                        ciphertext: rest.dropLast(16), tag: rest.suffix(16))
        guard let pt = try? AES.GCM.open(box, using: receiveKey, authenticating: Data([RecordType.sealed.rawValue]) + u64(ctr)),
              let first = pt.first, let ch = Channel(rawValue: first) else { throw LinkError(reason: .authFailed) }
        receiveCounter += 1
        return (ch, Data(pt.dropFirst()))
    }
}

/// A paired gateway. Its public identity key is not secret, but is kept in the Keychain to protect integrity.
struct TrustedGateway: Codable, Equatable, Sendable {
    let id: Data
    let publicKey: Data
    let name: String
    let pairedAt: Date
    var fingerprintText: String { fingerprint(publicKey) }
}

protocol LinkTransport: AnyObject {
    func write(_ data: Data)
    func close()
}

@MainActor
protocol ClientLinkDelegate: AnyObject {
    func linkSasReady(sas: String, gatewayName: String, fingerprint: String)
    func linkPaired(_ gateway: TrustedGateway)
    func linkEstablished()
    func linkMessage(_ json: String)
    func linkAudio(_ frame: Data)
    func linkClosed(_ reason: CloseReason, byPeer: Bool)
}

/// Client handshake and session state machine (pair or resume). Main-actor only.
@MainActor
final class ClientLink {
    enum Mode { case pair(clientName: String), resume(TrustedGateway) }
    private enum State { case idle, awaitReveal, awaitConfirms, awaitHelloAck, awaitFinish, established }

    static let handshakeTimeout: TimeInterval = 15
    static let confirmTimeout: TimeInterval = 120

    private let identity: DeviceIdentity
    private weak var transport: LinkTransport?
    weak var delegate: ClientLinkDelegate?
    private let mode: Mode
    private var state = State.idle
    private var reader = RecordReader()
    private var channel: SecureChannel?
    private let ephemeral = P256.KeyAgreement.PrivateKey()
    private let nonce = Data((0..<HandshakeConst.nonceSize).map { _ in UInt8.random(in: 0...255) })
    private var first = Data()
    private var transcript = Data()
    private var pending: TrustedGateway?
    private var localConfirmed = false
    private var peerConfirmed = false
    private(set) var closed = false
    private var deadline = Date.distantFuture

    var isEstablished: Bool { state == .established && !closed }

    init(identity: DeviceIdentity, transport: LinkTransport, mode: Mode) {
        self.identity = identity; self.transport = transport; self.mode = mode
    }

    func start() {
        let ePub = ephemeral.publicKey.x963Representation
        switch mode {
        case .pair(let name):
            let nameBytes = encodeName(name)
            first = Data([HandshakeConst.version]) + sha256(HandshakeConst.commit, ePub, identity.publicKey, nonce, nameBytes)
            transport?.write(encodeRecord(.pairCommit, first))
            state = .awaitReveal
        case .resume:
            first = Data([HandshakeConst.version]) + ePub + nonce + deviceId(identity.publicKey)
            transport?.write(encodeRecord(.hello, first))
            state = .awaitHelloAck
        }
        deadline = Date().addingTimeInterval(Self.handshakeTimeout)
    }

    func tick() { if !closed && Date() >= deadline { finish(.timeout, byPeer: false, sendReject: true) } }

    func confirmSas() {
        guard !closed, state == .awaitConfirms, !localConfirmed else { return }
        localConfirmed = true
        do {
            try sendSealed(.pairConfirm, identity.sign(HandshakeConst.sigClient + transcript))
            maybeFinishPairing()
        } catch { finish(.authFailed, byPeer: false, sendReject: false) }
    }

    func declineSas() { close(.declined) }

    func send(_ json: String) throws {
        guard isEstablished else { throw LinkError(reason: .transport) }
        try sendSealed(.message, Data(json.utf8))
    }

    /// One encoded call-audio frame (`codec ‖ seq ‖ payload`).
    func sendAudio(_ frame: Data) throws {
        guard isEstablished else { throw LinkError(reason: .transport) }
        try sendSealed(.audio, frame)
    }

    func close(_ reason: CloseReason = .normal) { if !closed { finish(reason, byPeer: false, sendReject: true) } }
    func transportClosed() { if !closed { finish(.transport, byPeer: true, sendReject: false) } }

    func receive(_ bytes: Data) {
        guard !closed else { return }
        do {
            for (type, body) in try reader.feed(bytes) {
                if closed { return }
                if type == .reject {
                    finish(body.count == 1 ? CloseReason(rawValue: body[body.startIndex]) ?? .malformed : .malformed, byPeer: true, sendReject: false)
                    return
                }
                try handle(type, body)
            }
        } catch let e as LinkError {
            finish(e.reason, byPeer: false, sendReject: e.reason != .malformed && e.reason != .authFailed)
        } catch {
            finish(.authFailed, byPeer: false, sendReject: false)
        }
    }

    private func handle(_ type: RecordType, _ body: Data) throws {
        switch state {
        case .awaitReveal:
            guard type == .pairRevealGateway else { throw LinkError(reason: .malformed) }
            var c = Cursor(body)
            guard try c.u8() == HandshakeConst.version else { throw LinkError(reason: .badVersion) }
            let eG = try c.bytes(65), sG = try c.bytes(65)
            _ = try c.bytes(HandshakeConst.nonceSize)
            let nameLen = Int(try c.u8())
            guard nameLen <= LinkLimits.maxNameBytes else { throw LinkError(reason: .malformed) }
            let name = decodeName(try c.bytes(nameLen))
            try c.end()
            _ = try P256.Signing.PublicKey(x963Representation: sG) // validates the point
            guard case .pair(let clientName) = mode else { throw LinkError(reason: .malformed) }
            let nameBytes = encodeName(clientName)
            let revealC = ephemeral.publicKey.x963Representation + identity.publicKey + nonce + Data([UInt8(nameBytes.count)]) + nameBytes
            transport?.write(encodeRecord(.pairRevealClient, revealC))
            transcript = sha256(HandshakeConst.pair, first, body, revealC)
            let shared = try ephemeral.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(x963Representation: eG))
            let keys = sessionKeys(shared: shared.withUnsafeBytes { Data($0) }, transcript: transcript)
            channel = SecureChannel(sendKey: keys.c2g, receiveKey: keys.g2c)
            pending = TrustedGateway(id: deviceId(sG), publicKey: sG, name: name, pairedAt: Date())
            state = .awaitConfirms
            deadline = Date().addingTimeInterval(Self.confirmTimeout)
            delegate?.linkSasReady(sas: shortAuthString(transcript), gatewayName: name, fingerprint: fingerprint(sG))

        case .awaitConfirms:
            guard type == .sealed, var ch = channel else { throw LinkError(reason: .malformed) }
            let (c, payload) = try ch.open(body); channel = ch
            guard c == .pairConfirm, !peerConfirmed, let g = pending else { throw LinkError(reason: .malformed) }
            guard verify(g.publicKey, HandshakeConst.sigGateway + transcript, payload) else { throw LinkError(reason: .authFailed) }
            peerConfirmed = true
            maybeFinishPairing()

        case .awaitHelloAck:
            guard type == .helloAck, case .resume(let gw) = mode else { throw LinkError(reason: .malformed) }
            var c = Cursor(body)
            guard try c.u8() == HandshakeConst.version else { throw LinkError(reason: .badVersion) }
            let eG = try c.bytes(65), nG = try c.bytes(HandshakeConst.nonceSize)
            let sigLen = Int(try c.u8())
            let sig = try c.bytes(sigLen)
            try c.end()
            transcript = sha256(HandshakeConst.resume, first, Data([HandshakeConst.version]), eG, nG)
            guard verify(gw.publicKey, HandshakeConst.sigGateway + transcript, sig) else { throw LinkError(reason: .authFailed) }
            let shared = try ephemeral.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(x963Representation: eG))
            let keys = sessionKeys(shared: shared.withUnsafeBytes { Data($0) }, transcript: transcript)
            channel = SecureChannel(sendKey: keys.c2g, receiveKey: keys.g2c)
            try sendSealed(.finish, identity.sign(HandshakeConst.sigClient + transcript))
            state = .awaitFinish

        case .awaitFinish:
            guard type == .sealed, var ch = channel else { throw LinkError(reason: .malformed) }
            let (c, payload) = try ch.open(body); channel = ch
            guard c == .finish, payload.isEmpty else { throw LinkError(reason: .malformed) }
            state = .established
            deadline = .distantFuture
            delegate?.linkEstablished()

        case .established:
            guard type == .sealed, var ch = channel else { throw LinkError(reason: .malformed) }
            let (c, payload) = try ch.open(body); channel = ch
            switch c {
            case .message: delegate?.linkMessage(String(decoding: payload, as: UTF8.self))
            case .audio: delegate?.linkAudio(payload)
            default: throw LinkError(reason: .malformed)
            }

        case .idle:
            throw LinkError(reason: .malformed)
        }
    }

    private func maybeFinishPairing() {
        guard localConfirmed, peerConfirmed, state == .awaitConfirms, let g = pending else { return }
        state = .established
        deadline = .distantFuture
        delegate?.linkPaired(g)
        delegate?.linkEstablished()
    }

    private func verify(_ key: Data, _ data: Data, _ der: Data) -> Bool {
        guard let pk = try? P256.Signing.PublicKey(x963Representation: key),
              let sig = try? P256.Signing.ECDSASignature(derRepresentation: der) else { return false }
        return pk.isValidSignature(sig, for: data)
    }

    private func sendSealed(_ ch: Channel, _ payload: Data) throws {
        guard var c = channel else { throw LinkError(reason: .malformed) }
        let rec = try c.seal(ch, payload)
        channel = c
        transport?.write(rec)
    }

    private func finish(_ reason: CloseReason, byPeer: Bool, sendReject: Bool) {
        guard !closed else { return }
        closed = true
        if sendReject { transport?.write(encodeRecord(.reject, Data([reason.rawValue]))) }
        channel = nil
        transport?.close()
        delegate?.linkClosed(reason, byPeer: byPeer)
    }
}
