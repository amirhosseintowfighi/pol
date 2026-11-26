import AVFoundation
import COpus
import Foundation
import os

/// G.711 µ-law, identical to core/…/audio/Audio.kt (checked by the shared tests).
enum G711 {
    static func encode(_ sample: Int16) -> UInt8 {
        var s = Int(sample)
        let sign = s < 0 ? 0x80 : 0
        if s < 0 { s = -s }
        s = min(s, 32635) + 0x84
        var exponent = 7
        var mask = 0x4000
        while exponent > 0 && (s & mask) == 0 { exponent -= 1; mask >>= 1 }
        let mantissa = (s >> (exponent + 3)) & 0x0F
        return ~UInt8(sign | (exponent << 4) | mantissa)
    }

    static func decode(_ code: UInt8) -> Int16 {
        let u = Int(~code)
        let exponent = (u >> 4) & 0x07
        let magnitude = ((((u & 0x0F) << 3) + 0x84) << exponent) - 0x84
        return Int16((u & 0x80) != 0 ? -magnitude : magnitude)
    }
}

enum AudioSpec {
    static let codecOpus: UInt8 = 1
    static let codecPcmu: UInt8 = 2
    /// The engine runs at 48 kHz (Opus's native rate); G.711 frames are resampled to and from 8 kHz.
    static let engineRate: Double = 48000
    static let frameSamples = 960 // 20 ms at 48 kHz
    /// What this iPhone offers the gateway, best first.
    static let codecs = ["opus", "pcmu"]
}

/// One call's voice codec: Opus wideband (HD) via the vendored libopus, or G.711 µ-law as the fallback.
final class VoiceCodec: @unchecked Sendable {
    let name: String
    let id: UInt8
    private var enc: OpaquePointer?
    private var dec: OpaquePointer?
    private var pcm = [Int16](repeating: 0, count: 960)
    private var packet = [UInt8](repeating: 0, count: 400)
    private var lastPcmu = [Float](repeating: 0, count: 960)

    init(_ name: String) {
        var err: Int32 = 0
        if name == "opus", let e = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &err), err == OPUS_OK,
           let d = opus_decoder_create(48000, 1, &err), err == OPUS_OK {
            pol_opus_configure_voice(e, 32000)
            enc = e; dec = d
            self.name = "opus"; id = AudioSpec.codecOpus
        } else {
            self.name = "pcmu"; id = AudioSpec.codecPcmu
        }
    }

    deinit {
        if let enc { opus_encoder_destroy(enc) }
        if let dec { opus_decoder_destroy(dec) }
    }

    /// 960 samples at 48 kHz in, one packet out.
    func encode(_ frame: [Int16]) -> Data? {
        if let enc {
            let n = frame.withUnsafeBufferPointer { opus_encode(enc, $0.baseAddress, 960, &packet, Int32(packet.count)) }
            return n > 0 ? Data(packet[0..<Int(n)]) : nil
        }
        // G.711: average 6:1 down to 8 kHz (the capture is already band-limited by voice processing).
        var out = Data(count: 160)
        for i in 0..<160 {
            var sum = 0
            for k in 0..<6 { sum += Int(frame[i * 6 + k]) }
            out[i] = G711.encode(Int16(clamping: sum / 6))
        }
        return out
    }

    /// One packet in (nil = lost: conceal), 960 Float samples at 48 kHz out.
    func decode(_ payload: Data?) -> [Float] {
        if let dec {
            let n: Int32 = pcm.withUnsafeMutableBufferPointer { out in
                guard let payload else { return opus_decode(dec, nil, 0, out.baseAddress, 960, 0) }
                return payload.withUnsafeBytes { opus_decode(dec, $0.bindMemory(to: UInt8.self).baseAddress, Int32(payload.count), out.baseAddress, 960, 0) }
            }
            return n > 0 ? pcm[0..<Int(n)].map { Float($0) / 32768 } : [Float](repeating: 0, count: 960)
        }
        guard let payload, payload.count == 160 else { lastPcmu = lastPcmu.map { $0 * 0.5 }; return lastPcmu }
        // G.711 8 kHz → 48 kHz, linear interpolation.
        let s = payload.map { Float(G711.decode($0)) / 32768 }
        var out = [Float](repeating: 0, count: 960)
        for i in 0..<960 {
            let pos = Float(i) / 6, a = Int(pos), b = min(a + 1, 159), f = pos - Float(a)
            out[i] = s[a] * (1 - f) + s[b] * f
        }
        lastPcmu = out
        return out
    }
}

/// Lock-protected sample FIFO between the link (main thread) and the real-time render callback. It starts playing
/// after 60 ms are buffered and never holds more than 200 ms, dropping the oldest audio so latency stays bounded.
final class PlayoutBuffer: @unchecked Sendable {
    private var samples: [Float] = []
    private var playing = false
    private var lock = os_unfair_lock()
    private let start = AudioSpec.frameSamples * 3
    private let cap = AudioSpec.frameSamples * 10

    func push(_ decoded: [Float]) {
        os_unfair_lock_lock(&lock); defer { os_unfair_lock_unlock(&lock) }
        samples.append(contentsOf: decoded)
        if samples.count > cap { samples.removeFirst(samples.count - cap) }
        if !playing && samples.count >= start { playing = true }
    }

    /// Fills `out` with `count` samples; silence while buffering or empty.
    func pull(into out: UnsafeMutablePointer<Float>, count: Int) {
        os_unfair_lock_lock(&lock); defer { os_unfair_lock_unlock(&lock) }
        guard playing else { out.update(repeating: 0, count: count); return }
        let n = min(count, samples.count)
        for i in 0..<n { out[i] = samples[i] }
        if n < count { out.advanced(by: n).update(repeating: 0, count: count - n); playing = false }
        samples.removeFirst(n)
    }

    func clear() { os_unfair_lock_lock(&lock); samples.removeAll(); playing = false; os_unfair_lock_unlock(&lock) }
}

/// Captures the microphone and plays the far party for a bridged call. Uses Apple's voice processing (echo
/// cancellation, noise suppression), so speakerphone works. Nothing is recorded: frames go straight to the link.
/// Start it only from CallKit's `didActivate audioSession`, which is when iOS grants the audio session.
final class CallAudioEngine: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode?
    private var converter: AVAudioConverter?
    private let capFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: AudioSpec.engineRate, channels: 1, interleaved: true)!
    private let playFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: AudioSpec.engineRate, channels: 1, interleaved: false)!
    let playout = PlayoutBuffer()
    private var pending: [Int16] = []
    private var lock = os_unfair_lock()
    private var muted = false
    private var tone: (f1: Double, f2: Double, left: Int, n: Int)?
    private var codec: VoiceCodec?
    private(set) var running = false

    /// Called on an audio thread with one encoded packet every 20 ms, and the codec id for the frame header.
    var onFrame: (@Sendable (UInt8, Data) -> Void)?

    /// Set when the gateway confirms the call's codec; until then nothing is sent or played.
    func setCodec(_ name: String) { let c = VoiceCodec(name); os_unfair_lock_lock(&lock); codec = c; os_unfair_lock_unlock(&lock) }

    /// Decodes and queues a received frame (main thread).
    func receive(codecId: UInt8, payload: Data) {
        os_unfair_lock_lock(&lock); let c = codec; os_unfair_lock_unlock(&lock)
        guard let c, c.id == codecId else { return }
        playout.push(c.decode(payload))
    }

    func setMuted(_ m: Bool) { os_unfair_lock_lock(&lock); muted = m; os_unfair_lock_unlock(&lock) }

    /// Mixes a DTMF tone (ITU-T Q.23 frequencies) into the uplink for 160 ms.
    func playDTMF(_ digit: Character) {
        let rows: [Character: Double] = ["1": 697, "2": 697, "3": 697, "4": 770, "5": 770, "6": 770, "7": 852, "8": 852, "9": 852, "*": 941, "0": 941, "#": 941]
        let cols: [Character: Double] = ["1": 1209, "4": 1209, "7": 1209, "*": 1209, "2": 1336, "5": 1336, "8": 1336, "0": 1336, "3": 1477, "6": 1477, "9": 1477, "#": 1477]
        guard let r = rows[digit], let c = cols[digit] else { return }
        os_unfair_lock_lock(&lock); tone = (r, c, Int(AudioSpec.engineRate * 0.16), 0); os_unfair_lock_unlock(&lock)
    }

    func start() throws {
        guard !running else { return }
        let input = engine.inputNode
        try input.setVoiceProcessingEnabled(true)
        let inFormat = input.outputFormat(forBus: 0)
        converter = AVAudioConverter(from: inFormat, to: capFormat)
        input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buffer, _ in self?.capture(buffer) }

        let playout = self.playout
        let node = AVAudioSourceNode(format: playFormat) { _, _, frameCount, abl -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(abl)
            if let p = buffers.first?.mData?.assumingMemoryBound(to: Float.self) { playout.pull(into: p, count: Int(frameCount)) }
            return noErr
        }
        source = node
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: playFormat)
        engine.prepare()
        try engine.start()
        running = true
    }

    func stop() {
        guard running else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        if let s = source { engine.detach(s) }
        source = nil
        playout.clear()
        os_unfair_lock_lock(&lock); pending.removeAll(); tone = nil; codec = nil; os_unfair_lock_unlock(&lock)
        running = false
    }

    private func capture(_ buffer: AVAudioPCMBuffer) {
        guard let converter else { return }
        let ratio = AudioSpec.engineRate / buffer.format.sampleRate
        guard let out = AVAudioPCMBuffer(pcmFormat: capFormat, frameCapacity: AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64) else { return }
        var supplied = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if supplied { status.pointee = .noDataNow; return nil }
            supplied = true; status.pointee = .haveData; return buffer
        }
        guard error == nil, let ch = out.int16ChannelData else { return }
        let samples = UnsafeBufferPointer(start: ch[0], count: Int(out.frameLength))

        var frames: [(UInt8, Data)] = []
        os_unfair_lock_lock(&lock)
        pending.append(contentsOf: samples)
        if let codec {
            while pending.count >= AudioSpec.frameSamples {
                var frame = Array(pending.prefix(AudioSpec.frameSamples))
                pending.removeFirst(AudioSpec.frameSamples)
                if muted { frame = Array(repeating: 0, count: frame.count) }
                if var t = tone {
                    for i in 0..<frame.count where t.left > 0 {
                        let x = Double(t.n) / AudioSpec.engineRate
                        let v = 0.25 * (sin(2 * .pi * t.f1 * x) + sin(2 * .pi * t.f2 * x)) * 32767
                        frame[i] = Int16(clamping: Int(v) + (muted ? 0 : Int(frame[i]) / 4))
                        t.n += 1; t.left -= 1
                    }
                    tone = t.left > 0 ? t : nil
                }
                if let packet = codec.encode(frame) { frames.append((codec.id, packet)) }
            }
        } else if pending.count > AudioSpec.frameSamples * 5 {
            pending.removeFirst(pending.count - AudioSpec.frameSamples) // codec not agreed yet: don't build up delay
        }
        os_unfair_lock_unlock(&lock)
        for (id, f) in frames { onFrame?(id, f) }
    }
}
