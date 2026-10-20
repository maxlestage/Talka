// TalkaKit relit exactement ce que produit le navigateur (le même code Rust,
// `cargo run -p talka-web --example vectors`) : codes, chiffrement,
// signatures, code de verrouillage, sauvegarde, enveloppes, données gardées.

import Foundation
import XCTest
@testable import TalkaKit

final class CompatTests: XCTestCase {
    var v: [String: Any] = [:]

    override func setUpWithError() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        v = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func identity(_ key: String) throws -> Identity {
        let data = try JSONSerialization.data(withJSONObject: try XCTUnwrap(v[key]))
        return try JSONDecoder().decode(Identity.self, from: data)
    }

    func testCodes() throws {
        XCTAssertEqual(TalkaCode.of(sign: Data(), dh: Data()), v["code_of_empty"] as? String)
        XCTAssertEqual(TalkaCode.of(sign: Data(), dh: Data()), "WGSEAJNSZJ")
        XCTAssertEqual(TalkaCode.normalize("wgsea-jnszj"), "WGSEAJNSZJ")
        XCTAssertNil(TalkaCode.normalize("WGSEA-JNSZ0"))
        XCTAssertEqual(TalkaCode.pretty("WGSEAJNSZJ"), "WGSEA-JNSZJ")
        XCTAssertEqual(TalkaCode.find(in: "https://talka.app/c/WGSEAJNSZJ"), "WGSEAJNSZJ")
        XCTAssertEqual(TalkaCode.find(in: "https://x.github.io/Talka/app/#add=WGSEAJNSZJ"), "WGSEAJNSZJ")
        let a = try identity("a")
        XCTAssertTrue(a.isValid, "l'identité du navigateur est relue telle quelle")
        XCTAssertTrue(TalkaCrypto.verifyKeys(code: a.code, sign: a.sign, dh: a.dh))
    }

    func testOpenWhatTheBrowserSealed() throws {
        let a = try identity("a"), b = try identity("b")
        let opened = TalkaCrypto.open(b, peerCode: a.code, peerDh: a.dh, body: try XCTUnwrap(v["sealed_a_to_b"] as? String))
        XCTAssertEqual(opened, v["plain"] as? String)
        // Attribué à quelqu'un d'autre : illisible.
        XCTAssertNil(TalkaCrypto.open(a, peerCode: b.code, peerDh: b.dh, body: v["sealed_a_to_b"] as! String))
    }

    func testSealBothWays() throws {
        let a = try identity("a"), b = try identity("b")
        let sealed = try XCTUnwrap(TalkaCrypto.seal(b, peerCode: a.code, peerDh: a.dh, plain: "{\"k\":\"typing\",\"on\":true}"))
        XCTAssertEqual(TalkaCrypto.open(a, peerCode: b.code, peerDh: b.dh, body: sealed), "{\"k\":\"typing\",\"on\":true}")
        let fresh = Identity.create()
        XCTAssertTrue(fresh.isValid)
        XCTAssertEqual(fresh.code.count, 10)
    }

    func testSignatures() throws {
        let a = try identity("a")
        let challenge = try XCTUnwrap(v["challenge"] as? [String: String])
        XCTAssertTrue(TalkaCrypto.verify(sign: a.sign, message: Data("talka-auth:abc".utf8), signature: challenge["sig"]!))
        let mine = a.signChallenge("xyz")
        XCTAssertTrue(TalkaCrypto.verify(sign: a.sign, message: Data("talka-auth:xyz".utf8), signature: mine))
        XCTAssertFalse(TalkaCrypto.verify(sign: a.sign, message: Data("talka-auth:xyw".utf8), signature: mine))
    }

    func testSafetyNumber() throws {
        let a = try identity("a"), b = try identity("b")
        XCTAssertEqual(TalkaCrypto.safetyNumber(a.code, b.code), v["safety"] as? String)
        XCTAssertEqual(TalkaCrypto.safetyNumber(b.code, a.code), v["safety"] as? String)
    }

    func testPinAndBackup() throws {
        let pin = try XCTUnwrap(v["pin"] as? [String: Any])
        let hash = try JSONDecoder().decode(PinHash.self, from: JSONSerialization.data(withJSONObject: pin["hash"]!))
        XCTAssertTrue(TalkaCrypto.checkPin("2580", hash))
        XCTAssertFalse(TalkaCrypto.checkPin("1111", hash))

        let backup = try XCTUnwrap(v["backup"] as? [String: Any])
        let file = try JSONDecoder().decode(Backup.self, from: JSONSerialization.data(withJSONObject: backup["file"]!))
        XCTAssertEqual(try TalkaCrypto.decryptBackup(password: "motdepasse", file: file), backup["plain"] as? String)
        XCTAssertThrowsError(try TalkaCrypto.decryptBackup(password: "autre", file: file))
        let mine = try XCTUnwrap(TalkaCrypto.encryptBackup(password: "secret123", plain: "{\"x\":1}"))
        XCTAssertEqual(try TalkaCrypto.decryptBackup(password: "secret123", file: mine), "{\"x\":1}")
    }

    func testWires() throws {
        let wires = try XCTUnwrap(v["wires"] as? [String])
        XCTAssertEqual(wires.count, 13)
        for text in wires {
            let w = try XCTUnwrap(Wire.parse(text), text)
            if case .unknown = w.body { XCTFail("sorte inconnue : \(text)") }
            // Réécrite puis relue : identique.
            XCTAssertEqual(Wire.parse(w.json()), w, text)
            // Les mêmes champs que le navigateur (rien ne manque).
            let theirs = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
            let ours = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(w.json().utf8)) as? [String: Any])
            for (key, value) in theirs where !isEmptyValue(value) {
                XCTAssertNotNil(ours[key], "\(key) manque dans \(w.json())")
            }
        }
        let msg = try XCTUnwrap(Wire.parse(wires[0]))
        guard case let .msg(id, _, pn, content) = msg.body else { return XCTFail("msg") }
        XCTAssertEqual(id, "m1")
        XCTAssertEqual(pn, "Léa")
        XCTAssertEqual(content.text, "Salut 👋 *Talka*")
        XCTAssertEqual(content.reply?.text, "avant")
        XCTAssertEqual(msg.groupId, "g1")
        XCTAssertEqual(msg.grp?.v, 3)
        XCTAssertEqual(msg.ttl, 86_400)
        let call = try XCTUnwrap(Wire.parse(wires[12]))
        guard case let .call(op, callId, video, _, cand) = call.body else { return XCTFail("call") }
        XCTAssertEqual(op, "ice")
        XCTAssertEqual(callId, "c1")
        XCTAssertTrue(video)
        XCTAssertEqual(cand?["sdpMid"]?.string, "0")
        XCTAssertEqual(cand?["sdpMLineIndex"]?.number, 0)
    }

    func isEmptyValue(_ v: Any) -> Bool {
        if let s = v as? String { return s.isEmpty }
        if let b = v as? Bool { return !b }
        if let n = v as? NSNumber { return n == 0 }
        if v is NSNull { return true }
        return false
    }

    func testStoredData() throws {
        let store = try XCTUnwrap(v["store"] as? [String: String])
        let m = try XCTUnwrap(JSON.decode(Message.self, store["message"]!))
        XCTAssertEqual(m.kind, "image")
        XCTAssertEqual(m.w, 4)
        XCTAssertEqual(m.exp, 2)
        XCTAssertEqual(m.reactions.count, 1)
        XCTAssertEqual(m.tick, .sent)
        let chat = try XCTUnwrap(JSON.decode(Chat.self, store["chat"]!))
        XCTAssertTrue(chat.group)
        XCTAssertEqual(chat.last?.text, "📷 Photo")
        let contact = try XCTUnwrap(JSON.decode(Contact.self, store["contact"]!))
        XCTAssertTrue(contact.saved)
        XCTAssertEqual(contact.name, "Bob")
        XCTAssertNotNil(JSON.decode(StatusItem.self, store["status"]!))
        XCTAssertEqual(JSON.decode(CallEntry.self, store["call"]!)?.dur, 42)
        XCTAssertEqual(JSON.decode(OutItem.self, store["outbox"]!)?.msgId, "m1")
        XCTAssertEqual(JSON.decode(OutItem.self, store["outbox"]!)?.boot, "B00T")
        XCTAssertEqual(JSON.decode(Settings.self, store["settings"]!), Settings())
        XCTAssertEqual(JSON.decode(Profile.self, store["profile"]!)?.name, "Léa")
        // Et dans l'autre sens : ce que garde l'iPhone se relit à l'identique.
        XCTAssertEqual(JSON.decode(Message.self, JSON.encode(m)), m)
        XCTAssertEqual(JSON.decode(Chat.self, JSON.encode(chat)), chat)
    }

    /// L'épingle d'une discussion : la même clé que le navigateur (`pin_msg`),
    /// absente des anciennes données.
    func testPinnedMessageField() throws {
        let old = try XCTUnwrap(JSON.decode(Chat.self, #"{"id":"WGSEAJNSZJ","unread":2}"#))
        XCTAssertEqual(old.pinMsg, "")
        var chat = old
        chat.pinMsg = "m1"
        let json = JSON.encode(chat)
        XCTAssertTrue(json.contains(#""pin_msg":"m1""#))
        XCTAssertEqual(JSON.decode(Chat.self, json)?.pinMsg, "m1")
    }
}
