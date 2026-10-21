// Les appels audio et vidéo, de pair à pair (WebRTC). Le même protocole que
// le navigateur (`client/src/calls.rs`), par les enveloppes chiffrées :
//
// - l'appelant envoie `offer` (avec son SDP), l'appelé répond `answer`,
//   `reject` ou `busy` ;
// - les candidats ICE passent par `ice` ;
// - `end` raccroche ; sans réponse en 45 secondes, l'appel s'arrête.
//
// Le son et l'image vont directement d'un appareil à l'autre (DTLS-SRTP) :
// le serveur n'en voit rien.

import AudioToolbox
import AVFoundation
import Foundation
import Observation
import TalkaKit
import WebRTC

@MainActor
@Observable
final class CallEngine: Identifiable {
    enum Phase { case ringing, calling, connecting, live, ended }

    /// Les rappels de WebRTC arrivent sur son propre fil : on revient au fil
    /// principal.
    final class Delegate: NSObject, RTCPeerConnectionDelegate {
        weak var call: CallEngine?

        init(_ call: CallEngine) { self.call = call }

        func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
            let cand = JSONValue.object([
                "candidate": .string(candidate.sdp), "sdpMid": .string(candidate.sdpMid ?? "0"),
                "sdpMLineIndex": .number(Double(candidate.sdpMLineIndex)),
            ])
            Task { @MainActor [weak call] in call?.send("ice", cand: cand) }
        }

        func peerConnection(_ pc: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
            guard let video = rtpReceiver.track as? RTCVideoTrack else { return }
            Task { @MainActor [weak call] in call?.remoteVideo = video }
        }

        func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
            Task { @MainActor [weak call] in call?.stateChanged(newState) }
        }

        func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
        func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
        func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
        func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
        func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
        func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
        func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
        func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    }

    static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    }()

    let id: String
    let peer: String
    let video: Bool
    let incoming: Bool
    let at = nowMs()
    @ObservationIgnored let engine: Engine
    @ObservationIgnored weak var model: AppModel?
    @ObservationIgnored var remoteOffer = ""
    @ObservationIgnored var pc: RTCPeerConnection?
    @ObservationIgnored var delegate: Delegate?
    @ObservationIgnored var pending: [RTCIceCandidate] = []
    @ObservationIgnored var audioTrack: RTCAudioTrack?
    @ObservationIgnored var capturer: RTCCameraVideoCapturer?
    @ObservationIgnored var timeout: Task<Void, Never>?
    @ObservationIgnored var ringer: Task<Void, Never>?

    var phase: Phase
    var status: String
    var connectedAt: Date?
    var localVideo: RTCVideoTrack?
    var remoteVideo: RTCVideoTrack?
    var muted = false
    var speaker: Bool
    var frontCamera = true

    init(engine: Engine, model: AppModel, peer: String, video: Bool, incoming: Bool, id: String) {
        self.engine = engine
        self.model = model
        self.peer = peer
        self.video = video
        self.incoming = incoming
        self.id = id
        speaker = video
        phase = incoming ? .ringing : .calling
        status = incoming ? (video ? "Appel vidéo entrant…" : "Appel audio entrant…") : "Appel…"
    }

    var camera: Bool { localVideo != nil }

    // MARK: Les signaux

    func send(_ op: String, sdp: String = "", cand: JSONValue? = nil) {
        engine.sendCallSignal(peer, .call(op: op, callId: id, video: op == "offer" ? video : false, sdp: sdp, cand: cand))
    }

    /// Un signal de l'autre (après l'offre).
    func signal(_ body: Body) {
        guard case let .call(op, _, _, sdp, cand) = body else { return }
        switch op {
        case "answer":
            timeout?.cancel()
            status = "Connexion…"
            phase = .connecting
            pc?.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { [weak self] _ in
                Task { @MainActor in self?.flush() }
            }
        case "ice":
            guard let cand, let text = cand["candidate"]?.string else { return }
            let c = RTCIceCandidate(
                sdp: text, sdpMLineIndex: Int32(cand["sdpMLineIndex"]?.number ?? 0), sdpMid: cand["sdpMid"]?.string)
            if let pc, pc.remoteDescription != nil {
                pc.add(c) { _ in }
            } else {
                pending.append(c)
            }
        case "reject": end("Appel refusé")
        case "busy": end("Occupé")
        case "end":
            let missed = incoming && connectedAt == nil
            end(missed ? "Appel manqué" : "Appel terminé", missed: missed)
        default: break
        }
    }

    func flush() {
        let list = pending
        pending.removeAll()
        for c in list { pc?.add(c) { _ in } }
    }

    // MARK: Appeler, répondre, raccrocher

    func startOutgoing() async {
        guard await media() else { return end("Micro ou caméra refusés.") }
        await createPeer()
        guard let pc else { return end("Appel impossible") }
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": video ? "true" : "false"],
            optionalConstraints: nil)
        guard let offer = await Self.offer(pc, constraints) else { return end("Appel impossible") }
        await Self.setLocal(pc, offer)
        send("offer", sdp: offer.sdp)
        playRing(outgoing: true)
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(45))
            guard !Task.isCancelled, let self, self.connectedAt == nil, self.phase != .ended else { return }
            self.send("end")
            self.end("Pas de réponse")
        }
    }

    /// Un appel entrant : sonner 45 secondes au plus.
    func ring() {
        playRing(outgoing: false)
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(45))
            guard !Task.isCancelled, let self, self.phase == .ringing else { return }
            self.end("Appel manqué", missed: true)
        }
    }

    func accept() async {
        timeout?.cancel()
        stopRing()
        // Nos autres appareils cessent de sonner (v4).
        engine.callTaken(id)
        phase = .connecting
        status = "Connexion…"
        guard await media() else {
            send("reject")
            return end("Micro ou caméra refusés.")
        }
        await createPeer()
        guard let pc else { return end("Appel impossible") }
        await Self.setRemote(pc, RTCSessionDescription(type: .offer, sdp: remoteOffer))
        flush()
        guard let answer = await Self.answer(pc)
        else { return end("Appel impossible") }
        await Self.setLocal(pc, answer)
        send("answer", sdp: answer.sdp)
    }

    func hangup() {
        let reject = incoming && pc == nil
        send(reject ? "reject" : "end")
        // Refusé ici : nos autres appareils cessent de sonner (v4).
        if reject { engine.callTaken(id) }
        end("Appel terminé")
    }

    /// Pris ou refusé sur un autre de nos appareils (v4) : on cesse de
    /// sonner, sans rien dire à l'appelant.
    func takenElsewhere() {
        end("Pris sur un autre appareil", elsewhere: true)
    }

    func end(_ reason: String, missed: Bool = false, elsewhere: Bool = false) {
        guard phase != .ended else { return }
        let wasConnected = pc != nil
        phase = .ended
        status = reason
        timeout?.cancel()
        stopRing()
        if wasConnected { AudioServicesPlaySystemSound(1112) }
        capturer?.stopCapture(completionHandler: nil)
        capturer = nil
        localVideo = nil
        remoteVideo = nil
        pc?.close()
        pc = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let dur = connectedAt.map { Date().timeIntervalSince($0).rounded() } ?? 0
        let lost = !elsewhere && (missed || (incoming && dur == 0))
        engine.logCall(CallEntry(id: id, peer: peer, video: video, incoming: incoming, at: at, dur: dur, missed: lost))
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1400))
            guard let self, let model = self.model, model.call === self else { return }
            model.call = nil
        }
    }

    // MARK: WebRTC

    /// Le micro (et la caméra pour un appel vidéo), avec la permission.
    func media() async -> Bool {
        guard await AVAudioApplication.requestRecordPermission() else { return false }
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        var options: AVAudioSession.CategoryOptions = [.allowBluetooth, .allowBluetoothA2DP]
        if speaker { options.insert(.defaultToSpeaker) }
        try? session.setCategory(.playAndRecord, with: options)
        try? session.setMode(video ? .videoChat : .voiceChat)
        session.unlockForConfiguration()
        let source = Self.factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        audioTrack = Self.factory.audioTrack(with: source, trackId: "talka-audio")
        if video {
            guard await AVCaptureDevice.requestAccess(for: .video) else { return false }
            let source = Self.factory.videoSource()
            capturer = RTCCameraVideoCapturer(delegate: source)
            localVideo = Self.factory.videoTrack(with: source, trackId: "talka-video")
            startCapture()
        }
        return true
    }

    func createPeer() async {
        let config = RTCConfiguration()
        config.iceServers = await Self.iceServers(engine.relay.server)
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        let delegate = Delegate(self)
        guard let pc = Self.factory.peerConnection(
            with: config, constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil),
            delegate: delegate)
        else { return }
        self.delegate = delegate
        if let audioTrack { _ = pc.add(audioTrack, streamIds: ["talka"]) }
        if let localVideo { _ = pc.add(localVideo, streamIds: ["talka"]) }
        self.pc = pc
    }

    /// Les serveurs STUN/TURN du serveur Talka (`/api/ice`).
    static func iceServers(_ server: URL) async -> [RTCIceServer] {
        var list: [[String: Any]] = [["urls": "stun:stun.l.google.com:19302"]]
        if let url = URL(string: "api/ice", relativeTo: server.appendingPathComponent("/")),
           let data = try? await URLSession.shared.data(from: url).0,
           let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
           let servers = json["iceServers"] as? [[String: Any]], !servers.isEmpty {
            list = servers
        }
        return list.compactMap { s in
            let urls = (s["urls"] as? [String]) ?? (s["urls"] as? String).map { [$0] } ?? []
            guard !urls.isEmpty else { return nil }
            return RTCIceServer(urlStrings: urls, username: s["username"] as? String, credential: s["credential"] as? String)
        }
    }

    func stateChanged(_ state: RTCPeerConnectionState) {
        guard phase != .ended else { return }
        switch state {
        case .connected:
            if connectedAt == nil {
                connectedAt = Date()
                stopRing()
            }
            phase = .live
        case .failed: end("La connexion a échoué")
        case .disconnected: status = "Reconnexion…"
        default: break
        }
    }

    // MARK: Micro, caméra, haut-parleur

    func toggleMute() {
        muted.toggle()
        audioTrack?.isEnabled = !muted
    }

    func toggleSpeaker() {
        speaker.toggle()
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        try? session.overrideOutputAudioPort(speaker ? .speaker : .none)
        session.unlockForConfiguration()
    }

    /// Couper ou rallumer la caméra (appel vidéo).
    func toggleCamera() {
        guard let localVideo else { return }
        localVideo.isEnabled.toggle()
    }

    func switchCamera() {
        frontCamera.toggle()
        startCapture()
    }

    func startCapture() {
        guard let capturer else { return }
        let position: AVCaptureDevice.Position = frontCamera ? .front : .back
        let devices = RTCCameraVideoCapturer.captureDevices()
        guard let device = devices.first(where: { $0.position == position }) ?? devices.first else { return }
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let format = formats.min { a, b in
            let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
            let db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            return abs(Int(da.width) - 1280) + abs(Int(da.height) - 720) < abs(Int(db.width) - 1280) + abs(Int(db.height) - 720)
        }
        guard let format else { return }
        let fps = min(30, Int(format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30))
        capturer.startCapture(with: device, format: format, fps: fps)
    }

    // Les fonctions à rappel de WebRTC, en `async`.
    static func offer(_ pc: RTCPeerConnection, _ c: RTCMediaConstraints) async -> RTCSessionDescription? {
        await withCheckedContinuation { k in pc.offer(for: c) { sdp, _ in k.resume(returning: sdp) } }
    }

    static func answer(_ pc: RTCPeerConnection) async -> RTCSessionDescription? {
        await withCheckedContinuation { k in
            pc.answer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { sdp, _ in k.resume(returning: sdp) }
        }
    }

    static func setLocal(_ pc: RTCPeerConnection, _ d: RTCSessionDescription) async {
        await withCheckedContinuation { k in pc.setLocalDescription(d) { _ in k.resume() } }
    }

    static func setRemote(_ pc: RTCPeerConnection, _ d: RTCSessionDescription) async {
        await withCheckedContinuation { k in pc.setRemoteDescription(d) { _ in k.resume() } }
    }

    // MARK: La sonnerie

    func playRing(outgoing: Bool) {
        ringer?.cancel()
        ringer = Task {
            while !Task.isCancelled {
                AudioServicesPlaySystemSound(outgoing ? 1070 : 1151)
                if !outgoing { AudioServicesPlaySystemSound(kSystemSoundID_Vibrate) }
                try? await Task.sleep(for: .seconds(outgoing ? 3 : 2))
            }
        }
    }

    func stopRing() {
        ringer?.cancel()
        ringer = nil
    }
}
