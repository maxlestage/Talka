// Les messages vocaux enregistrés sur la montre : le même format que
// l'iPhone (AAC mono, 32 kbit/s), envoyés à l'iPhone en fichier ; c'est lui
// qui les chiffre et les envoie.

import AVFoundation
import Observation
import SwiftUI

@MainActor
@Observable
final class WatchRecorder {
    private(set) var recording = false
    private(set) var started = Date()
    @ObservationIgnored private var recorder: AVAudioRecorder?
    @ObservationIgnored private var url: URL?

    /// Commence (avec la permission du micro). Faux sans micro.
    func start() async -> Bool {
        guard await AVAudioApplication.requestRecordPermission() else { return false }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default)
        try? session.setActive(true)
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let url = dir.appendingPathComponent("vocal-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 24_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32_000,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        guard let recorder = try? AVAudioRecorder(url: url, settings: settings),
              recorder.record(forDuration: WatchLink.maxVoiceSeconds)
        else { return false }
        self.recorder = recorder
        self.url = url
        started = Date()
        recording = true
        return true
    }

    /// Arrête, et rend le fichier et sa durée (rien en dessous d'une
    /// demi-seconde). Le fichier est à effacer une fois parti.
    func stop() -> (url: URL, seconds: Double)? {
        let seconds = min(Date().timeIntervalSince(started), WatchLink.maxVoiceSeconds)
        recorder?.stop()
        recording = false
        recorder = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        defer { url = nil }
        guard let url else { return nil }
        guard seconds >= 0.5 else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return (url, seconds)
    }

    func cancel() {
        recorder?.stop()
        recorder?.deleteRecording()
        recording = false
        recorder = nil
        url = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// « 0:07 »
func voiceDuration(_ seconds: Double) -> String {
    let t = max(0, Int(seconds.rounded()))
    return "\(t / 60):" + String(format: "%02d", t % 60)
}

/// L'enregistrement : le temps qui passe, Envoyer, Annuler.
struct RecordView: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let chatId: String
    @State private var recorder = WatchRecorder()
    @State private var failed = false
    @State private var pulse = false

    var body: some View {
        VStack(spacing: 10) {
            if failed {
                Image(systemName: "mic.slash.fill").font(.title).foregroundStyle(.red)
                Text("Micro indisponible").font(.headline)
                Text("Autorisez le micro pour Talka dans les réglages de la montre.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            } else {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let elapsed = recorder.recording ? context.date.timeIntervalSince(recorder.started) : 0
                    HStack(spacing: 6) {
                        Circle()
                            .fill(.red)
                            .frame(width: 10, height: 10)
                            .opacity(pulse ? 0.3 : 1)
                        Text(voiceDuration(elapsed))
                            .font(.system(.title, design: .rounded).monospacedDigit())
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Enregistrement : \(voiceDuration(elapsed))")
                }
                Button {
                    if let r = recorder.stop() { model.sendVoice(chatId, r.url, seconds: r.seconds) }
                    dismiss()
                } label: {
                    Label("Envoyer", systemImage: "arrow.up.circle.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.talkaGreen)
                .disabled(!recorder.recording)
            }
            Button("Annuler", role: .cancel) {
                recorder.cancel()
                dismiss()
            }
        }
        .task {
            if await !recorder.start() { failed = true }
            withAnimation(.easeInOut(duration: 0.8).repeatForever()) { pulse = true }
        }
        .onDisappear { if recorder.recording { recorder.cancel() } }
    }
}
