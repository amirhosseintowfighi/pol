import CryptoKit
import XCTest
@testable import Pol

/// Checks the Swift implementation against protocol/vectors.json, produced by an independent Python
/// implementation (tools/vectors.py) and also asserted by the Kotlin core tests.
final class VectorTests: XCTestCase {
    private func hex(_ s: String) -> Data {
        var d = Data(); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return d
    }
    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    private func vectors() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "vectors", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testSasAndKeys() throws {
        let v = try vectors()
        let sas = try XCTUnwrap(v["sas"] as? [String: String])
        XCTAssertEqual(shortAuthString(hex(sas["transcript"]!)), sas["sas"])
        let k = try XCTUnwrap(v["keys"] as? [String: String])
        let keys = sessionKeys(shared: hex(k["shared"]!), transcript: hex(k["transcript"]!))
        XCTAssertEqual(hex(keys.c2g.withUnsafeBytes { Data($0) }), k["client_to_gateway"])
        XCTAssertEqual(hex(keys.g2c.withUnsafeBytes { Data($0) }), k["gateway_to_client"])
    }

    func testDeviceIdAndRecord() throws {
        let v = try vectors()
        let d = try XCTUnwrap(v["device_id"] as? [String: String])
        XCTAssertEqual(hex(deviceId(hex(d["public_key"]!))), d["id"])
        let r = try XCTUnwrap(v["record"] as? [String: Any])
        XCTAssertEqual(hex(encodeRecord(.sealed, hex(r["body"] as! String))), r["encoded"] as? String)
    }

    func testSealedRoundTripRejectsReplayAndTamper() throws {
        let k1 = SymmetricKey(size: .bits256), k2 = SymmetricKey(size: .bits256)
        var a = SecureChannel(sendKey: k1, receiveKey: k2)
        var b = SecureChannel(sendKey: k2, receiveKey: k1)
        let rec = try a.seal(.message, Data("hi".utf8))
        var reader = RecordReader()
        let parsed = try reader.feed(rec)
        let (ch, payload) = try b.open(parsed[0].1)
        XCTAssertEqual(ch, .message); XCTAssertEqual(payload, Data("hi".utf8))
        XCTAssertThrowsError(try b.open(parsed[0].1)) // replay
        var tampered = try a.seal(.message, Data("x".utf8))
        tampered[tampered.count - 1] ^= 1
        var r2 = RecordReader()
        XCTAssertThrowsError(try b.open(try r2.feed(tampered)[0].1))
    }

    func testValidation() {
        XCTAssertEqual(Validate.dialNumber("+98 (912) 123-4567"), "+989121234567")
        XCTAssertEqual(Validate.dialNumber("۰۹۱۲۱۲۳۴۵۶۷"), "09121234567")
        XCTAssertNil(Validate.dialNumber("*140#"))
        XCTAssertEqual(Validate.ussdCode("*140#"), "*140#")
        XCTAssertNil(Validate.ussdCode("*140"))
        XCTAssertNil(Validate.smsBody("   "))
    }

    func testWireIgnoresUnknownFields() {
        let e = Wire.decode(#"{"t":"sms.ack","b":{"cmid":"abc","state":"delivered","future":1},"extra":true}"#)
        XCTAssertEqual(e?.type, "sms.ack")
        XCTAssertEqual(e.flatMap { Wire.body($0, SmsAck.self) }?.state, .delivered)
        XCTAssertNil(Wire.decode("not json"))
    }

    func testG711MatchesKotlinAnchors() {
        XCTAssertEqual(G711.decode(0xFF), 0)
        XCTAssertEqual(G711.decode(0x00), -32124)
        XCTAssertEqual(G711.decode(0x80), 32124)
        XCTAssertEqual(G711.encode(0), 0xFF)
        XCTAssertEqual(G711.encode(Int16.max), 0x80)
        XCTAssertEqual(G711.encode(Int16.min), 0x00)
        for v in stride(from: -32000, through: 32000, by: 7) {
            let back = Int(G711.decode(G711.encode(Int16(v))))
            XCTAssertLessThanOrEqual(abs(back - v), abs(v) / 16 + 8)
        }
    }

    func testG711MatchesVectorFile() throws {
        let g = try XCTUnwrap(try vectors()["g711"] as? [String: [Int]])
        for (i, v) in g["samples"]!.enumerated() { XCTAssertEqual(Int(G711.encode(Int16(v))), g["ulaw"]![i], "sample \(v)") }
    }

    func testPlayoutBufferStartsAfterThreeFramesAndBoundsLatency() {
        let b = PlayoutBuffer()
        var out = [Float](repeating: 1, count: 960)
        b.push([Float](repeating: 0.5, count: 960)); b.push([Float](repeating: 0.5, count: 960))
        out.withUnsafeMutableBufferPointer { b.pull(into: $0.baseAddress!, count: 960) }
        XCTAssertEqual(out[0], 0, "silence while buffering")
        b.push([Float](repeating: 0.5, count: 960))
        out.withUnsafeMutableBufferPointer { b.pull(into: $0.baseAddress!, count: 960) }
        XCTAssertEqual(out[0], 0.5)
    }

    /// The vendored libopus with the shared voice settings: wideband, ~32 kbit/s, decodes back to the signal.
    func testOpusRoundTripIsWidebandHD() {
        let enc = VoiceCodec("opus"), dec = VoiceCodec("opus")
        XCTAssertEqual(enc.name, "opus")
        var bytes = 0, energy440: Double = 0, energy5k: Double = 0, n = 0
        for f in 0..<100 {
            let frame = (0..<960).map { i -> Int16 in
                let t = Double(f * 960 + i) / 48000
                return Int16(6000 * sin(2 * .pi * 440 * t) + 3000 * sin(2 * .pi * 5000 * t))
            }
            let packet = enc.encode(frame)!
            bytes += packet.count
            if f == 0 { XCTAssertEqual(packet[0] & 0xF8, 0x48, "SILK wideband, 20 ms") }
            let out = dec.decode(packet)
            if f >= 25 {
                for (i, v) in out.enumerated() {
                    let t = Double(f * 960 + i) / 48000
                    energy440 += Double(v) * sin(2 * .pi * 440 * t); energy5k += Double(v) * sin(2 * .pi * 5000 * t); n += 1
                }
            }
        }
        XCTAssertEqual(Double(bytes) * 8 / 2 / 1000, 32, accuracy: 8, "kbit/s")
        XCTAssertGreaterThan(abs(energy5k) / Double(n), 0.01, "5 kHz survives: wideband")
        XCTAssertEqual(dec.decode(nil).count, 960, "lost frame concealed")
    }
}
