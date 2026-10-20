// Ce qui sert partout : avatars, images en adresses `data:`, texte des
// messages, dates, QR codes, sons, notifications.

import AudioToolbox
import CoreImage.CIFilterBuiltins
import SwiftUI
import TalkaKit
import UIKit
import UserNotifications

// MARK: Avatars

/// Une photo, sinon l'initiale sur une couleur tirée du code.
struct Avatar: View {
    let id: String
    let name: String
    var image: String = ""
    var group = false
    var size: CGFloat = 48

    static let colors: [Color] = [
        .init(red: 0.898, green: 0.2588, blue: 0.6392), .init(red: 0.1216, green: 0.4784, blue: 0.9255),
        .init(red: 0.8275, green: 0.4196, blue: 0), .init(red: 0.0078, green: 0.651, blue: 0.5961),
        .init(red: 0.498, green: 0.4, blue: 1), .init(red: 0.7686, green: 0.3255, blue: 0.1765),
        .init(red: 0, green: 0.502, blue: 0.4118), .init(red: 0.651, green: 0.1725, blue: 0.4431),
    ]

    static func color(_ id: String) -> Color {
        colors[Int(id.unicodeScalars.reduce(UInt32(0)) { $0 &* 31 &+ $1.value } % UInt32(colors.count))]
    }

    var body: some View {
        Group {
            if let ui = UIImage.fromDataURL(image) {
                Image(uiImage: ui).resizable().scaledToFill()
            } else if group {
                Circle().fill(Color(.systemGray3)).overlay {
                    Image(systemName: "person.2.fill").font(.system(size: size * 0.4)).foregroundStyle(.white)
                }
            } else {
                Circle().fill(Self.color(id)).overlay {
                    Text(String(name.trimmingCharacters(in: CharacterSet(charactersIn: "~ ")).prefix(1)).uppercased())
                        .font(.system(size: size * 0.42, weight: .semibold))
                        .foregroundStyle(.white)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(.circle)
        .accessibilityHidden(true)
    }
}

extension Engine {
    /// L'avatar d'un code ou d'une discussion.
    func avatar(_ id: String, size: CGFloat = 48) -> Avatar {
        if let c = chat(id), c.group {
            return Avatar(id: id, name: c.name, image: c.avatar, group: true, size: size)
        }
        let name = id == me.code ? profile.name : displayName(id)
        return Avatar(id: id, name: name, image: avatarOf(id), size: size)
    }

    /// L'avatar avec la pastille « en ligne » : verte si le contact est
    /// connecté, grise sinon. Ni pour un groupe, ni pour soi, ni pour un
    /// contact bloqué, ni si elle est désactivée dans les réglages.
    func presenceAvatar(_ id: String, size: CGFloat = 48) -> some View {
        let shown = settings.presenceDot && id != me.code && !isGroup(id) && !blocked(id)
        let online = presence[id]?.online ?? false
        return avatar(id, size: size)
            .overlay(alignment: .bottomTrailing) {
                if shown {
                    PresenceDot(online: online, size: max(10, size * 0.27))
                }
            }
    }
}

struct PresenceDot: View {
    let online: Bool
    let size: CGFloat

    var body: some View {
        Circle()
            .fill(online ? Color(red: 0.1451, green: 0.8275, blue: 0.4) : Color(.systemGray3))
            .frame(width: size, height: size)
            .overlay(Circle().stroke(Color(.systemBackground), lineWidth: 2))
            .shadow(color: online ? Color(red: 0.1451, green: 0.8275, blue: 0.4).opacity(0.7) : .clear, radius: 3)
            .animation(.easeInOut(duration: 0.3), value: online)
            .accessibilityLabel(online ? "En ligne" : "Hors ligne")
    }
}

// MARK: Images et fichiers en adresses `data:`

extension Data {
    func dataURL(_ mime: String) -> String { "data:\(mime);base64,\(base64EncodedString())" }

    /// Le contenu d'une adresse `data:…;base64,…`.
    init?(dataURL: String) {
        guard dataURL.hasPrefix("data:"), let comma = dataURL.firstIndex(of: ",") else { return nil }
        self.init(base64Encoded: String(dataURL[dataURL.index(after: comma)...]))
    }
}

extension UIImage {
    static func fromDataURL(_ url: String) -> UIImage? {
        Data(dataURL: url).flatMap(UIImage.init(data:))
    }

    /// Réduite (comme dans le navigateur) et sans métadonnées : (JPEG, largeur, hauteur).
    func compressed(max: CGFloat = 1600, quality: CGFloat = 0.82) -> (String, Int, Int)? {
        let scale = Swift.min(1, max / Swift.max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: target, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: target))
            draw(in: CGRect(origin: .zero, size: target))
        }
        guard let jpeg = image.jpegData(compressionQuality: quality) else { return nil }
        return (jpeg.dataURL("image/jpeg"), Int(target.width), Int(target.height))
    }

    /// Une petite photo carrée (profil, groupe).
    func avatarDataURL(size side: CGFloat = 160) -> String? {
        let s = Swift.min(size.width, size.height)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            let scale = side / s
            draw(in: CGRect(x: -(size.width - s) / 2 * scale, y: -(size.height - s) / 2 * scale, width: size.width * scale, height: size.height * scale))
        }
        return image.jpegData(compressionQuality: 0.8)?.dataURL("image/jpeg")
    }
}

/// Un fichier temporaire (partager une photo, un document).
func temporaryFile(_ data: Data, name: String) -> URL {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let url = dir.appendingPathComponent(name.isEmpty ? "fichier" : name)
    try? data.write(to: url)
    return url
}

// MARK: Le texte des messages

/// Liens cliquables, *gras*, _italique_, ~barré~, `code` : comme le navigateur.
func richText(_ text: String) -> AttributedString {
    // Un jeton Brocolis qu'aucune clé n'ouvre : « 🔒🥦 », pas un pavé de base64.
    let text = Brocolis.masked(text)
    var out = AttributedString()
    let chars = Array(text)
    var plain = ""
    var i = 0
    func flush() {
        if !plain.isEmpty { out += AttributedString(plain); plain = "" }
    }
    while i < chars.count {
        let atStart = i == 0 || chars[i - 1].isWhitespace
        let rest = String(chars[i..<Swift.min(i + 8, chars.count)])
        if atStart, rest.hasPrefix("https://") || rest.hasPrefix("http://") {
            var j = i
            while j < chars.count, !chars[j].isWhitespace { j += 1 }
            while j > i, ".,;:!?)]'\"".contains(chars[j - 1]) { j -= 1 }
            let link = String(chars[i..<j])
            flush()
            var a = AttributedString(link)
            a.link = URL(string: link)
            a.swiftUI.underlineStyle = .single
            out += a
            i = j
            continue
        }
        let c = chars[i]
        if "*_~`".contains(c), i + 1 < chars.count,
           let len = chars[(i + 1)...].firstIndex(where: { $0 == c || $0 == "\n" }).map({ $0 - i - 1 }),
           len > 0, chars[i + 1 + len] == c {
            let inner = String(chars[(i + 1)..<(i + 1 + len)])
            if !inner.hasPrefix(" "), !inner.hasSuffix(" ") {
                flush()
                var a = AttributedString(inner)
                switch c {
                case "*": a.inlinePresentationIntent = .stronglyEmphasized
                case "_": a.inlinePresentationIntent = .emphasized
                case "~": a.swiftUI.strikethroughStyle = .single
                default:
                    a.swiftUI.font = .system(.body, design: .monospaced)
                    a.swiftUI.backgroundColor = Color.primary.opacity(0.08)
                }
                out += a
                i += len + 2
                continue
            }
        }
        plain.append(c)
        i += 1
    }
    flush()
    return out
}

/// Un message fait seulement de 1 à 3 emojis s'affiche en grand.
func onlyEmoji(_ text: String) -> Bool {
    let t = text.trimmingCharacters(in: .whitespaces)
    guard !t.isEmpty, t.count <= 3 else { return false }
    return t.allSatisfy { ch in ch.unicodeScalars.contains { $0.properties.isEmojiPresentation || ($0.properties.isEmoji && $0.value > 0xFF) } }
}

// MARK: Les dates

enum Dates {
    static let time: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "HH:mm"
        return f
    }()

    static let day: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "EEEE"
        return f
    }()

    static let short: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "dd/MM/yy"
        return f
    }()

    static let long: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "d MMMM yyyy"
        return f
    }()

    static func date(_ ms: Double) -> Date { Date(timeIntervalSince1970: ms / 1000) }

    static func daysAgo(_ ms: Double) -> Int {
        let cal = Calendar.current
        return cal.dateComponents([.day], from: cal.startOfDay(for: date(ms)), to: cal.startOfDay(for: Date())).day ?? 0
    }

    /// `14:05`.
    static func clock(_ ms: Double) -> String { time.string(from: date(ms)) }

    /// L'heure d'un élément de liste : `14:05`, « Hier », « Lundi », `03/03/26`.
    static func list(_ ms: Double) -> String {
        switch daysAgo(ms) {
        case 0: clock(ms)
        case 1: "Hier"
        case 2..<7: day.string(from: date(ms)).capitalized
        default: short.string(from: date(ms))
        }
    }

    /// « Aujourd'hui », « Hier », « Lundi », « 3 mars 2026 ».
    static func separator(_ ms: Double) -> String {
        switch daysAgo(ms) {
        case 0: "Aujourd'hui"
        case 1: "Hier"
        case 2..<7: day.string(from: date(ms)).capitalized
        default: long.string(from: date(ms))
        }
    }

    static func lastSeen(_ ms: Double) -> String {
        switch daysAgo(ms) {
        case 0: "vu(e) aujourd'hui à \(clock(ms))"
        case 1: "vu(e) hier à \(clock(ms))"
        default: "vu(e) le \(short.string(from: date(ms)))"
        }
    }

    /// Un jour, passé ou à venir : « aujourd'hui », « demain », « hier »,
    /// « lundi » (dans la semaine), « le 3 mars 2026 ».
    static func when(_ ms: Double) -> String {
        switch daysAgo(ms) {
        case 0: "aujourd'hui"
        case 1: "hier"
        case -1: "demain"
        case -6..<7: day.string(from: date(ms))
        default: "le \(long.string(from: date(ms)))"
        }
    }

    static func sameDay(_ a: Double, _ b: Double) -> Bool {
        Calendar.current.isDate(date(a), inSameDayAs: date(b))
    }
}

func formatBytes(_ n: Double) -> String {
    ByteCountFormatter.string(fromByteCount: Int64(n), countStyle: .file)
}

// MARK: QR code

enum QR {
    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent)
        else { return nil }
        return UIImage(cgImage: cg)
    }
}

// MARK: Sons et notifications

enum Sounds {
    static func prepare() {}
    static func incoming() { AudioServicesPlaySystemSound(1007) }
    static func sent() { AudioServicesPlaySystemSound(1004) }
}

/// Les notifications locales : quand Talka est en arrière-plan, l'iPhone,
/// qui a déchiffré le message, se prévient lui-même. Rien ne passe par Apple
/// ni par le serveur.
@MainActor
enum Notifier {
    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    static func message(title: String, body: String, thread: String, sound: Bool) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = String(body.prefix(160))
        content.threadIdentifier = thread
        if sound { content.sound = .default }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}

// MARK: Petits éléments

/// Le code Talka en gros, à copier.
struct CodeBadge: View {
    let code: String

    var body: some View {
        Text(TalkaCode.pretty(code))
            .font(.system(size: 28, weight: .bold, design: .monospaced))
            .kerning(2)
            .foregroundStyle(Color.talkaDark)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color.talkaGreen.opacity(0.12), in: .rect(cornerRadius: 12))
            .minimumScaleFactor(0.6)
            .lineLimit(1)
            .textSelection(.enabled)
    }
}

extension View {
    /// Une carte de réglage (fond groupé).
    func card() -> some View {
        padding(16).background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
    }
}

// MARK: Appareil photo

/// Prendre une photo avec l'appareil (la galerie passe par PhotosPicker).
struct CameraPicker: UIViewControllerRepresentable {
    let done: (UIImage?) -> Void

    static var available: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    func makeCoordinator() -> Coordinator { Coordinator(done: done) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let c = UIImagePickerController()
        c.sourceType = .camera
        c.delegate = context.coordinator
        return c
    }

    func updateUIViewController(_ c: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (UIImage?) -> Void
        init(done: @escaping (UIImage?) -> Void) { self.done = done }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            done(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { done(nil) }
    }
}
