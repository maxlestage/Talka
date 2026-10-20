// Les messages vocaux : les enregistrer (AAC dans du MP4, lisible partout,
// navigateurs compris), et les lire — y compris ceux enregistrés par Chrome
// ou Firefox (WebM / Ogg Opus), qu'AVFoundation ne lit pas : ceux-là passent
// par le lecteur de WebKit, qui les lit depuis iOS 17.

import AVFoundation
import Foundation
import Observation
import WebKit

/// L'enregistreur (2 minutes au plus).
@MainActor
@Observable
final class VoiceRecorder {
    static let maxDuration: TimeInterval = 120

    var recording = false
    var started = Date()
    @ObservationIgnored var recorder: AVAudioRecorder?
    @ObservationIgnored var url: URL?

    /// Commence (avec la permission du micro). Faux sans micro.
    func start() async -> Bool {
        guard await AVAudioApplication.requestRecordPermission() else { return false }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try? session.setActive(true)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("talka-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 24_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32_000,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        guard let recorder = try? AVAudioRecorder(url: url, settings: settings),
              recorder.record(forDuration: Self.maxDuration)
        else { return false }
        self.recorder = recorder
        self.url = url
        started = Date()
        recording = true
        return true
    }

    /// Arrête, et rend l'enregistrement (rien s'il dure moins d'une
    /// demi-seconde). Le fichier est effacé aussitôt.
    func stop() -> Data? {
        recorder?.stop()
        recording = false
        defer {
            if let url { try? FileManager.default.removeItem(at: url) }
            url = nil
            recorder = nil
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        guard Date().timeIntervalSince(started) > 0.5, let url else { return nil }
        return try? Data(contentsOf: url)
    }

    func cancel() {
        recorder?.stop()
        recorder?.deleteRecording()
        recording = false
        recorder = nil
        url = nil
    }
}

/// Le lecteur d'un message vocal.
@MainActor
@Observable
final class VoicePlayer: NSObject, AVAudioPlayerDelegate {
    var playing = false
    /// La vitesse de lecture : 1×, 1,5× ou 2×, comme dans le navigateur.
    var speed: Float = 1
    @ObservationIgnored var player: AVAudioPlayer?
    @ObservationIgnored var web: WKWebView?

    /// 1× → 1,5× → 2× → 1×, appliquée aussitôt, même pendant la lecture.
    func nextSpeed() {
        speed = speed < 1.4 ? 1.5 : speed < 1.9 ? 2 : 1
        player?.rate = speed
        web?.evaluateJavaScript("document.querySelector('audio').playbackRate = \(speed)")
    }

    var speedLabel: String { speed == 1.5 ? "1,5×" : speed == 2 ? "2×" : "1×" }

    func toggle(_ data: Data?, mime: String) {
        if playing { return stop() }
        guard let data else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        if let p = try? AVAudioPlayer(data: data), p.duration > 0 {
            p.delegate = self
            p.enableRate = true
            p.rate = speed
            player = p
            playing = p.play()
            return
        }
        playInWebKit(data, mime: mime)
    }

    func stop() {
        player?.stop()
        web?.evaluateJavaScript("document.querySelector('audio').pause()")
        playing = false
    }

    /// WebM / Ogg Opus : un `<audio>` dans une vue WebKit invisible.
    func playInWebKit(_ data: Data, mime: String) {
        let config = WKWebViewConfiguration()
        config.mediaTypesRequiringUserActionForPlayback = []
        config.allowsInlineMediaPlayback = true
        config.userContentController.add(WeakHandler(self), name: "ended")
        let web = WKWebView(frame: .zero, configuration: config)
        self.web = web
        let safeMime = mime.allSatisfy { $0.isLetter || $0.isNumber || "/.+-".contains($0) } ? mime : "audio/webm"
        let html = """
            <audio src="data:\(safeMime);base64,\(data.base64EncodedString())" autoplay></audio>
            <script>
              const a = document.querySelector('audio');
              a.playbackRate = \(speed);
              a.onended = a.onerror = () => webkit.messageHandlers.ended.postMessage(1);
            </script>
            """
        web.loadHTMLString(html, baseURL: nil)
        playing = true
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.playing = false }
    }

    nonisolated func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        Task { @MainActor in
            self.playing = false
            self.web = nil
        }
    }

    /// WebKit garde son gestionnaire : une référence faible évite le cycle.
    final class WeakHandler: NSObject, WKScriptMessageHandler {
        weak var target: VoicePlayer?
        init(_ target: VoicePlayer) { self.target = target }
        func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
            target?.userContentController(c, didReceive: message)
        }
    }
}
