// Ce que l'iPhone et l'Apple Watch s'échangent (WatchConnectivity).
//
// La montre n'a ni clés ni relais : c'est l'iPhone qui reste l'appareil Talka.
// Il envoie à la montre un résumé des discussions récentes (`WatchSnapshot`),
// la montre lui renvoie ce que l'on y fait (`WatchAction`) : répondre, réagir,
// marquer comme lu. Rien d'autre ne passe, et seulement entre les deux
// appareils appairés.
//
// Ce fichier est compilé dans TalkaKit (testé avec le reste) et tel quel dans
// l'application de la montre : il ne dépend de rien d'autre.

import Foundation

public enum WatchLink {
    /// Les clés des dictionnaires WatchConnectivity.
    public static let snapshotKey = "snapshot"
    public static let actionKey = "action"

    public static func encode<T: Encodable>(_ value: T) -> Data? {
        try? JSONEncoder().encode(value)
    }

    public static func decode<T: Decodable>(_ type: T.Type, _ data: Any?) -> T? {
        guard let data = data as? Data else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}

/// Les discussions récentes, telles que la montre les affiche.
public struct WatchSnapshot: Codable, Equatable, Sendable {
    /// Votre nom (votre profil).
    public var name = ""
    /// L'iPhone est-il relié au relais Talka ?
    public var online = false
    public var chats: [WatchChat] = []

    public init(name: String = "", online: Bool = false, chats: [WatchChat] = []) {
        self.name = name
        self.online = online
        self.chats = chats
    }
}

public struct WatchChat: Codable, Equatable, Identifiable, Sendable {
    public var id = ""
    public var title = ""
    /// Les initiales de l'avatar (pas de photo : la montre reste légère).
    public var initials = ""
    /// La couleur de l'avatar : la même que sur l'iPhone et dans le navigateur.
    public var color = 0
    public var group = false
    public var unread = 0
    public var muted = false
    public var pinned = false
    /// Envoi impossible : contact bloqué, ou groupe quitté.
    public var readOnly = false
    public var at: Double = 0
    public var last = ""
    /// Les derniers messages, du plus ancien au plus récent.
    public var messages: [WatchMessage] = []

    public init(id: String) { self.id = id }
}

public struct WatchMessage: Codable, Equatable, Identifiable, Sendable {
    public var id = ""
    /// Le nom de l'auteur, dans un groupe (vide sinon).
    public var author = ""
    public var mine = false
    public var system = false
    public var text = ""
    public var at: Double = 0
    /// Pour vos messages : `pending`, `sent`, `delivered`, `read`, `failed`.
    public var tick = ""
    /// Les réactions, regroupées (« ❤️2 👍 »).
    public var reactions = ""
    /// Votre réaction à ce message (vide : aucune).
    public var myReaction = ""

    public init(id: String) { self.id = id }
}

/// Ce que l'on fait sur la montre ; l'iPhone l'exécute.
public struct WatchAction: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        /// Envoyer `text` dans `chat`.
        case send
        /// Réagir (`text` : l'emoji ; le même une deuxième fois la retire).
        case react
        /// Marquer `chat` comme lu.
        case read
        /// Demander un résumé à jour.
        case refresh
    }

    public var kind: Kind
    public var chat = ""
    public var id = ""
    public var text = ""

    public init(_ kind: Kind, chat: String = "", id: String = "", text: String = "") {
        self.kind = kind
        self.chat = chat
        self.id = id
        self.text = text
    }
}

/// Les réponses rapides proposées sur la montre.
public let watchQuickReplies = ["OK", "J'arrive !", "Merci !", "Je te rappelle", "Oui", "Non"]
public let watchReactions = ["❤️", "👍", "😂", "😮", "😢", "🙏"]
