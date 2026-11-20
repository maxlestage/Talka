// Un compte de démonstration, pour les captures de la CI seulement : il
// n'existe que dans les builds Debug, et ne sert que si l'application est
// lancée avec `-TalkaDemo YES` (voir .github/workflows/ios.yml).
//
// Une base neuve dans un dossier temporaire (jamais celle de l'utilisateur) :
// un compte, un contact, et une discussion de 999 messages numérotés, pour
// voir à l'écran ce qu'aucun test sans interface ne montre : une longue
// discussion qui s'ouvre sur sa fin, puis les messages plus anciens qui
// arrivent quand on remonte (`-TalkaDemoTop YES` remonte tout seul).

#if DEBUG
import Foundation
import TalkaKit

@MainActor
enum Demo {
    static var on: Bool { UserDefaults.standard.bool(forKey: "TalkaDemo") }
    /// Remonter tout seul en haut du fil, deux fois.
    static var climbs: Bool { UserDefaults.standard.bool(forKey: "TalkaDemoTop") }
    /// Afficher toute la discussion d'emblée (pour comparer avec son
    /// ouverture sur une page).
    static var all: Bool { UserDefaults.standard.bool(forKey: "TalkaDemoAll") }

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
        store.transaction {
            for i in 1...count {
                // Un message par minute, le dernier à l'instant ; un sur trois est de nous.
                let m = Message(chat: contact, id: "demo\(i)", from: i % 3 == 0 ? me : contact,
                                at: now - Double(count - i) * 60_000, content: Content(text: "Message \(i)"))
                store.writeMessage(m)
                chat.last = Last(id: m.id, from: m.from, text: m.text, at: m.at, kind: m.kind)
                chat.lastAt = m.at
            }
        }
        store.write("chats", chat.id, chat)
        return store
    }
}
#endif
