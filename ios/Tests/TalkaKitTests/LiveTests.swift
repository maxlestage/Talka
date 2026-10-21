// Deux iPhones (deux moteurs TalkaKit) sur un vrai serveur Talka :
//
//     TALKA_SERVER=http://localhost:3000 swift test
//
// Sans `TALKA_SERVER`, ces tests sont sautés.

import Foundation
import XCTest
@testable import TalkaKit

@MainActor
final class LiveTests: XCTestCase {
    func server() throws -> URL {
        guard let s = ProcessInfo.processInfo.environment["TALKA_SERVER"], let url = URL(string: s) else {
            throw XCTSkip("TALKA_SERVER n'est pas défini")
        }
        return url
    }

    func phone(_ name: String, _ url: URL) -> Engine {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-\(name)-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: name, avatar: "")
        let e = Engine(store: store, server: url)
        e.start()
        return e
    }

    func wait(_ what: String, timeout: Double = 10, _ ok: @escaping () -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while !ok() {
            if Date() > end { XCTFail("trop long : \(what)"); throw XCTSkip(what) }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    func testTwoPhones() async throws {
        let url = try server()
        let a = phone("Alice", url), b = phone("Bob", url)
        defer { a.stop(); b.stop() }
        try await wait("connexion") { a.netState == .online && b.netState == .online }

        // Alice ajoute Bob par son code et lui écrit.
        let reachable = try await a.addContact(b.me.code, name: "Bob")
        XCTAssertTrue(reachable)
        a.ensureDirectChat(b.me.code)
        let sent = try XCTUnwrap(a.send(b.me.code, Content(text: "Bonjour depuis l'iPhone 📱")))
        try await wait("réception") { !(b.messages(a.me.code).isEmpty) }
        let got = try XCTUnwrap(b.messages(a.me.code).last)
        XCTAssertEqual(got.text, "Bonjour depuis l'iPhone 📱")
        XCTAssertEqual(b.displayName(a.me.code), "~Alice", "le profil a suivi")
        // Deux iPhones à jour : une session à double cliquet (v3) ; avec un
        // relais d'avant v3, l'ancien format (sans attendre le paquet).
        let protection = await b.protection(a.me.code)
        let v3 = a.relay.relayV3 == true
        XCTAssertEqual(protection, v3 ? .ratchet(pq: V3.postQuantum) : .legacy)
        XCTAssertEqual(a.v3?.hasSession(b.me.code), v3)

        // L'expéditeur scellé : chacun a reçu le jeton de l'autre ; ensuite,
        // tout part par la connexion anonyme (le relais ne sait plus qui écrit).
        if a.relay.sealed {
            try await wait("jetons") { b.contact(a.me.code)?.jeton.isEmpty == false && a.contact(b.me.code)?.jeton.isEmpty == false }
            let before = a.relay.sealedSent
            a.send(b.me.code, Content(text: "Scellé"))
            try await wait("scellé") { b.messages(a.me.code).contains { $0.text == "Scellé" } }
            XCTAssertGreaterThan(a.relay.sealedSent, before)
            XCTAssertEqual(b.messages(a.me.code).last { $0.text == "Scellé" }?.from, a.me.code)
        }

        // ✓✓ puis ✓✓ bleus.
        try await wait("distribué") { a.store.message(b.me.code, sent.id)?.tick == .delivered }
        b.markRead(a.me.code)
        try await wait("lu") { a.store.message(b.me.code, sent.id)?.tick == .read }

        // Réaction, modification, suppression.
        b.react(a.me.code, got.id, "❤️")
        try await wait("réaction") { a.store.message(b.me.code, sent.id)?.reactions[b.me.code] == "❤️" }
        a.edit(b.me.code, sent.id, text: "Bonjour, modifié")
        try await wait("modification") { b.store.message(a.me.code, sent.id)?.text == "Bonjour, modifié" }
        a.delete(b.me.code, sent.id, forEveryone: true)
        try await wait("suppression") { b.store.message(a.me.code, sent.id)?.deleted == true }

        // Un groupe.
        try await b.addContact(a.me.code, name: "Alice")
        let g = a.createGroup(name: "Les amis", members: [b.me.code], avatar: "")
        a.send(g.id, Content(text: "Dans le groupe"))
        try await wait("groupe") { b.messages(g.id).contains { $0.text == "Dans le groupe" } }
        XCTAssertEqual(b.chat(g.id)?.name, "Les amis")

        // Un statut.
        a.postStatus(kind: "text", text: "Vacances 🌊", bg: "#008069", data: "")
        try await wait("statut") { b.statuses().contains { $0.text == "Vacances 🌊" } }

        // Hors ligne : Bob part, Alice écrit, Bob revient.
        b.relay.stop()
        a.send(b.me.code, Content(text: "Tu étais parti"))
        try await Task.sleep(nanoseconds: 500_000_000)
        b.relay.start()
        try await wait("message en attente") { b.messages(a.me.code).contains { $0.text == "Tu étais parti" } }

        // La sauvegarde, et son import ailleurs.
        let file = try XCTUnwrap(a.exportBackup(password: "motdepasse"))
        let other = Store(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-import-\(randomId(4))"))
        try Engine.importBackup(other, password: "motdepasse", file: file)
        XCTAssertEqual(other.read("kv", "identity", as: Identity.self), a.me)
        XCTAssertFalse(other.messagesOf(g.id).isEmpty)
    }

    /// Le relais redémarre pendant que Bob est absent : sa boîte aux lettres
    /// est perdue, mais Alice renvoie ce qui n'a pas été confirmé.
    /// `TALKA_RESTART_FILE` : un fichier que le test crée pour demander à son
    /// lanceur de redémarrer le serveur (voir ios/README.md).
    func testResendAfterRelayRestart() async throws {
        let url = try server()
        guard let flag = ProcessInfo.processInfo.environment["TALKA_RESTART_FILE"] else {
            throw XCTSkip("TALKA_RESTART_FILE n'est pas défini")
        }
        let a = phone("Alice", url), b = phone("Bob", url)
        defer { a.stop(); b.stop() }
        try await wait("connexion") { a.netState == .online && b.netState == .online }
        XCTAssertFalse(a.relay.boot.isEmpty)
        let before = a.relay.boot
        _ = try await a.addContact(b.me.code, name: "Bob")
        a.ensureDirectChat(b.me.code)

        b.relay.stop()
        let sent = try XCTUnwrap(a.send(b.me.code, Content(text: "Pendant ton absence")))
        try await wait("accepté par le relais") { a.store.message(b.me.code, sent.id)?.sentTo.contains(b.me.code) == true }
        XCTAssertEqual(a.store.read("outbox", "\(sent.id):\(b.me.code)", as: OutItem.self)?.boot, before)

        // Le relais redémarre : la boîte aux lettres de Bob est perdue.
        FileManager.default.createFile(atPath: flag, contents: Data())
        try await wait("Alice reconnectée au nouveau relais", timeout: 40) { a.netState == .online && a.relay.boot != before }

        b.relay.start()
        try await wait("message renvoyé puis reçu", timeout: 30) { b.messages(a.me.code).contains { $0.text == "Pendant ton absence" } }
        try await wait("accusé de réception", timeout: 15) { a.store.message(b.me.code, sent.id)?.deliveredTo.contains(b.me.code) == true }
        XCTAssertNil(a.store.read("outbox", "\(sent.id):\(b.me.code)", as: OutItem.self))
    }
}
