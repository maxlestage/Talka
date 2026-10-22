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
}
