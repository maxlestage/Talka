// Ce que garde l'appareil et ce qui voyage, chiffré, entre appareils : le
// même JSON que le navigateur (`client/src/model.rs`). Un champ absent prend
// sa valeur par défaut, comme `#[serde(default)]`.

import Foundation

extension KeyedDecodingContainer {
    /// Une valeur, ou celle par défaut si elle manque (ou est `null`).
    func v<T: Decodable>(_ key: Key, _ fallback: T) -> T {
        ((try? decodeIfPresent(T.self, forKey: key)) ?? nil) ?? fallback
    }
}

/// Une valeur JSON quelconque (les candidats ICE d'un appel).
public enum JSONValue: Codable, Equatable, Sendable {
    case null, bool(Bool), number(Double), string(String)
    case array([JSONValue]), object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n):
            if n == n.rounded(), abs(n) < 1e15 { try c.encode(Int64(n)) } else { try c.encode(n) }
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    /// En objets Foundation (pour WebRTC).
    public var any: Any {
        switch self {
        case .null: NSNull()
        case .bool(let b): b
        case .number(let n): n == n.rounded() ? Int(n) as Any : n as Any
        case .string(let s): s
        case .array(let a): a.map(\.any)
        case .object(let o): o.mapValues(\.any)
        }
    }

    public subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    public var string: String? { if case .string(let s) = self { s } else { nil } }
    public var number: Double? { if case .number(let n) = self { n } else { nil } }
}

public struct Profile: Codable, Equatable, Sendable {
    public var name = ""
    public var about = ""
    public var avatar = ""
    public var v = 0

    public init(name: String = "", about: String = "", avatar: String = "", v: Int = 0) {
        self.name = name; self.about = about; self.avatar = avatar; self.v = v
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = c.v(.name, ""); about = c.v(.about, ""); avatar = c.v(.avatar, ""); v = c.v(.v, 0)
    }
}

public struct Settings: Codable, Equatable, Sendable {
    /// `auto`, `light` ou `dark`.
    public var theme = "auto"
    public var readReceipts = true
    public var hideOnline = false
    /// La pastille verte des contacts en ligne (grise hors ligne).
    public var presenceDot = true
    public var notify = false
    public var sound = true
    public var enterSends = true
    public var wallpaper = "doodle"
    public var fontSize = 15
    /// Verrouillage automatique : secondes passées hors de Talka.
    public var lockAfter = 0
    /// L'adresse du serveur Talka.
    public var server = ""

    enum CodingKeys: String, CodingKey {
        case theme, notify, sound, wallpaper, server
        case readReceipts = "read_receipts"
        case hideOnline = "hide_online"
        case presenceDot = "presence_dot"
        case enterSends = "enter_sends"
        case fontSize = "font_size"
        case lockAfter = "lock_after"
    }

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Settings()
        theme = c.v(.theme, d.theme); readReceipts = c.v(.readReceipts, d.readReceipts)
        hideOnline = c.v(.hideOnline, d.hideOnline); notify = c.v(.notify, d.notify)
        sound = c.v(.sound, d.sound); enterSends = c.v(.enterSends, d.enterSends)
        wallpaper = c.v(.wallpaper, d.wallpaper); fontSize = c.v(.fontSize, d.fontSize)
        lockAfter = c.v(.lockAfter, d.lockAfter); server = c.v(.server, d.server)
        presenceDot = c.v(.presenceDot, d.presenceDot)
    }
}

public struct Contact: Codable, Equatable, Identifiable, Sendable {
    public var code: String
    /// Le nom que vous lui donnez.
    public var name = ""
    /// Le nom qu'il s'est choisi (affiché `~Nom`).
    public var profileName = ""
    public var about = ""
    public var avatar = ""
    public var sign = ""
    public var dh = ""
    public var blocked = false
    public var saved = false
    public var addedAt: Double = 0
    public var profileV = 0
    public var sentProfileV = 0
    /// Son jeton de remise (dans son profil) : pour lui écrire en expéditeur
    /// scellé, sans que le relais sache qui écrit. Vide : pas encore reçu.
    public var jeton = ""
    /// Lui a-t-on donné le nôtre ?
    public var jetonSent = false

    public var id: String { code }

    enum CodingKeys: String, CodingKey {
        case code, name, about, avatar, sign, dh, blocked, saved, jeton
        case profileName = "profile_name"
        case addedAt = "added_at"
        case profileV = "profile_v"
        case sentProfileV = "sent_profile_v"
        case jetonSent = "jeton_sent"
    }

    public init(code: String, addedAt: Double = nowMs()) {
        self.code = code
        self.addedAt = addedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        code = c.v(.code, ""); name = c.v(.name, ""); profileName = c.v(.profileName, "")
        about = c.v(.about, ""); avatar = c.v(.avatar, ""); sign = c.v(.sign, ""); dh = c.v(.dh, "")
        blocked = c.v(.blocked, false); saved = c.v(.saved, false); addedAt = c.v(.addedAt, 0)
        profileV = c.v(.profileV, 0); sentProfileV = c.v(.sentProfileV, 0)
        jeton = c.v(.jeton, ""); jetonSent = c.v(.jetonSent, false)
    }

    /// Comme le navigateur : le jeton n'est écrit que s'il y en a un.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(code, forKey: .code); try c.encode(name, forKey: .name)
        try c.encode(profileName, forKey: .profileName); try c.encode(about, forKey: .about)
        try c.encode(avatar, forKey: .avatar); try c.encode(sign, forKey: .sign); try c.encode(dh, forKey: .dh)
        try c.encode(blocked, forKey: .blocked); try c.encode(saved, forKey: .saved)
        try c.encode(addedAt, forKey: .addedAt); try c.encode(profileV, forKey: .profileV)
        try c.encode(sentProfileV, forKey: .sentProfileV)
        if !jeton.isEmpty { try c.encode(jeton, forKey: .jeton) }
        if jetonSent { try c.encode(jetonSent, forKey: .jetonSent) }
    }
}

public struct Last: Codable, Equatable, Sendable {
    public var id = ""
    public var from = ""
    public var text = ""
    public var at: Double = 0
    public var kind = ""

    public init(id: String, from: String, text: String, at: Double, kind: String) {
        self.id = id; self.from = from; self.text = text; self.at = at; self.kind = kind
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.v(.id, ""); from = c.v(.from, ""); text = c.v(.text, ""); at = c.v(.at, 0); kind = c.v(.kind, "")
    }
}

public struct Chat: Codable, Equatable, Identifiable, Sendable {
    /// Le code du contact, ou `g:<id>` pour un groupe.
    public var id: String
    public var group = false
    public var name = ""
    public var avatar = ""
    public var members: [String] = []
    public var admins: [String] = []
    public var createdBy = ""
    public var createdAt: Double = 0
    public var lastAt: Double = 0
    public var unread = 0
    public var markedUnread = false
    public var pinned = false
    public var archived = false
    public var muted = false
    /// Messages éphémères : leur durée de vie en secondes (0 : désactivés).
    public var timer = 0
    public var draft = ""
    public var wallpaper = ""
    public var left = false
    public var v = 0
    public var last: Last?
    public var hasExp = false
    /// Le message épinglé en haut de la discussion, pour soi (vide : aucun).
    public var pinMsg = ""
    /// Les clés Brocolis 🥦 (leurs `id`) qui chiffrent ce qui part dans cette
    /// discussion, en plus de Talka. Vide : désactivé.
    public var brocolis: [String] = []
    /// Sourdine pour un temps : l'heure (ms) où elle se lève d'elle-même
    /// (absente : pour toujours, tant que `muted`).
    public var muteUntil: Double?

    enum CodingKeys: String, CodingKey {
        case id, group, name, avatar, members, admins, unread, pinned, archived, muted, timer, draft, wallpaper, left, v, last, brocolis
        case muteUntil = "mute_until"
        case createdBy = "created_by"
        case createdAt = "created_at"
        case lastAt = "last_at"
        case markedUnread = "marked_unread"
        case hasExp = "has_exp"
        case pinMsg = "pin_msg"
    }

    public init(id: String) {
        self.id = id
        createdAt = nowMs()
        lastAt = createdAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.v(.id, ""); group = c.v(.group, false); name = c.v(.name, ""); avatar = c.v(.avatar, "")
        members = c.v(.members, []); admins = c.v(.admins, []); createdBy = c.v(.createdBy, "")
        createdAt = c.v(.createdAt, 0); lastAt = c.v(.lastAt, 0); unread = c.v(.unread, 0)
        markedUnread = c.v(.markedUnread, false); pinned = c.v(.pinned, false); archived = c.v(.archived, false)
        muted = c.v(.muted, false); timer = c.v(.timer, 0); draft = c.v(.draft, ""); wallpaper = c.v(.wallpaper, "")
        left = c.v(.left, false); v = c.v(.v, 0); last = c.v(.last, nil); hasExp = c.v(.hasExp, false)
        pinMsg = c.v(.pinMsg, ""); brocolis = c.v(.brocolis, [])
        muteUntil = c.v(.muteUntil, nil)
    }
}

/// Une clé de Brocolis 🥦, partagée avec ses proches dans l'app Brocolis.
public struct BrocolisKey: Codable, Equatable, Identifiable, Sendable {
    public var id = ""
    public var name = ""
    /// 32 octets en base64.
    public var key = ""
    /// Remplacée par une clé neuve : elle ouvre encore les anciens messages,
    /// mais ne chiffre plus rien.
    public var retired = false

    public init(id: String, name: String, key: String, retired: Bool = false) {
        self.id = id; self.name = name; self.key = key; self.retired = retired
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.v(.id, ""); name = c.v(.name, ""); key = c.v(.key, ""); retired = c.v(.retired, false)
    }
}

public struct Reply: Codable, Equatable, Sendable {
    public var id = ""
    public var from = ""
    public var text = ""

    public init(id: String, from: String, text: String) {
        self.id = id; self.from = from; self.text = text
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.v(.id, ""); from = c.v(.from, ""); text = c.v(.text, "")
    }
}

/// Le contenu d'un message : ce qui est commun à l'envoi et au stockage.
public struct Content: Codable, Equatable, Sendable {
    /// `text`, `image`, `voice`, `file`, `contact`.
    public var kind = "text"
    public var text = ""
    /// Une photo, un vocal ou un fichier : une adresse `data:`.
    public var data = ""
    public var name = ""
    public var mime = ""
    public var size: Double = 0
    public var dur: Double = 0
    public var w = 0
    public var h = 0
    /// Une carte de contact : son code.
    public var code = ""
    public var reply: Reply?
    public var fwd = false
    /// Une capsule temporelle : à ouvrir à cette heure (ms). Absent sinon ;
    /// les versions qui ne la connaissent pas affichent le message.
    public var openAt: Double?

    enum CodingKeys: String, CodingKey {
        case kind, text, data, name, mime, size, dur, w, h, code, reply, fwd
        case openAt = "open_at"
    }

    public init(kind: String = "text", text: String = "", data: String = "", name: String = "", mime: String = "",
                size: Double = 0, dur: Double = 0, w: Int = 0, h: Int = 0, code: String = "", reply: Reply? = nil, fwd: Bool = false,
                openAt: Double? = nil) {
        self.kind = kind; self.text = text; self.data = data; self.name = name; self.mime = mime
        self.size = size; self.dur = dur; self.w = w; self.h = h; self.code = code; self.reply = reply; self.fwd = fwd
        self.openAt = openAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = c.v(.kind, "text"); text = c.v(.text, ""); data = c.v(.data, ""); name = c.v(.name, "")
        mime = c.v(.mime, ""); size = c.v(.size, 0); dur = c.v(.dur, 0); w = c.v(.w, 0); h = c.v(.h, 0)
        code = c.v(.code, ""); reply = c.v(.reply, nil); fwd = c.v(.fwd, false)
        openAt = c.v(.openAt, nil)
    }

    /// Seulement ce qui n'est pas vide (le navigateur complète avec les
    /// valeurs par défaut).
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(kind, forKey: .kind)
        if !text.isEmpty { try c.encode(text, forKey: .text) }
        if !data.isEmpty { try c.encode(data, forKey: .data) }
        if !name.isEmpty { try c.encode(name, forKey: .name) }
        if !mime.isEmpty { try c.encode(mime, forKey: .mime) }
        if size != 0 { try c.encode(size, forKey: .size) }
        if dur != 0 { try c.encode(dur, forKey: .dur) }
        if w != 0 { try c.encode(w, forKey: .w) }
        if h != 0 { try c.encode(h, forKey: .h) }
        if !code.isEmpty { try c.encode(code, forKey: .code) }
        try c.encodeIfPresent(reply, forKey: .reply)
        if fwd { try c.encode(fwd, forKey: .fwd) }
        try c.encodeIfPresent(openAt, forKey: .openAt)
    }
}

public struct Message: Codable, Equatable, Identifiable, Sendable {
    public var chat = ""
    public var id = ""
    /// Le code de l'auteur, ou `system`.
    public var from = ""
    public var at: Double = 0
    /// `text`, `image`, `voice`, `file`, `contact`, `system`.
    public var kind = "text"
    public var text = ""
    public var data = ""
    public var name = ""
    public var mime = ""
    public var size: Double = 0
    public var dur: Double = 0
    public var w = 0
    public var h = 0
    public var code = ""
    public var reply: Reply?
    public var fwd = false
    /// `pending`, `sent`, `failed` (les accusés sont calculés).
    public var status = ""
    public var to: [String] = []
    public var sentTo: [String] = []
    public var deliveredTo: [String] = []
    public var readBy: [String] = []
    public var reactions: [String: String] = [:]
    public var edited = false
    public var deleted = false
    public var starred = false
    public var exp: Double?
    public var readByMe = false
    /// Une capsule temporelle : scellée jusqu'à cette heure (ms).
    public var openAt: Double?
    /// Arrivé ou parti chiffré par Brocolis 🥦 (et ouvert sur cet appareil).
    public var bro = false

    enum CodingKeys: String, CodingKey {
        case chat, id, from, at, kind, text, data, name, mime, size, dur, w, h, code, reply, fwd, status, to, reactions, edited, deleted, starred, exp, bro
        case openAt = "open_at"
        case sentTo = "sent_to"
        case deliveredTo = "delivered_to"
        case readBy = "read_by"
        case readByMe = "read_by_me"
    }

    public init(chat: String, id: String, from: String, at: Double, content: Content) {
        self.chat = chat; self.id = id; self.from = from; self.at = at
        kind = content.kind; text = content.text; data = content.data; name = content.name; mime = content.mime
        size = content.size; dur = content.dur; w = content.w; h = content.h; code = content.code
        reply = content.reply; fwd = content.fwd; openAt = content.openAt
    }

    /// Une capsule temporelle (scellée ou déjà ouverte).
    public var isCapsule: Bool { (openAt ?? 0) > 0 }

    /// Une capsule encore scellée pour son destinataire ; l'auteur, lui,
    /// voit toujours ce qu'il a écrit.
    public func sealed(for me: String, now: Double = nowMs()) -> Bool {
        (openAt ?? 0) > now && from != me
    }

    /// L'œuf de Pâques 🥦, comme dans le navigateur : un texte qui contient
    /// 🥦 ou « brocoli » fait pleuvoir des brocolis. Une capsule scellée ne
    /// se trahit pas.
    public func summonsBrocoli(me: String, now: Double = nowMs()) -> Bool {
        guard kind == "text", !deleted, !sealed(for: me, now: now) else { return false }
        // Pas un jeton Brocolis resté scellé : il ne dit rien de ce qu'il cache.
        let text = Brocolis.masked(text).replacingOccurrences(of: "🔒🥦", with: "")
        return text.contains("🥦")
            || text.range(of: "brocoli", options: [.caseInsensitive, .diacriticInsensitive]) != nil
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        chat = c.v(.chat, ""); id = c.v(.id, ""); from = c.v(.from, ""); at = c.v(.at, 0); kind = c.v(.kind, "text")
        text = c.v(.text, ""); data = c.v(.data, ""); name = c.v(.name, ""); mime = c.v(.mime, "")
        size = c.v(.size, 0); dur = c.v(.dur, 0); w = c.v(.w, 0); h = c.v(.h, 0); code = c.v(.code, "")
        reply = c.v(.reply, nil); fwd = c.v(.fwd, false); status = c.v(.status, ""); to = c.v(.to, [])
        sentTo = c.v(.sentTo, []); deliveredTo = c.v(.deliveredTo, []); readBy = c.v(.readBy, [])
        reactions = c.v(.reactions, [:]); edited = c.v(.edited, false); deleted = c.v(.deleted, false)
        starred = c.v(.starred, false); exp = c.v(.exp, nil); readByMe = c.v(.readByMe, false)
        openAt = c.v(.openAt, nil); bro = c.v(.bro, false)
    }

    /// `chat|id` : la clé dans le stockage.
    public var key: String { "\(chat)|\(id)" }

    public var content: Content {
        Content(kind: kind, text: text, data: data, name: name, mime: mime, size: size, dur: dur, w: w, h: h, code: code, reply: reply, fwd: fwd,
                openAt: openAt)
    }

    public enum Tick: String, Sendable { case pending, sent, delivered, read, failed }

    /// L'état d'un message envoyé.
    public var tick: Tick {
        if status == "failed" { return .failed }
        if to.isEmpty { return .sent }
        if to.allSatisfy(readBy.contains) { return .read }
        if to.allSatisfy(deliveredTo.contains) { return .delivered }
        if to.allSatisfy(sentTo.contains) { return .sent }
        return .pending
    }
}

/// Une enveloppe qui attend de partir.
public struct OutItem: Codable, Equatable, Sendable {
    public var id = ""
    public var to = ""
    /// Le `Wire`, en JSON : chiffré au moment de partir.
    public var payload = ""
    public var chat = ""
    public var msgId = ""
    public var seq: Double = 0
    /// Le démarrage du relais qui a accepté ce message (`ready.boot`) : il est
    /// gardé jusqu'à l'accusé de réception, et renvoyé si le relais a
    /// redémarré entre-temps. Vide : pas encore parti.
    public var boot = ""

    enum CodingKeys: String, CodingKey {
        case id, to, payload, chat, seq, boot
        case msgId = "msg_id"
    }

    public init(id: String, to: String, payload: String, chat: String, msgId: String, seq: Double) {
        self.id = id; self.to = to; self.payload = payload; self.chat = chat; self.msgId = msgId; self.seq = seq
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.v(.id, ""); to = c.v(.to, ""); payload = c.v(.payload, ""); chat = c.v(.chat, "")
        msgId = c.v(.msgId, ""); seq = c.v(.seq, 0); boot = c.v(.boot, "")
    }
}

public struct StatusItem: Codable, Equatable, Identifiable, Sendable {
    public var owner = ""
    public var id = ""
    /// `text` ou `image`.
    public var kind = "text"
    public var text = ""
    public var bg = ""
    public var data = ""
    public var at: Double = 0
    public var seen = false
    public var viewers: [String: Double] = [:]

    public var key: String { "\(owner)|\(id)" }

    public init(owner: String, id: String, kind: String, text: String, bg: String, data: String, at: Double) {
        self.owner = owner; self.id = id; self.kind = kind; self.text = text; self.bg = bg; self.data = data; self.at = at
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        owner = c.v(.owner, ""); id = c.v(.id, ""); kind = c.v(.kind, "text"); text = c.v(.text, "")
        bg = c.v(.bg, ""); data = c.v(.data, ""); at = c.v(.at, 0); seen = c.v(.seen, false); viewers = c.v(.viewers, [:])
    }
}

public struct CallEntry: Codable, Equatable, Identifiable, Sendable {
    public var id = ""
    public var peer = ""
    public var video = false
    public var incoming = false
    public var at: Double = 0
    public var dur: Double = 0
    public var missed = false

    public init(id: String, peer: String, video: Bool, incoming: Bool, at: Double, dur: Double, missed: Bool) {
        self.id = id; self.peer = peer; self.video = video; self.incoming = incoming; self.at = at; self.dur = dur; self.missed = missed
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.v(.id, ""); peer = c.v(.peer, ""); video = c.v(.video, false); incoming = c.v(.incoming, false)
        at = c.v(.at, 0); dur = c.v(.dur, 0); missed = c.v(.missed, false)
    }
}

/// Ce qui décrit un groupe, joint à chaque envoi dans ce groupe.
public struct GroupInfo: Codable, Equatable, Sendable {
    public var id = ""
    public var name = ""
    public var members: [String] = []
    public var admins: [String] = []
    public var avatar = ""
    public var v = 0

    public init(id: String, name: String, members: [String], admins: [String], avatar: String, v: Int) {
        self.id = id; self.name = name; self.members = members; self.admins = admins; self.avatar = avatar; self.v = v
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.v(.id, ""); name = c.v(.name, ""); members = c.v(.members, []); admins = c.v(.admins, [])
        avatar = c.v(.avatar, ""); v = c.v(.v, 0)
    }
}

/// Ce qui voyage, chiffré, d'un appareil à l'autre (`k` en donne la sorte).
public enum Body: Equatable, Sendable {
    case msg(id: String, at: Double, pn: String, content: Content)
    /// `st` : `delivered` ou `read`.
    case receipt(st: String, ids: [String])
    case react(id: String, emoji: String)
    case edit(id: String, text: String)
    case delete(id: String)
    case typing(on: Bool)
    /// `jeton` : le jeton de remise de l'expéditeur (expéditeur scellé).
    case profile(Profile, jeton: String = "")
    case group(op: String, note: String)
    case timer(secs: Int)
    case status(id: String, kind: String, text: String, bg: String, data: String, at: Double)
    case statusSeen(id: String)
    case statusDelete(id: String)
    case call(op: String, callId: String, video: Bool, sdp: String, cand: JSONValue?)
    /// Une sorte inconnue (une version plus récente) : ignorée.
    case unknown(String)
}

/// Une enveloppe : le contenu, et la discussion à laquelle il appartient.
public struct Wire: Codable, Equatable, Sendable {
    public var body: Body
    /// Le groupe (décrit en entier).
    public var grp: GroupInfo?
    /// Le groupe (seulement son identifiant : accusés).
    public var g: String?
    /// Messages éphémères : la durée de vie, en secondes.
    public var ttl: Int?

    public init(_ body: Body, grp: GroupInfo? = nil, g: String? = nil, ttl: Int? = nil) {
        self.body = body; self.grp = grp; self.g = g; self.ttl = ttl
    }

    /// L'identifiant du groupe, s'il y en a un.
    public var groupId: String? { grp?.id ?? g }

    struct K: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(_ s: String) { stringValue = s }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        func s(_ k: String) -> String { c.v(K(k), "") }
        let kind = s("k")
        switch kind {
        case "msg":
            body = .msg(id: s("id"), at: c.v(K("at"), 0), pn: s("pn"), content: try Content(from: decoder))
        case "receipt": body = .receipt(st: s("st"), ids: c.v(K("ids"), []))
        case "react": body = .react(id: s("id"), emoji: s("emoji"))
        case "edit": body = .edit(id: s("id"), text: s("text"))
        case "delete": body = .delete(id: s("id"))
        case "typing": body = .typing(on: c.v(K("on"), false))
        case "profile": body = .profile(try Profile(from: decoder), jeton: s("jeton"))
        case "group": body = .group(op: s("op"), note: s("note"))
        case "timer": body = .timer(secs: c.v(K("secs"), 0))
        case "status":
            body = .status(id: s("id"), kind: s("type"), text: s("text"), bg: s("bg"), data: s("data"), at: c.v(K("at"), 0))
        case "status-seen": body = .statusSeen(id: s("id"))
        case "status-delete": body = .statusDelete(id: s("id"))
        case "call":
            body = .call(op: s("op"), callId: s("call_id"), video: c.v(K("video"), false), sdp: s("sdp"), cand: c.v(K("cand"), nil))
        default: body = .unknown(kind)
        }
        grp = c.v(K("grp"), nil)
        g = c.v(K("g"), nil)
        ttl = c.v(K("ttl"), nil)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        func put<T: Encodable>(_ k: String, _ v: T) throws { try c.encode(v, forKey: K(k)) }
        switch body {
        case let .msg(id, at, pn, content):
            try put("k", "msg"); try put("id", id); try put("at", at)
            if !pn.isEmpty { try put("pn", pn) }
            try content.encode(to: encoder)
        case let .receipt(st, ids): try put("k", "receipt"); try put("st", st); try put("ids", ids)
        case let .react(id, emoji): try put("k", "react"); try put("id", id); try put("emoji", emoji)
        case let .edit(id, text): try put("k", "edit"); try put("id", id); try put("text", text)
        case let .delete(id): try put("k", "delete"); try put("id", id)
        case let .typing(on): try put("k", "typing"); try put("on", on)
        case let .profile(p, jeton):
            try put("k", "profile"); try p.encode(to: encoder)
            if !jeton.isEmpty { try put("jeton", jeton) }
        case let .group(op, note): try put("k", "group"); try put("op", op); try put("note", note)
        case let .timer(secs): try put("k", "timer"); try put("secs", secs)
        case let .status(id, kind, text, bg, data, at):
            try put("k", "status"); try put("id", id); try put("type", kind); try put("text", text)
            try put("bg", bg); try put("data", data); try put("at", at)
        case let .statusSeen(id): try put("k", "status-seen"); try put("id", id)
        case let .statusDelete(id): try put("k", "status-delete"); try put("id", id)
        case let .call(op, callId, video, sdp, cand):
            try put("k", "call"); try put("op", op); try put("call_id", callId); try put("video", video)
            try put("sdp", sdp)
            if let cand { try put("cand", cand) }
        case let .unknown(k): try put("k", k)
        }
        if let grp { try put("grp", grp) }
        if let g { try put("g", g) }
        if let ttl { try put("ttl", ttl) }
    }

    public func json() -> String {
        (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    public static func parse(_ text: String) -> Wire? {
        try? JSONDecoder().decode(Wire.self, from: Data(text.utf8))
    }
}

/// L'heure en millisecondes, comme `Date.now()`.
public func nowMs() -> Double { Date().timeIntervalSince1970 * 1000 }

/// JSON ↔ structures, comme serde_json.
public enum JSON {
    public static func encode<T: Encodable>(_ v: T) -> String {
        (try? JSONEncoder().encode(v)).flatMap { String(data: $0, encoding: .utf8) } ?? "null"
    }

    public static func decode<T: Decodable>(_ type: T.Type, _ text: String) -> T? {
        try? JSONDecoder().decode(type, from: Data(text.utf8))
    }
}
