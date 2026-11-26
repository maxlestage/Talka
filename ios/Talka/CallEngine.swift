// Les appels audio et vidéo, de pair à pair (WebRTC), à deux ou à plusieurs
// (huit au plus, chacun relié à chacun). Le même protocole que le navigateur
// (`client/src/calls.rs`), par les enveloppes chiffrées ; `Mesh` (TalkaKit)
// décide qui appelle qui :
//
// - l'appelant envoie `offer` (avec son SDP, et la liste des participants à
//   plusieurs), l'appelé répond `answer`, `reject` ou `busy` ;
// - à plusieurs, qui répond dit `join` aux autres, et deux personnes déjà
//   dans l'appel se relient par `link` ;
// - les candidats ICE passent par `ice` ;
// - `end` raccroche ; sans réponse en 45 secondes, l'invitation s'arrête.
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
    /// principal. Un délégué par participant.
    final class Delegate: NSObject, RTCPeerConnectionDelegate {
        weak var call: CallEngine?
        let peer: String

        init(_ call: CallEngine, peer: String) {
            self.call = call
            self.peer = peer
        }

        func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
            let cand = JSONValue.object([
                "candidate": .string(candidate.sdp), "sdpMid": .string(candidate.sdpMid ?? "0"),
                "sdpMLineIndex": .number(Double(candidate.sdpMLineIndex)),
            ])
            let peer = peer
            Task { @MainActor [weak call] in call?.send(peer, "ice", cand: cand) }
        }

        func peerConnection(_ pc: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
            guard let video = rtpReceiver.track as? RTCVideoTrack else { return }
            let peer = peer
            Task { @MainActor [weak call] in call?.remoteVideos[peer] = video }
        }

        func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
            let peer = peer
            Task { @MainActor [weak call] in call?.stateChanged(peer, newState) }
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

    /// La liaison avec un participant.
    final class Peer {
        let pc: RTCPeerConnection
        let delegate: Delegate
        /// Ses candidats ICE arrivés avant sa description.
        var pending: [RTCIceCandidate] = []

        init(pc: RTCPeerConnection, delegate: Delegate, pending: [RTCIceCandidate]) {
            self.pc = pc
            self.delegate = delegate
            self.pending = pending
        }
    }

    static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    }()

    let id: String
    /// Qui nous a appelés, ou le premier appelé.
    let first: String
    /// Le groupe d'où l'appel est lancé, s'il y en a un.
    let chat: String
    let video: Bool
    let incoming: Bool
    let at = nowMs()
    @ObservationIgnored let engine: Engine
    @ObservationIgnored weak var model: AppModel?
    @ObservationIgnored var mesh: Mesh
    @ObservationIgnored var peers: [String: Peer] = [:]
    /// Des candidats arrivés avant la liaison (un appel qui sonne).
    @ObservationIgnored var early: [String: [RTCIceCandidate]] = [:]
    /// Tous ceux qui ont été de l'appel (pour le journal).
    @ObservationIgnored var ever: Set<String> = []
    @ObservationIgnored var iceServers: [RTCIceServer]?
    @ObservationIgnored var audioTrack: RTCAudioTrack?
    @ObservationIgnored var capturer: RTCCameraVideoCapturer?
    @ObservationIgnored var timeout: Task<Void, Never>?
    @ObservationIgnored var ringer: Task<Void, Never>?

    var phase: Phase
    var status: String
    var connectedAt: Date?
    var localVideo: RTCVideoTrack?
    /// L'image de chacun, à mesure qu'elle arrive.
    var remoteVideos: [String: RTCVideoTrack] = [:]
    /// Les autres participants (invités ou reliés), pour l'écran.
    var participants: [String] = []
    /// Ceux avec qui la liaison est faite.
    var connected: Set<String> = []
    var muted = false
    var speaker: Bool
    var frontCamera = true

    init(engine: Engine, model: AppModel, first: String, chat: String, mesh: Mesh, video: Bool, incoming: Bool, id: String) {
        self.engine = engine
        self.model = model
        self.first = first
        self.chat = chat
        self.mesh = mesh
        self.video = video
        self.incoming = incoming
        self.id = id
        speaker = video
        phase = incoming ? .ringing : .calling
        let group = mesh.isGroup
        status = incoming
            ? (group ? (video ? "Appel vidéo de groupe entrant…" : "Appel de groupe entrant…")
                : (video ? "Appel vidéo entrant…" : "Appel audio entrant…"))
            : "Appel…"
        participants = mesh.others
    }

    var camera: Bool { localVideo != nil }
    var isGroup: Bool { participants.count > 1 }

    /// Le titre de l'appel : l'autre, le groupe, ou les participants.
    var title: String {
        if participants.count <= 1 { return engine.displayName(participants.first ?? first) }
        if let c = engine.chat(chat) { return engine.title(c) }
        return Self.names(participants.map { engine.displayName($0) })
    }

    /// Qui en est, sous le nom du groupe (rien s'il redirait le titre).
    var people: String {
        guard isGroup else { return "" }
        let list = Self.names(participants.map { engine.displayName($0) })
        return list == title ? "" : list
    }

    /// « Léa, Max et 3 autres ».
    static func names(_ list: [String]) -> String {
        switch list.count {
        case 0: return ""
        case 1: return list[0]
        case 2, 3: return list.dropLast().joined(separator: ", ") + " et " + list.last!
        default: return "\(list[0]), \(list[1]) et \(list.count - 2) autres"
        }
    }

    // MARK: Les signaux

    /// Un signal à `peer`. Les invitations, liaisons et `join` d'un appel à
    /// plusieurs portent la liste de ses participants ; un appel à deux part
    /// exactement comme avant.
    func send(_ peer: String, _ op: String, sdp: String = "", cand: JSONValue? = nil) {
        let rich = mesh.isGroup && ["offer", "link", "join"].contains(op)
        engine.sendCallSignal(peer, .call(op: op, callId: id, video: ["offer", "link"].contains(op) ? video : false,
                                          sdp: sdp, cand: cand, members: rich ? mesh.members : [], chat: rich ? chat : ""))
    }

    /// Un signal de cet appel, de l'un des participants.
    func signal(from: String, _ body: Body, live: Bool) async {
        guard case let .call(op, _, _, sdp, cand, members, _) = body, phase != .ended else { return }
        switch op {
        case "offer", "link":
            guard live else { return }
            let todo = mesh.onOffer(from: from, sdp: sdp, members: members, ring: op == "offer")
            await run(todo, bye: "end", reason: "Appel terminé")
        case "join":
            guard live else { return }
            await run(mesh.onJoin(from: from, members: members), bye: "end", reason: "Appel terminé")
        case "answer":
            guard mesh.onAnswer(from: from), let p = peers[from] else { return }
            if connectedAt == nil {
                status = "Connexion…"
                phase = .connecting
            }
            await Self.setRemote(p.pc, RTCSessionDescription(type: .answer, sdp: sdp))
            flush(p)
            refresh()
        case "ice":
            guard let cand, let text = cand["candidate"]?.string else { return }
            let c = RTCIceCandidate(
                sdp: text, sdpMLineIndex: Int32(cand["sdpMLineIndex"]?.number ?? 0), sdpMid: cand["sdpMid"]?.string)
            if let p = peers[from] {
                if p.pc.remoteDescription != nil { p.pc.add(c) { _ in } } else { p.pending.append(c) }
            } else {
                early[from, default: []].append(c)
            }
        case "reject", "busy", "end":
            let group = mesh.isGroup
            let missed = incoming && connectedAt == nil
            let reason = group ? "Appel terminé"
                : op == "reject" ? "Appel refusé" : op == "busy" ? "Occupé" : missed ? "Appel manqué" : "Appel terminé"
            let todo = mesh.onGone(from)
            let last = todo.contains(.end)
            await run(todo, bye: "end", reason: reason)
            if group, !last {
                let name = engine.displayName(from)
                model?.show(op == "end" ? "\(name) a quitté l'appel." : op == "busy" ? "\(name) est déjà en ligne." : "\(name) ne vient pas.")
            }
        default: break
        }
    }

    func flush(_ p: Peer) {
        let list = p.pending
        p.pending.removeAll()
        for c in list { p.pc.add(c) { _ in } }
    }

    /// Fait ce que `Mesh` a décidé. `bye` : ce que dit un au revoir (`end`,
    /// ou `reject` pour un appel qui sonnait) ; `reason` : ce qu'affiche la fin.
    func run(_ todo: [Mesh.Do], bye: String, reason: String) async {
        for d in todo {
            switch d {
            case let .offer(peer, ring): await offer(peer, ring: ring)
            case let .answer(peer, sdp): await answer(peer, sdp: sdp)
            case let .join(peer): send(peer, "join")
            case let .hangup(peer):
                send(peer, bye)
                close(peer)
            case let .close(peer): close(peer)
            case .end: end(reason)
            }
        }
        refresh()
    }

    /// Une liaison qui n'a pas pu se faire, ou qui a échoué.
    func lost(_ peer: String, _ reason: String) {
        for d in mesh.onGone(peer) {
            switch d {
            case let .close(p): close(p)
            case .end: end(reason)
            default: break
            }
        }
        refresh()
    }

    func refresh() {
        participants = mesh.others
        connected = connected.filter { peers[$0] != nil }
    }

    // MARK: Appeler, répondre, raccrocher

    func startOutgoing(_ todo: [Mesh.Do]) async {
        // Personne n'a encore été appelé.
        guard await media() else { return end("Micro ou caméra refusés.") }
        await run(todo, bye: "end", reason: "Appel impossible")
        guard phase != .ended else { return }
        playRing(outgoing: true)
        waitForAnswers()
    }

    /// Sans réponse en 45 secondes, les invitations en attente s'arrêtent (et
    /// l'appel avec elles, s'il n'y a personne d'autre).
    func waitForAnswers() {
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(45))
            guard !Task.isCancelled, let self, self.phase != .ended else { return }
            await self.run(self.mesh.noAnswer(), bye: "end", reason: "Pas de réponse")
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
            return await run(mesh.hangup(), bye: "reject", reason: "Micro ou caméra refusés.")
        }
        await run(mesh.accept(), bye: "end", reason: "Appel terminé")
    }

    func hangup() {
        let ringing = !mesh.joined
        // Refusé ici : nos autres appareils cessent de sonner (v4).
        if ringing { engine.callTaken(id) }
        let todo = mesh.hangup()
        Task { await run(todo, bye: ringing ? "reject" : "end", reason: "Appel terminé") }
    }

    /// Faire sonner d'autres contacts pendant l'appel.
    func add(_ codes: [String]) async {
        var any = false
        for code in codes where phase != .ended {
            switch mesh.add(code) {
            case let .success(todo):
                any = true
                await run(todo, bye: "end", reason: "Appel terminé")
            case .failure(.full):
                model?.show("Un appel réunit huit personnes au plus.")
                return
            case .failure: continue
            }
        }
        if any { waitForAnswers() }
    }

    /// Pris ou refusé sur un autre de nos appareils (v4) : on cesse de
    /// sonner, sans rien dire à l'appelant.
    func takenElsewhere() {
        end("Pris sur un autre appareil", elsewhere: true)
    }

    func end(_ reason: String, missed: Bool = false, elsewhere: Bool = false) {
        guard phase != .ended else { return }
        let wasConnected = connectedAt != nil
        phase = .ended
        status = reason
        timeout?.cancel()
        stopRing()
        if wasConnected { AudioServicesPlaySystemSound(1112) }
        capturer?.stopCapture(completionHandler: nil)
        capturer = nil
        localVideo = nil
        remoteVideos = [:]
        for p in peers.values { p.pc.close() }
        peers = [:]
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let dur = connectedAt.map { Date().timeIntervalSince($0).rounded() } ?? 0
        let lostCall = !elsewhere && (missed || (incoming && dur == 0))
        var everyone = ever.union(mesh.others)
        everyone.insert(first)
        engine.logCall(CallEntry(id: id, peer: first, video: video, incoming: incoming, at: at, dur: dur, missed: lostCall,
                                 members: everyone.count > 1 ? everyone.sorted() : [], chat: chat))
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

    /// La connexion WebRTC avec `peer` (le même micro et la même caméra pour
    /// chacun).
    func createPeer(_ peer: String) async -> Peer? {
        let servers: [RTCIceServer]
        if let iceServers {
            servers = iceServers
        } else {
            servers = await Self.iceServers(engine.relay.server)
            iceServers = servers
        }
        guard phase != .ended else { return nil }
        let config = RTCConfiguration()
        config.iceServers = servers
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        let delegate = Delegate(self, peer: peer)
        guard let pc = Self.factory.peerConnection(
            with: config, constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil),
            delegate: delegate)
        else { return nil }
        if let audioTrack { _ = pc.add(audioTrack, streamIds: ["talka"]) }
        if let localVideo { _ = pc.add(localVideo, streamIds: ["talka"]) }
        peers[peer]?.pc.close()
        let p = Peer(pc: pc, delegate: delegate, pending: early.removeValue(forKey: peer) ?? [])
        peers[peer] = p
        ever.insert(peer)
        return p
    }

    func offer(_ peer: String, ring: Bool) async {
        guard let p = await createPeer(peer) else { return lost(peer, "Appel impossible") }
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": video ? "true" : "false"],
            optionalConstraints: nil)
        guard let offer = await Self.offer(p.pc, constraints) else { return lost(peer, "Appel impossible") }
        await Self.setLocal(p.pc, offer)
        send(peer, ring ? "offer" : "link", sdp: offer.sdp)
    }

    func answer(_ peer: String, sdp: String) async {
        guard let p = await createPeer(peer) else { return lost(peer, "Appel impossible") }
        await Self.setRemote(p.pc, RTCSessionDescription(type: .offer, sdp: sdp))
        flush(p)
        guard let answer = await Self.answer(p.pc) else { return lost(peer, "Appel impossible") }
        await Self.setLocal(p.pc, answer)
        send(peer, "answer", sdp: answer.sdp)
    }

    func close(_ peer: String) {
        peers.removeValue(forKey: peer)?.pc.close()
        early.removeValue(forKey: peer)
        remoteVideos.removeValue(forKey: peer)
        connected.remove(peer)
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

    func stateChanged(_ peer: String, _ state: RTCPeerConnectionState) {
        guard phase != .ended, peers[peer] != nil else { return }
        switch state {
        case .connected:
            connected.insert(peer)
            if connectedAt == nil {
                connectedAt = Date()
                stopRing()
            }
            phase = .live
        case .failed: lost(peer, "La connexion a échoué")
        case .disconnected:
            connected.remove(peer)
            if !mesh.isGroup { status = "Reconnexion…" }
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
