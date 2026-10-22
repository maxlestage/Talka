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
}
