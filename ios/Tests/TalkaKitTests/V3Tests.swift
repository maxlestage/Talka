// Le protocole v3 de l'iPhone (`V3Engine`), comme les tests du navigateur
// (`client/src/v3.rs`) : premier message, réponses, désordre, doublon, deux
// côtés qui commencent ensemble, vieux message rejoué sans réparation,
// session perdue qui guérit, gros contenu en ancien format. Puis le moteur
// entier : l'état rangé avec le message, la sauvegarde.

import Foundation
import XCTest
import BrocolisProto
@testable import TalkaKit

final class V3Tests: XCTestCase {
    final class Side {
        let id: Identity
        var engine: V3Engine
        init(_ engine: V3Engine? = nil, id: Identity = Identity.create()) {
            self.id = id
            self.engine = engine ?? V3Engine(deviceJson: nil, now: 1_000)
        }
    }

    func bundleOf(_ s: Side) -> KeyBundle {
        let (bundle, sig) = s.engine.published(s.id)!
        return V3.verifyBundle(code: s.id.code, sign: s.id.sign, dh: s.id.dh, bundle: bundle, sig: sig)!
    }

    func send(_ from: Side, _ to: Side, bundle: Bool, _ plain: String) -> V3.Sealed {
        from.engine.seal(from.id, peerCode: to.id.code, peerDh: to.id.dh, bundle: bundle ? bundleOf(to) : nil, plain: plain)!
    }

    func recv(_ to: Side, _ from: Side, _ s: V3.Sealed, now: UInt64 = 2_000) -> V3.Opened {
        to.engine.open(to.id, peerCode: from.id.code, peerDh: from.id.dh, body: s.body, now: now)
    }

    func testBundleIsBoundToTheTalkaCode() throws {
        let a = Side(), b = Side()
        let (bundle, sig) = try XCTUnwrap(a.engine.published(a.id))
        // Hybride ML-KEM-768 là où il existe (swift-crypto 4 sous Linux, iOS et
        // macOS 26) ; X25519 seul avant.
        XCTAssertEqual(unb64(bundle)?.count, V3.postQuantum ? Paquet.bundleLengthPq : Paquet.bundleLengthClassic)
        XCTAssertNotNil(V3.verifyBundle(code: a.id.code, sign: a.id.sign, dh: a.id.dh, bundle: bundle, sig: sig))
        // Signé par un autre, ou présenté sous un autre code : refusé.
        let (_, other) = try XCTUnwrap(b.engine.published(b.id))
        XCTAssertNil(V3.verifyBundle(code: a.id.code, sign: a.id.sign, dh: a.id.dh, bundle: bundle, sig: other))
        XCTAssertNil(V3.verifyBundle(code: b.id.code, sign: b.id.sign, dh: b.id.dh, bundle: bundle, sig: sig))
        XCTAssertNil(V3.verifyBundle(code: b.id.code, sign: a.id.sign, dh: a.id.dh, bundle: bundle, sig: sig))
        // Sans le contexte : refusé.
        let raw = a.id.sign(try XCTUnwrap(unb64(bundle)))
        XCTAssertNil(V3.verifyBundle(code: a.id.code, sign: a.id.sign, dh: a.id.dh, bundle: bundle, sig: raw))
        // Un octet changé, même re-signé par la bonne identité : refusé (Ed25519).
        var bytes = try XCTUnwrap(unb64(bundle))
        bytes[40] ^= 1
        XCTAssertNil(V3.verifyBundle(code: a.id.code, sign: a.id.sign, dh: a.id.dh, bundle: b64(bytes), sig: sig))
        let resigned = a.id.sign(V3.bundleMessage(bytes))
        XCTAssertNil(V3.verifyBundle(code: a.id.code, sign: a.id.sign, dh: a.id.dh, bundle: b64(bytes), sig: resigned))
        // Trop gros : refusé avant toute vérification.
        let big = Data(repeating: 7, count: V3.maxBundle + 1)
        XCTAssertNil(V3.verifyBundle(code: a.id.code, sign: a.id.sign, dh: a.id.dh, bundle: b64(big), sig: a.id.sign(V3.bundleMessage(big))))
    }

    func testFirstMessageRepliesDisorderAndReplay() {
        let a = Side(), b = Side()
        let m1 = send(a, b, bundle: true, #"{"k":"un"}"#)
        let m2 = send(a, b, bundle: false, #"{"k":"deux"}"#)
        XCTAssertTrue(m1.v3 && m2.v3)
        // Dans le désordre : le second premier message d'abord.
        XCTAssertEqual(recv(b, a, m2), .v3(#"{"k":"deux"}"#))
        XCTAssertEqual(recv(b, a, m1), .v3(#"{"k":"un"}"#))
        // Un doublon (le relais renvoie) : ignoré.
        XCTAssertEqual(recv(b, a, m1), .replay)
        XCTAssertEqual(b.engine.sessionProtection(a.id.code), .ratchet(pq: V3.postQuantum))
        // La réponse, sans paquet : la session suffit.
        let r = send(b, a, bundle: false, #"{"k":"réponse"}"#)
        XCTAssertTrue(r.v3)
        XCTAssertEqual(recv(a, b, r), .v3(#"{"k":"réponse"}"#))
        let m3 = send(a, b, bundle: false, #"{"k":"trois"}"#)
        XCTAssertEqual(recv(b, a, m3), .v3(#"{"k":"trois"}"#))
        XCTAssertEqual(recv(b, a, m3), .replay)
        // Pas pour lui : illisible.
        let c = Side()
        XCTAssertEqual(recv(c, a, m3), .failed(.envelope))
    }

    func testLegacyWithoutBundleAndForLargePayloads() {
        let a = Side(), b = Side()
        let old = send(a, b, bundle: false, #"{"k":"ancien"}"#)
        XCTAssertFalse(old.v3)
        // Exactement l'ancien format : `TalkaCrypto.open` le lit seul.
        XCTAssertEqual(TalkaCrypto.open(b.id, peerCode: a.id.code, peerDh: a.id.dh, body: old.body), #"{"k":"ancien"}"#)
        XCTAssertEqual(recv(b, a, old), .legacy(#"{"k":"ancien"}"#))
        // Une session existe, mais une photo de 300 Kio ne tient pas dans un
        // jeton v3 : elle part dans l'ancien format.
        _ = send(a, b, bundle: true, "{}")
        XCTAssertTrue(a.engine.hasSession(b.id.code))
        let big = #"{"data":""# + String(repeating: "x", count: 300 * 1024) + #""}"#
        let sealed = send(a, b, bundle: false, big)
        XCTAssertFalse(sealed.v3)
        XCTAssertEqual(recv(b, a, sealed), .legacy(big))
    }

    func testBothSidesStartAtOnce() {
        let a = Side(), b = Side()
        let fromA = send(a, b, bundle: true, #"{"k":"a"}"#)
        let fromB = send(b, a, bundle: true, #"{"k":"b"}"#)
        XCTAssertEqual(recv(b, a, fromA), .v3(#"{"k":"a"}"#))
        XCTAssertEqual(recv(a, b, fromB), .v3(#"{"k":"b"}"#))
        for i in 0..<4 {
            let x = send(a, b, bundle: false, #"{"a":\#(i)}"#)
            let y = send(b, a, bundle: false, #"{"b":\#(i)}"#)
            XCTAssertEqual(recv(a, b, y), .v3(#"{"b":\#(i)}"#))
            XCTAssertEqual(recv(b, a, x), .v3(#"{"a":\#(i)}"#))
        }
        // Deux sessions au plus par contact, même si l'un recommence.
        XCTAssertEqual(a.engine.peers[b.id.code]?.sessions.count, 2)
        a.engine.forget(b.id.code)
        let again = send(a, b, bundle: true, #"{"k":"encore"}"#)
        XCTAssertEqual(recv(b, a, again), .v3(#"{"k":"encore"}"#))
        XCTAssertEqual(b.engine.peers[a.id.code]?.sessions.count, 2)
    }

    func testStatesSurviveAReloadAndALostSessionHeals() throws {
        let a = Side(), b = Side()
        let m = send(a, b, bundle: true, #"{"k":1}"#)
        guard case .v3 = recv(b, a, m) else { return XCTFail("illisible") }
        // Ce qui a changé, et rien de plus (l'appareil a été créé, puis il a
        // retenu le premier message).
        let dirty = b.engine.takeDirty()
        let device = try XCTUnwrap(dirty.device)
        XCTAssertEqual(dirty.peers.count, 1)
        XCTAssertTrue(b.engine.takeDirty().peers.isEmpty)
        // Relu depuis ce qui a été gardé.
        let b2 = Side(V3Engine(deviceJson: device, now: 2_000), id: b.id)
        XCTAssertFalse(b2.engine.isLoaded(a.id.code))
        b2.engine.load(a.id.code, json: dirty.peers[0].json)
        XCTAssertNil(b2.engine.takeDirty().device)
        let r = send(b2, a, bundle: false, #"{"k":2}"#)
        XCTAssertTrue(r.v3)
        XCTAssertEqual(recv(a, b2, r), .v3(#"{"k":2}"#))
        // Le premier message rejoué après relecture : toujours ignoré.
        XCTAssertEqual(recv(b2, a, m), .replay)

        // B perd ses sessions (données effacées) : le message suivant de A est
        // illisible — une session perdue, à réparer —, B en ouvre une neuve,
        // que A accepte.
        let b3 = Side(V3Engine(deviceJson: device, now: 3_000), id: b.id)
        let lost = send(a, b3, bundle: false, #"{"k":3}"#)
        guard case .failed(.v3(let e)) = recv(b3, a, lost) else { return XCTFail("lisible ?") }
        XCTAssertTrue(V3.needsRepair(e), "\(e)")
        b3.engine.forget(a.id.code)
        let heal = send(b3, a, bundle: true, #"{"k":"profil"}"#)
        XCTAssertEqual(recv(a, b3, heal), .v3(#"{"k":"profil"}"#))
        let after = send(a, b3, bundle: false, #"{"k":4}"#)
        XCTAssertEqual(recv(b3, a, after), .v3(#"{"k":4}"#))
    }

    func testAnOldReplayedMessageNeverResetsTheSession() {
        // Un message d'une chaîne plus vieille que celles que la session
        // retient (16 cliquets), rejoué par le relais : refusé, sans
        // réparation (sinon rejouer suffirait à faire tomber la session).
        let a = Side(), b = Side()
        let first = send(a, b, bundle: true, #"{"k":"bonjour"}"#)
        XCTAssertEqual(recv(b, a, first), .v3(#"{"k":"bonjour"}"#))
        let old = send(a, b, bundle: false, #"{"k":"vieux"}"#)
        XCTAssertEqual(recv(b, a, old), .v3(#"{"k":"vieux"}"#))
        for i in 0..<20 {
            let r = send(b, a, bundle: false, #"{"r":\#(i)}"#)
            XCTAssertEqual(recv(a, b, r), .v3(#"{"r":\#(i)}"#))
            let m = send(a, b, bundle: false, #"{"m":\#(i)}"#)
            XCTAssertEqual(recv(b, a, m), .v3(#"{"m":\#(i)}"#))
        }
        for replayed in [old, first] {
            switch recv(b, a, replayed) {
            case .replay: break
            case .failed(.v3(let e)): XCTAssertFalse(V3.needsRepair(e), "\(e)")
            case let other: XCTFail("\(other)")
            }
        }
        // La session tient toujours.
        let after = send(a, b, bundle: false, #"{"k":"après"}"#)
        XCTAssertEqual(recv(b, a, after), .v3(#"{"k":"après"}"#))
    }

    /// Les mots du navigateur (`client/src/ui.rs`, fiche du contact).
    func testProtectionLabelsAreTheBrowsers() {
        XCTAssertEqual(V3.Protection.ratchet(pq: true).label, "🔐 Session à double cliquet (post-quantique)")
        XCTAssertEqual(V3.Protection.ratchet(pq: false).label, "🔐 Session à double cliquet (classique)")
        XCTAssertEqual(V3.Protection.legacy.label, "Ancien chiffrement (le contact n'a pas encore la mise à jour)")
    }

    func testOnlyALostSessionIsRepaired() {
        XCTAssertTrue(V3.needsRepair(.sessionInconnue))
        XCTAssertTrue(V3.needsRepair(.cleInconnue))
        XCTAssertFalse(V3.needsRepair(.dechiffrement))
        XCTAssertFalse(V3.needsRepair(.rejoue))
        XCTAssertFalse(V3.needsRepair(.nouvelleSession))
    }

    func testRotationKeepsOldFirstMessagesReadable() {
        let a = Side(), b = Side()
        let oldBundle = bundleOf(b)
        XCTAssertFalse(b.engine.maintain(now: 1_000))
        XCTAssertTrue(b.engine.maintain(now: 1_000 + Paquet.prekeyRotation))
        XCTAssertNotEqual(bundleOf(b).spkId, oldBundle.spkId)
        let m = a.engine.seal(a.id, peerCode: b.id.code, peerDh: b.id.dh, bundle: oldBundle, plain: "{}")!
        XCTAssertEqual(recv(b, a, m), .v3("{}"))
        // Un mois plus tard, l'ancienne clé est effacée : un premier message
        // qui la vise est une session perdue (le contact en rouvrira une).
        _ = b.engine.maintain(now: 1_000 + 2 * Paquet.prekeyRotation)
        XCTAssertTrue(b.engine.maintain(now: 1_000 + 2 * Paquet.prekeyRotation + Paquet.prekeyGrace + Paquet.prekeyRotation))
        let c = Side()
        let late = c.engine.seal(c.id, peerCode: b.id.code, peerDh: b.id.dh, bundle: oldBundle, plain: "{}")!
        XCTAssertEqual(recv(b, c, late, now: 1_000 + 3 * Paquet.prekeyRotation + Paquet.prekeyGrace), .failed(.v3(.cleInconnue)))
    }

    /// Les états écrits par l'iPhone ont la forme de ceux du navigateur.
    func testStoredPeerHasTheBrowserShape() throws {
        let a = Side(), b = Side()
        _ = recv(b, a, send(a, b, bundle: true, "{}"))
        let json = try XCTUnwrap(b.engine.takeDirty().peers.first?.json)
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual((obj["current"] as? String)?.count, 16)
        XCTAssertEqual((obj["sessions"] as? [Any])?.count, 1)
    }
}

/// Le moteur entier, sans relais : ce qu'il garde, et où. (Sous Linux, les
/// tests d'une classe `@MainActor` doivent être `async`.)
@MainActor
final class V3EngineStoreTests: XCTestCase {
    func phone(_ name: String) -> Engine {
        let store = Store(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-v3-\(name)-\(randomId(4))"))
        Engine.createAccount(store, name: name, avatar: "")
        return Engine(store: store, server: URL(string: "http://127.0.0.1:9")!)
    }

    /// Les clés publiées, sans leurs signatures : CryptoKit signe avec un
    /// hasard (Ed25519 comme P-256), deux paquets du même appareil n'ont donc
    /// pas les mêmes octets sur un Mac ou un iPhone.
    func keys(_ e: Engine) -> [[UInt8]]? {
        guard let b = e.v3?.bundle(), let p = e.v3?.published(e.me), V3.verifyBundle(code: e.me.code, sign: e.me.sign, dh: e.me.dh, bundle: p.bundle, sig: p.sig) != nil
        else { return nil }
        return [b.identity.dh, b.identity.sign, b.spk, b.pq?.key ?? []]
    }

    func testDeviceIsCreatedOnceAndKept() async throws {
        let e = phone("Alice")
        let device = try XCTUnwrap(e.store.readRaw("kv", V3.deviceKey))
        let again = Engine(store: e.store, server: URL(string: "http://127.0.0.1:9")!)
        XCTAssertEqual(again.store.readRaw("kv", V3.deviceKey), device)
        XCTAssertEqual(keys(again), keys(e))
        XCTAssertNotNil(keys(e))
    }

    func testReceivedMessageAndSessionAreStoredTogether() async throws {
        let a = phone("Alice"), b = phone("Bob")
        // Bob connaît les clés d'Alice (sinon il les demanderait au relais).
        var c = Contact(code: a.me.code)
        c.sign = a.me.sign
        c.dh = a.me.dh
        c.saved = true
        b.saveContact(c)
        b.ensureDirectChat(a.me.code)
        let bundle = try XCTUnwrap(b.v3?.bundle())
        let wire = Wire(.msg(id: "m1", at: nowMs(), pn: "", content: TalkaKit.Content(text: "Bonjour 🥦")))
        let sealed = try XCTUnwrap(a.v3?.seal(a.me, peerCode: b.me.code, peerDh: b.me.dh, bundle: bundle, plain: wire.json()))
        XCTAssertTrue(sealed.v3)
        await b.receive(["from": a.me.code, "body": sealed.body, "ts": nowMs()])
        XCTAssertEqual(b.messages(a.me.code).last?.text, "Bonjour 🥦")
        // La session est dans la base, à côté du message.
        XCTAssertNotNil(b.store.readRaw("kv", V3.sessionPrefix + a.me.code))
        XCTAssertEqual(b.v3?.sessionProtection(a.me.code), .ratchet(pq: V3.postQuantum))
        let protection = await b.protection(a.me.code)
        XCTAssertEqual(protection, .ratchet(pq: V3.postQuantum))
        // Le même message, renvoyé par le relais : ignoré, sans réparation
        // (rien ne part vers Alice).
        let outbox = b.store.all("outbox", as: OutItem.self).count
        await b.receive(["from": a.me.code, "body": sealed.body, "ts": nowMs()])
        XCTAssertEqual(b.messages(a.me.code).filter { $0.text == "Bonjour 🥦" }.count, 1)
        XCTAssertEqual(b.store.all("outbox", as: OutItem.self).count, outbox)

        // Relu d'une base neuve (l'application relancée) : la session revient.
        let again = Engine(store: b.store, server: URL(string: "http://127.0.0.1:9")!)
        let next = Wire(.msg(id: "m2", at: nowMs(), pn: "", content: TalkaKit.Content(text: "Encore")))
        let s2 = try XCTUnwrap(a.v3?.seal(a.me, peerCode: b.me.code, peerDh: b.me.dh, bundle: nil, plain: next.json()))
        XCTAssertTrue(s2.v3)
        await again.receive(["from": a.me.code, "body": s2.body, "ts": nowMs()])
        XCTAssertEqual(again.messages(a.me.code).last?.text, "Encore")
    }

    func testALostSessionIsRepairedOnce() async throws {
        let a = phone("Alice"), b = phone("Bob")
        var c = Contact(code: a.me.code)
        c.sign = a.me.sign
        c.dh = a.me.dh
        b.saveContact(c)
        // Une session établie, confirmée des deux côtés…
        let first = try XCTUnwrap(a.v3?.seal(a.me, peerCode: b.me.code, peerDh: b.me.dh, bundle: b.v3?.bundle(), plain: "{}"))
        await b.receive(["from": a.me.code, "body": first.body, "ts": nowMs()])
        XCTAssertTrue(b.v3?.hasSession(a.me.code) ?? false)
        XCTAssertTrue(b.store.all("outbox", as: OutItem.self).isEmpty, "rien à réparer")
        let reply = try XCTUnwrap(b.v3?.seal(b.me, peerCode: a.me.code, peerDh: a.me.dh, bundle: nil, plain: "{}"))
        XCTAssertEqual(a.v3?.open(a.me, peerCode: b.me.code, peerDh: b.me.dh, body: reply.body, now: 2_000), .v3("{}"))
        // … que Bob perd (ses données effacées).
        b.v3?.forget(a.me.code)
        b.saveV3()
        let lost = try XCTUnwrap(a.v3?.seal(a.me, peerCode: b.me.code, peerDh: b.me.dh, bundle: nil, plain: "{}"))
        XCTAssertTrue(lost.v3)
        await b.receive(["from": a.me.code, "body": lost.body, "ts": nowMs()])
        // Bob renvoie son profil (une session neuve) : une seule fois.
        let profiles = b.store.all("outbox", as: OutItem.self).filter { $0.to == a.me.code }
        XCTAssertEqual(profiles.count, 1)
        await b.receive(["from": a.me.code, "body": lost.body, "ts": nowMs()])
        XCTAssertEqual(b.store.all("outbox", as: OutItem.self).filter { $0.to == a.me.code }.count, 1)
    }

    func testBackupCarriesTheDeviceButNotTheSessions() async throws {
        let a = phone("Alice"), b = phone("Bob")
        let bundle = try XCTUnwrap(b.v3?.bundle())
        _ = a.v3?.seal(a.me, peerCode: b.me.code, peerDh: b.me.dh, bundle: bundle, plain: "{}")
        a.saveV3()
        XCTAssertNotNil(a.store.readRaw("kv", V3.sessionPrefix + b.me.code))
        let file = try XCTUnwrap(a.exportBackup(password: "motdepasse"))
        let other = Store(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-v3-import-\(randomId(4))"))
        try Engine.importBackup(other, password: "motdepasse", file: file)
        XCTAssertEqual(other.readRaw("kv", V3.deviceKey), a.store.readRaw("kv", V3.deviceKey))
        XCTAssertNil(other.readRaw("kv", V3.sessionPrefix + b.me.code))
        // Le même appareil, donc le même paquet.
        let restored = Engine(store: other, server: URL(string: "http://127.0.0.1:9")!)
        XCTAssertEqual(keys(restored), keys(a))
        XCTAssertNotNil(keys(a))
    }

    func testTransactionsNest() async {
        let e = phone("Alice")
        e.store.transaction {
            e.store.writeRaw("kv", "x", "1")
            e.store.transaction { e.store.writeRaw("kv", "y", "2") }
        }
        let again = Store(directory: e.store.dir)
        XCTAssertEqual(again.readRaw("kv", "x"), "1")
        XCTAssertEqual(again.readRaw("kv", "y"), "2")
    }
}
