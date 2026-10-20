// La capsule temporelle : un message scellé jusqu'à une heure choisie. Il
// part tout de suite, mais le destinataire ne voit qu'une enveloppe et un
// compte à rebours ; à l'heure dite, il s'ouvre de lui-même. Le même champ
// (`open_at`) que dans le navigateur.

import SwiftUI
import TalkaKit

/// « dans 2 j 4 h », « dans 3 h 05 », « dans 12 min », « dans 40 s ».
func capsuleCountdown(_ openAt: Double, now: Double) -> String {
    let s = max(0, Int(((openAt - now) / 1000).rounded(.up)))
    let (d, h, m) = (s / 86_400, s % 86_400 / 3600, s % 3600 / 60)
    if d > 0 { return "dans \(d) j \(h) h" }
    if h > 0 { return "dans \(h) h \(String(format: "%02d", m))" }
    if m > 0 { return "dans \(m) min" }
    return "dans \(s) s"
}

/// L'enveloppe d'une capsule encore scellée.
struct SealedCapsule: View {
    let openAt: Double
    let now: Double
    @State private var breathe = false

    /// « Demain à 09:00 », « Le 25 décembre 2026 à 09:00 ».
    var openLine: String {
        let w = Dates.when(openAt)
        return w.prefix(1).uppercased() + w.dropFirst() + " à " + Dates.clock(openAt)
    }

    var body: some View {
        VStack(spacing: 3) {
            Text("✉️")
                .font(.system(size: 40))
                .scaleEffect(breathe ? 1.08 : 1)
                .rotationEffect(.degrees(breathe ? -4 : 0))
                .animation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true), value: breathe)
                .onAppear { breathe = true }
                .accessibilityHidden(true)
            Text("Capsule temporelle").font(.subheadline.weight(.semibold))
            Text("S'ouvre \(capsuleCountdown(openAt, now: now))")
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(Color.talkaGreen)
            Text(openLine)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
        .frame(minWidth: 200)
        .accessibilityElement(children: .combine)
    }
}

/// Le bandeau d'une capsule : pour l'auteur, quand elle s'ouvrira ; une fois
/// ouverte, quand elle a été écrite.
struct CapsuleBand: View {
    let m: Message
    let now: Double

    var body: some View {
        let openAt = m.openAt ?? 0
        Text(openAt > now
             ? "⏳ Scellée · s'ouvre \(Dates.when(openAt)) à \(Dates.clock(openAt))"
             : "⏳ Capsule écrite \(Dates.when(m.at)) · ouverte \(Dates.when(openAt))")
            .font(.caption)
            .foregroundStyle(.secondary)
    }
}

/// Écrire une capsule : le message, et quand elle s'ouvre.
struct CapsuleComposer: View {
    let send: (String, Double) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var openAt = CapsuleComposer.at(days: 1, hour: 9)
    @FocusState private var focused: Bool

    static func at(days: Int, hour: Int) -> Date {
        let cal = Calendar.current
        let day = cal.date(byAdding: .day, value: days, to: Date()) ?? Date()
        return cal.date(bySettingHour: hour, minute: 0, second: 0, of: day) ?? day
    }

    static var newYear: Date {
        let cal = Calendar.current
        let year = cal.component(.year, from: Date()) + 1
        return cal.date(from: DateComponents(year: year, month: 1, day: 1)) ?? Date()
    }

    var presets: [(String, Date)] {
        [
            ("Dans 1 heure", Date().addingTimeInterval(3600)),
            ("Demain 9 h", Self.at(days: 1, hour: 9)),
            ("Dans une semaine", Self.at(days: 7, hour: 9)),
            ("Au Nouvel An", Self.newYear),
            ("Dans un an", Self.at(days: 365, hour: 9)),
        ]
    }

    var valid: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && openAt.timeIntervalSinceNow >= 60
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Ce que vous voulez qu'on lise plus tard…", text: $text, axis: .vertical)
                        .lineLimit(4...10)
                        .focused($focused)
                } footer: {
                    Text("Un message scellé : il part maintenant, mais ne s'ouvre qu'à l'heure choisie. Jusque-là, on ne voit qu'une enveloppe et un compte à rebours.")
                }
                Section("S'ouvre") {
                    DatePicker("Le", selection: $openAt,
                               in: Date().addingTimeInterval(60)...Date().addingTimeInterval(capsuleMaxMs / 1000))
                        .environment(\.locale, Locale(identifier: "fr_FR"))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(presets, id: \.0) { label, date in
                                Button(label) { openAt = date }
                                    .buttonStyle(.bordered)
                                    .tint(.talkaGreen)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Capsule temporelle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sceller") {
                        send(text.trimmingCharacters(in: .whitespacesAndNewlines), openAt.timeIntervalSince1970 * 1000)
                    }
                    .disabled(!valid)
                }
            }
            .onAppear { focused = true }
        }
    }
}
