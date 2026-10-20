
// Brocolis 🥦 : les jetons du navigateur s'ouvrent sur l'iPhone, et
// l'inverse (même format que l'app Brocolis) ; une discussion et un message
// gardent leur réglage Brocolis d'un appareil à l'autre.

import Foundation
import XCTest
@testable import TalkaKit

final class BrocolisTokenTests: XCTestCase {
    func vectors() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        let all = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        return try XCTUnwrap(all["brocolis"] as? [String: Any])
    }

    func keys(_ v: [String: Any]) throws -> [BrocolisKey] {
        let raw = try JSONSerialization.data(withJSONObject: try XCTUnwrap(v["keys"]))
        return try JSONDecoder().decode([BrocolisKey].self, from: raw)
    }

    func testOpensTheBrowsersTokens() throws {
        let v = try vectors()
        let ks = try keys(v).compactMap { Brocolis.key(base64: $0.key) }
        XCTAssertEqual(ks.count, 2)
        for name in ["v1", "v2", "marked"] {
            let opened = Brocolis.open(try XCTUnwrap(v[name] as? String), keys: ks)
            XCTAssertEqual(opened.text, v["\(name)_plain"] as? String, name)
            XCTAssertEqual(opened.locked, 0, name)
        }
        // La seule clé « Travail » n'ouvre pas le jeton v1 de « Bob ».
        let other = Brocolis.open(v["v1"] as! String, keys: [ks[1]])
        XCTAssertEqual(other.opened, 0)
        XCTAssertEqual(Brocolis.masked(other.text), "🔒🥦")
        // Le v2 s'ouvre avec n'importe laquelle des deux.
        XCTAssertEqual(Brocolis.open(v["v2"] as! String, keys: [ks[1]]).text, "À tous 🥦")
    }

    func testSealsLikeTheBrowser() throws {
        let k1 = try XCTUnwrap(Brocolis.key(base64: Brocolis.generateKey()))
        let k2 = try XCTUnwrap(Brocolis.key(base64: Brocolis.generateKey()))
        let one = Brocolis.seal("Salut", keys: [k1])
        XCTAssertTrue(one.hasPrefix("[[BROCOLIS:v1:") && one.hasSuffix("]]"))
        XCTAssertEqual(Brocolis.open(one, keys: [k2, k1]).text, "Salut")
        let both = Brocolis.seal("À tous", keys: [k1, k2])
        XCTAssertTrue(both.hasPrefix("[[BROCOLIS:v2:"))
        XCTAssertEqual(Brocolis.open(both, keys: [k2]).text, "À tous")
        let marked = Brocolis.seal("Rendez-vous ((au parc)) à ((18 h)) !", keys: [k1])
        XCTAssertTrue(marked.hasPrefix("Rendez-vous [[BROCOLIS:v1:") && marked.hasSuffix("]] !"))
        XCTAssertFalse(marked.contains("parc"))
        XCTAssertEqual(Brocolis.open(marked, keys: [k1]), Brocolis.Opened(text: "Rendez-vous au parc à 18 h !", opened: 2, locked: 0))
        XCTAssertEqual(Brocolis.seal(one, keys: [k2]), one, "un jeton déjà là part tel quel")
        XCTAssertEqual(Brocolis.seal("clair", keys: []), "clair")
    }

    func testFindsTheKeyInAnInvitation() throws {
        let key = Brocolis.generateKey()
        XCTAssertEqual(Brocolis.findKey(in: "3) Colle CETTE clé :\n\n\(key)\n\n(C'est notre clé commune.)"), key)
        XCTAssertNil(Brocolis.findKey(in: "pas de clé ici"))
    }

    func testBrokenTokensStayAsTheyAre() throws {
        let k = try XCTUnwrap(Brocolis.key(base64: Brocolis.generateKey()))
        for text in ["[[BROCOLIS:v1:pas-du-base64]]", "[[BROCOLIS:v3:AAAA]]", "[[BROCOLIS:v1:AAAA"] {
            XCTAssertEqual(Brocolis.open(text, keys: [k]).text, text)
        }
    }

    func testAStoredChatAndMessageKeepBrocolis() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        let all = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let store = try XCTUnwrap(all["store"] as? [String: String])
        let chat = try JSONDecoder().decode(Chat.self, from: Data(store["chat"]!.utf8))
        XCTAssertEqual(chat.brocolis, ["k1"])
        let message = try JSONDecoder().decode(Message.self, from: Data(store["message"]!.utf8))
        XCTAssertTrue(message.bro)
    }

    func testALockedTokenDoesNotMakeItRain() {
        let token = Brocolis.seal("secret", keys: [Brocolis.key(base64: Brocolis.generateKey())!])
        let m = Message(chat: "c", id: "m", from: "K7Q2MX9PRT", at: 1, content: Content(text: token))
        XCTAssertFalse(m.summonsBrocoli(me: "WGSEAJNSZJ"))
    }
}
