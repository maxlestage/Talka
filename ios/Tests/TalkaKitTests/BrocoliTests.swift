// L'œuf de Pâques 🥦 : les mêmes messages font pleuvoir des brocolis sur
// l'iPhone et dans le navigateur.

import Foundation
import XCTest
@testable import TalkaKit

final class BrocoliTests: XCTestCase {
    let me = "WGSEAJNSZJ"

    func message(_ text: String, from: String = "K7Q2MX9PRT", openAt: Double? = nil) -> Message {
        Message(chat: from, id: "m1", from: from, at: 1, content: Content(text: text, openAt: openAt))
    }

    func testTextAndEmojiSummonBrocolis() {
        XCTAssertTrue(message("Tu veux des brocolis ? 🥦").summonsBrocoli(me: me))
        XCTAssertTrue(message("🥦").summonsBrocoli(me: me))
        XCTAssertTrue(message("BROCOLI").summonsBrocoli(me: me))
        XCTAssertTrue(message("brócoli").summonsBrocoli(me: me))
        XCTAssertFalse(message("Des carottes 🥕").summonsBrocoli(me: me))
    }

    func testOnlyVisibleTextSummons() {
        var deleted = message("🥦")
        deleted.deleted = true
        XCTAssertFalse(deleted.summonsBrocoli(me: me))
        var file = message("")
        file.kind = "file"
        file.name = "brocoli.pdf"
        XCTAssertFalse(file.summonsBrocoli(me: me))
    }

    func testSealedCapsuleKeepsItsSecret() {
        let later = nowMs() + 3_600_000
        XCTAssertFalse(message("🥦", openAt: later).summonsBrocoli(me: me))
        // L'auteur, lui, voit ce qu'il a écrit.
        XCTAssertTrue(message("🥦", from: me, openAt: later).summonsBrocoli(me: me))
    }
}
