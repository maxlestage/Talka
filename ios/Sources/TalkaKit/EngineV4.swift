// La v4 dans le moteur (`docs/SECURITE.md` §7.11 à §7.13), comme le
// navigateur (`client/src/core.rs`) :
//
// - plusieurs appareils par compte : chacun a son identifiant (`kv/dev`),
//   son appareil v3 et ses sessions (`<code>/<dev>`) ; la liste des appareils
//   (`kv/appareils`) part aux contacts dans le profil ; un message part en
//   une copie par appareil du destinataire, et une copie `sync` vers nos
//   autres appareils, qui refont ce que celui-ci a fait ;
// - les boîtes aux lettres tournantes : vers un contact qui les lit,
//   l'enveloppe scellée part dans une boîte qui change chaque jour ; une
//   troisième connexion, anonyme, lit les nôtres ;
// - la liaison d'un nouvel appareil par QR code : le compte lui part chiffré
//   (ECDH + HKDF + AES-GCM), en morceaux, dans une boîte de liaison.

import Foundation

/// Ce qui, d'une discussion, est le même sur tous nos appareils (v4).
struct ChatState: Codable, Equatable {
    var markedUnread = false
    var pinned = false
    var archived = false
    var muted = false
    /// 0 : pour toujours (tant que `muted`).
    var muteUntil: Double = 0
    var wallpaper = ""
    var pinMsg = ""

    enum CodingKeys: String, CodingKey {
        case pinned, archived, muted, wallpaper
        case markedUnread = "marked_unread"
        case muteUntil = "mute_until"
        case pinMsg = "pin_msg"
    }

    init(_ c: Chat) {
        markedUnread = c.markedUnread; pinned = c.pinned; archived = c.archived; muted = c.muted
        muteUntil = c.muteUntil ?? 0; wallpaper = c.wallpaper; pinMsg = c.pinMsg
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        markedUnread = c.v(.markedUnread, false); pinned = c.v(.pinned, false); archived = c.v(.archived, false)
        muted = c.v(.muted, false); muteUntil = c.v(.muteUntil, 0); wallpaper = c.v(.wallpaper, "")
        pinMsg = c.v(.pinMsg, "")
    }

    func apply(_ c: inout Chat) {
        c.markedUnread = markedUnread; c.pinned = pinned; c.archived = archived; c.muted = muted
        c.muteUntil = muteUntil > 0 ? muteUntil : nil
        c.wallpaper = String(wallpaper.prefix(200)); c.pinMsg = pinMsg
    }
}

/// Une valeur (ou un objet) en `JSONValue`, pour le champ `d` d'un `sync`.
func jsonValue<T: Encodable>(_ v: T) -> JSONValue { JSON.decode(JSONValue.self, JSON.encode(v)) ?? .null }

/// Un `JSONValue` relu comme une structure.
func decodeJSON<T: Decodable>(_ v: JSONValue, as type: T.Type) -> T? { JSON.decode(T.self, JSON.encode(v)) }

/// La liaison d'un appareil n'a pas pu se faire.
public enum LinkError: Error, LocalizedError, Equatable {
    case notALink, alreadyLinked, tooMany, offline, unreadable, failed(String), bad

    public var errorDescription: String? {
        switch self {
        case .notALink: "Ce n'est pas un code de liaison Talka."
        case .alreadyLinked: "Cet appareil est déjà lié."
        case .tooMany: "Six appareils au plus : retirez-en un d'abord."
        case .offline: "Pas de connexion au relais : réessayez."
        case .unreadable: "Code de liaison illisible."
        case .failed(let e): "La liaison n'a pas pu partir (\(e)) : réessayez."
        case .bad: "La liaison n'a pas abouti : réessayez."
        }
    }
}

extension Engine {
    /// Le nom d'un appareil que l'on vient de lier, jusqu'à ce qu'il donne
    /// le sien.
    public static let newDeviceName = "Nouvel appareil"

    /// Notre compte, vu comme un contact (pour écrire à nos autres appareils).
    var selfContact: Contact {
        var c = Contact(code: me.code)
        c.sign = me.sign
        c.dh = me.dh
        c.pj = true
        c.v3 = true
        return c
    }

    /// Nos autres appareils.
    public var siblings: [String] { devices.list.map(\.dev).filter { $0 != dev } }

    /// Les appareils où écrire à `code` : chacun des siens (v4, sa dernière
    /// liste annoncée), ou le code seul (vide, avant la v4). Pour nous-mêmes :
    /// nos autres appareils.
    func targets(_ code: String) -> [String] {
        if code == me.code { return siblings }
        if let c = contacts[code], !c.devs.isEmpty { return c.devs.map(\.d) }
        return [""]
    }

    // ----- Cet appareil et ceux du compte -----

    /// Cet appareil : son identifiant (tiré une fois, jamais sauvegardé), et
    /// sa place dans la liste des appareils du compte.
    func ensureDevice() {
        var d = store.read("kv", "dev", as: String.self) ?? ""
        if !TalkaV4.isDev(d) {
            d = TalkaV4.newDev()
            store.write("kv", "dev", d)
        }
        dev = d
        relay.dev = d
        var list = store.read("kv", "appareils", as: Devices.self) ?? Devices()
        // Lié depuis un autre appareil : il prend son vrai nom.
        if let i = list.list.firstIndex(where: { $0.dev == d && $0.name == Self.newDeviceName }) {
            list.list[i].name = Self.deviceName
            list.v += 1
            store.write("kv", "appareils", list)
        }
        if !list.has(d) {
            list.list.append(AccountDevice(dev: d, name: Self.deviceName, at: nowMs()))
            // Au-delà de six, les plus anciens cèdent leur place.
            while list.list.count > maxDevices { list.list.removeFirst() }
            list.v += 1
            store.write("kv", "appareils", list)
        }
        devices = list
    }

    /// La liste des appareils a changé : gardée, donnée à nos autres
    /// appareils et annoncée aux contacts (dans le profil).
    func devicesChanged(_ list: Devices, tellSiblings: Bool) {
        devices = list
        store.write("kv", "appareils", list)
        if tellSiblings { sync("devices", d: jsonValue(list)) }
        announceProfile()
    }

    /// La liste des appareils a changé depuis la dernière annonce (un
    /// appareil ajouté au démarrage, une liaison faite hors ligne) : annoncée.
    func announceDevices() {
        let told = store.read("kv", "appareils_annonces", as: Int.self) ?? 0
        guard devices.v > told else { return }
        sync("devices", d: jsonValue(devices))
        announceProfile()
        store.write("kv", "appareils_annonces", devices.v)
    }

    /// Renomme cet appareil (ou un autre du compte).
    public func renameDevice(_ d: String, name: String) {
        var list = devices
        guard let i = list.list.firstIndex(where: { $0.dev == d }) else { return }
        let trimmed = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
        guard !trimmed.isEmpty, trimmed != list.list[i].name else { return }
        list.list[i].name = trimmed
        list.v += 1
        devicesChanged(list, tellSiblings: true)
    }

    /// Retire un appareil du compte : il reçoit l'ordre de tout effacer, le
    /// relais l'oublie, et les contacts ne lui écrivent plus.
    public func unlinkDevice(_ d: String) async {
        guard d != dev, devices.has(d) else { return }
        // Lui d'abord (tant qu'il est encore dans la liste).
        enqueueTo(me.code, dev: d, Wire(.sync(op: "unlink", d: .object(["dev": .string(d)]))))
        await flush()
        relay.unlink(d)
        var list = devices
        list.remove(d)
        devicesChanged(list, tellSiblings: true)
    }

    /// Les appareils retirés, redits au relais à chaque connexion (il ne
    /// garde rien sur disque) : un appareil retiré pendant qu'il était hors
    /// ligne reste refusé, et efface tout à son retour.
    func unlinkGone() {
        for d in devices.gone { relay.unlink(d) }
    }

    /// Cet appareil quitte le compte (avant de tout effacer) : nos autres
    /// appareils et les contacts ne lui écrivent plus. Au mieux : sans
    /// réseau, il reste dans la liste jusqu'à ce qu'un autre appareil le
    /// retire.
    public func leaveAccount() async {
        guard !siblings.isEmpty, devices.has(dev), relay.state == .online else { return }
        var list = devices
        list.list.removeAll { $0.dev == dev }
        list.v += 1
        devicesChanged(list, tellSiblings: true)
        await flush()
        // Un envoi déjà en cours finit le travail : on l'attend (5 s au plus).
        var waited = 0
        while flushing, waited < 50 {
            try? await Task.sleep(nanoseconds: 100_000_000)
            waited += 1
        }
    }

    /// Le relais (ou un autre de nos appareils) dit que cet appareil a été
    /// retiré du compte : tout est effacé.
    func unlinked() {
        if let onUnlinked {
            onUnlinked()
        } else {
            wipeEverything()
        }
    }

    // ----- La synchronisation entre nos appareils (§7.11) -----

    /// Ce que cet appareil a fait, pour nos autres appareils.
    func sync(_ op: String, c: String = "", w: Wire? = nil, d: JSONValue = .null) {
        guard !siblings.isEmpty else { return }
        enqueue(me.code, Wire(.sync(op: op, c: c, w: w, d: d)))
    }

    /// Un contact ajouté, renommé, bloqué : pareil sur nos autres appareils.
    func syncContact(_ c: Contact) {
        sync("contact", d: .object([
            "code": .string(c.code), "name": .string(c.name), "saved": .bool(c.saved),
            "blocked": .bool(c.blocked), "sign": .string(c.sign), "dh": .string(c.dh),
        ]))
    }

    /// Un groupe changé ici : nos autres appareils le changent aussi.
    func syncGroup(_ c: Chat, note: String) {
        sync("group", c: c.id, d: .object(["grp": jsonValue(groupInfo(c)), "left": .bool(c.left), "note": .string(note)]))
    }

    /// Un élément de la boîte d'envoi bien arrivé sur l'appareil `d` du
    /// destinataire : gardé tant qu'un autre l'attend.
    func gotBy(_ key: String, _ d: String) {
        guard !d.isEmpty, var item = store.read("outbox", key, as: OutItem.self) else {
            store.remove("outbox", key)
            return
        }
        if !item.got.contains(d) { item.got.append(d) }
        if item.boot.isEmpty || stillUnconfirmed(item) {
            store.write("outbox", key, item)
        } else {
            store.remove("outbox", key)
        }
    }

    /// Ce qu'un autre de nos appareils (`fd`) a fait : refait ici, sans le
    /// renvoyer à personne.
    func handleSync(_ fd: String, _ op: String, _ chat: String, _ w: Wire?, _ d: JSONValue) {
        switch op {
        case "wire":
            guard let w else { return }
            applyOwnWire(chat, w)
            // Un message : l'appareil qui l'a envoyé sait que nous l'avons.
            if case let .msg(id, _, _, _) = w.body {
                enqueueTo(me.code, dev: fd, Wire(.sync(op: "got", c: chat, d: .object(["ids": .array([.string(id)])]))))
            }
        case "got":
            for id in d["ids"]?.array?.compactMap(\.string) ?? [] { gotBy("\(id):\(me.code)", fd) }
        case "read":
            readHere(chat, at: d["at"]?.number ?? 0)
        case "contact":
            syncedContact(d)
        case "chat":
            if d["deleted"]?.bool == true {
                dropChat(chat)
            } else if d["cleared"]?.bool == true {
                emptyChat(chat)
            } else if let state = decodeJSON(d, as: ChatState.self), var c = chats[chat] {
                state.apply(&c)
                saveChat(c)
            }
        case "forget":
            for id in d["ids"]?.array?.compactMap(\.string) ?? [] {
                guard let m = store.message(chat, id) else { continue }
                store.removeMessage(chat, id)
                bury(chat, [(m.id, m.at)])
            }
            refreshLast(chat)
            bump(chat)
        case "star":
            if var m = store.message(chat, d["id"]?.string ?? "") {
                m.starred = d["on"]?.bool ?? false
                store.writeMessage(m)
                bump(chat)
            }
        case "group":
            guard let grp = d["grp"].flatMap({ decodeJSON($0, as: GroupInfo.self) }) else { return }
            applyOwnGroup(grp, left: d["left"]?.bool ?? false, note: d["note"]?.string ?? "")
        case "profile":
            guard let p = decodeJSON(d, as: Profile.self), p.v > profile.v else { return }
            profile = Profile(name: String(p.name.prefix(40)), about: String(p.about.prefix(140)),
                              avatar: p.avatar.count <= 60_000 ? p.avatar : "", v: p.v)
            store.write("kv", "profile", profile)
        case "devices":
            guard let list = decodeJSON(d, as: Devices.self) else { return }
            var current = devices
            // Retirés par un autre appareil, nous ne le sommes que par
            // `unlink` (une liste en retard ne nous efface pas).
            if current.merge(list), current.has(dev) { devicesChanged(current, tellSiblings: false) }
        case "status":
            if let id = d["deleted"]?.string {
                store.remove("statuses", "\(me.code)|\(id)")
            } else if var st = decodeJSON(d, as: StatusItem.self) {
                st.owner = me.code
                st.seen = true
                store.write("statuses", st.key, st)
            }
            statusRevision += 1
        case "call":
            onCallSignal?(me.code, .call(op: "elsewhere", callId: d["id"]?.string ?? "", video: false, sdp: "", cand: nil), true)
        case "unlink":
            // Après la transaction en cours (la base est effacée).
            if d["dev"]?.string == dev, devices.has(fd) { Task { @MainActor in self.unlinked() } }
        default:
            break
        }
    }

    /// Un contact ajouté, renommé ou bloqué sur un autre de nos appareils.
    func syncedContact(_ d: JSONValue) {
        guard let code = d["code"]?.string.flatMap(TalkaCode.normalize), code != me.code else { return }
        var k = contacts[code] ?? Contact(code: code)
        k.name = String((d["name"]?.string ?? "").prefix(40))
        k.saved = d["saved"]?.bool ?? k.saved
        k.blocked = d["blocked"]?.bool ?? k.blocked
        // Ses clés, seulement si elles vont avec son code.
        let sign = d["sign"]?.string ?? "", dh = d["dh"]?.string ?? ""
        if k.sign.isEmpty, TalkaCrypto.verifyKeys(code: code, sign: sign, dh: dh) {
            k.sign = sign
            k.dh = dh
        }
        saveContact(k)
        relay.watch([code])
    }

    /// Un groupe créé, changé ou quitté sur un autre de nos appareils.
    func applyOwnGroup(_ grp: GroupInfo, left: Bool, note: String) {
        let id = "g:\(grp.id)"
        var c = chats[id] ?? Chat(id: id)
        if chats[id] == nil {
            c.group = true
            c.createdBy = me.code
            c.createdAt = nowMs()
            c.lastAt = nowMs()
        }
        guard grp.v >= c.v else { return }
        c.name = String(grp.name.prefix(60))
        c.avatar = grp.avatar
        c.members = grp.members
        c.admins = grp.admins
        if let d = grp.desc { c.desc = groupDesc(d) }
        if let a = grp.adminsOnly { c.adminsOnly = a }
        c.v = grp.v
        c.left = left
        saveChat(c)
        if !note.isEmpty { systemMessage(id, note) }
        relay.watch(grp.members.filter { $0 != me.code })
    }

    /// Ce qu'un autre de nos appareils a envoyé dans la discussion `chatId`.
    func applyOwnWire(_ chatId: String, _ wire: Wire) {
        // La discussion : un groupe dont nous sommes, ou un contact.
        let c: Chat
        if chatId.hasPrefix("g:") {
            guard let found = chats[chatId] else { return }
            c = found
        } else {
            guard let code = TalkaCode.normalize(chatId), code != me.code else { return }
            c = ensureDirectChat(code)
        }
        switch wire.body {
        case let .msg(id, at, _, content):
            guard store.message(c.id, id) == nil, !buried(c.id, id) else { return }
            var safe = content
            let kinds = ["text", "image", "voice", "file", "contact", "brokey"]
            if !kinds.contains(safe.kind) || (safe.kind == "brokey" && Brocolis.key(base64: safe.data) == nil) { safe.kind = "text" }
            safe.text = String(safe.text.prefix(65_536))
            var m = Message(chat: c.id, id: id, from: me.code, at: at, content: safe)
            let to = recipients(c)
            m.status = "sent"
            m.to = to
            m.sentTo = to
            m.readByMe = true
            if let ttl = wire.ttl { m.exp = at + Double(ttl) * 1000 }
            openMessage(&m)
            storeMessage(m)
        case let .react(id, emoji):
            guard var m = store.message(c.id, id) else { return }
            m.reactions[me.code] = emoji.isEmpty ? nil : String(emoji.prefix(8))
            store.writeMessage(m)
            bump(c.id)
        case let .edit(id, text):
            guard var m = store.message(c.id, id), m.from == me.code, !m.deleted else { return }
            let opened = Brocolis.open(String(text.prefix(65_536)), keys: brocolisKeys(for: c.id))
            m.text = opened.text
            m.bro = opened.opened > 0
            m.edited = true
            store.writeMessage(m)
            refreshLast(c.id)
            bump(c.id)
        case let .delete(id):
            guard var m = store.message(c.id, id), m.from == me.code else { return }
            m.deleted = true
            m.text = ""; m.data = ""; m.reactions = [:]
            store.writeMessage(m)
            refreshLast(c.id)
            bump(c.id)
        case let .timer(secs):
            if var chat = chats[c.id] {
                chat.timer = secs
                saveChat(chat)
            }
            systemMessage(c.id, timerText(me.code, secs))
        default:
            break
        }
    }

    // ----- Les boîtes tournantes (§7.13) -----

    /// Les boîtes où nos contacts nous écrivent (à cet appareil) : pour
    /// chacun, chaque jour depuis la dernière écoute (un de marge, trente au
    /// plus) jusqu'à demain. La connexion anonyme de lecture les suit.
    func refreshBoxes() {
        boxRefresh?.cancel()
        boxRefresh = nil
        let today = TalkaV4.day(relay.now())
        boxDay = today
        let last = store.read("kv", "boites_jour", as: Int.self) ?? today
        let from = max(max(last - 1, 0), today - 30)
        var map: [String: String] = [:]
        if from <= today + 1 {
            for c in contacts.values where !c.blocked && !c.dh.isEmpty && c.code != me.code {
                guard let key = TalkaV4.boitesKey(me, peerCode: c.code, peerDh: c.dh) else { continue }
                for day in from...(today + 1) {
                    map[TalkaV4.boite(key: key, to: me.code, dev: dev, day: day)] = c.code
                }
            }
        }
        // Des boîtes de couverture (v6, §7.15) : à aujourd'hui et demain, et
        // assez pour que le nombre de boîtes suivies ne dise pas combien nous
        // avons de contacts (un multiple de 64).
        let secret = coverSecret()
        for day in [today, today + 1] {
            for i in 0..<Self.coverMin { map[TalkaV4.coverBox(secret: secret, day: day, i: i)] = "" }
        }
        var i = Self.coverMin
        while map.count % Self.coverRound != 0 {
            map[TalkaV4.coverBox(secret: secret, day: today, i: i)] = ""
            i += 1
        }
        boxMap = map
        relay.boxes.listen(map.keys.sorted())
        if relay.state == .online { store.write("kv", "boites_jour", today) }
    }

    /// Boîtes de couverture par jour, au moins ; le nombre de boîtes suivies
    /// est arrondi au multiple de `coverRound` supérieur.
    static let coverMin = 4
    static let coverRound = 64

    /// Le secret des boîtes de couverture : tiré une fois, propre à
    /// l'appareil (la même clé que le navigateur, `kv/couverture`).
    func coverSecret() -> Data {
        if let saved = store.read("kv", "couverture", as: String.self), let s = unb64(saved), s.count == 32 { return s }
        let s = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        store.write("kv", "couverture", b64(s))
        return s
    }

    /// Le trafic de couverture (v6, §7.15) : de temps en temps (une fois
    /// toutes les trois minutes en moyenne, en ligne), une enveloppe scellée
    /// vide, au même palier qu'un court message, part dans une de nos boîtes
    /// de couverture ; elle nous revient, et on l'oublie.
    func sendCover() async {
        guard relay.state == .online, relay.sealed, Double.random(in: 0..<1) < 0.35 else { return }
        let day = TalkaV4.day(relay.now())
        let boite = TalkaV4.coverBox(secret: coverSecret(), day: day, i: Int.random(in: 0..<Self.coverMin))
        guard let sealed = TalkaCrypto.sealSender(from: me.code, fd: dev, to: me.code, toDh: me.dh, body: "") else { return }
        // Pas `sendBoite` : la couverture ne compte pas parmi les envois.
        let id = randomId(9)
        _ = await relay.anonSend(id: id, ["t": "send", "id": id, "boite": boite, "body": sealed, "live": false],
                                 wake: false, apercu: nil)
    }

    /// Les boîtes à recalculer bientôt (une fois, après une rafale de
    /// changements).
    func scheduleBoxRefresh() {
        guard boxRefresh == nil else { return }
        boxRefresh = Task { @MainActor [weak self] in
            await Task.yield()
            guard let self, !Task.isCancelled else { return }
            self.boxRefresh = nil
            self.refreshBoxes()
        }
    }

    // ----- La liaison d'un appareil (§7.12) -----

    /// Ce qu'un nouvel appareil ne doit pas recevoir : ce qui est propre à
    /// cet appareil-ci.
    nonisolated static func deviceOnly(_ key: String) -> Bool {
        ["dev", "nk", "pin", "appareils", "boites_jour", "brocolis_sealed", "couverture"].contains(key)
            || key == V3.deviceKey || key.hasPrefix(V3.sessionPrefix)
    }

    /// Lie un nouvel appareil (son code de liaison, lu dans son QR code) : il
    /// reçoit notre compte, chiffré pour lui seul. Renvoie le code de
    /// vérification à comparer avec celui qu'il affiche.
    public func linkDevice(_ text: String) async throws -> String {
        guard let offer = TalkaV4.LinkOffer.parse(text) else { throw LinkError.notALink }
        var list = devices
        if list.has(offer.dev) { throw LinkError.alreadyLinked }
        if list.list.count >= maxDevices { throw LinkError.tooMany }
        guard relay.state == .online, relay.sealed else { throw LinkError.offline }
        guard let answer = TalkaV4.linkAnswer(offer) else { throw LinkError.unreadable }
        list.list.append(AccountDevice(dev: offer.dev, name: Self.newDeviceName, at: nowMs()))
        list.v += 1
        let payload: [String: Any] = [
            "talka": "lien", "v": 1, "code": me.code,
            "devices": (try? JSONSerialization.jsonObject(with: Data(JSON.encode(list).utf8))) ?? [:],
            "data": linkDump(),
        ]
        guard let plain = try? JSONSerialization.data(withJSONObject: payload),
              let chunks = TalkaV4.linkSeal(key: answer.key, lid: offer.lid, ek: answer.ek, plain: plain)
        else { throw LinkError.unreadable }
        let boite = offer.boite
        for (i, chunk) in chunks.enumerated() {
            let ack = await relay.sendBoite(id: "lien-\(randomId(8))-\(i)", boite: boite, body: chunk,
                                            live: false, wake: false, ttl: 3600)
            if case .failed(let e) = ack { throw LinkError.failed(e) }
        }
        devicesChanged(list, tellSiblings: true)
        return TalkaV4.linkVerification(ek: answer.ek, lk: offer.lk)
    }

    /// Notre compte, pour un nouvel appareil : comme la sauvegarde, sans ce
    /// qui est propre à cet appareil, ni les pièces jointes de plus de
    /// 512 Kio, ni plus de 32 Mio de messages (les plus anciens restent).
    func linkDump() -> [String: Any] {
        var dump = store.dump()
        var kv = (dump["kv"] as? [[Any]] ?? []).filter {
            let key = $0.first as? String ?? ""
            return !Self.deviceOnly(key) && key != "brocolis"
        }
        // Les clés Brocolis (dans le trousseau de cet iPhone) en clair : le
        // lien est chiffré pour le seul nouvel appareil.
        if !brocolisKeys.isEmpty { kv.append(["brocolis", JSON.encode(brocolisKeys)]) }
        dump["kv"] = kv
        dump["outbox"] = [[Any]]()
        // Les plus récents d'abord.
        let rows = (dump["messages"] as? [[Any]] ?? []).sorted {
            ($0.count > 3 ? ($0[3] as? Double ?? 0) : 0) > ($1.count > 3 ? ($1[3] as? Double ?? 0) : 0)
        }
        var kept: [[Any]] = []
        var total = 0
        for row in rows {
            guard row.count >= 2, var json = row[1] as? String else { continue }
            if var m = JSON.decode(Message.self, json), m.data.utf8.count > 512 * 1024 {
                m.data = ""
                json = JSON.encode(m)
            }
            total += json.utf8.count
            guard total <= 32 * 1024 * 1024 else { break }
            var out = row
            out[1] = json
            kept.append(out)
        }
        dump["messages"] = kept
        return dump
    }

    /// Range le compte reçu par la liaison : tout, sauf ce qui est propre à
    /// l'autre appareil ; cet appareil garde son identifiant (`dev`).
    nonisolated static func importLink(_ bytes: Data, dev: String, into store: Store) -> Bool {
        guard let payload = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any],
              payload["talka"] as? String == "lien", payload["v"] as? Int == 1,
              let code = payload["code"] as? String,
              let listObject = payload["devices"],
              JSONSerialization.isValidJSONObject(listObject),
              let listData = try? JSONSerialization.data(withJSONObject: listObject),
              let devices = try? JSONDecoder().decode(Devices.self, from: listData), devices.has(dev),
              var data = payload["data"] as? [String: Any]
        else { return false }
        let kv = data["kv"] as? [[Any]] ?? []
        // L'identité doit y être, et valable.
        guard let row = kv.first(where: { ($0.first as? String) == "identity" }), row.count >= 2,
              let identity = (row[1] as? String).flatMap({ JSON.decode(Identity.self, $0) }),
              identity.isValid, identity.code == code
        else { return false }
        data["kv"] = kv.filter { !deviceOnly($0.first as? String ?? "") }
            + [["dev", JSON.encode(dev)], ["appareils", JSON.encode(devices)]]
        #if os(iOS)
        // Un appareil v3 laissé par une liaison d'avant (le trousseau survit à
        // l'app) : celui-ci en tire un neuf.
        V3Vault.clear()
        #endif
        store.restore(data)
        return true
    }
}

/// Le nouvel appareil attend d'être lié (§7.12) : il montre `text` (QR
/// code), écoute sa boîte de liaison (une connexion anonyme), ouvre les
/// morceaux qu'y dépose l'appareil du compte, puis range le compte dans
/// `store`. `onDone` : le code de vérification à comparer, ou l'erreur.
@MainActor
public final class DeviceLink {
    public let secret: TalkaV4.LinkSecret
    let store: Store
    let socket: BoxSocket
    var key: Data?
    var ek = Data()
    var chunks: [Data?] = []
    var finished = false
    public var onDone: ((Result<String, LinkError>) -> Void)?

    public init(store: Store, server: URL) {
        self.store = store
        secret = TalkaV4.LinkSecret()
        socket = BoxSocket(url: Relay.wsURL(server))
        socket.onEnvelope = { [weak self] env in self?.receive(env) }
    }

    /// Ce que montre le QR code (et le bouton « Copier »).
    public var text: String { secret.offer.text }
    /// L'identifiant de cet appareil.
    public var dev: String { secret.offer.dev }

    public func start() {
        socket.listen([secret.offer.boite])
    }

    /// La liaison abandonnée.
    public func cancel() {
        finished = true
        socket.listen([])
    }

    func receive(_ env: [String: Any]) {
        if let n = env["n"] as? Int { socket.got([n]) }
        guard !finished, env["boite"] as? String == secret.offer.boite,
              let body = env["body"] as? String,
              let chunk = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any],
              let e = (chunk["e"] as? String).flatMap(unb64)
        else { return }
        if key == nil {
            key = secret.key(ek: e)
            ek = e
        }
        guard ek == e, let key, let opened = TalkaV4.linkOpen(key: key, lid: secret.offer.lid, chunk: chunk) else { return }
        if chunks.count != opened.n { chunks = Array(repeating: nil, count: opened.n) }
        chunks[opened.i] = opened.plain
        guard chunks.allSatisfy({ $0 != nil }) else { return }
        let bytes = chunks.reduce(into: Data()) { $0 += $1 ?? Data() }
        finished = true
        socket.listen([])
        if Engine.importLink(bytes, dev: dev, into: store) {
            onDone?(.success(TalkaV4.linkVerification(ek: ek, lk: secret.offer.lk)))
        } else {
            onDone?(.failure(.bad))
        }
    }
}
