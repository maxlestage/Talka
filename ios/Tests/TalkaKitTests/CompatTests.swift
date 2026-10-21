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

    /// L'expéditeur scellé : ce que le navigateur a scellé s'ouvre ici, et
    /// réciproquement ; son jeton de remise se vérifie.
    func testSealedSenderWithTheBrowser() throws {
        let a = try identity("a"), b = try identity("b"), scelle = try XCTUnwrap(v["scelle"] as? [String: Any])
        let opened = try XCTUnwrap(TalkaCrypto.openSealed(b, try XCTUnwrap(scelle["a_to_b"] as? String)))
        XCTAssertEqual(opened.from, a.code)
        XCTAssertEqual(opened.body, v["sealed_a_to_b"] as? String)
        XCTAssertEqual(TalkaCrypto.open(b, peerCode: opened.from, peerDh: a.dh, body: opened.body), v["plain"] as? String)
        XCTAssertNil(TalkaCrypto.openSealed(a, scelle["a_to_b"] as! String))

        let jeton = try XCTUnwrap(scelle["jeton_a"] as? String)
        XCTAssertTrue(TalkaCrypto.verifyJeton(to: a.code, sign: a.sign, dh: a.dh, jeton: jeton))
        XCTAssertFalse(TalkaCrypto.verifyJeton(to: b.code, sign: b.sign, dh: b.dh, jeton: jeton))
        // Le même nonce que le navigateur (la signature, elle, varie).
        XCTAssertEqual(a.jeton.split(separator: ".").first, jeton.split(separator: ".").first)
        XCTAssertTrue(TalkaCrypto.verifyJeton(to: a.code, sign: a.sign, dh: a.dh, jeton: a.jeton))

        // Dans l'autre sens, et quelqu'un qui se fait passer pour A.
        let inner = try XCTUnwrap(TalkaCrypto.seal(b, peerCode: a.code, peerDh: a.dh, plain: "{}"))
        let sealed = try XCTUnwrap(TalkaCrypto.sealSender(from: b.code, to: a.code, toDh: a.dh, body: inner))
        XCTAssertFalse(sealed.contains(b.code))
        let back = try XCTUnwrap(TalkaCrypto.openSealed(a, sealed))
        XCTAssertEqual(back.from, b.code)
        XCTAssertEqual(TalkaCrypto.open(a, peerCode: back.from, peerDh: b.dh, body: back.body), "{}")
        let c = Identity.create()
        let forged = try XCTUnwrap(TalkaCrypto.sealSender(
            from: b.code, to: a.code, toDh: a.dh,
            body: try XCTUnwrap(TalkaCrypto.seal(c, peerCode: a.code, peerDh: a.dh, plain: "{}"))))
        let claimed = try XCTUnwrap(TalkaCrypto.openSealed(a, forged))
        XCTAssertNil(TalkaCrypto.open(a, peerCode: claimed.from, peerDh: b.dh, body: claimed.body))
    }

    /// L'aperçu des notifications : ce que le navigateur a chiffré pour une
    /// clé de notification s'ouvre ici, ce que l'iPhone chiffre aussi, et
    /// l'iPhone compose le même aperçu que le navigateur.
    @MainActor
    func testApercuWithTheBrowser() throws {
        let ap = try XCTUnwrap(v["apercu"] as? [String: Any])
        let nk = try XCTUnwrap(ap["nk"] as? String), plain = try XCTUnwrap(ap["plain"] as? String)
        let priv = try XCTUnwrap(unb64(try XCTUnwrap(ap["nk_priv"] as? String)))
        let a = try identity("a"), b = try identity("b")
        let contacts = { (code: String) in code == a.code ? a.dh : nil }
        XCTAssertTrue(TalkaCrypto.validNk(nk))
        XCTAssertFalse(TalkaCrypto.validNk("abc"))
        let opened = try XCTUnwrap(TalkaCrypto.openApercu(nkPriv: priv, sealed: try XCTUnwrap(ap["sealed"] as? String), dhOf: contacts))
        XCTAssertEqual(opened.from, a.code)
        XCTAssertEqual(opened.plain, plain)
        let mine = try XCTUnwrap(TalkaCrypto.sealApercu(b, nk: nk, plain: "{\"t\":\"🙂\"}"))
        XCTAssertEqual(TalkaCrypto.openApercu(nkPriv: priv, sealed: mine, dhOf: { $0 == b.code ? b.dh : nil })?.plain, "{\"t\":\"🙂\"}")
        // Un inconnu, ou B qui se ferait passer pour A : rien.
        XCTAssertNil(TalkaCrypto.openApercu(nkPriv: priv, sealed: mine, dhOf: contacts))
        XCTAssertNil(TalkaCrypto.openApercu(nkPriv: priv, sealed: mine, dhOf: { _ in a.dh }))
        XCTAssertNil(TalkaCrypto.openApercu(nkPriv: Data(repeating: 7, count: 32), sealed: mine, dhOf: { _ in b.dh }))

        let wires = try XCTUnwrap(v["wires"] as? [String])
        let msg = try XCTUnwrap(JSON.decode(Wire.self, wires[0]))
        let ours = try XCTUnwrap(Engine.apercu(me: a.code, name: "Léa", wire: msg))
        let parse = { (s: String) in try JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: String] }
        XCTAssertEqual(try parse(ours), try parse(plain))
        // La clé de notification voyage dans le profil, et se garde.
        let profile = try XCTUnwrap(wires.compactMap { JSON.decode(Wire.self, $0) }.first {
            if case .profile = $0.body { return true } else { return false }
        })
        guard case let .profile(_, _, wireNk) = profile.body else { return XCTFail() }
        XCTAssertEqual(wireNk, nk)
        let store = try XCTUnwrap(v["store"] as? [String: Any])
        XCTAssertEqual(JSON.decode(Contact.self, try XCTUnwrap(store["contact"] as? String))?.nk, nk)
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
        XCTAssertEqual(wires.count, 14)
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
        // La capsule temporelle : son heure d'ouverture traverse intacte.
        let capsule = try XCTUnwrap(Wire.parse(wires[13]))
        guard case let .msg(_, _, _, sealed) = capsule.body else { return XCTFail("capsule") }
        XCTAssertEqual(sealed.openAt, 1_800_000_000_000)
        XCTAssertEqual(sealed.text, "À ouvrir plus tard")
        // Et un message ordinaire n'en a pas.
        XCTAssertNil(content.openAt)
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

/// La pastille « en ligne » : un réglage que le navigateur et l'iPhone
/// partagent (sauvegardes), activé par défaut, même dans les anciens réglages.
final class PresenceSettingTests: XCTestCase {
    func testPresenceDotDefaultsAndRoundTrip() throws {
        let old = #"{"theme":"dark","hide_online":true}"#
        let s = try XCTUnwrap(JSON.decode(Settings.self, old))
        XCTAssertTrue(s.presenceDot)
        XCTAssertTrue(s.hideOnline)
        var off = s
        off.presenceDot = false
        let json = JSON.encode(off)
        XCTAssertTrue(json.contains("\"presence_dot\":false"))
        XCTAssertEqual(JSON.decode(Settings.self, json)?.presenceDot, false)
    }
}
