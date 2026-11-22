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

    /// Une tombe de plus s'écrit au bout de la ligne gardée, sans réencoder
    /// les autres : la ligne reste celle qu'on aurait réécrite en entier
    /// (identifiants, dates, ordre), doublons d'un même envoi compris, et
    /// une ligne d'une autre forme est réécrite comme avant.
    func testANewTombIsAppendedToTheStoredRow() async {
        let e = phone()
        let now = nowMs()
        var ref: [Engine.Tomb] = []
        func check(_ label: String) {
            let row = e.store.read("kv", "tombes:AMI", as: [Engine.Tomb].self) ?? []
            XCTAssertEqual(row.map(\.id), ref.map(\.id), label)
            XCTAssertEqual(row.map(\.at), ref.map(\.at), label)
            for tomb in ref { XCTAssertTrue(e.buried("AMI", tomb.id), label) }
        }
        let steps: [[(String, Double)]] = [
            [("a", now - 5000.5)],
            [("b", now - 4000), ("b", now - 3000), ("a", now), ("c \"é\"\\", now - 2000.25)],
            [("trop vieux", now - Engine.tombWindow - 1000)],
            [("d", now - 1)],
            (0..<300).map { ("m\($0)", now - Double($0)) },
            [("m7", now), ("e", now - 10)],
        ]
        for (i, msgs) in steps.enumerated() {
            let before = e.store.readRaw("kv", "tombes:AMI")
            e.bury("AMI", msgs)
            oldBury(&ref, msgs, now: nowMs())
            check("étape \(i)")
            // Rien de neuf : la ligne n'est pas réécrite.
            if i == 2 { XCTAssertEqual(e.store.readRaw("kv", "tombes:AMI"), before) }
        }
        XCTAssertEqual(ref.count, 305)
        // La ligne fait foi : relue par un autre moteur, elle dit la même chose.
        let again = Engine(store: e.store, server: URL(string: "http://localhost:1")!)
        XCTAssertTrue(again.buried("AMI", "c \"é\"\\"))
        XCTAssertTrue(again.buried("AMI", "m299"))
        XCTAssertFalse(again.buried("AMI", "trop vieux"))
        // Une ligne écrite autrement (des blancs autour) : réécrite en entier.
        e.store.writeRaw("kv", "tombes:AMI", " [ {\"id\":\"x\",\"at\":\(now - 100)} ] \n")
        ref = [Engine.Tomb(id: "x", at: now - 100)]
        e.bury("AMI", [("y", now - 50)])
        oldBury(&ref, [("y", now - 50)], now: nowMs())
        check("ligne d'une autre forme")
        e.bury("AMI", [("z", now - 25)])
        oldBury(&ref, [("z", now - 25)], now: nowMs())
        check("puis au bout")
        XCTAssertEqual(ref.map(\.id), ["x", "y", "z"])
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
        XCTAssertTrue(Message.matching([[Message]](), "café", me: "MOI", now: now).found.isEmpty)
        XCTAssertEqual(Message.matching([[Message]()], "café", me: "MOI", now: now).nextOpen, .infinity)
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

    /// La recherche dans une discussion (son écran) trouve ce que l'écran
    /// trouvait en la lisant en entier, dans le même ordre ; un mot rare ne
    /// la charge pas ; gardée d'un dessin à l'autre, elle n'est jamais
    /// périmée (message reçu, supprimé, modifié, capsule qui s'ouvre).
    func testSearchInAChatFindsWhatTheScreenFound() async throws {
        let e = phone()
        let c = e.ensureDirectChat("K7Q2MX9PRT")
        let other = e.ensureDirectChat("WGSEAJNSZJ")
        let now = nowMs()
        e.store.transaction {
            for i in 0..<700 {
                var content = Content(text: i % 100 == 42 ? "Un café numéro \(i)" : "Message \(i)")
                if i == 300 { content = Content(kind: "file", text: "", data: "data:application/pdf;base64,AAAA", name: "Carte du Café.pdf") }
                var m = Message(chat: c.id, id: "m\(i)", from: i % 2 == 0 ? c.id : e.me.code, at: now - Double(1000 - i) * 1000, content: content)
                if i == 142 { m.deleted = true }
                if i == 242 { m.kind = "system" }
                // Une capsule encore scellée (reçue), une autre écrite par soi.
                if i == 342 || i == 343 { m.openAt = now + 3_600_000; m.text = "Le café de plus tard" }
                if i == 442 { m.exp = now + 600_000 }
                e.store.writeMessage(m)
            }
            e.store.writeMessage(Message(chat: other.id, id: "x", from: other.id, at: now, content: Content(text: "Un café ailleurs")))
        }
        /// Ce que faisait l'écran : toute la discussion, filtrée.
        func screen(_ engine: Engine, _ q: String) -> [String] {
            engine.messages(c.id).filter { m in
                m.kind != "system" && !m.deleted && !m.sealed(for: engine.me.code)
                    && "\(m.text) \(m.name)".range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil
            }.map(\.id)
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        XCTAssertTrue(cold.searchChat(c.id, "  ").isEmpty)
        // Un mot rare : trouvé sans lire la discussion en entier.
        let found = cold.searchChat(c.id, " CAFE ")
        XCTAssertEqual(found.map(\.id), ["m42", "m300", "m343", "m442", "m542", "m642"])
        XCTAssertEqual(found.first?.text, "Un café numéro 42")
        XCTAssertEqual(found[1].name, "Carte du Café.pdf")
        XCTAssertTrue(cold.store.messages.isEmpty)
        // La même recherche n'est pas refaite ; une autre, si.
        let kept = cold.chatSearchKept?.at
        XCTAssertEqual(cold.searchChat(c.id, "CAFE  ").count, 6)
        XCTAssertEqual(cold.chatSearchKept?.at, kept)
        XCTAssertEqual(cold.searchChat(c.id, "numéro 64").map(\.id), ["m642"])
        XCTAssertEqual(cold.searchChat(other.id, "cafe").map(\.id), ["x"])
        XCTAssertTrue(cold.store.messages.isEmpty)
        // Un message reçu, un supprimé, un modifié : la recherche suit.
        cold.storeMessage(Message(chat: c.id, id: "neuf", from: c.id, at: nowMs(), content: Content(text: "Encore un CAFÉ")))
        cold.delete(c.id, "m42", forEveryone: false)
        var edited = try XCTUnwrap(cold.store.message(c.id, "m542"))
        edited.text = "Un thé"
        cold.store.writeMessage(edited)
        XCTAssertEqual(cold.searchChat(c.id, "cafe").map(\.id), ["m300", "m343", "m442", "m642", "neuf"])
        XCTAssertTrue(cold.store.messages.isEmpty)
        // Les mêmes que l'écran d'avant, pour des mots rares ou très courants
        // (une lettre, ou plus d'une centaine de messages : la discussion est lue).
        for q in ["cafe", "Message 1", "9", "m", "é", "numero", "pdf", "zzz", "plus tard", "message 69", "E 6"] {
            XCTAssertEqual(cold.searchChat(c.id, q).map(\.id), screen(cold, q), "« \(q) »")
        }
        XCTAssertGreaterThan(cold.searchChat(c.id, "Message 1").count, Engine.searchSingles)
        XCTAssertEqual(cold.searchChat(c.id, "zzz"), [])
        // Une capsule scellée ne se trahit pas ; ouverte, elle se trouve.
        let t = nowMs()
        var capsule = Message(chat: c.id, id: "capsule", from: c.id, at: t, content: Content(text: "Le café de tout à l'heure"))
        capsule.openAt = t + 400
        cold.storeMessage(capsule)
        XCTAssertEqual(cold.searchChat(c.id, "tout à l'heure"), [])
        XCTAssertEqual(cold.searchChat(c.id, "tout à l'heure"), [])
        try await Task.sleep(nanoseconds: 600_000_000)
        XCTAssertEqual(cold.searchChat(c.id, "tout à l'heure").map(\.id), ["capsule"])
        // Quitter la discussion rend ce que la recherche tenait.
        cold.activeChat = c.id
        XCTAssertEqual(cold.searchChat(c.id, "neuf").count, 0)
        XCTAssertEqual(cold.searchChat(c.id, "encore un").map(\.id), ["neuf"])
        XCTAssertNotNil(cold.chatSearchKept)
        cold.activeChat = nil
        XCTAssertNil(cold.chatSearchKept)
        XCTAssertEqual(cold.searchChat(c.id, "encore un").map(\.id), ["neuf"])
        cold.foreground = false
        XCTAssertNil(cold.chatSearchKept)
    }

    /// Des recherches de suite dans une longue discussion (dans son écran
    /// ou dans toutes les discussions) ne finissent pas par la charger en
    /// entier ni par la garder en mémoire : lire un message trouvé ne compte
    /// pas parmi les lectures qui font charger une discussion.
    func testSearchingAgainAndAgainDoesNotLoadTheChat() async throws {
        let e = phone()
        let c = e.ensureDirectChat("K7Q2MX9PRT")
        let now = nowMs()
        let words = ["anniversaire", "vacances", "piscine", "dentiste", "facture", "concert"]
        e.store.transaction {
            for i in 0..<900 {
                // Chaque mot dans trente messages, répartis dans toute la discussion.
                let text = "Message \(i)" + (i % 30 < words.count ? " \(words[i % 30])" : "")
                e.store.writeMessage(Message(chat: c.id, id: "m\(i)", from: c.id, at: now - Double(1000 - i) * 1000, content: Content(text: text)))
            }
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        for (n, word) in words.enumerated() {
            let here = cold.searchChat(c.id, word)
            XCTAssertEqual(here.count, 30)
            XCTAssertEqual(here.first?.text, "Message \(n) \(word)")
            XCTAssertEqual(here.last?.id, "m\(870 + n)")
            let everywhere = cold.searchMessages(word, limit: 40)
            XCTAssertEqual(everywhere.count, 30)
            XCTAssertEqual(everywhere.first?.id, "m\(870 + n)")
        }
        // 180 messages lus, plus qu'il n'en faut pour charger une discussion
        // quand ils sont demandés un par un.
        XCTAssertGreaterThan(words.count * 30, Store.loadAfter)
        XCTAssertTrue(cold.store.messages.isEmpty)
        XCTAssertTrue(cold.store.busy.isEmpty)
        XCTAssertTrue(cold.store.singles.isEmpty)
        // Demandés un par un pour autre chose, ils comptent toujours.
        for i in 0...Store.loadAfter { _ = cold.store.message(c.id, "m\(i)") }
        XCTAssertEqual(cold.store.messages[c.id]?.count, 900)
        XCTAssertEqual(cold.store.busy, [c.id])
        XCTAssertEqual(cold.store.passingMessage(c.id, "m5")?.text, "Message 5 concert")
        XCTAssertNil(cold.store.passingMessage(c.id, "absent"))
    }

    /// Les messages importants d'une longue discussion pas chargée : leur
    /// compte et leur liste (les plus récents d'abord) sans la lire en
    /// entier, et « tout vider sauf eux » les garde.
    func testStarredMessagesOfAChatThatIsNotLoaded() async throws {
        let e = phone()
        let c = e.ensureDirectChat("K7Q2MX9PRT")
        let now = nowMs()
        e.store.transaction {
            for i in 0..<400 {
                var m = Message(chat: c.id, id: "m\(i)", from: c.id, at: now - Double(1000 - i) * 1000, content: Content(text: "Message \(i)"))
                m.starred = i == 30 || i == 200 || i == 399
                e.store.writeMessage(m)
            }
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        XCTAssertEqual(cold.starredCount(in: c.id), 3)
        XCTAssertTrue(cold.hasStarred(c.id))
        XCTAssertEqual(cold.starred(in: c.id).map(\.text), ["Message 399", "Message 200", "Message 30"])
        XCTAssertNil(cold.store.messages[c.id])
        // La même liste qu'en lisant tout.
        XCTAssertEqual(cold.starred(in: c.id), cold.store.messagesOf(c.id).filter(\.isStarred).sorted { $0.at > $1.at })
        // Une étoile retirée, une posée.
        cold.toggleStar(c.id, "m200")
        cold.toggleStar(c.id, "m7")
        XCTAssertEqual(cold.starredCount(in: c.id), 3)
        XCTAssertEqual(cold.starred(in: c.id).map(\.text), ["Message 399", "Message 30", "Message 7"])
        // Tout vider sauf eux.
        cold.clearChat(c.id, keepStarred: true)
        XCTAssertEqual(cold.messages(c.id).map(\.text), ["Message 7", "Message 30", "Message 399"])
        XCTAssertEqual(cold.starredCount(in: c.id), 3)
        cold.clearChat(c.id)
        XCTAssertFalse(cold.hasStarred(c.id))
        XCTAssertEqual(cold.starredCount(in: c.id), 0)
        // Aucune discussion de ce nom : rien.
        XCTAssertEqual(cold.starredCount(in: "INCONNU"), 0)
    }

    /// « Gérer le stockage » ne charge aucune discussion : les plus lourdes
    /// d'abord, celles sans pièce jointe écartées.
    func testStorageByChatLoadsNoChat() async throws {
        let e = phone()
        let a = e.ensureDirectChat("K7Q2MX9PRT")
        let b = e.ensureDirectChat("WGSEAJNSZJ")
        let c = e.ensureDirectChat("AAAAAAAAAA")
        func photo(_ chat: String, _ id: String, size: Double) -> Message {
            var content = Content(text: "")
            content.kind = "image"
            content.data = "data:image/jpeg;base64,/9j/4AAQ"
            content.size = size
            return Message(chat: chat, id: id, from: chat, at: nowMs(), content: content)
        }
        e.store.transaction {
            for i in 0..<400 {
                for chat in [a.id, b.id, c.id] {
                    e.store.writeMessage(Message(chat: chat, id: "m\(i)", from: chat, at: Double(i), content: Content(text: "Message \(i)")))
                }
            }
            e.store.writeMessage(photo(a.id, "p1", size: 1_000))
            e.store.writeMessage(photo(b.id, "p1", size: 5_000))
            e.store.writeMessage(photo(b.id, "p2", size: 2_000))
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        let rows = cold.storageByChat()
        XCTAssertEqual(rows.map(\.chat.id), [b.id, a.id])
        XCTAssertEqual(rows.map(\.storage), [Storage(bytes: 7_000, photos: 2), Storage(bytes: 1_000, photos: 1)])
        XCTAssertTrue(cold.store.messages.isEmpty)
        // Une photo reçue, une supprimée : la liste suit, toujours sans rien charger.
        cold.storeMessage(photo(c.id, "p1", size: 9_000))
        cold.delete(b.id, "p1", forEveryone: false)
        XCTAssertEqual(cold.storageByChat().map(\.chat.id), [c.id, b.id, a.id])
        XCTAssertEqual(cold.storageByChat().map(\.storage.bytes), [9_000, 2_000, 1_000])
        XCTAssertTrue(cold.store.messages.isEmpty)
    }

    /// Les messages importants de toutes les discussions : les mêmes qu'en
    /// relisant tous les messages, sans charger aucune discussion, et la
    /// liste suit ce qui arrive.
    func testAllStarredMessagesWithoutReadingEverything() async throws {
        let e = phone()
        let a = e.ensureDirectChat("K7Q2MX9PRT")
        let b = e.ensureDirectChat("WGSEAJNSZJ")
        let now = nowMs()
        e.store.transaction {
            for i in 0..<400 {
                for chat in [a.id, b.id, "ORPHELINE"] {
                    var m = Message(chat: chat, id: "m\(i)", from: chat, at: now - Double(1000 - i) * 1000 - (chat == a.id ? 500 : 0),
                                    content: Content(text: "Message \(i) de \(chat)"))
                    m.starred = (chat == a.id && i == 5) || (chat == b.id && (i == 100 || i == 399)) || (chat == "ORPHELINE" && i == 50)
                    // Supprimé pour tous : son étoile ne compte plus.
                    if chat == b.id, i == 200 { m.starred = true; m.deleted = true }
                    e.store.writeMessage(m)
                }
            }
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        let all = cold.starred()
        XCTAssertEqual(all.map(\.key), ["\(b.id)|m399", "\(b.id)|m100", "ORPHELINE|m50", "\(a.id)|m5"])
        XCTAssertTrue(cold.store.messages.isEmpty)
        XCTAssertEqual(all.sorted { $0.key < $1.key }, cold.store.allMessages().filter(\.isStarred).sorted { $0.key < $1.key })
        XCTAssertEqual(Set(cold.store.chatsWithMessages()), [a.id, b.id, "ORPHELINE"])
        // Une étoile posée, une retirée, un message important qui arrive.
        cold.toggleStar(a.id, "m7")
        cold.toggleStar(b.id, "m100")
        var fresh = Message(chat: a.id, id: "neuf", from: a.id, at: nowMs(), content: Content(text: "Important"))
        fresh.starred = true
        cold.storeMessage(fresh)
        XCTAssertEqual(cold.starred().map(\.key), ["\(a.id)|neuf", "\(b.id)|m399", "ORPHELINE|m50", "\(a.id)|m7", "\(a.id)|m5"])
        XCTAssertTrue(cold.store.messages.isEmpty)
        // Une discussion vidée n'en a plus.
        cold.clearChat(a.id)
        XCTAssertEqual(cold.starred().map(\.key), ["\(b.id)|m399", "ORPHELINE|m50"])
    }

    /// Ouvrir une autre discussion, ou passer à l'arrière-plan, rend la
    /// mémoire d'une longue discussion lue en entier ; celle qui est à
    /// l'écran reste.
    func testLongChatsAreReleasedWhenAnotherOpens() async throws {
        let e = phone()
        let a = e.ensureDirectChat("K7Q2MX9PRT")
        let b = e.ensureDirectChat("WGSEAJNSZJ")
        e.store.transaction {
            for i in 0..<800 {
                for chat in [a.id, b.id] {
                    e.store.writeMessage(Message(chat: chat, id: "m\(i)", from: chat, at: Double(i), content: Content(text: "Message \(i)")))
                }
            }
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        cold.activeChat = a.id
        XCTAssertEqual(cold.messages(a.id).count, 800)
        XCTAssertNotNil(cold.store.messages[a.id])
        // L'écran des infos la recouvre, on y revient : elle est toujours là.
        cold.activeChat = nil
        cold.activeChat = a.id
        XCTAssertNotNil(cold.store.messages[a.id])
        // Une autre s'ouvre.
        cold.activeChat = nil
        cold.activeChat = b.id
        XCTAssertNil(cold.store.messages[a.id])
        XCTAssertEqual(cold.messages(b.id).count, 800)
        XCTAssertEqual(cold.recentMessages(a.id, limit: 300).list.last?.text, "Message 799")
        XCTAssertEqual(cold.messages(a.id).count, 800)
        // À l'arrière-plan : celle qui est à l'écran reste, l'autre part.
        cold.foreground = false
        XCTAssertNotNil(cold.store.messages[b.id])
        XCTAssertNil(cold.store.messages[a.id])
        cold.foreground = true
        XCTAssertEqual(cold.messages(a.id).count, 800)
    }

    /// La recherche dans toutes les discussions ne charge aucune discussion :
    /// les mêmes résultats qu'en lisant tout, et elle suit ce qui arrive.
    func testSearchingEveryChatLoadsNone() async throws {
        let e = phone()
        let a = e.ensureDirectChat("K7Q2MX9PRT")
        let b = e.ensureDirectChat("WGSEAJNSZJ")
        let now = nowMs()
        e.store.transaction {
            for i in 0..<700 {
                for chat in [a.id, b.id] {
                    let text = i % 100 == 42 ? "Un café numéro \(i) chez \(chat)" : "Message \(i)"
                    e.store.writeMessage(Message(chat: chat, id: "m\(i)", from: chat, at: now - Double(1000 - i) * 1000 - (chat == a.id ? 500 : 0), content: Content(text: text)))
                }
            }
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        let found = cold.searchMessages("cafe", limit: 5)
        XCTAssertEqual(found.map(\.key), ["\(b.id)|m642", "\(a.id)|m642", "\(b.id)|m542", "\(a.id)|m542", "\(b.id)|m442"])
        XCTAssertEqual(found.first?.text, "Un café numéro 642 chez \(b.id)")
        XCTAssertTrue(cold.store.messages.isEmpty)
        // Les mêmes qu'en fouillant les discussions lues en entier.
        let whole = Message.matching([a.id, b.id].map { cold.store.messagesOf($0) }, "cafe", me: cold.me.code, now: nowMs()).found
        XCTAssertEqual(cold.searchMessages("cafe", limit: 100).sorted { $0.key < $1.key }, whole.sorted { $0.key < $1.key })
        XCTAssertEqual(whole.count, 14)
        // Une autre discussion s'ouvre (les longues rendent leur mémoire), un
        // message arrive, un autre est supprimé : la recherche suit, sans rien recharger.
        cold.activeChat = "AAAAAAAAAA"
        XCTAssertTrue(cold.store.messages.isEmpty)
        cold.storeMessage(Message(chat: a.id, id: "neuf", from: a.id, at: nowMs(), content: Content(text: "Encore un CAFÉ")))
        cold.delete(b.id, "m642", forEveryone: false)
        XCTAssertEqual(cold.searchMessages("cafe", limit: 3).map(\.key), ["\(a.id)|neuf", "\(a.id)|m642", "\(b.id)|m542"])
        XCTAssertTrue(cold.store.messages.isEmpty)
        // Un contact bloqué n'est plus fouillé.
        cold.setBlocked(a.id, true)
        XCTAssertEqual(cold.searchMessages("cafe", limit: 2).map(\.key), ["\(b.id)|m542", "\(b.id)|m442"])
        cold.setBlocked(a.id, false)
        // À l'arrière-plan les textes sont oubliés ; au retour la recherche les relit.
        cold.foreground = false
        XCTAssertTrue(cold.store.linesIn.isEmpty)
        cold.foreground = true
        XCTAssertEqual(cold.searchMessages("encore un").map(\.key), ["\(a.id)|neuf"])
        XCTAssertTrue(cold.store.messages.isEmpty)
    }

    // ----- L'envoi à un groupe -----

    /// Un envoi à tout un groupe n'encode et n'échappe son contenu qu'une
    /// fois : la boîte d'envoi garde pourtant, pour chaque membre, exactement
    /// la même ligne qu'en l'encodant un par un.
    func testSendingToAGroupWritesTheSameOutboxRows() async throws {
        // Le JSON assemblé se relit comme celui de l'encodeur (qui ne range
        // pas ses clés deux fois dans le même ordre : les textes ne se comparent pas).
        for payload in ["", "simple", "des \"guillemets\" et des \\ barres / obliques\n\tet un saut",
                        "accents éàü, emoji 🥦, \u{1}contenu\u{1} (la marque elle-même)", String(repeating: "/9j/4AAQ+abc=", count: 3_000)] {
            var item = OutItem(id: "m1:AMI", to: "AMI", payload: payload, chat: "g:groupe", msgId: "m1", seq: 1_234_567, sent: ["a", "b"])
            item.boot = "démarrage"
            item.got = ["a"]
            let json = item.json(escapedPayload: JSON.encode(payload))
            XCTAssertEqual(JSON.decode(OutItem.self, json), item)
            XCTAssertEqual(json.utf8.count, JSON.encode(item).utf8.count)
        }

        let e = phone()
        let members = (0..<12).map { String(format: "AMI%07d", $0) }
        let group = e.createGroup(name: "Les douze", members: members, avatar: "")
        for key in e.store.rawRows("outbox").keys { e.store.remove("outbox", key) }
        var photo = Content(text: "Une \"légende\" / avec des barres \\")
        photo.kind = "image"
        photo.data = "data:image/jpeg;base64," + String(repeating: "/9j/4AAQSkZJRg+abc=", count: 2_000)
        let m = try XCTUnwrap(e.send(group.id, photo))
        let rows = e.store.rawRows("outbox")
        XCTAssertEqual(Set(rows.keys), Set(members.map { "\(m.id):\($0)" }))
        var payloads = Set<String>()
        for (key, json) in rows {
            let item = try XCTUnwrap(JSON.decode(OutItem.self, json))
            XCTAssertEqual(item.id, key)
            XCTAssertEqual(item.chat, group.id)
            XCTAssertEqual(item.msgId, m.id)
            XCTAssertEqual(json.utf8.count, JSON.encode(item).utf8.count, "la même ligne qu'encodée d'un bloc")
            XCTAssertEqual(item.to, String(key.split(separator: ":").last ?? ""))
            XCTAssertTrue(item.sent.isEmpty && item.got.isEmpty && item.boot.isEmpty)
            payloads.insert(item.payload)
        }
        // Le même contenu pour tous, et c'est bien le message.
        XCTAssertEqual(payloads.count, 1)
        let wire = try XCTUnwrap(Wire.parse(try XCTUnwrap(payloads.first)))
        guard case .msg(let id, _, _, let content) = wire.body else { return XCTFail("pas un message") }
        XCTAssertEqual(id, m.id)
        XCTAssertEqual(content.data, photo.data)
        XCTAssertEqual(content.text, photo.text)
        // À une seule personne : une ligne, comme avant.
        let one = e.ensureDirectChat("K7Q2MX9PRT")
        let direct = try XCTUnwrap(e.send(one.id, Content(text: "Bonjour")))
        let row = try XCTUnwrap(e.store.read("outbox", "\(direct.id):K7Q2MX9PRT", as: OutItem.self))
        XCTAssertEqual(row.to, "K7Q2MX9PRT")
        XCTAssertEqual(row.msgId, direct.id)
    }

    /// Une actu part à chaque contact enregistré (pas aux bloqués ni aux
    /// inconnus), avec le même contenu pour tous ; une réaction dans un
    /// groupe, à chaque membre.
    func testAStatusAndAGroupReactionReachEveryone() async throws {
        let e = phone()
        for i in 0..<8 {
            var c = Contact(code: String(format: "AMI%07d", i))
            c.saved = i != 6
            c.blocked = i == 7
            e.saveContact(c)
        }
        let photo = "data:image/jpeg;base64," + String(repeating: "/9j/4AAQ\"\\/+abc=", count: 500)
        e.postStatus(kind: "image", text: "Une \"légende\"", bg: "", data: photo)
        let st = try XCTUnwrap(e.statuses().first)
        var rows = e.store.all("outbox", as: OutItem.self)
        XCTAssertEqual(Set(rows.map(\.to)), Set((0..<6).map { String(format: "AMI%07d", $0) }))
        XCTAssertEqual(Set(rows.map(\.payload)).count, 1)
        guard case .status(let id, let kind, let text, _, let data, _) = try XCTUnwrap(Wire.parse(rows[0].payload)).body else {
            return XCTFail("pas une actu")
        }
        XCTAssertEqual([id, kind, text], [st.id, "image", "Une \"légende\""])
        XCTAssertEqual(data, photo)
        // La retirer prévient les mêmes.
        for key in e.store.rawRows("outbox").keys { e.store.remove("outbox", key) }
        e.deleteStatus(st.id)
        rows = e.store.all("outbox", as: OutItem.self)
        XCTAssertEqual(rows.count, 6)
        XCTAssertEqual(Set(rows.map(\.payload)).count, 1)
        // Une réaction dans un groupe : une ligne par membre.
        let members = (0..<5).map { String(format: "AMI%07d", $0) }
        let group = e.createGroup(name: "Cinq", members: members, avatar: "")
        let m = try XCTUnwrap(e.send(group.id, Content(text: "Bonjour")))
        for key in e.store.rawRows("outbox").keys { e.store.remove("outbox", key) }
        e.react(group.id, m.id, "👍")
        rows = e.store.all("outbox", as: OutItem.self)
        XCTAssertEqual(Set(rows.map(\.to)), Set(members))
        XCTAssertEqual(Set(rows.map(\.payload)).count, 1)
        XCTAssertEqual(Set(rows.map(\.id)).count, 5)
    }

    /// Tout marquer comme lu : chaque message reçu l'est, sur le disque
    /// aussi, un accusé de lecture part par discussion, et les compteurs
    /// tombent à zéro.
    func testMarkingEverythingReadIsKept() async throws {
        let e = phone()
        let codes = ["K7Q2MX9PRT", "WGSEAJNSZJ", "AAAAAAAAAA"]
        e.store.transaction {
            for code in codes {
                var chat = e.ensureDirectChat(code)
                for i in 0..<120 {
                    let from = i % 10 == 0 ? e.me.code : code
                    e.store.writeMessage(Message(chat: code, id: "m\(i)", from: from, at: Double(i), content: Content(text: "Message \(i)")))
                }
                chat.unread = code == "AAAAAAAAAA" ? 0 : 108
                e.saveChat(chat)
            }
        }
        for key in e.store.rawRows("outbox").keys { e.store.remove("outbox", key) }
        XCTAssertEqual(e.markAllRead(), 2)
        XCTAssertEqual(codes.map { e.chat($0)?.unread }, [0, 0, 0])
        // Relu depuis le disque : lus dans les deux discussions marquées, pas dans la troisième.
        let cold = Store(directory: e.store.dir)
        for code in codes.prefix(2) {
            let received = cold.messagesOf(code).filter { $0.from == code }
            XCTAssertEqual(received.count, 108)
            XCTAssertTrue(received.allSatisfy(\.readByMe))
            XCTAssertFalse(cold.messagesOf(code).filter { $0.from == e.me.code }.contains(where: \.readByMe))
        }
        XCTAssertFalse(cold.messagesOf("AAAAAAAAAA").contains(where: \.readByMe))
        // Un accusé de lecture attend pour chaque discussion lue (ils partent groupés, peu après).
        if e.settings.readReceipts {
            XCTAssertEqual(Set(e.receipts.keys), Set(codes.prefix(2).map { "\($0)|read|\($0)" }))
            XCTAssertEqual(e.receipts.values.map(\.count), [108, 108])
        }
        // Rien de plus à faire la deuxième fois.
        XCTAssertEqual(e.markAllRead(), 0)
        // Une discussion ouverte très en retard.
        e.updateChat("AAAAAAAAAA") { $0.unread = 108 }
        e.markRead("AAAAAAAAAA")
        XCTAssertEqual(e.chat("AAAAAAAAAA")?.unread, 0)
        XCTAssertTrue(Store(directory: e.store.dir).messagesOf("AAAAAAAAAA").filter { $0.from == "AAAAAAAAAA" }.allSatisfy(\.readByMe))
    }

    /// Vider ou supprimer une longue discussion pas chargée : les messages
    /// récents sont enterrés (ils ne reviendront pas), les anciens non, les
    /// importants restent si on le demande, et rien n'est chargé pour ça.
    func testClearingALongChatWithoutLoadingIt() async throws {
        let e = phone()
        let codes = ["K7Q2MX9PRT", "WGSEAJNSZJ", "AAAAAAAAAA"]
        let now = nowMs()
        e.store.transaction {
            for code in codes {
                var chat = e.ensureDirectChat(code)
                for i in 0..<700 {
                    // Les cent premiers ont plus de 31 jours.
                    let at = i < 100 ? now - Engine.tombWindow - Double(1000 - i) * 1000 : now - Double(1000 - i) * 1000
                    var m = Message(chat: code, id: "m\(i)", from: code, at: at, content: Content(text: "Message \(i)"))
                    m.starred = i == 50 || i == 600
                    if i == 650 { m.starred = true; m.deleted = true }
                    e.store.writeMessage(m)
                    if i == 699 { chat.last = e.lastOf(m); chat.lastAt = m.at }
                }
                chat.unread = 4
                e.saveChat(chat)
            }
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        // Tout vider.
        cold.clearChat(codes[0])
        XCTAssertTrue(cold.messages(codes[0]).isEmpty)
        XCTAssertTrue(cold.buried(codes[0], "m699"))
        XCTAssertTrue(cold.buried(codes[0], "m100"))
        XCTAssertFalse(cold.buried(codes[0], "m99"), "trop vieux pour revenir : pas enterré")
        XCTAssertNil(cold.chat(codes[0])?.last)
        XCTAssertEqual(cold.chat(codes[0])?.unread, 0)
        // Vider sauf les importants (une étoile sur un message supprimé ne le garde pas).
        cold.clearChat(codes[1], keepStarred: true)
        XCTAssertNil(cold.store.messages[codes[1]], "pas chargée pour être vidée")
        XCTAssertEqual(cold.messages(codes[1]).map(\.id), ["m50", "m600"])
        XCTAssertFalse(cold.buried(codes[1], "m600"))
        XCTAssertTrue(cold.buried(codes[1], "m650"))
        XCTAssertTrue(cold.buried(codes[1], "m699"))
        XCTAssertFalse(cold.buried(codes[1], "m99"))
        XCTAssertEqual(cold.chat(codes[1])?.last?.text, "Message 600")
        XCTAssertEqual(cold.starredCount(in: codes[1]), 2)
        // Supprimer.
        cold.deleteChat(codes[2])
        XCTAssertNil(cold.chat(codes[2]))
        XCTAssertTrue(cold.buried(codes[2], "m300"))
        XCTAssertTrue(Store(directory: e.store.dir).messagesOf(codes[2]).isEmpty)
        XCTAssertEqual(Store(directory: e.store.dir).messagesOf(codes[1]).count, 2)
    }

    /// Un accusé de lecture pour des centaines de messages ne charge pas la
    /// discussion : chacun est marqué, y compris un vieux message hors de la
    /// fin lue d'un coup, et un accusé rejoué ne réécrit rien.
    func testAReceiptForHundredsOfMessagesLoadsNothing() async throws {
        let e = phone()
        let x = "K7Q2MX9PRT"
        _ = e.ensureDirectChat(x)
        let now = nowMs()
        e.store.transaction {
            for i in 0..<2_000 {
                var m = Message(chat: x, id: "m\(i)", from: i % 2 == 0 ? e.me.code : x, at: now - Double(2_000 - i) * 1000, content: Content(text: "Message \(i)"))
                if i % 2 == 0 { m.to = [x]; m.status = "sent" }
                e.store.writeMessage(m)
            }
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        // Les 200 derniers messages envoyés, un très ancien, un reçu (pas à nous) et un inconnu.
        let ids = (0..<200).map { "m\(1_600 + $0 * 2)" } + ["m10", "m1999", "inconnu"]
        cold.handle(x, Wire(.receipt(st: "read", ids: ids, dev: "")), ts: nowMs(), live: true)
        XCTAssertNil(cold.store.messages[x], "pas chargée")
        for id in ["m1600", "m1998", "m10"] {
            let m = try XCTUnwrap(cold.store.message(x, id))
            XCTAssertEqual(m.readBy, [x], id)
            XCTAssertEqual(m.deliveredTo, [x], id)
            XCTAssertEqual(m.tick, .read, id)
        }
        XCTAssertEqual(cold.store.message(x, "m1999")?.readBy, [], "un message reçu n'a pas d'accusé")
        XCTAssertEqual(cold.store.message(x, "m1598")?.readBy, [], "pas dans l'accusé")
        // Gardé sur le disque.
        let disk = Store(directory: e.store.dir)
        XCTAssertEqual(disk.messagesOf(x).filter { $0.readBy == [x] }.count, 201)
        // Rejoué (un autre appareil du contact) : rien n'est réécrit.
        let generation = cold.store.messagesGeneration
        let revision = cold.revisions[x]
        cold.handle(x, Wire(.receipt(st: "read", ids: ids, dev: "")), ts: nowMs(), live: true)
        XCTAssertEqual(cold.store.messagesGeneration, generation)
        XCTAssertEqual(cold.revisions[x], revision)
        // Un accusé de réception seul, pour un message : comme avant.
        cold.handle(x, Wire(.receipt(st: "delivered", ids: ["m1598"], dev: "")), ts: nowMs(), live: true)
        XCTAssertEqual(cold.store.message(x, "m1598")?.deliveredTo, [x])
        XCTAssertEqual(cold.store.message(x, "m1598")?.readBy, [])
        XCTAssertNil(cold.store.messages[x])
    }

    /// Le ménage des vieux messages ne lit que leur âge : les trop vieux
    /// partent, sauf les importants et une capsule pas encore ouverte depuis
    /// assez longtemps ; la discussion n'est pas chargée, et une discussion
    /// chargée est nettoyée de la même façon.
    func testOldMessagesLeaveByTheirAgeAlone() async throws {
        let e = phone()
        let codes = ["K7Q2MX9PRT", "WGSEAJNSZJ"]
        let now = nowMs()
        let limit = now - Double(e.settings.keepDays) * 86_400_000
        e.store.transaction {
            for code in codes {
                var chat = e.ensureDirectChat(code)
                for i in 0..<400 {
                    // Les 150 premiers sont trop vieux.
                    let at = (i < 150 ? limit - 3_600_000 : now) - Double(400 - i) * 1000
                    var m = Message(chat: code, id: "m\(i)", from: code, at: at, content: Content(text: "Message \(i)"))
                    if i == 10 || i == 300 { m.starred = true }
                    if i == 20 { m.starred = true; m.deleted = true }
                    if i == 30 { m.openAt = now - 1_000 }
                    if i == 40 { m.openAt = limit - 10_000 }
                    e.store.writeMessage(m)
                    if i == 399 { chat.last = e.lastOf(m); chat.lastAt = m.at }
                }
                e.saveChat(chat)
            }
        }
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        _ = cold.messages(codes[1])
        cold.sweep()
        XCTAssertNil(cold.store.messages[codes[0]], "pas chargée pour son ménage")
        XCTAssertNotNil(cold.store.messages[codes[1]])
        for code in codes {
            let left = cold.store.datesOf(code).map(\.0)
            XCTAssertEqual(left.count, 252, code)
            XCTAssertEqual(Array(left.prefix(3)), ["m10", "m30", "m150"], code)
            XCTAssertNil(cold.store.message(code, "m20"), "une étoile sur un message supprimé ne le garde pas")
            XCTAssertNil(cold.store.message(code, "m40"), "une capsule ouverte depuis trop longtemps part")
            XCTAssertEqual(cold.starredCount(in: code), 2)
            XCTAssertEqual(cold.chat(code)?.last?.text, "Message 399")
            XCTAssertFalse(cold.buried(code, "m0"), "trop vieux pour revenir : pas enterré")
        }
        XCTAssertEqual(Store(directory: e.store.dir).messagesOf(codes[0]).count, 252)
        // Plus rien à faire l'heure suivante.
        let generation = cold.store.messagesGeneration
        cold.lastPrune = 0
        cold.sweep()
        XCTAssertEqual(cold.store.messagesGeneration, generation)
        // « Toujours garder » : rien ne part.
        XCTAssertTrue(cold.store.removeOld(codes[0], before: now + 1, keepDays: 0, now: now).isEmpty)
        XCTAssertEqual(cold.store.removeOld(codes[0], before: now + 1, keepDays: 1, now: now + 3 * 86_400_000).count, 250)
        XCTAssertEqual(cold.store.datesOf(codes[0]).map(\.0), ["m10", "m300"])
    }

    /// Quitter un groupe prévient chaque membre restant, une fois, du même message.
    func testLeavingAGroupTellsEveryMember() async throws {
        let e = phone()
        let members = (0..<6).map { String(format: "AMI%07d", $0) }
        let group = e.createGroup(name: "Six", members: members, avatar: "")
        for key in e.store.rawRows("outbox").keys { e.store.remove("outbox", key) }
        e.leaveGroup(group.id)
        let rows = e.store.all("outbox", as: OutItem.self)
        XCTAssertEqual(Set(rows.map(\.to)), Set(members))
        XCTAssertEqual(rows.count, 6)
        XCTAssertEqual(Set(rows.map(\.payload)).count, 1)
        let wire = try XCTUnwrap(Wire.parse(rows[0].payload))
        guard case .group(let op, _) = wire.body else { return XCTFail("pas un message de groupe") }
        XCTAssertEqual(op, "leave")
        XCTAssertEqual(e.chat(group.id)?.left, true)
        XCTAssertEqual(e.chat(group.id)?.members.contains(e.me.code), false)
    }

    /// Un profil changé part à chaque contact enregistré (pas aux bloqués ni
    /// aux inconnus), chacun avec son propre jeton de remise.
    func testAProfileChangeReachesEveryContactWithItsOwnToken() async throws {
        let e = phone()
        for i in 0..<8 {
            var c = Contact(code: String(format: "AMI%07d", i))
            c.saved = i != 6
            c.blocked = i == 7
            e.saveContact(c)
        }
        for key in e.store.rawRows("outbox").keys { e.store.remove("outbox", key) }
        e.saveProfile { $0.name = "Léa D." }
        let rows = e.store.all("outbox", as: OutItem.self)
        XCTAssertEqual(Set(rows.map(\.to)), Set((0..<6).map { String(format: "AMI%07d", $0) }))
        XCTAssertEqual(rows.count, 6)
        var tokens = Set<String>()
        for row in rows {
            guard case .profile(let p, let jeton, _, _, _, _, _) = try XCTUnwrap(Wire.parse(row.payload)).body else {
                return XCTFail("pas un profil")
            }
            XCTAssertEqual(p.name, "Léa D.")
            // La signature du jeton change à chaque fois : on compare ce qu'elle signe.
            let nonce = String(jeton.split(separator: ".").first ?? "")
            XCTAssertEqual(nonce, e.me.jetonNonce(for: row.to, epoch: 0))
            XCTAssertFalse(nonce.isEmpty)
            tokens.insert(nonce)
        }
        XCTAssertEqual(tokens.count, 6, "un jeton par contact")
    }

    // ----- Le journal d'appels -----

    /// Le journal d'appels n'est pas redécodé à chaque affichage, et suit
    /// chaque ligne qui arrive, change ou part.
    func testTheCallLogFollowsItsRows() async {
        let e = phone()
        func call(_ id: String, at: Double, missed: Bool = false) -> CallEntry {
            CallEntry(id: id, peer: "K7Q2MX9PRT", video: false, incoming: true, at: at, dur: 12, missed: missed)
        }
        XCTAssertTrue(e.callLog().isEmpty)
        e.logCall(call("a", at: 10))
        e.logCall(call("b", at: 30))
        e.logCall(call("c", at: 20, missed: true))
        XCTAssertEqual(e.callLog().map(\.id), ["b", "c", "a"])
        XCTAssertEqual(e.callLog(), e.store.all("calls", as: CallEntry.self).sorted { $0.at > $1.at })
        XCTAssertEqual(Set(e.callsKept.keys), ["a", "b", "c"])
        // Une ligne changée sans passer par le moteur, une retirée, une ajoutée.
        e.store.write("calls", "a", call("a", at: 40, missed: true))
        e.store.remove("calls", "c")
        e.store.write("calls", "d", call("d", at: 5))
        XCTAssertEqual(e.callLog().map(\.id), ["a", "b", "d"])
        XCTAssertEqual(e.callLog().first?.missed, true)
        XCTAssertEqual(Set(e.callsKept.keys), ["a", "b", "d"])
        e.clearCalls()
        XCTAssertTrue(e.callLog().isEmpty)
        XCTAssertTrue(e.callsKept.isEmpty)
        e.logCall(call("e", at: 1))
        XCTAssertEqual(e.callLog().map(\.id), ["e"])
    }

    // ----- Les messages éphémères -----

    /// Le ménage retire les messages éphémères échus sans charger la
    /// discussion : ils sont enterrés, et l'aperçu de la discussion suit.
    func testExpiredMessagesLeaveAtTheSweep() async throws {
        let e = phone()
        let c = e.ensureDirectChat("K7Q2MX9PRT")
        let now = nowMs()
        e.store.transaction {
            for i in 0..<400 {
                e.store.writeMessage(Message(chat: c.id, id: "m\(i)", from: c.id, at: now - Double(1000 - i) * 1000, content: Content(text: "Message \(i)")))
            }
        }
        var gone = Message(chat: c.id, id: "échu", from: c.id, at: now - 5_000, content: Content(text: "Éphémère échu"))
        gone.exp = now - 1_000
        var soon = Message(chat: c.id, id: "bientôt", from: c.id, at: now - 4_000, content: Content(text: "Éphémère"))
        soon.exp = now + 300
        e.storeMessage(gone)
        e.storeMessage(soon)
        XCTAssertEqual(e.chat(c.id)?.hasExp, true)
        XCTAssertEqual(e.chat(c.id)?.last?.text, "Éphémère")

        // Un lancement : rien en mémoire.
        let cold = Engine(store: Store(directory: e.store.dir), server: URL(string: "http://localhost:1")!)
        cold.sweep()
        XCTAssertNil(cold.store.messages[c.id])
        XCTAssertNil(cold.store.message(c.id, "échu"))
        XCTAssertNotNil(cold.store.message(c.id, "bientôt"))
        XCTAssertTrue(cold.buried(c.id, "échu"))
        XCTAssertFalse(cold.buried(c.id, "bientôt"))
        XCTAssertEqual(cold.chat(c.id)?.last?.text, "Éphémère")
        // Le suivant échoit à son tour : l'aperçu revient au dernier message resté.
        try await Task.sleep(nanoseconds: 400_000_000)
        cold.sweep()
        XCTAssertNil(cold.store.message(c.id, "bientôt"))
        XCTAssertTrue(cold.buried(c.id, "bientôt"))
        XCTAssertEqual(cold.chat(c.id)?.last?.text, "Message 399")
        XCTAssertNil(cold.store.messages[c.id])
        // Un message éphémère reçu ensuite part à son heure lui aussi.
        var late = Message(chat: c.id, id: "tardif", from: c.id, at: nowMs(), content: Content(text: "Encore un"))
        late.exp = nowMs() + 200
        cold.storeMessage(late)
        cold.sweep()
        XCTAssertNotNil(cold.store.message(c.id, "tardif"))
        try await Task.sleep(nanoseconds: 300_000_000)
        cold.sweep()
        XCTAssertNil(cold.store.message(c.id, "tardif"))
        XCTAssertEqual(cold.messages(c.id).count, 400)
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
