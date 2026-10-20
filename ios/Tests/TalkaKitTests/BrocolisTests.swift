
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

// Le partage d'une clé par Talka, son acceptation, son renouvellement : la
// clé ne reste jamais dans les messages gardés, et la neuve remplace
// l'ancienne des deux côtés. (Des tests `async`, comme WatchTests.)
@MainActor
final class BrocolisEngineTests: XCTestCase {
    func phone() -> Engine {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-bro-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        return Engine(store: store, server: URL(string: "http://localhost:1")!)
    }

    func testFingerprintMatchesTheBrowser() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        let all = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let v = try XCTUnwrap(all["brocolis"] as? [String: Any])
        let k1 = try XCTUnwrap((v["keys"] as? [[String: String]])?.first?["key"])
        XCTAssertEqual(Brocolis.fingerprint(k1), v["fingerprint_k1"] as? String)
        XCTAssertNil(Brocolis.fingerprint("pas une clé"))
    }

    func testSharingKeepsNoKeyInTheMessages() async throws {
        let e = phone()
        let chat = e.ensureDirectChat("K7Q2MX9PRT")
        let k = try e.addBrocolisKey(name: "Nous", pasted: Brocolis.generateKey())
        let m = try XCTUnwrap(e.shareBrocolisKey(chat.id, keyId: k.id))
        XCTAssertEqual(m.kind, "brokey")
        let stored = try XCTUnwrap(e.store.message(chat.id, m.id))
        XCTAssertEqual(stored.data, "", "la clé ne reste pas dans les messages")
        XCTAssertEqual(stored.code, k.id)
        XCTAssertEqual(e.sharedKey(of: stored), k)
        XCTAssertFalse(stored.bro, "une clé partagée ne se scelle pas")
        XCTAssertEqual(Engine.preview(stored), "🥦 Clé Brocolis « Nous »")
    }

    func testAcceptingARenewedKeyRetiresTheOldOne() async throws {
        let e = phone()
        let chat = e.ensureDirectChat("K7Q2MX9PRT")
        let old = try e.addBrocolisKey(name: "Nous", pasted: Brocolis.generateKey())
        e.setChatBrocolis(chat.id, [old.id])
        // Une clé neuve reçue, qui renouvelle l'ancienne.
        let fresh = Brocolis.generateKey()
        var m = Message(chat: chat.id, id: "m1", from: "K7Q2MX9PRT", at: 1,
                        content: Content(kind: "brokey", data: fresh, name: "Nous", code: Brocolis.fingerprint(old.key)!))
        e.store.writeMessage(m)
        let k = try e.acceptBrocolisKey(m)
        XCTAssertEqual(k.key, fresh)
        XCTAssertEqual(e.chat(chat.id)?.brocolis, [k.id])
        XCTAssertTrue(try XCTUnwrap(e.brocolisKeys.first { $0.id == old.id }).retired)
        XCTAssertEqual(e.chatBrocolis(e.chat(chat.id)!).map(\.id), [k.id])
        m = try XCTUnwrap(e.store.message(chat.id, "m1"))
        XCTAssertEqual(m.data, "")
        XCTAssertEqual(try e.acceptBrocolisKey(m), k, "accepter deux fois ne change rien")
    }

    func testRenewingReplacesTheKeyInItsChats() async throws {
        let e = phone()
        let chat = e.ensureDirectChat("K7Q2MX9PRT")
        let old = try e.addBrocolisKey(name: "Nous", pasted: Brocolis.generateKey())
        e.setChatBrocolis(chat.id, [old.id])
        XCTAssertEqual(try e.renewBrocolisKey(old.id), 1)
        let ids = try XCTUnwrap(e.chat(chat.id)?.brocolis)
        XCTAssertEqual(ids.count, 1)
        XCTAssertNotEqual(ids.first, old.id)
        XCTAssertTrue(e.brocolisKeys.first { $0.id == old.id }!.retired)
        // L'ancienne ouvre encore l'historique ; la neuve chiffre.
        let sealed = Brocolis.seal("avant", keys: [Brocolis.key(base64: old.key)!])
        XCTAssertEqual(Brocolis.open(sealed, keys: e.allBrocolisKeys).text, "avant")
        let sent = try XCTUnwrap(e.send(chat.id, Content(text: "après")))
        XCTAssertTrue(sent.bro)
        XCTAssertEqual(sent.text, "après")
    }
}

// Une clé protégée par une phrase secrète : le vecteur commun avec le
// navigateur et l'app Brocolis (iOS, Android).
final class BrocolisProtectedKeyTests: XCTestCase {
    func testMatchesTheSharedVector() throws {
        let raw = Data((0..<32).map { UInt8($0) })
        let token = try XCTUnwrap(Brocolis.protectKey(raw: raw, phrase: "brocoli vert 2026",
                                                      salt: Data(repeating: 0x11, count: 16), nonce: Data(repeating: 0x22, count: 12)))
        XCTAssertEqual(token, "[[BROCOLIS-CLE:v1:ERERERERERERERERERERESIiIiIiIiIiIiIiIsq57j18BXkqq/4VdIw82nQqzrax9Cfn2mfUJjWH8l327NAf2rWaSYCJh3BfGH4eWA==]]")
        XCTAssertEqual(Brocolis.unprotectKey("Voici : \(token) !", phrase: "brocoli vert 2026"), raw.base64EncodedString())
        XCTAssertNil(Brocolis.unprotectKey(token, phrase: "brocoli vert 2025"))
    }

    func testNeedsAPhraseAndHidesTheKey() throws {
        let k = Brocolis.generateKey()
        XCTAssertNil(Brocolis.protectKey(k, phrase: "court"))
        let token = try XCTUnwrap(Brocolis.protectKey(k, phrase: "une phrase longue"))
        XCTAssertTrue(Brocolis.hasProtectedKey(token))
        XCTAssertFalse(token.contains(k))
        XCTAssertEqual(Brocolis.unprotectKey(token, phrase: "une phrase longue"), k)
        XCTAssertNil(Brocolis.unprotectKey(token.replacingOccurrences(of: "v1:", with: "v1:AAAA"), phrase: "une phrase longue"))
        XCTAssertNil(Brocolis.findKey(in: token), "un jeton protégé n'est pas une clé en clair")
    }
}
