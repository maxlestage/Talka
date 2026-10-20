// L'Apple Watch : le résumé que l'iPhone lui envoie, et ce qu'elle renvoie.
// Sans réseau : le moteur n'est pas démarré, les messages attendent dans
// la boîte d'envoi. (Des tests `async` : sous Linux, XCTest ne sait pas
// appeler une méthode synchrone isolée sur le MainActor.)

import Foundation
import XCTest
@testable import TalkaKit

@MainActor
final class WatchTests: XCTestCase {
    func phone() -> Engine {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-watch-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        return Engine(store: store, server: URL(string: "http://localhost:1")!)
    }

    func testSnapshotOrderMessagesAndRoundTrip() async throws {
        let e = phone()
        let old = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(old.id, Content(text: "Bonjour"))
        let recent = e.ensureDirectChat("K7Q2MX9PRT")
        let m = try XCTUnwrap(e.send(recent.id, Content(text: "Salut")))
        e.react(recent.id, m.id, "❤️")
        _ = e.ensureDirectChat("AAAAAAAAAA") // jamais écrit : absent

        let s = e.watchSnapshot()
        XCTAssertEqual(s.name, "Léa")
        XCTAssertEqual(s.chats.map(\.id), ["K7Q2MX9PRT", "WGSEAJNSZJ"])
        let first = s.chats[1]
        // Un contact sans nom : son code, comme sur l'iPhone.
        XCTAssertEqual(first.title, "WGSEA-JNSZJ")
        XCTAssertEqual(first.initials, "WJ")
        XCTAssertEqual(first.last, "Bonjour")
        let mine = try XCTUnwrap(s.chats[0].messages.last)
        XCTAssertTrue(mine.mine)
        XCTAssertEqual(mine.text, "Salut")
        XCTAssertEqual(mine.tick, "pending")
        XCTAssertEqual(mine.reactions, "❤️")
        XCTAssertEqual(mine.myReaction, "❤️")

        // Ce qui traverse WatchConnectivity revient à l'identique.
        let data = try XCTUnwrap(WatchLink.encode(s))
        XCTAssertEqual(WatchLink.decode(WatchSnapshot.self, data), s)
        XCTAssertNil(WatchLink.decode(WatchSnapshot.self, "pas des données"))
    }

    func testActionsFromTheWatch() async throws {
        let e = phone()
        let c = e.ensureDirectChat("WGSEAJNSZJ")
        XCTAssertTrue(e.perform(WatchAction(.send, chat: c.id, text: "  J'arrive !  ")))
        let sent = try XCTUnwrap(e.messages(c.id).last)
        XCTAssertEqual(sent.text, "J'arrive !")
        XCTAssertFalse(e.perform(WatchAction(.send, chat: c.id, text: "   ")))
        XCTAssertFalse(e.perform(WatchAction(.send, chat: "INCONNU", text: "x")))

        XCTAssertTrue(e.perform(WatchAction(.react, chat: c.id, id: sent.id, text: "👍")))
        XCTAssertEqual(e.messages(c.id).last?.reactions[e.me.code], "👍")
        XCTAssertTrue(e.perform(WatchAction(.react, chat: c.id, id: sent.id, text: "👍")))
        XCTAssertNil(e.messages(c.id).last?.reactions[e.me.code])

        e.updateChat(c.id) { $0.unread = 3 }
        XCTAssertTrue(e.perform(WatchAction(.read, chat: c.id)))
        XCTAssertEqual(e.chat(c.id)?.unread, 0)

        e.setBlocked(c.id, true)
        XCTAssertTrue(e.watchSnapshot().chats[0].readOnly)
        XCTAssertFalse(e.perform(WatchAction(.send, chat: c.id, text: "bloqué")))

        let data = try XCTUnwrap(WatchLink.encode(WatchAction(.refresh)))
        XCTAssertEqual(WatchLink.decode(WatchAction.self, data)?.kind, .refresh)
    }

    func testInitialsAndColors() async {
        XCTAssertEqual(watchInitials("Léa Martin"), "LM")
        XCTAssertEqual(watchInitials("~Léa"), "L")
        XCTAssertEqual(watchInitials("WGS-EAJ-NSZJ"), "WE")
        XCTAssertEqual(watchInitials(""), "")
        XCTAssertTrue((0..<8).contains(watchColor("WGSEAJNSZJ")))
        XCTAssertEqual(watchColor("WGSEAJNSZJ"), watchColor("WGSEAJNSZJ"))
    }
}
