// « Vider la discussion » : tout, ou tout sauf les messages favoris (comme
// WhatsApp), et l'ordre partagé avec nos autres appareils.

import Foundation
import XCTest
@testable import TalkaKit

@MainActor
final class ClearChatTests: XCTestCase {
    func phone() -> Engine {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-vider-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        return Engine(store: store, server: URL(string: "http://localhost:1")!)
    }

    func testClearingKeepsStarredMessagesWhenAsked() async throws {
        let e = phone()
        let c = e.ensureDirectChat("K7Q2MX9PRT")
        let keep = try XCTUnwrap(e.send(c.id, Content(text: "À garder")))
        e.send(c.id, Content(text: "À effacer"))
        XCTAssertFalse(e.hasStarred(c.id))
        e.toggleStar(c.id, keep.id)
        XCTAssertTrue(e.hasStarred(c.id))

        e.clearChat(c.id, keepStarred: true)
        XCTAssertEqual(e.messages(c.id).map(\.text), ["À garder"])
        XCTAssertEqual(e.chats[c.id]?.last?.text, "À garder")

        e.clearChat(c.id)
        XCTAssertTrue(e.messages(c.id).isEmpty)
        XCTAssertNil(e.chats[c.id]?.last)
    }

    /// Un message supprimé pour tous n'est plus important (il n'a plus rien
    /// à garder) ; la liste d'une discussion ne montre que les siens.
    func testDeletedMessagesLeaveTheStarredList() async throws {
        let e = phone()
        let c = e.ensureDirectChat("K7Q2MX9PRT")
        let other = e.ensureDirectChat("WGSEAJNSZJ")
        let a = try XCTUnwrap(e.send(c.id, Content(text: "Un")))
        let b = try XCTUnwrap(e.send(c.id, Content(text: "Deux")))
        let o = try XCTUnwrap(e.send(other.id, Content(text: "Ailleurs")))
        for (chat, id) in [(c.id, a.id), (c.id, b.id), (other.id, o.id)] { e.toggleStar(chat, id) }
        XCTAssertEqual(e.starred(in: c.id).map(\.text), ["Deux", "Un"])
        XCTAssertEqual(e.starred().count, 3)

        e.delete(c.id, b.id, forEveryone: true)
        XCTAssertEqual(e.starred(in: c.id).map(\.text), ["Un"])
        XCTAssertEqual(e.starred().count, 2)
        // Vider en gardant les importants : le message supprimé part aussi.
        e.clearChat(c.id, keepStarred: true)
        XCTAssertEqual(e.messages(c.id).map(\.id), [a.id])
    }

    /// Les vieux messages s'effacent sauf les importants ; une étoile restée
    /// sur un message supprimé pour tous (une version d'avant) ne le garde pas.
    func testOldDeletedMessagesGoEvenIfStarred() async {
        let now = 1_800_000_000_000.0
        var m = Message(chat: "c", id: "m", from: "WGSEAJNSZJ", at: now - 400 * 86_400_000, content: Content(text: "Vieux"))
        XCTAssertTrue(m.tooOld(keepDays: keepDaysDefault, now: now))
        m.starred = true
        XCTAssertFalse(m.tooOld(keepDays: keepDaysDefault, now: now))
        m.deleted = true
        XCTAssertTrue(m.tooOld(keepDays: keepDaysDefault, now: now))
    }

    /// Chercher parmi les messages importants : le texte (accents et
    /// majuscules ignorés), ou le nom de la discussion.
    func testSearchInStarredMessages() async throws {
        let e = phone()
        let c = e.ensureDirectChat("K7Q2MX9PRT")
        let a = try XCTUnwrap(e.send(c.id, Content(text: "Rendez-vous à l'École")))
        let b = try XCTUnwrap(e.send(c.id, Content(text: "Le code du portail")))
        for m in [a, b] { e.toggleStar(c.id, m.id) }
        let all = e.starred()
        XCTAssertEqual(e.starred(all, matching: "ecole").map(\.id), [a.id])
        XCTAssertEqual(e.starred(all, matching: "PORTAIL").map(\.id), [b.id])
        XCTAssertEqual(e.starred(all, matching: "  ").count, 2)
        XCTAssertEqual(e.starred(all, matching: "K7Q2M").count, 2, "le nom de la discussion compte")
        XCTAssertTrue(e.starred(all, matching: "piscine").isEmpty)
    }

    // ----- Les tombes (messages effacés qui ne doivent pas revenir) -----

    /// Les tombes d'avant (lues et redécodées à chaque fois), pour comparer.
    func oldBury(_ list: inout [Engine.Tomb], _ msgs: [(String, Double)], now t: Double) {
        list.removeAll { t - $0.at >= Engine.tombWindow }
        for (id, at) in msgs where t - at < Engine.tombWindow && !list.contains(where: { $0.id == id }) {
            list.append(Engine.Tomb(id: id, at: at))
        }
        if list.count > 20_000 { list.removeFirst(list.count - 20_000) }
    }

    func stored(_ e: Engine, _ chat: String) -> [String] {
        (e.store.read("kv", "tombes:\(chat)", as: [Engine.Tomb].self) ?? []).map(\.id)
    }

    /// Gardées décodées en mémoire, les tombes restent exactement celles
    /// qu'écrivait la version d'avant : ajouts, doublons, messages trop
    /// vieux pour être enterrés, et ce que `buried` répond.
    func testTombsStayTheSameAsBefore() async {
        let e = phone()
        var ref: [Engine.Tomb] = []
        var seed: UInt64 = 7
        func next(_ n: Int) -> Int {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Int((seed >> 33) % UInt64(n))
        }
        XCTAssertFalse(e.buried("AMI", "m0"))
        e.bury("AMI", [])
        XCTAssertNil(e.store.readRaw("kv", "tombes:AMI"))
        for step in 0..<200 {
            let now = nowMs()
            // De 0 à 3 messages, certains déjà enterrés, certains trop vieux.
            let msgs = (0..<next(4)).map { _ in
                ("m\(next(60))", now - (next(5) == 0 ? Engine.tombWindow + 1000 : Double(next(1000))))
            }
            e.bury("AMI", msgs)
            oldBury(&ref, msgs, now: now)
            XCTAssertEqual(stored(e, "AMI"), ref.map(\.id), "étape \(step)")
            let probe = "m\(next(60))"
            XCTAssertEqual(e.buried("AMI", probe), ref.contains { $0.id == probe }, "étape \(step)")
            XCTAssertFalse(e.buried("AUTRE", probe))
        }
        XCTAssertFalse(ref.isEmpty)
    }

    /// Une tombe de plus de 31 jours s'en va au ménage, et la ligne gardée
    /// fait foi : changée sans passer par `bury` (une restauration), elle
    /// est relue.
    func testOldTombsLeaveAndTheStoredRowWins() async {
        let e = phone()
        let now = nowMs()
        e.store.write("kv", "tombes:AMI", [
            Engine.Tomb(id: "vieux", at: now - Engine.tombWindow - 60_000),
            Engine.Tomb(id: "récent", at: now - 60_000),
        ])
        XCTAssertTrue(e.buried("AMI", "vieux"))
        XCTAssertTrue(e.buried("AMI", "récent"))
        // Le ménage, sans rien à enterrer : la vieille part.
        e.bury("AMI", [])
        XCTAssertEqual(stored(e, "AMI"), ["récent"])
        XCTAssertFalse(e.buried("AMI", "vieux"))
        // Rien à faire : la ligne n'est pas réécrite.
        let before = e.store.readRaw("kv", "tombes:AMI")
        e.bury("AMI", [])
        e.bury("AMI", [("récent", now - 60_000)])
        XCTAssertEqual(e.store.readRaw("kv", "tombes:AMI"), before)
        // La ligne change sous nos pieds.
        e.store.write("kv", "tombes:AMI", [Engine.Tomb(id: "autre", at: now)])
        XCTAssertTrue(e.buried("AMI", "autre"))
        XCTAssertFalse(e.buried("AMI", "récent"))
        e.bury("AMI", [("encore", now)])
        XCTAssertEqual(stored(e, "AMI"), ["autre", "encore"])
        // La dernière tombe partie, la ligne aussi.
        e.store.write("kv", "tombes:AMI", [Engine.Tomb(id: "vieux", at: now - Engine.tombWindow - 60_000)])
        e.bury("AMI", [])
        XCTAssertNil(e.store.readRaw("kv", "tombes:AMI"))
        XCTAssertFalse(e.buried("AMI", "vieux"))
        e.store.remove("kv", "tombes:AMI")
        XCTAssertFalse(e.buried("AMI", "autre"))
    }

    /// Au plus 20 000 tombes par discussion : les plus anciennes sortent.
    func testTombsAreCapped() async {
        let e = phone()
        let now = nowMs()
        e.bury("AMI", (0..<20_010).map { ("m\($0)", now - 1000) })
        XCTAssertEqual(stored(e, "AMI").count, 20_000)
        XCTAssertFalse(e.buried("AMI", "m9"))
        XCTAssertTrue(e.buried("AMI", "m10"))
        XCTAssertTrue(e.buried("AMI", "m20009"))
        // Une tombe sortie peut être enterrée de nouveau (comme avant).
        e.bury("AMI", [("m9", now - 1000)])
        XCTAssertTrue(e.buried("AMI", "m9"))
        XCTAssertFalse(e.buried("AMI", "m10"))
        XCTAssertEqual(stored(e, "AMI").count, 20_000)
    }

    /// La liste des messages importants est gardée tant qu'aucun message
    /// ne change, et jamais périmée : étoile posée ou retirée, message
    /// supprimé, discussion vidée, tout effacé — elle suit aussitôt.
    func testStarredListIsKeptUntilAMessageChanges() async throws {
        let e = phone()
        let c = e.ensureDirectChat("K7Q2MX9PRT")
        let other = e.ensureDirectChat("WGSEAJNSZJ")
        let a = try XCTUnwrap(e.send(c.id, Content(text: "Un")))
        let b = try XCTUnwrap(e.send(other.id, Content(text: "Deux")))
        XCTAssertTrue(e.starred().isEmpty)
        // Relue sans rien changer : la même, sans rien relire.
        let before = e.store.messagesGeneration
        XCTAssertTrue(e.starred().isEmpty)
        _ = e.messages(c.id)
        _ = e.store.message(other.id, b.id)
        e.store.write("kv", "sans rapport", 1)
        XCTAssertEqual(e.store.messagesGeneration, before, "lire, ou écrire autre chose qu'un message, ne change rien")
        XCTAssertEqual(e.starredKept?.generation, before)

        e.toggleStar(c.id, a.id)
        XCTAssertGreaterThan(e.store.messagesGeneration, before)
        XCTAssertEqual(e.starred().map(\.text), ["Un"])
        e.toggleStar(other.id, b.id)
        XCTAssertEqual(Set(e.starred().map(\.text)), ["Un", "Deux"])
        e.toggleStar(c.id, a.id)
        XCTAssertEqual(e.starred().map(\.text), ["Deux"])
        e.delete(other.id, b.id, forEveryone: true)
        XCTAssertTrue(e.starred().isEmpty)

        e.toggleStar(c.id, a.id)
        XCTAssertEqual(e.starred().count, 1)
        e.store.removeMessage(c.id, a.id)
        XCTAssertTrue(e.starred().isEmpty)
        let d = try XCTUnwrap(e.send(c.id, Content(text: "Trois")))
        e.toggleStar(c.id, d.id)
        XCTAssertEqual(e.starred().map(\.text), ["Trois"])
        e.clearChat(c.id)
        XCTAssertTrue(e.starred().isEmpty)
        // Une restauration (une autre base, d'un coup) : relue aussi.
        let f = try XCTUnwrap(e.send(c.id, Content(text: "Quatre")))
        e.toggleStar(c.id, f.id)
        let dump = e.store.dump()
        e.toggleStar(c.id, f.id)
        XCTAssertTrue(e.starred().isEmpty)
        e.store.restore(dump)
        XCTAssertEqual(e.starred().map(\.text), ["Quatre"])
        e.store.wipe()
        XCTAssertTrue(e.starred().isEmpty)
    }

    // ----- Les accusés de réception -----

    /// L'accusé d'un second appareil du même contact, ou un accusé rejoué, ne
    /// réécrit pas le message et ne redessine rien ; un accusé qui apporte
    /// quelque chose (reçu, puis lu) reste écrit comme avant.
    func testADuplicateReceiptRewritesNothing() async throws {
        let e = phone()
        let x = Identity.create()
        let c = e.ensureDirectChat(x.code)
        let a = try XCTUnwrap(e.send(c.id, Content(text: "Un")))
        let b = try XCTUnwrap(e.send(c.id, Content(text: "Deux")))
        let t = nowMs()
        func receipt(_ st: String, _ ids: [String], _ dev: String) {
            e.handle(x.code, Wire(.receipt(st: st, ids: ids, dev: dev)), ts: t, live: true)
        }
        receipt("delivered", [a.id, b.id], "dev1")
        XCTAssertEqual(e.store.message(c.id, a.id)?.deliveredTo, [x.code])
        XCTAssertEqual(e.store.message(c.id, b.id)?.deliveredTo, [x.code])
        XCTAssertEqual(e.store.message(c.id, a.id)?.readBy, [])

        // Le second appareil, puis un rejeu : rien ne bouge.
        let (written, drawn) = (e.store.messagesGeneration, e.revisions[c.id])
        receipt("delivered", [a.id, b.id], "dev2")
        receipt("delivered", [a.id], "dev1")
        XCTAssertEqual(e.store.messagesGeneration, written, "aucun message réécrit")
        XCTAssertEqual(e.revisions[c.id], drawn, "rien à redessiner")
        XCTAssertEqual(e.store.message(c.id, a.id)?.deliveredTo, [x.code])

        // Lu : écrit, redessiné ; relu par l'autre appareil : plus rien.
        receipt("read", [a.id], "dev2")
        XCTAssertEqual(e.store.message(c.id, a.id)?.readBy, [x.code])
        XCTAssertEqual(e.store.message(c.id, b.id)?.readBy, [])
        XCTAssertGreaterThan(e.store.messagesGeneration, written)
        XCTAssertNotEqual(e.revisions[c.id], drawn)
        let after = e.store.messagesGeneration
        receipt("read", [a.id], "dev1")
        XCTAssertEqual(e.store.messagesGeneration, after)
        // Lu sans avoir été « reçu » d'abord : les deux d'un coup.
        receipt("read", [b.id], "dev1")
        XCTAssertEqual(e.store.message(c.id, b.id)?.deliveredTo, [x.code])
        XCTAssertEqual(e.store.message(c.id, b.id)?.readBy, [x.code])
        // Un accusé pour un message qui n'est pas de nous, ou inconnu : ignoré.
        e.handle(x.code, Wire(.msg(id: "r1", at: t, pn: "", content: Content(text: "De lui"))), ts: t, live: true)
        receipt("read", ["r1", "inconnu"], "dev1")
        XCTAssertEqual(e.store.message(c.id, "r1")?.readBy, [])
    }

    /// Une enveloppe accusée par un appareil : gardée, avec tout son contenu,
    /// tant qu'un autre l'attend ; partie quand plus rien ne l'attend ; et
    /// laissée telle quelle par un accusé déjà connu.
    func testAnAcknowledgedEnvelopeKeepsItsPayload() async throws {
        let e = phone()
        let x = Identity.create()
        let c = e.ensureDirectChat(x.code)
        let m = try XCTUnwrap(e.send(c.id, Content(text: "Bonjour")))
        let key = "\(m.id):\(x.code)"
        let before = try XCTUnwrap(e.store.read("outbox", key, as: OutItem.self))
        XCTAssertFalse(before.payload.isEmpty)
        // Pas encore partie (hors ligne) : l'accusé d'un appareil est noté.
        e.gotBy(key, "dev1")
        var want = before
        want.got = ["dev1"]
        XCTAssertEqual(e.store.read("outbox", key, as: OutItem.self), want)
        // Le même accusé : la ligne ne bouge pas.
        let row = e.store.readRaw("outbox", key)
        e.gotBy(key, "dev1")
        XCTAssertEqual(e.store.readRaw("outbox", key), row)
        e.gotBy(key, "dev2")
        want.got = ["dev1", "dev2"]
        XCTAssertEqual(e.store.read("outbox", key, as: OutItem.self), want)
        // Acceptée par le relais, et le message distribué : plus rien n'attend.
        var sent = want
        sent.boot = "B00T"
        e.store.write("outbox", key, sent)
        e.gotBy(key, "dev3")
        XCTAssertEqual(e.store.read("outbox", key, as: OutItem.self)?.got, ["dev1", "dev2", "dev3"], "le message n'est pas encore distribué")
        e.handle(x.code, Wire(.receipt(st: "delivered", ids: [m.id], dev: "dev1")), ts: nowMs(), live: true)
        XCTAssertNil(e.store.readRaw("outbox", key))
        // Sans appareil, ou sans enveloppe : rien, ou la ligne retirée.
        e.store.write("outbox", key, sent)
        e.gotBy(key, "")
        XCTAssertNil(e.store.readRaw("outbox", key))
        e.gotBy("inconnue", "dev1")
        e.store.writeRaw("outbox", "abîmée", "pas du JSON")
        e.gotBy("abîmée", "dev1")
        XCTAssertNil(e.store.readRaw("outbox", "abîmée"))
    }

    // ----- La recherche dans toutes les discussions -----

    /// Fouillées par tranches sur plusieurs cœurs, les discussions rendent
    /// exactement ce que rendait la lecture message après message : les
    /// mêmes messages, dans le même ordre, et l'heure de la prochaine capsule.
    func testSearchFindsWhatAPlainScanFinds() async {
        let now = 1_800_000_000_000.0
        var seed: UInt64 = 5
        func next(_ n: Int) -> Int {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Int((seed >> 33) % UInt64(n))
        }
        let texts = ["Un café ?", "CAFE ou thé", "on se voit demain", "Cafétéria fermée", "rien", "le cafe du coin", "École", "ecole"]
        var lists: [[Message]] = []
        for c in 0..<30 {
            var list: [Message] = []
            // Des discussions vides, courtes, et plus longues qu'une tranche.
            for i in 0..<[0, 3, 40, 2500][next(4)] {
                var m = Message(chat: "c\(c)", id: "m\(i)", from: next(3) == 0 ? "MOI" : "AMI", at: Double(next(100_000)), content: Content(text: texts[next(texts.count)]))
                switch next(12) {
                case 0: m.deleted = true
                case 1: m.kind = "system"
                case 2: m.openAt = now + Double(next(1000) + 1)
                case 3: m.openAt = now - 5
                case 4: m.kind = "file"; m.text = ""; m.name = "Café.pdf"
                default: break
                }
                list.append(m)
            }
            lists.append(list)
        }
        for q in ["café", "CAFE", "ecole", "thé", "introuvable", "f ?"] {
            var want: [Message] = []
            var open = Double.infinity
            for list in lists {
                for m in list where m.kind != "system" && !m.deleted {
                    if m.sealed(for: "MOI", now: now) { open = min(open, m.openAt ?? .infinity); continue }
                    if "\(m.text) \(m.name)".range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil { want.append(m) }
                }
            }
            let got = Message.matching(lists, q, me: "MOI", now: now)
            XCTAssertEqual(got.found, want, q)
            XCTAssertEqual(got.nextOpen, open, q)
        }
        XCTAssertTrue(Message.matching([], "café", me: "MOI", now: now).found.isEmpty)
        XCTAssertEqual(Message.matching([[]], "café", me: "MOI", now: now).nextOpen, .infinity)
    }

    /// La même recherche n'est pas refaite deux fois de suite, mais elle
    /// n'est jamais périmée : un message qui arrive ou part, un contact
    /// bloqué, une capsule qui s'ouvre la font refaire.
    func testSearchIsKeptButNeverStale() async throws {
        let e = phone()
        let x = Identity.create(), y = Identity.create()
        let c = e.ensureDirectChat(x.code)
        e.ensureDirectChat(y.code)
        let a = try XCTUnwrap(e.send(c.id, Content(text: "Rendez-vous au café")))
        e.send(y.code, Content(text: "Un autre CAFE"))
        XCTAssertTrue(e.searchMessages("c").isEmpty, "deux lettres au moins")
        XCTAssertEqual(Set(e.searchMessages("cafe").map(\.text)), ["Rendez-vous au café", "Un autre CAFE"])
        let kept = e.searchKept?.at
        XCTAssertEqual(e.searchMessages(" cafe ").count, 2)
        XCTAssertEqual(e.searchKept?.at, kept, "la même recherche, pas refaite")
        XCTAssertEqual(e.searchMessages("cafe", limit: 1).count, 1)
        XCTAssertEqual(e.searchMessages("rendez").map(\.id), [a.id])

        // Un message de plus, un de moins.
        let t = nowMs()
        e.handle(x.code, Wire(.msg(id: "r1", at: t, pn: "", content: Content(text: "Quel café ?"))), ts: t, live: true)
        XCTAssertEqual(e.searchMessages("cafe").count, 3)
        e.delete(c.id, a.id, forEveryone: false)
        XCTAssertEqual(Set(e.searchMessages("cafe").map(\.text)), ["Quel café ?", "Un autre CAFE"])
        // Un contact bloqué n'est plus fouillé ; débloqué, il l'est de nouveau.
        e.setBlocked(y.code, true)
        XCTAssertEqual(e.searchMessages("cafe").map(\.text), ["Quel café ?"])
        e.setBlocked(y.code, false)
        XCTAssertEqual(e.searchMessages("cafe").count, 2)

        // Une capsule scellée ne se trahit pas ; ouverte, elle se trouve.
        var capsule = Content(text: "Le café de l'an prochain")
        capsule.openAt = t + 400
        e.handle(x.code, Wire(.msg(id: "r2", at: t, pn: "", content: capsule)), ts: t, live: true)
        XCTAssertEqual(e.searchMessages("cafe").count, 2)
        XCTAssertEqual(e.searchMessages("cafe").count, 2)
        try await Task.sleep(nanoseconds: 600_000_000)
        XCTAssertEqual(e.searchMessages("cafe").count, 3)
    }

    // ----- Les actus -----

    /// Le ménage lit la date des actus sans décoder leurs photos : celles de
    /// plus de 24 heures partent, les autres restent, et la liste suit chaque
    /// changement de ligne.
    func testOldStatusesLeaveWithoutDecodingTheirPhotos() async {
        let e = phone()
        let now = nowMs()
        let photo = "data:image/jpeg;base64," + String(repeating: "/9j/4AAQ", count: 20_000)
        func status(_ id: String, hoursAgo: Double, kind: String = "image") -> StatusItem {
            StatusItem(owner: "WGSEAJNSZJ", id: id, kind: kind, text: "", bg: "", data: kind == "image" ? photo : "", at: now - hoursAgo * 3_600_000)
        }
        for st in [status("vieille", hoursAgo: 25), status("hier", hoursAgo: 23), status("texte", hoursAgo: 30, kind: "text"), status("neuve", hoursAgo: 1)] {
            e.store.write("statuses", st.key, st)
        }
        // Les dates, sans le contenu.
        XCTAssertEqual(Set(e.statusDates().map(\.key)), ["WGSEAJNSZJ|vieille", "WGSEAJNSZJ|hier", "WGSEAJNSZJ|texte", "WGSEAJNSZJ|neuve"])
        XCTAssertEqual(e.statusDates().first { $0.id == "hier" }?.at, now - 23 * 3_600_000)
        // Avant le ménage, la liste ne montre déjà que les moins de 24 heures.
        XCTAssertEqual(e.statuses().map(\.id), ["hier", "neuve"])
        e.sweep()
        XCTAssertEqual(Set(e.store.rawRows("statuses").keys), ["WGSEAJNSZJ|hier", "WGSEAJNSZJ|neuve"])
        XCTAssertEqual(e.statuses().map(\.id), ["hier", "neuve"])
        XCTAssertEqual(e.statuses().first?.data, photo)
        XCTAssertEqual(Set(e.statusHeads.keys), ["WGSEAJNSZJ|hier", "WGSEAJNSZJ|neuve"])
        // Une ligne qui change (vue), une qui arrive, une qui part : suivies.
        e.markStatusSeen(e.statuses()[1])
        XCTAssertEqual(e.statuses().map(\.seen), [false, true])
        let fresh = status("toute neuve", hoursAgo: 0)
        e.store.write("statuses", fresh.key, fresh)
        e.store.remove("statuses", "WGSEAJNSZJ|hier")
        XCTAssertEqual(e.statuses().map(\.id), ["neuve", "toute neuve"])
        e.sweep()
        XCTAssertEqual(e.statuses().map(\.id), ["neuve", "toute neuve"])
        XCTAssertEqual(Set(e.statusKept.keys), ["WGSEAJNSZJ|neuve", "WGSEAJNSZJ|toute neuve"])
        // La même liste que le décodage complet d'avant.
        let t = nowMs()
        XCTAssertEqual(e.statuses(), e.store.all("statuses", as: StatusItem.self).filter { $0.at + dayMs > t }.sorted { $0.at < $1.at })
    }

    // ----- La boîte d'envoi -----

    /// La boîte d'envoi lue sans son contenu : les mêmes enveloppes, dans le
    /// même ordre, que la lecture complète ; elle suit chaque changement de
    /// ligne, et le contenu reste dans la mémoire.
    func testOutboxIsReadWithoutItsPayloads() async {
        let e = phone()
        func full() -> [OutItem] { e.store.all("outbox", as: OutItem.self).sorted { $0.seq < $1.seq } }
        func heads() -> [OutItem] { e.outbox().map(\.head) }
        func stripped(_ items: [OutItem]) -> [OutItem] { items.map { var i = $0; i.payload = ""; return i } }
        XCTAssertTrue(e.outbox().isEmpty)

        let photo = String(repeating: "A", count: 200_000)
        for (i, to) in ["K7Q2MX9PRT", "WGSEAJNSZJ", "BGWQXQ2HU9"].enumerated() {
            let item = OutItem(id: "m\(i):\(to)", to: to, payload: "{\"k\":\"msg\",\"data\":\"\(photo)\"}",
                               chat: to, msgId: "m\(i)", seq: Double(10 - i))
            e.store.write("outbox", item.id, item)
        }
        XCTAssertEqual(heads(), stripped(full()))
        XCTAssertEqual(heads().map(\.msgId), ["m2", "m1", "m0"])
        // Chaque enveloppe vient avec sa ligne : le contenu s'y relit entier.
        for (head, json) in e.outbox() {
            XCTAssertTrue(head.payload.isEmpty)
            XCTAssertEqual(JSON.decode(OutItem.self, json)?.payload.count, photo.count + 21)
            XCTAssertEqual(JSON.decode(OutItem.self, json)?.id, head.id)
        }
        // Une ligne qui change (un accusé, le relais qui l'accepte), une qui
        // part, une qui arrive : relues ; les autres non.
        var changed = full()[0]
        changed.boot = "B00T"
        changed.got = ["dev1"]
        e.store.write("outbox", changed.id, changed)
        e.store.remove("outbox", "m0:K7Q2MX9PRT")
        e.store.write("outbox", "m9:K7Q2MX9PRT", OutItem(id: "m9:K7Q2MX9PRT", to: "K7Q2MX9PRT", payload: "{}", chat: "K7Q2MX9PRT", msgId: "m9", seq: 1))
        XCTAssertEqual(heads(), stripped(full()))
        XCTAssertEqual(heads().map(\.msgId), ["m9", "m2", "m1"])
        XCTAssertEqual(heads()[1].boot, "B00T")
        XCTAssertEqual(heads()[1].got, ["dev1"])
        XCTAssertEqual(Set(e.outHeads.keys), Set(full().map(\.id)), "rien de gardé pour une enveloppe partie")
        // Le contenu n'a pas quitté la mémoire.
        XCTAssertEqual(e.store.read("outbox", changed.id, as: OutItem.self)?.payload.count, photo.count + 21)
        e.store.clear("outbox")
        XCTAssertTrue(e.outbox().isEmpty)
        XCTAssertTrue(e.outHeads.isEmpty)
    }
}
