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
