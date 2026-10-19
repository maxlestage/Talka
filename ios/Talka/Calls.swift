// L'écran d'appel (plein écran) et l'onglet Appels (le journal).

import SwiftUI
import TalkaKit
import UIKit
import WebRTC

struct CallScreen: View {
    @Environment(AppModel.self) private var model
    let call: CallEngine

    var body: some View {
        let engine = call.engine
        ZStack {
            Color(red: 11 / 255, green: 20 / 255, blue: 26 / 255).ignoresSafeArea()
            if let remote = call.remoteVideo, call.video {
                VideoView(track: remote, mirror: false).ignoresSafeArea()
            }
            VStack(spacing: 14) {
                if !(call.video && call.phase == .live) {
                    Spacer().frame(height: 40)
                    engine.avatar(call.peer, size: 120)
                }
                Text(engine.displayName(call.peer))
                    .font(.title.weight(.semibold))
                    .foregroundStyle(.white)
                    .shadow(radius: 4)
                Group {
                    if let start = call.connectedAt, call.phase == .live {
                        TimelineView(.periodic(from: .now, by: 1)) { ctx in
                            Text(formatDuration(ctx.date.timeIntervalSince(start)))
                        }
                    } else {
                        Text(call.status)
                    }
                }
                .font(.headline.monospacedDigit())
                .foregroundStyle(.white.opacity(0.8))
                Label("Chiffré de bout en bout", systemImage: "lock.fill")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.6))
                Spacer()
                controls
            }
            .padding(.bottom, 30)
            if let local = call.localVideo {
                VStack {
                    HStack {
                        Spacer()
                        VideoView(track: local, mirror: call.frontCamera)
                            .frame(width: 110, height: 160)
                            .clipShape(.rect(cornerRadius: 12))
                            .shadow(radius: 6)
                            .padding()
                    }
                    Spacer()
                }
            }
        }
        .statusBarHidden(call.video)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    @ViewBuilder var controls: some View {
        if call.phase == .ringing {
            HStack(spacing: 70) {
                RoundButton(icon: "phone.down.fill", label: "Refuser", color: .red) { call.hangup() }
                RoundButton(icon: call.video ? "video.fill" : "phone.fill", label: "Répondre", color: .talkaAccent) {
                    Task { await call.accept() }
                }
            }
        } else if call.phase != .ended {
            HStack(spacing: 18) {
                RoundButton(icon: call.muted ? "mic.slash.fill" : "mic.fill", label: call.muted ? "Activer le micro" : "Couper le micro",
                            color: call.muted ? .white : .white.opacity(0.2), dark: call.muted) { call.toggleMute() }
                if call.video {
                    RoundButton(icon: "arrow.triangle.2.circlepath.camera", label: "Changer de caméra", color: .white.opacity(0.2)) {
                        call.switchCamera()
                    }
                    RoundButton(icon: call.localVideo?.isEnabled == false ? "video.slash.fill" : "video.fill", label: "Caméra",
                                color: .white.opacity(0.2)) { call.toggleCamera() }
                }
                RoundButton(icon: call.speaker ? "speaker.wave.3.fill" : "speaker.fill", label: "Haut-parleur",
                            color: call.speaker ? .white : .white.opacity(0.2), dark: call.speaker) { call.toggleSpeaker() }
                RoundButton(icon: "phone.down.fill", label: "Raccrocher", color: .red) { call.hangup() }
                    .accessibilityIdentifier("hangup")
            }
        }
    }
}

struct RoundButton: View {
    let icon: String
    let label: String
    let color: Color
    var dark = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(dark ? .black : .white)
                .frame(width: 62, height: 62)
                .background(color, in: .circle)
        }
        .accessibilityLabel(label)
    }
}

struct VideoView: UIViewRepresentable {
    let track: RTCVideoTrack
    let mirror: Bool

    final class Coordinator {
        var track: RTCVideoTrack?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> RTCMTLVideoView {
        let view = RTCMTLVideoView(frame: .zero)
        view.videoContentMode = .scaleAspectFill
        view.clipsToBounds = true
        return view
    }

    func updateUIView(_ view: RTCMTLVideoView, context: Context) {
        view.transform = mirror ? CGAffineTransform(scaleX: -1, y: 1) : .identity
        if context.coordinator.track !== track {
            context.coordinator.track?.remove(view)
            track.add(view)
            context.coordinator.track = track
        }
    }

    static func dismantleUIView(_ view: RTCMTLVideoView, coordinator: Coordinator) {
        coordinator.track?.remove(view)
    }
}

// MARK: L'onglet Appels

struct CallsTab: View {
    @Environment(AppModel.self) private var model
    @State private var picking = false

    var body: some View {
        NavigationStack {
            if let e = model.engine {
                let _ = e.callRevision
                let log = e.callLog()
                List {
                    if log.isEmpty {
                        ContentUnavailableView(
                            "Aucun appel", systemImage: "phone",
                            description: Text("Les appels audio et vidéo sont chiffrés de bout en bout, de pair à pair."))
                    }
                    ForEach(log) { entry in
                        HStack(spacing: 12) {
                            e.avatar(entry.peer, size: 44)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(e.displayName(entry.peer))
                                    .font(.body.weight(.medium))
                                    .foregroundStyle(entry.missed ? .red : .primary)
                                Label(describe(entry), systemImage: entry.incoming ? "arrow.down.left" : "arrow.up.right")
                                    .font(.subheadline)
                                    .foregroundStyle(entry.missed ? .red : .secondary)
                                    .labelStyle(.titleAndIcon)
                            }
                            Spacer()
                            Text(Dates.list(entry.at)).font(.caption).foregroundStyle(.secondary)
                            Button {
                                Task { await model.startCall(entry.peer, video: entry.video) }
                            } label: {
                                Image(systemName: entry.video ? "video.fill" : "phone.fill").foregroundStyle(Color.talkaGreen)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Rappeler")
                        }
                    }
                }
                .listStyle(.plain)
                .navigationTitle("Appels")
                .toolbar {
                    if !log.isEmpty {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("Effacer") { e.clearCalls() }
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { picking = true } label: { Image(systemName: "phone.badge.plus") }
                            .accessibilityLabel("Nouvel appel")
                    }
                }
                .sheet(isPresented: $picking) {
                    ContactPicker(title: "Nouvel appel") { code in
                        picking = false
                        Task { await model.startCall(code, video: false) }
                    }
                }
            }
        }
    }

    func describe(_ e: CallEntry) -> String {
        let kind = e.video ? "Vidéo" : "Audio"
        if e.missed { return "\(kind) · manqué" }
        if e.dur > 0 { return "\(kind) · \(formatDuration(e.dur))" }
        return "\(kind) · \(e.incoming ? "entrant" : "sans réponse")"
    }
}

/// Choisir un contact (appel, transfert, partage de contact).
struct ContactPicker<Header: View>: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let title: String
    @ViewBuilder var header: () -> Header
    let pick: (String) -> Void
    @State private var query = ""

    init(title: String, @ViewBuilder header: @escaping () -> Header, pick: @escaping (String) -> Void) {
        self.title = title
        self.header = header
        self.pick = pick
    }

    var body: some View {
        NavigationStack {
            if let e = model.engine {
                let list = e.contacts.values
                    .filter { $0.saved && !$0.blocked }
                    .filter { query.isEmpty || e.displayName($0.code).localizedCaseInsensitiveContains(query) || $0.code.contains(query.uppercased()) }
                    .sorted { e.displayName($0.code).localizedCompare(e.displayName($1.code)) == .orderedAscending }
                List {
                    header()
                    Section("Contacts") {
                        if list.isEmpty { Text("Aucun contact").foregroundStyle(.secondary) }
                        ForEach(list) { c in
                            Button { pick(c.code) } label: {
                                HStack(spacing: 12) {
                                    e.avatar(c.code, size: 40)
                                    VStack(alignment: .leading) {
                                        Text(e.displayName(c.code)).foregroundStyle(.primary)
                                        Text(TalkaCode.pretty(c.code)).font(.caption.monospaced()).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
                .searchable(text: $query)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } } }
            }
        }
    }
}

extension ContactPicker where Header == EmptyView {
    init(title: String, pick: @escaping (String) -> Void) {
        self.init(title: title, header: { EmptyView() }, pick: pick)
    }
}
