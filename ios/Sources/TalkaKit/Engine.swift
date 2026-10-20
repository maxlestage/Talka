// Le cœur de Talka sur l'iPhone : contacts, discussions, messages, groupes,
// statuts. La même logique que le navigateur (`client/src/core.rs`) : un
// iPhone et un navigateur discutent ensemble, dans les mêmes groupes.
//
// Un message part en autant d'enveloppes que de destinataires : chacune est
// chiffrée pour son destinataire au moment de partir. En attendant, elle est
// gardée dans `outbox` : un message écrit hors ligne part au retour du réseau.

import Foundation
import Observation

public let dayMs: Double = 86_400_000

@MainActor
@Observable
public final class Engine {
    public private(set) var me: Identity
    public private(set) var profile: Profile
    public private(set) var settings: Settings
    public private(set) var contacts: [String: Contact] = [:]
    public private(set) var chats: [String: Chat] = [:]
    /// code → (en ligne, vu pour la dernière fois).
    public private(set) var presence: [String: (online: Bool, seen: Double?)] = [:]
    /// discussion → codes en train d'écrire.
    public private(set) var typing: [String: Set<String>] = [:]
    public private(set) var netState: Relay.State = .offline
    /// Augmente à chaque changement des messages d'une discussion.
    public private(set) var revisions: [String: Int] = [:]
    public private(set) var statusRevision = 0
    public private(set) var callRevision = 0
    /// La discussion à l'écran (ses messages arrivent lus).
    public var activeChat: String?
    /// L'application est-elle au premier plan ?
    public var foreground = true

    /// Un message arrive : (discussion, message, déjà à l'écran).
    @ObservationIgnored public var onIncoming: ((Chat, Message, Bool) -> Void)?
    /// Quelqu'un réagit à un de vos messages.
    @ObservationIgnored public var onReaction: ((Chat, String, String) -> Void)?
    /// Un signal d'appel (offre, réponse, candidat…).
    @ObservationIgnored public var onCallSignal: ((String, Body, Bool) -> Void)?
    /// Le relais a refusé un message trop lourd (il est marqué en échec).
    @ObservationIgnored public var onTooLarge: (() -> Void)?

    @ObservationIgnored public let store: Store
    @ObservationIgnored public let relay: Relay
    @ObservationIgnored var flushing = false
    @ObservationIgnored var flushAgain = false
    @ObservationIgnored var seq = 0
    @ObservationIgnored var typingSent: Double = 0
    @ObservationIgnored var typingTimers: [String: Task<Void, Never>] = [:]
    @ObservationIgnored var receipts: [String: Set<String>] = [:]
    @ObservationIgnored var receiptTask: Task<Void, Never>?
    @ObservationIgnored var sweeper: Task<Void, Never>?

    // ----- Le compte -----

    /// Une identité est-elle déjà gardée ?
    public static func hasAccount(_ store: Store) -> Bool {
        store.read("kv", "identity", as: Identity.self)?.isValid ?? false
    }

    /// Crée l'identité et le profil.
    public static func createAccount(_ store: Store, name: String, avatar: String) {
        store.write("kv", "identity", Identity.create())
        store.write("kv", "profile", Profile(name: String(name.trimmingCharacters(in: .whitespaces).prefix(40)), about: "Disponible sur Talka", avatar: avatar, v: 1))
        store.save()
    }

    public init(store: Store, server: URL) {
        self.store = store
        let identity = store.read("kv", "identity", as: Identity.self) ?? Identity.create()
        me = identity
        profile = store.read("kv", "profile", as: Profile.self) ?? Profile()
        settings = store.read("kv", "settings", as: Settings.self) ?? Settings()
        relay = Relay(identity: identity, server: server)
        for c in store.all("contacts", as: Contact.self) { contacts[c.code] = c }
        for c in store.all("chats", as: Chat.self) { chats[c.id] = c }
        relay.hidden = settings.hideOnline
        relay.onState = { [weak self] s in self?.onNet(s) }
        relay.onEnvelope = { [weak self] e in self?.onEnvelope(e) }
        relay.onPresence = { [weak self] code, online, seen in
            self?.presence[code] = (online, seen)
        }
    }

    public func start() {
        relay.start()
        sweep()
        sweeper = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                self?.sweep()
            }
        }
    }

    public func stop() {
        sweeper?.cancel()
        relay.stop()
        store.save()
    }

    func onNet(_ s: Relay.State) {
        netState = s
        if s == .online {
            watchContacts()
            Task { await flush() }
            Task { await refreshMissingKeys() }
        }
    }

    func watchContacts() {
        var codes = Set(contacts.keys)
        for c in chats.values {
            if c.group { codes.formUnion(c.members) } else { codes.insert(c.id) }
        }
        codes.remove(me.code)
        relay.watch(Array(codes))
    }

    func bump(_ chat: String) { revisions[chat, default: 0] += 1 }

    /// Les messages éphémères échus et les statuts de plus de 24 heures.
    public func sweep() {
        let t = nowMs()
        for chat in chats.values where chat.timer > 0 || chat.hasExp {
            var changed = false
            for m in store.messagesOf(chat.id) where (m.exp ?? .infinity) < t {
                store.removeMessage(chat.id, m.id)
                changed = true
            }
            if changed { refreshLast(chat.id); bump(chat.id) }
        }
        for s in store.all("statuses", as: StatusItem.self) where s.at + dayMs < t {
            store.remove("statuses", s.key)
        }
        statusRevision += 1
    }

    public func saveSettings(_ f: (inout Settings) -> Void) {
        f(&settings)
        store.write("kv", "settings", settings)
        relay.hidden = settings.hideOnline
    }

    public func saveProfile(_ f: (inout Profile) -> Void) {
        f(&profile)
        profile.v += 1
        store.write("kv", "profile", profile)
        for c in contacts.values where c.saved && !c.blocked {
            enqueue(c.code, Wire(.profile(profile)))
        }
        Task { await flush() }
    }

    // ----- Lecture -----

    public func contact(_ code: String) -> Contact? { contacts[code] }
    public func chat(_ id: String) -> Chat? { chats[id] }
    public func isGroup(_ id: String) -> Bool { id.hasPrefix("g:") }
    public func blocked(_ code: String) -> Bool { contacts[code]?.blocked ?? false }

    public func displayName(_ code: String) -> String {
        if code == me.code { return "Vous" }
        if let c = contacts[code] {
            if !c.name.isEmpty { return c.name }
            if !c.profileName.isEmpty { return "~\(c.profileName)" }
        }
        return TalkaCode.pretty(code)
    }

    public func avatarOf(_ code: String) -> String {
        code == me.code ? profile.avatar : contacts[code]?.avatar ?? ""
    }

    public func title(_ chat: Chat) -> String {
        chat.group ? (chat.name.isEmpty ? "Groupe" : chat.name) : displayName(chat.id)
    }

    public func recipients(_ chat: Chat) -> [String] {
        chat.group ? chat.members.filter { $0 != me.code } : [chat.id]
    }

    public func messages(_ chat: String, limit: Int = .max) -> [Message] {
        store.messagesOf(chat, limit: limit)
    }

    // ----- Contacts -----

    func saveContact(_ c: Contact) {
        contacts[c.code] = c
        store.write("contacts", c.code, c)
    }

    /// Les clés d'un code, vérifiées : le code doit être leur empreinte.
    func keys(for code: String) async -> Contact? {
        if let c = contacts[code], !c.sign.isEmpty, !c.dh.isEmpty { return c }
        guard let found = await relay.lookup(code),
              let sign = found["sign"] as? String, let dh = found["dh"] as? String,
              TalkaCrypto.verifyKeys(code: code, sign: sign, dh: dh)
        else { return nil }
        // Relu après l'attente : un nom donné entre-temps n'est pas écrasé.
        var c = contacts[code] ?? Contact(code: code)
        c.sign = sign
        c.dh = dh
        saveContact(c)
        return c
    }

    func refreshMissingKeys() async {
        for c in contacts.values where c.sign.isEmpty {
            if await keys(for: c.code) != nil { await flush() }
        }
    }

    public enum AddError: Error, LocalizedError {
        case yourself
        public var errorDescription: String? { "C'est votre propre code 🙂" }
    }

    /// Ajoute un contact par son code ; `true` s'il est joignable tout de suite.
    @discardableResult
    public func addContact(_ code: String, name: String) async throws -> Bool {
        guard code != me.code else { throw AddError.yourself }
        var c = contacts[code] ?? Contact(code: code)
        c.saved = true
        let n = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        if !n.isEmpty { c.name = n }
        saveContact(c)
        relay.watch([code])
        let reachable = await keys(for: code) != nil
        enqueue(code, Wire(.profile(profile)))
        Task { await flush() }
        return reachable
    }

    public func updateContact(_ code: String, _ f: (inout Contact) -> Void) {
        guard var c = contacts[code] else { return }
        f(&c)
        saveContact(c)
    }

    public func setBlocked(_ code: String, _ blocked: Bool) {
        var c = contacts[code] ?? Contact(code: code)
        c.blocked = blocked
        saveContact(c)
    }

    // ----- Discussions -----

    func saveChat(_ c: Chat) {
        chats[c.id] = c
        store.write("chats", c.id, c)
    }

    @discardableResult
    public func ensureDirectChat(_ code: String) -> Chat {
        if let c = chats[code] { return c }
        let c = Chat(id: code)
        saveChat(c)
        relay.watch([code])
        return c
    }

    public func updateChat(_ id: String, _ f: (inout Chat) -> Void) {
        guard var c = chats[id] else { return }
        f(&c)
        saveChat(c)
    }

    public func deleteChat(_ id: String) {
        store.deleteMessages(of: id)
        store.remove("chats", id)
        chats[id] = nil
        bump(id)
    }

    public func clearChat(_ id: String) {
        store.deleteMessages(of: id)
        updateChat(id) { $0.last = nil; $0.unread = 0 }
        bump(id)
    }

    public static func preview(_ m: Message) -> String {
        if m.deleted { return "🚫 Message supprimé" }
        switch m.kind {
        case "image": return "📷 \(m.text.isEmpty ? "Photo" : m.text)"
        case "voice": return "🎙️ Message vocal (\(formatDuration(m.dur)))"
        case "file": return "📄 \(m.name.isEmpty ? "Fichier" : m.name)"
        case "contact": return "👤 \(m.name.isEmpty ? "Contact" : m.name)"
        default: return m.text
        }
    }

    func lastOf(_ m: Message) -> Last {
        Last(id: m.id, from: m.from, text: Self.preview(m), at: m.at, kind: m.kind)
    }

    func refreshLast(_ chat: String) {
        let last = store.messagesOf(chat, limit: 1).last
        updateChat(chat) { $0.last = last.map(lastOf) }
    }

    func storeMessage(_ m: Message, bump bumpChat: Bool = true) {
        store.writeMessage(m)
        if bumpChat, var c = chats[m.chat] {
            c.last = lastOf(m)
            c.lastAt = max(c.lastAt, m.at)
            if c.archived, m.from != me.code, !c.muted { c.archived = false }
            if m.exp != nil { c.hasExp = true }
            saveChat(c)
        }
        bump(m.chat)
    }

    // ----- Envoi -----

    /// Une enveloppe à envoyer à `to`, gardée jusqu'à ce que le relais confirme ;
    /// `live` : seulement si le destinataire est en ligne, sans garde.
    func enqueue(_ to: String, _ wire: Wire, chat: String = "", msgId: String = "", live: Bool = false) {
        seq += 1
        let item = OutItem(
            id: "\(msgId.isEmpty ? randomId(10) : msgId):\(to)", to: to, payload: wire.json(),
            chat: chat, msgId: msgId, seq: nowMs() * 1000 + Double(seq % 1000))
        if live {
            Task { _ = await sendNow(item, live: true) }
            return
        }
        store.write("outbox", item.id, item)
        Task { await flush() }
    }

    func sendNow(_ item: OutItem, live: Bool) async -> Relay.Ack {
        guard let peer = await keys(for: item.to) else { return .failed("unknown") }
        guard let body = TalkaCrypto.seal(me, peerCode: peer.code, peerDh: peer.dh, plain: item.payload) else { return .failed("crypto") }
        return await relay.send(id: item.id, to: item.to, body: body, live: live)
    }

    /// Envoie ce qui attend dans la boîte d'envoi.
    public func flush() async {
        guard relay.state == .online else { return }
        if flushing { flushAgain = true; return }
        flushing = true
        defer { flushing = false }
        repeat {
            flushAgain = false
            let items = store.all("outbox", as: OutItem.self).sorted { $0.seq < $1.seq }
            let boot = relay.boot
            for var item in items {
                guard relay.state == .online else { return }
                // Déjà accepté par le relais : on attend l'accusé de réception,
                // sauf si le relais a redémarré depuis (sa boîte aux lettres,
                // en mémoire, est perdue) : alors on renvoie.
                if !item.boot.isEmpty {
                    if !stillUnconfirmed(item) {
                        store.remove("outbox", item.id)
                        continue
                    }
                    if item.boot == boot || boot.isEmpty { continue }
                }
                switch await sendNow(item, live: false) {
                case .ok:
                    if !item.msgId.isEmpty, !boot.isEmpty {
                        item.boot = boot
                        store.write("outbox", item.id, item)
                    } else {
                        store.remove("outbox", item.id)
                    }
                    if !item.msgId.isEmpty { markSent(item.chat, item.msgId, item.to) }
                case .failed(let e) where e == "size" || e == "code":
                    store.remove("outbox", item.id)
                    if !item.msgId.isEmpty {
                        markFailed(item.chat, item.msgId)
                        if e == "size" { onTooLarge?() }
                    }
                case .failed(let e) where e == "offline" || e == "timeout":
                    return
                case .failed:
                    // « unknown » : le destinataire n'a pas rouvert Talka depuis
                    // le redémarrage du relais ; l'enveloppe attend ses clés.
                    break
                }
            }
        } while flushAgain
    }

    /// Un message accepté par le relais attend-il encore l'accusé de réception
    /// de ce destinataire ? Au-delà de 30 jours (la durée des boîtes aux
    /// lettres), on n'insiste plus.
    func stillUnconfirmed(_ item: OutItem) -> Bool {
        guard let m = store.message(item.chat, item.msgId) else { return false }
        return !m.deleted && !m.deliveredTo.contains(item.to) && nowMs() - m.at < 30 * dayMs
    }

    func markSent(_ chat: String, _ id: String, _ to: String) {
        guard var m = store.message(chat, id) else { return }
        if !m.sentTo.contains(to) { m.sentTo.append(to) }
        if m.tick != .pending, m.status == "pending" { m.status = "sent" }
        store.writeMessage(m)
        bump(chat)
    }

    func markFailed(_ chat: String, _ id: String) {
        guard var m = store.message(chat, id) else { return }
        m.status = "failed"
        store.writeMessage(m)
        bump(chat)
    }

    func groupInfo(_ c: Chat) -> GroupInfo {
        GroupInfo(id: String(c.id.dropFirst(2)), name: c.name, members: c.members, admins: c.admins, avatar: c.avatar, v: max(c.v, 1))
    }

    /// Ce qui accompagne chaque envoi : le groupe, la durée de vie des
    /// messages éphémères.
    func envelope(_ c: Chat, _ body: Body) -> Wire {
        Wire(body, grp: c.group ? groupInfo(c) : nil, ttl: c.timer > 0 ? c.timer : nil)
    }

    /// Envoie un message dans une discussion.
    @discardableResult
    public func send(_ chatId: String, _ content: Content) -> Message? {
        guard let c = chats[chatId] else { return nil }
        let id = randomId(12)
        let at = relay.now()
        let to = recipients(c).filter { !blocked($0) }
        var m = Message(chat: chatId, id: id, from: me.code, at: at, content: content)
        m.status = to.isEmpty ? "sent" : "pending"
        m.to = to
        if c.timer > 0 { m.exp = at + Double(c.timer) * 1000 }
        storeMessage(m)
        // Le nom choisi voyage avec le message : un membre de groupe qui ne
        // vous connaît pas voit « ~Nom » plutôt qu'un code.
        let wire = envelope(c, .msg(id: id, at: at, pn: profile.name, content: content))
        for code in to { enqueue(code, wire, chat: chatId, msgId: id) }
        if !(chats[chatId]?.draft.isEmpty ?? true) { updateChat(chatId) { $0.draft = "" } }
        return m
    }

    func broadcast(_ c: Chat, _ body: Body, live: Bool = false) {
        let wire = envelope(c, body)
        for code in recipients(c) where !blocked(code) { enqueue(code, wire, live: live) }
    }

    public func react(_ chat: String, _ id: String, _ emoji: String) {
        guard let c = chats[chat], var m = store.message(chat, id) else { return }
        let e = m.reactions[me.code] == emoji ? "" : emoji
        if e.isEmpty { m.reactions[me.code] = nil } else { m.reactions[me.code] = e }
        store.writeMessage(m)
        bump(chat)
        broadcast(c, .react(id: id, emoji: e))
    }

    public func edit(_ chat: String, _ id: String, text: String) {
        guard let c = chats[chat], var m = store.message(chat, id), m.from == me.code else { return }
        m.text = text
        m.edited = true
        store.writeMessage(m)
        refreshLast(chat)
        bump(chat)
        broadcast(c, .edit(id: id, text: text))
    }

    public func delete(_ chat: String, _ id: String, forEveryone: Bool) {
        guard let c = chats[chat], var m = store.message(chat, id) else { return }
        if forEveryone, m.from == me.code {
            m.deleted = true
            m.text = ""; m.data = ""; m.reactions = [:]
            store.writeMessage(m)
            broadcast(c, .delete(id: id))
        } else {
            store.removeMessage(chat, id)
        }
        refreshLast(chat)
        bump(chat)
    }

    public func toggleStar(_ chat: String, _ id: String) {
        guard var m = store.message(chat, id) else { return }
        m.starred.toggle()
        store.writeMessage(m)
        bump(chat)
    }

    /// Épingle un message en haut de sa discussion ("" : retire l'épingle).
    /// Gardé sur l'appareil, pas envoyé aux autres (comme le navigateur).
    public func setPin(_ chat: String, _ id: String) {
        updateChat(chat) { $0.pinMsg = id }
    }

    /// Le message épinglé d'une discussion, s'il existe encore.
    public func pinnedMessage(_ chat: String) -> Message? {
        guard let id = chats[chat]?.pinMsg, !id.isEmpty, let m = store.message(chat, id), !m.deleted else { return nil }
        if let exp = m.exp, exp <= nowMs() { return nil }
        return m
    }

    public func starred() -> [Message] {
        store.allMessages().filter(\.starred).sorted { $0.at > $1.at }
    }

    public func forward(_ m: Message, to chats: [String]) {
        var content = m.content
        content.reply = nil
        content.fwd = true
        for id in chats { send(id, content) }
    }

    public func setTyping(_ chat: String, _ on: Bool) {
        guard let c = chats[chat], relay.state == .online else { return }
        let t = nowMs()
        if on, t - typingSent < 2500 { return }
        typingSent = on ? t : 0
        broadcast(c, .typing(on: on), live: true)
    }

    public func setTimer(_ chat: String, secs: Int) {
        updateChat(chat) { $0.timer = secs }
        systemMessage(chat, timerText(me.code, secs))
        if let c = chats[chat] { broadcast(c, .timer(secs: secs)) }
    }

    func timerText(_ code: String, _ secs: Int) -> String {
        let who = code == me.code ? "Vous avez" : "\(displayName(code)) a"
        if secs == 0 { return "⏳ \(who) désactivé les messages éphémères." }
        let label = secs >= 90 * 86400 ? "90 jours" : secs >= 7 * 86400 ? "7 jours" : "24 heures"
        return "⏳ \(who) activé les messages éphémères : \(label)."
    }

    func systemMessage(_ chat: String, _ text: String, at: Double = nowMs()) {
        var m = Message(chat: chat, id: randomId(12), from: "system", at: at, content: Content(kind: "system", text: text))
        m.kind = "system"
        storeMessage(m)
    }

    // ----- Groupes -----

    @discardableResult
    public func createGroup(name: String, members: [String], avatar: String) -> Chat {
        var c = Chat(id: "g:\(randomId(12))")
        c.group = true
        c.name = String(name.trimmingCharacters(in: .whitespaces).prefix(60))
        c.avatar = avatar
        c.members = [me.code] + members
        c.admins = [me.code]
        c.createdBy = me.code
        c.v = 1
        saveChat(c)
        systemMessage(c.id, "Vous avez créé le groupe « \(c.name) ».")
        broadcast(c, .group(op: "create", note: ""))
        return c
    }

    public func updateGroup(_ id: String, note: String, _ f: (inout Chat) -> Void) {
        guard let before = chats[id] else { return }
        var next = before
        f(&next)
        next.v = max(before.v, 1) + 1
        saveChat(next)
        if !note.isEmpty { systemMessage(id, note) }
        broadcast(next, .group(op: "update", note: note))
        // Les membres retirés l'apprennent aussi.
        for code in before.members where !next.members.contains(code) && code != me.code {
            enqueue(code, Wire(.group(op: "update", note: note), grp: groupInfo(next)))
        }
    }

    public func leaveGroup(_ id: String) {
        guard var c = chats[id] else { return }
        c.members.removeAll { $0 == me.code }
        c.admins.removeAll { $0 == me.code }
        if c.admins.isEmpty, let first = c.members.first { c.admins = [first] }
        c.v = max(c.v, 1) + 1
        c.left = true
        for code in c.members { enqueue(code, Wire(.group(op: "leave", note: ""), grp: groupInfo(c))) }
        saveChat(c)
        systemMessage(id, "Vous avez quitté le groupe.")
    }

    // ----- Réception -----

    func queueReceipt(_ to: String, _ chat: String, _ ids: [String], _ st: String) {
        receipts["\(to)|\(st)|\(chat)", default: []].formUnion(ids)
        receiptTask?.cancel()
        receiptTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard let self, !Task.isCancelled else { return }
            let batch = self.receipts
            self.receipts = [:]
            for (key, ids) in batch {
                let parts = key.split(separator: "|", maxSplits: 2).map(String.init)
                guard parts.count == 3 else { continue }
                let chat = parts[2]
                self.enqueue(parts[0], Wire(.receipt(st: parts[1], ids: Array(ids)), g: self.isGroup(chat) ? String(chat.dropFirst(2)) : nil))
            }
        }
    }

    /// Marque une discussion comme lue (accusés de lecture bleus).
    public func markRead(_ chat: String) {
        guard let c = chats[chat] else { return }
        var bySender: [String: [String]] = [:]
        for var m in store.messagesOf(chat, limit: 300) where m.from != me.code && m.from != "system" && !m.readByMe {
            m.readByMe = true
            store.writeMessage(m)
            bySender[m.from, default: []].append(m.id)
        }
        if settings.readReceipts {
            for (code, ids) in bySender { queueReceipt(code, chat, ids, "read") }
        }
        if c.unread > 0 || c.markedUnread { updateChat(chat) { $0.unread = 0; $0.markedUnread = false } }
    }

    func onEnvelope(_ env: [String: Any]) {
        let n = env["n"] as? Int
        Task { @MainActor in
            await receive(env)
            if let n { relay.got([n]) }
        }
    }

    func receive(_ env: [String: Any]) async {
        let from = env["from"] as? String ?? ""
        guard !from.isEmpty, !blocked(from), let peer = await keys(for: from),
              let plain = TalkaCrypto.open(me, peerCode: peer.code, peerDh: peer.dh, body: env["body"] as? String ?? ""),
              let wire = Wire.parse(plain)
        else { return }
        let ts = env["ts"] as? Double ?? nowMs()
        handle(from, wire, ts: ts, live: env["live"] as? Bool ?? false)
    }

    func groupChat(_ from: String, _ grp: GroupInfo, at: Double) -> Chat? {
        let id = "g:\(grp.id)"
        guard var c = chats[id] else {
            guard grp.members.contains(me.code) else { return nil }
            var c = Chat(id: id)
            c.group = true
            c.name = grp.name; c.avatar = grp.avatar; c.members = grp.members; c.admins = grp.admins
            c.createdBy = from; c.v = grp.v
            saveChat(c)
            systemMessage(id, "\(displayName(from)) vous a ajouté au groupe « \(grp.name) ».", at: at)
            relay.watch(grp.members.filter { $0 != me.code })
            return c
        }
        if grp.v > c.v {
            let wasIn = c.members.contains(me.code)
            c.name = grp.name; c.avatar = grp.avatar; c.members = grp.members; c.admins = grp.admins; c.v = grp.v
            if grp.members.contains(me.code) { c.left = false } else if wasIn { c.left = true }
            saveChat(c)
        }
        return c
    }

    func handle(_ from: String, _ wire: Wire, ts: Double, live: Bool) {
        switch wire.body {
        case .profile(let p):
            var c = contacts[from] ?? Contact(code: from)
            if p.v >= c.profileV {
                c.profileName = String(p.name.prefix(40))
                c.about = String(p.about.prefix(140))
                c.avatar = p.avatar.count <= 60_000 ? p.avatar : ""
                c.profileV = p.v
                saveContact(c)
            }
            return
        case .typing(let on):
            let chat = wire.groupId.map { "g:\($0)" } ?? from
            guard chats[chat] != nil else { return }
            let key = "\(chat)|\(from)"
            typingTimers[key]?.cancel()
            if on {
                typing[chat, default: []].insert(from)
                typingTimers[key] = Task { @MainActor [weak self] in
                    try? await Task.sleep(nanoseconds: 6_000_000_000)
                    guard !Task.isCancelled else { return }
                    self?.typing[chat]?.remove(from)
                }
            } else {
                typing[chat]?.remove(from)
            }
            return
        case .call:
            onCallSignal?(from, wire.body, live)
            return
        case let .status(id, kind, text, bg, data, at):
            guard contacts[from]?.saved == true, ts - at <= dayMs else { return }
            let st = StatusItem(owner: from, id: id, kind: kind, text: String(text.prefix(700)), bg: bg, data: data, at: at)
            store.write("statuses", st.key, st)
            statusRevision += 1
            return
        case .statusSeen(let id):
            let key = "\(me.code)|\(id)"
            if var st = store.read("statuses", key, as: StatusItem.self) {
                st.viewers[from] = ts
                store.write("statuses", key, st)
                statusRevision += 1
            }
            return
        case .statusDelete(let id):
            store.remove("statuses", "\(from)|\(id)")
            statusRevision += 1
            return
        case .unknown:
            return
        default:
            break
        }

        // Le reste appartient à une discussion.
        var chat: Chat?
        if let grp = wire.grp {
            // L'annonce « vous a ajouté » se range juste avant le premier message.
            var at = ts
            if case let .msg(_, mAt, _, _) = wire.body { at = min(mAt, ts) - 1 }
            chat = groupChat(from, grp, at: at)
        } else if let g = wire.g {
            chat = chats["g:\(g)"]
        } else {
            chat = ensureDirectChat(from)
        }
        guard var c = chat else { return }
        if c.group, !c.members.contains(from) {
            if case .group = wire.body {} else { return }
        }

        switch wire.body {
        case let .msg(id, at, pn, content):
            guard store.message(c.id, id) == nil else { return } // déjà reçu
            let kinds = ["text", "image", "voice", "file", "contact"]
            var safe = content
            if !kinds.contains(safe.kind) { safe.kind = "text" }
            safe.text = String(safe.text.prefix(65_536))
            var m = Message(chat: c.id, id: id, from: from, at: min(at, ts + 60_000), content: safe)
            if let ttl = wire.ttl { m.exp = m.at + Double(ttl) * 1000 }
            if var sender = contacts[from], sender.profileName.isEmpty, !pn.isEmpty {
                sender.profileName = String(pn.prefix(40))
                saveContact(sender)
            }
            let active = activeChat == c.id && foreground
            if active { m.readByMe = true } else { c.unread += 1; saveChat(c) }
            storeMessage(m)
            queueReceipt(from, c.id, [id], "delivered")
            if active, settings.readReceipts { queueReceipt(from, c.id, [id], "read") }
            // Votre profil, pour qui ne l'a pas encore.
            if var sender = contacts[from], sender.sentProfileV < profile.v {
                sender.sentProfileV = profile.v
                saveContact(sender)
                enqueue(from, Wire(.profile(profile)))
            }
            typing[c.id]?.remove(from)
            onIncoming?(chats[c.id] ?? c, m, active)
        case let .receipt(st, ids):
            for id in ids {
                guard var m = store.message(c.id, id), m.from == me.code else { continue }
                if !m.deliveredTo.contains(from) { m.deliveredTo.append(from) }
                if st == "read", !m.readBy.contains(from) { m.readBy.append(from) }
                store.writeMessage(m)
                // Bien arrivé : plus besoin de le garder pour un renvoi.
                store.remove("outbox", "\(id):\(from)")
            }
            bump(c.id)
        case let .react(id, emoji):
            guard var m = store.message(c.id, id) else { return }
            m.reactions[from] = emoji.isEmpty ? nil : String(emoji.prefix(8))
            store.writeMessage(m)
            bump(c.id)
            if m.from == me.code, !emoji.isEmpty { onReaction?(c, from, emoji) }
        case let .edit(id, text):
            guard var m = store.message(c.id, id), m.from == from, !m.deleted else { return }
            m.text = String(text.prefix(65_536))
            m.edited = true
            store.writeMessage(m)
            refreshLast(c.id)
            bump(c.id)
        case let .delete(id):
            guard var m = store.message(c.id, id), m.from == from else { return }
            m.deleted = true
            m.text = ""; m.data = ""; m.reactions = [:]
            store.writeMessage(m)
            refreshLast(c.id)
            bump(c.id)
        case let .timer(secs):
            updateChat(c.id) { $0.timer = secs }
            systemMessage(c.id, timerText(from, secs), at: ts)
        case let .group(op, note):
            if op == "leave" {
                systemMessage(c.id, "\(displayName(from)) a quitté le groupe.", at: ts)
            } else if op != "create", !note.isEmpty {
                let text = note.hasPrefix("Vous avez") ? "\(displayName(from)) a" + note.dropFirst("Vous avez".count) : note
                systemMessage(c.id, text, at: ts)
            }
        default:
            break
        }
    }

    // ----- Statuts -----

    public func postStatus(kind: String, text: String, bg: String, data: String) {
        let id = randomId(10)
        let at = relay.now()
        var st = StatusItem(owner: me.code, id: id, kind: kind, text: text, bg: bg, data: data, at: at)
        st.seen = true
        store.write("statuses", st.key, st)
        statusRevision += 1
        for c in contacts.values where c.saved && !c.blocked {
            enqueue(c.code, Wire(.status(id: id, kind: kind, text: text, bg: bg, data: data, at: at)))
        }
    }

    public func deleteStatus(_ id: String) {
        store.remove("statuses", "\(me.code)|\(id)")
        statusRevision += 1
        for c in contacts.values where c.saved && !c.blocked { enqueue(c.code, Wire(.statusDelete(id: id))) }
    }

    public func statuses() -> [StatusItem] {
        let t = nowMs()
        return store.all("statuses", as: StatusItem.self).filter { $0.at + dayMs > t }.sorted { $0.at < $1.at }
    }

    public func markStatusSeen(_ st: StatusItem) {
        guard !st.seen, st.owner != me.code else { return }
        var s = st
        s.seen = true
        store.write("statuses", s.key, s)
        statusRevision += 1
        if settings.readReceipts { enqueue(st.owner, Wire(.statusSeen(id: st.id))) }
    }

    // ----- Appels -----

    public func sendCallSignal(_ code: String, _ body: Body) {
        enqueue(code, Wire(body), live: true)
    }

    public func logCall(_ entry: CallEntry) {
        store.write("calls", entry.id, entry)
        callRevision += 1
    }

    public func callLog() -> [CallEntry] {
        store.all("calls", as: CallEntry.self).sorted { $0.at > $1.at }
    }

    public func clearCalls() {
        store.clear("calls")
        callRevision += 1
    }

    // ----- Sauvegarde -----

    /// Le fichier de sauvegarde (JSON) : le même que celui du navigateur.
    public func exportBackup(password: String) -> Data? {
        let payload: [String: Any] = ["app": "talka", "at": nowMs(), "data": store.dump()]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let plain = String(data: data, encoding: .utf8),
              let backup = TalkaCrypto.encryptBackup(password: password, plain: plain)
        else { return nil }
        return try? JSONEncoder().encode(backup)
    }

    /// Restaure une sauvegarde (du navigateur ou d'un iPhone) dans `store`.
    public static func importBackup(_ store: Store, password: String, file: Data) throws {
        guard let backup = try? JSONDecoder().decode(Backup.self, from: file) else { throw TalkaCrypto.BackupError.notBackup }
        let plain = try TalkaCrypto.decryptBackup(password: password, file: backup)
        guard let json = (try? JSONSerialization.jsonObject(with: Data(plain.utf8))) as? [String: Any],
              json["app"] as? String == "talka", let data = json["data"] as? [String: Any]
        else { throw TalkaCrypto.BackupError.notBackup }
        store.restore(data)
    }

    public func wipeEverything() {
        stop()
        store.wipe()
    }
}

/// `2:05`.
public func formatDuration(_ s: Double) -> String {
    let t = max(0, Int(s.rounded()))
    return "\(t / 60):" + String(format: "%02d", t % 60)
}
