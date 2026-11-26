import AVFoundation
import CallKit
import Foundation

/// Bridges gateway calls into CallKit, so a call ringing on the Android gateway rings on the iPhone with the
/// system call screen (lock screen included), and is answered, muted, ended and talked through here.
///
/// Used only when call audio is really available (gateway status `caps.callAudio`, and the user turned on
/// "Take calls on this iPhone"). Otherwise calls are shown in the app's own screen with "voice is on the gateway",
/// because presenting a call without audio as a system call would mislead.
///
/// Privacy: `includesCallsInRecents = false`, so bridged calls are not written to the iPhone's Phone app history
/// (which iCloud can sync). The gateway keeps the call log.
@MainActor
final class CallCoordinator: NSObject {
    private let provider: CXProvider
    private let controller = CXCallController()
    private let audio = CallAudioEngine()
    weak var model: AppModel?

    private var uuids: [String: UUID] = [:]          // gateway callId → CallKit UUID
    private var pendingOutgoing: (UUID, String, Int?)? // started here, waiting for the gateway's call id
    private var audioRequested: Set<String> = []
    private var seq: UInt16 = 0
    private(set) var speakerOn = false
    private(set) var muted = false

    override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallGroups = 2
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.phoneNumber, .generic]
        config.includesCallsInRecents = false
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: .main)
        audio.onFrame = { [weak self] codecId, packet in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.sendUplink(codecId, packet) } }
        }
    }

    func isBridged(_ callId: String) -> Bool { uuids[callId] != nil }

    // MARK: Gateway → CallKit

    /// Called on every `call.state` from the gateway.
    func update(_ calls: [LiveCall], bridged: Bool) {
        let ids = Set(calls.map(\.callId))
        // Calls that disappeared ended on the gateway.
        for (callId, uuid) in uuids where !ids.contains(callId) {
            provider.reportCall(with: uuid, endedAt: nil, reason: .remoteEnded)
            uuids[callId] = nil
            audioRequested.remove(callId)
        }
        guard bridged else { return }
        for call in calls {
            if uuids[call.callId] != nil {
                if call.state == .active, !call.incoming, let uuid = uuids[call.callId] {
                    provider.reportOutgoingCall(with: uuid, connectedAt: nil)
                    requestAudio(call.callId)
                }
                continue
            }
            if call.incoming && call.state == .ringing {
                let uuid = UUID()
                uuids[call.callId] = uuid
                let update = CXCallUpdate()
                update.remoteHandle = CXHandle(type: .phoneNumber, value: call.number.isEmpty ? "unknown" : call.number)
                update.localizedCallerName = call.name ?? model?.name(for: call.number)
                update.hasVideo = false
                update.supportsHolding = model?.caps?.hold == true
                update.supportsGrouping = false
                update.supportsUngrouping = false
                update.supportsDTMF = true
                provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
                    if error != nil { MainActor.assumeIsolated { self?.uuids[call.callId] = nil } }
                }
            } else if !call.incoming, let p = pendingOutgoing {
                uuids[call.callId] = p.0
                pendingOutgoing = nil
                provider.reportOutgoingCall(with: p.0, startedConnectingAt: nil)
                if call.state == .active { provider.reportOutgoingCall(with: p.0, connectedAt: nil); requestAudio(call.callId) }
            }
        }
    }

    func audioState(_ s: AudioState) {
        switch s.state {
        case "active": audio.setCodec(s.codec ?? "pcmu")
        case "failed":
            audioRequested.remove(s.callId)
            model?.lastError = "audio:\(s.reason ?? "failed")"
        case "stopped": audioRequested.remove(s.callId)
        default: break
        }
    }

    func receiveAudio(_ frame: Data) {
        guard frame.count >= 4 else { return }
        audio.receive(codecId: frame[frame.startIndex], payload: Data(frame.dropFirst(3)))
    }

    /// The link went away: end bridged calls cleanly instead of leaving a silent call on screen.
    func linkLost() {
        for (_, uuid) in uuids { provider.reportCall(with: uuid, endedAt: nil, reason: .failed) }
        uuids.removeAll(); audioRequested.removeAll(); pendingOutgoing = nil
        audio.stop()
    }

    // MARK: User actions from the app's own screens

    func startOutgoing(number: String, subId: Int?) {
        let uuid = UUID()
        pendingOutgoing = (uuid, number, subId)
        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: .phoneNumber, value: number))
        controller.request(CXTransaction(action: action)) { [weak self] error in
            if error != nil { MainActor.assumeIsolated { self?.pendingOutgoing = nil; self?.model?.lastError = "call:failed" } }
        }
    }

    func answer(_ callId: String) { if let u = uuids[callId] { controller.request(CXTransaction(action: CXAnswerCallAction(call: u))) { _ in } } }
    func end(_ callId: String) { if let u = uuids[callId] { controller.request(CXTransaction(action: CXEndCallAction(call: u))) { _ in } } }
    func setHeld(_ callId: String, _ on: Bool) { if let u = uuids[callId] { controller.request(CXTransaction(action: CXSetHeldCallAction(call: u, onHold: on))) { _ in } } }
    func setMuted(_ callId: String, _ on: Bool) { if let u = uuids[callId] { controller.request(CXTransaction(action: CXSetMutedCallAction(call: u, muted: on))) { _ in } } }
    func dtmf(_ callId: String, _ digits: String) {
        if let u = uuids[callId] { controller.request(CXTransaction(action: CXPlayDTMFCallAction(call: u, digits: digits, type: .singleTone))) { _ in } }
    }

    func setSpeaker(_ on: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
        speakerOn = on
    }

    // MARK: Internals

    private func callId(for uuid: UUID) -> String? { uuids.first { $0.value == uuid }?.key }

    private func requestAudio(_ callId: String) {
        guard !audioRequested.contains(callId) else { return }
        audioRequested.insert(callId)
        seq = 0
        model?.sendAudioStart(callId)
    }

    private func sendUplink(_ codecId: UInt8, _ packet: Data) {
        guard !audioRequested.isEmpty else { return }
        var frame = Data([codecId, UInt8(seq >> 8), UInt8(seq & 0xFF)])
        frame.append(packet)
        seq &+= 1
        model?.sendAudioFrame(frame)
    }
}

extension CallCoordinator: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        MainActor.assumeIsolated { audio.stop(); uuids.removeAll(); audioRequested.removeAll(); pendingOutgoing = nil }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        MainActor.assumeIsolated {
            guard let id = callId(for: action.callUUID), let model else { action.fail(); return }
            guard model.callAction(id, "answer") == .ok else { action.fail(); return }
            requestAudio(id)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        MainActor.assumeIsolated {
            if let id = callId(for: action.callUUID), let model {
                let ringing = model.liveCalls.first { $0.callId == id }?.state == .ringing
                model.callAction(id, ringing ? "reject" : "end")
                if audioRequested.contains(id) { model.sendAudioStop(id) }
                uuids[id] = nil; audioRequested.remove(id)
            } else if pendingOutgoing?.0 == action.callUUID { pendingOutgoing = nil }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        MainActor.assumeIsolated {
            guard let p = pendingOutgoing, p.0 == action.callUUID, let model, model.placeCall(p.1, subId: p.2) == .ok else {
                pendingOutgoing = nil; action.fail(); return
            }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        MainActor.assumeIsolated { muted = action.isMuted; audio.setMuted(action.isMuted); action.fulfill() }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        MainActor.assumeIsolated {
            // Real hold needs the gateway's "Full call control" (Pol as its phone app).
            guard let id = callId(for: action.callUUID), let model,
                  model.callAction(id, action.isOnHold ? "hold" : "unhold") == .ok else { action.fail(); return }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        MainActor.assumeIsolated {
            // Prefer real tones from the gateway's phone app; otherwise generate them into the call audio.
            if let id = callId(for: action.callUUID), model?.sendDtmf(id, action.digits) == .ok { action.fulfill(); return }
            for (i, d) in action.digits.enumerated() {
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(250 * i)) { [weak self] in
                    MainActor.assumeIsolated { self?.audio.playDTMF(d) }
                }
            }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated {
            do { try audio.start(); audio.setMuted(muted) } catch { model?.lastError = "audio:helper_failed" }
        }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated { audio.stop(); speakerOn = false; muted = false }
    }
}
