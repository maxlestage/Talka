// Un compte de démonstration, pour les captures de la CI seulement : il
// n'existe que dans les builds Debug, et ne sert que si l'application est
// lancée avec `-TalkaDemo YES` (voir .github/workflows/ios.yml).
//
// Une base neuve dans un dossier temporaire (jamais celle de l'utilisateur) :
// un compte, un contact, et une discussion de 999 messages numérotés, pour
// voir à l'écran ce qu'aucun test sans interface ne montre : une longue
// discussion qui s'ouvre sur sa fin, puis les messages plus anciens qui
// arrivent quand on remonte (`-TalkaDemoTop YES` remonte tout seul).
// Trois de ses derniers messages sont des photos ; un vieux message est
// épinglé (`-TalkaDemoJump YES` y saute tout seul) ; `-TalkaDemoUnread 350`
// l'ouvre avec 350 messages non lus.

#if DEBUG
import Foundation
import TalkaKit
import UIKit

@MainActor
enum Demo {
    static var on: Bool { UserDefaults.standard.bool(forKey: "TalkaDemo") }
    /// Remonter tout seul en haut du fil, deux fois.
    static var climbs: Bool { UserDefaults.standard.bool(forKey: "TalkaDemoTop") }
    /// Afficher toute la discussion d'emblée (pour comparer avec son
    /// ouverture sur une page).
    static var all: Bool { UserDefaults.standard.bool(forKey: "TalkaDemoAll") }
    /// Sauter tout seul vers le message épinglé, plus ancien que ce qui est
    /// affiché à l'ouverture.
    static var jumps: Bool { UserDefaults.standard.bool(forKey: "TalkaDemoJump") }
    /// Le message épinglé.
    static let pinned = "demo120"
    /// Les messages qui sont des photos (parmi les derniers).
    static let photos: Set<Int> = [984, 991, 996]

    static let contact = "K7Q2MX9PRT"
    static let count = 999

    static func store() -> Store {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-demo", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        let store = Store(directory: dir, key: nil)
        Engine.createAccount(store, name: "Démo", avatar: "")
        let me = store.read("kv", "identity", as: Identity.self)?.code ?? ""
        var friend = Contact(code: contact)
        friend.name = "Léa"
        friend.saved = true
        store.write("contacts", friend.code, friend)
        let now = nowMs()
        var chat = Chat(id: contact)
        chat.pinMsg = pinned
        chat.unread = max(0, UserDefaults.standard.integer(forKey: "TalkaDemoUnread"))
        store.transaction {
            for i in 1...count {
                // Un message par minute, le dernier à l'instant ; un sur trois est de nous.
                var content = Content(text: "Message \(i)")
                if photos.contains(i) {
                    // Une photo de la taille de celles que Talka envoie (1 600 pixels).
                    let wide = i % 2 == 0
                    content = Content(kind: "image", text: "", data: photo(i, wide: wide), mime: "image/jpeg",
                                      w: wide ? 1600 : 1200, h: wide ? 1200 : 1600)
                }
                let m = Message(chat: contact, id: "demo\(i)", from: i % 3 == 0 ? me : contact,
                                at: now - Double(count - i) * 60_000, content: content)
                store.writeMessage(m)
                chat.last = Last(id: m.id, from: m.from, text: m.text, at: m.at, kind: m.kind)
                chat.lastAt = m.at
            }
        }
        store.write("chats", chat.id, chat)
        return store
    }

    /// Une photo de démonstration : un aplat de couleur et son numéro.
    static func photo(_ n: Int, wide: Bool) -> String {
        let size = wide ? CGSize(width: 1600, height: 1200) : CGSize(width: 1200, height: 1600)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(hue: CGFloat(n % 10) / 10, saturation: 0.55, brightness: 0.85, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            ("Photo \(n)" as NSString).draw(
                at: CGPoint(x: size.width * 0.12, y: size.height * 0.4),
                withAttributes: [.font: UIFont.boldSystemFont(ofSize: 220), .foregroundColor: UIColor.white])
        }
        return (image.jpegData(compressionQuality: 0.8) ?? Data()).dataURL("image/jpeg")
    }
}
#endif
