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
        // (Rangée sous son code, ou déjà passée à l'adresse de son appareil, v4.)
        let session = a.v3?.hasSession(b.me.code) == true || a.v3?.hasSession(TalkaV4.address(b.me.code, b.dev)) == true
        XCTAssertEqual(session, v3)

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

    /// Un iPad lié à `a1` par le code de son QR code, et connecté.
    func linkIPad(to a1: Engine, _ url: URL) async throws -> (Engine, DeviceLink) {
        // Le nouvel appareil attend, le QR code à l'écran.
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-ipad-\(randomId(4))")
        let store = Store(directory: dir)
        let link = DeviceLink(store: store, server: url)
        var result: Result<String, LinkError>?
        link.onDone = { result = $0 }
        link.start()
        try await wait("boîte de liaison écoutée") { link.socket.ready }
        let verif = try await a1.linkDevice(link.text)
        try await wait("liaison reçue", timeout: 20) { result != nil }
        XCTAssertEqual(try result?.get(), verif, "le même code de vérification des deux côtés")
        XCTAssertTrue(a1.devices.has(link.dev))
        Engine.deviceName = "iPad"
        let a2 = Engine(store: store, server: url)
        Engine.deviceName = "iPhone"
        a2.start()
        try await wait("l'iPad connecté") { a2.netState == .online }
        return (a2, link)
    }

    /// v4 : le relais redémarre pendant que l'iPad d'Alice est absent ; Bob
    /// ne renvoie qu'à lui (l'iPhone avait accusé réception).
    func testResendToTheMissingDeviceAfterRelayRestart() async throws {
        let url = try server()
        guard let flag = ProcessInfo.processInfo.environment["TALKA_RESTART_FILE"] else {
            throw XCTSkip("TALKA_RESTART_FILE n'est pas défini")
        }
        let a1 = phone("Alice", url), b = phone("Bob", url)
        defer { a1.stop(); b.stop() }
        try await wait("connexion") { a1.netState == .online && b.netState == .online }
        let (a2, _) = try await linkIPad(to: a1, url)
        defer { a2.stop() }
        _ = try await a1.addContact(b.me.code, name: "Bob")
        _ = try await b.addContact(a1.me.code, name: "Alice")
        try await wait("Bob connaît les deux appareils d'Alice") { b.contact(a1.me.code)?.devs.count == 2 }
        try await wait("les boîtes de Bob suivies") { a2.boxMap.values.contains(b.me.code) }

        a2.relay.stop()
        b.ensureDirectChat(a1.me.code)
        let before = b.relay.boot
        let m = try XCTUnwrap(b.send(a1.me.code, Content(text: "Pendant l'absence de l'iPad")))
        let key = "\(m.id):\(a1.me.code)"
        try await wait("reçu sur l'iPhone") { a1.messages(b.me.code).contains { $0.id == m.id } }
        try await wait("accusé de l'iPhone seul", timeout: 15) { b.store.read("outbox", key, as: OutItem.self)?.got == [a1.dev] }
        XCTAssertEqual(Set(b.store.read("outbox", key, as: OutItem.self)?.sent ?? []), [a1.dev, a2.dev])

        // Le relais redémarre : la boîte de l'iPad est perdue.
        let boxed = b.relay.boxSent
        FileManager.default.createFile(atPath: flag, contents: Data())
        try await wait("Bob reconnecté au nouveau relais", timeout: 40) { b.netState == .online && b.relay.boot != before }
        try await wait("Alice reconnectée", timeout: 40) { a1.netState == .online }
        try await wait("renvoyé à l'iPad seul", timeout: 20) { b.store.read("outbox", key, as: OutItem.self)?.boot == b.relay.boot }
        XCTAssertEqual(b.relay.boxSent - boxed, 1, "une seule copie : l'iPhone l'a déjà")
        a2.relay.start()
        try await wait("reçu sur l'iPad", timeout: 30) { a2.messages(b.me.code).contains { $0.id == m.id } }
        try await wait("plus rien n'attend", timeout: 15) { b.store.read("outbox", key, as: OutItem.self) == nil }
        XCTAssertEqual(a1.messages(b.me.code).filter { $0.id == m.id }.count, 1)
    }

    /// v4 : deux appareils d'un même compte (Alice sur iPhone et iPad) et un
    /// contact (Bob). L'iPad est lié par le code du QR code ; les deux
    /// reçoivent ce que Bob écrit (dans leurs boîtes tournantes) ; ce que
    /// l'iPad envoie apparaît sur l'iPhone ; puis l'iPad est retiré.
    func testTwoDevicesOfOneAccount() async throws {
        let url = try server()
        let a1 = phone("Alice", url), b = phone("Bob", url)
        defer { a1.stop(); b.stop() }
        try await wait("connexion") { a1.netState == .online && b.netState == .online }

        let (a2, link) = try await linkIPad(to: a1, url)
        defer { a2.stop() }
        // Un code de liaison déjà servi, ou qui n'en est pas un : refusé.
        do {
            _ = try await a1.linkDevice(link.text)
            XCTFail("lié deux fois")
        } catch let e as LinkError { XCTAssertEqual(e, .alreadyLinked) }
        do {
            _ = try await a1.linkDevice(b.me.code)
            XCTFail("pas un code de liaison")
        } catch let e as LinkError { XCTAssertEqual(e, .notALink) }
        XCTAssertEqual(a2.me, a1.me)
        XCTAssertEqual(a2.dev, link.dev)
        XCTAssertNotEqual(a2.dev, a1.dev)
        XCTAssertEqual(a2.siblings, [a1.dev])
        // L'iPad prend son vrai nom, et l'iPhone l'apprend.
        try await wait("le nom de l'iPad") { a1.devices.list.first { $0.dev == a2.dev }?.name == "iPad" }

        // Alice ajoute Bob (sur l'iPhone : l'iPad l'apprend), Bob ajoute Alice.
        _ = try await a1.addContact(b.me.code, name: "Bob")
        try await wait("contact synchronisé") { a2.contact(b.me.code)?.saved == true && a2.contact(b.me.code)?.name == "Bob" }
        _ = try await b.addContact(a1.me.code, name: "Alice")
        try await wait("Bob connaît les deux appareils d'Alice") {
            Set(b.contact(a1.me.code)?.devs.map(\.d) ?? []) == [a1.dev, a2.dev] && b.contact(a1.me.code)?.bx == true
        }
        try await wait("les boîtes de Bob suivies") {
            a1.boxMap.values.contains(b.me.code) && a2.boxMap.values.contains(b.me.code)
        }

        // Bob écrit : les deux appareils d'Alice le reçoivent, par leurs
        // boîtes tournantes (le relais ne sait ni qui écrit, ni à qui).
        b.ensureDirectChat(a1.me.code)
        let boxed = b.relay.boxSent
        let hello = try XCTUnwrap(b.send(a1.me.code, Content(text: "Salut Alice")))
        try await wait("reçu sur l'iPhone") { a1.messages(b.me.code).contains { $0.id == hello.id } }
        try await wait("reçu sur l'iPad") { a2.messages(b.me.code).contains { $0.id == hello.id } }
        XCTAssertGreaterThanOrEqual(b.relay.boxSent - boxed, 2, "une copie par appareil, chacune dans sa boîte")
        XCTAssertEqual(a2.messages(b.me.code).first { $0.id == hello.id }?.text, "Salut Alice")
        // Chacun accuse réception : le message n'attend plus personne.
        try await wait("accusés des deux appareils", timeout: 15) {
            b.store.read("outbox", "\(hello.id):\(a1.me.code)", as: OutItem.self) == nil
                && b.store.message(a1.me.code, hello.id)?.tick == .delivered
        }

        // L'iPad écrit : Bob le reçoit, l'iPhone l'affiche comme envoyé.
        a2.ensureDirectChat(b.me.code)
        let reply = try XCTUnwrap(a2.send(b.me.code, Content(text: "Envoyé de l'iPad")))
        try await wait("reçu par Bob") { b.messages(a1.me.code).contains { $0.id == reply.id } }
        try await wait("synchronisé sur l'iPhone") { a1.messages(b.me.code).contains { $0.id == reply.id } }
        let mirrored = try XCTUnwrap(a1.messages(b.me.code).first { $0.id == reply.id })
        XCTAssertEqual(mirrored.from, a1.me.code)
        XCTAssertEqual(mirrored.text, "Envoyé de l'iPad")
        // L'iPhone a dit l'avoir (`sync`/`got`) : l'iPad n'attend plus.
        try await wait("sync/got", timeout: 15) {
            a2.store.read("outbox", "\(reply.id):\(a1.me.code)", as: OutItem.self) == nil
        }

        // Lu sur l'iPad : lu sur l'iPhone.
        XCTAssertGreaterThan(a1.chat(b.me.code)?.unread ?? 0, 0)
        a2.markRead(b.me.code)
        try await wait("lu partout") { a1.chat(b.me.code)?.unread == 0 }
        // Épinglée sur l'iPhone : épinglée sur l'iPad.
        a1.updateChat(b.me.code) { $0.pinned = true }
        try await wait("état de la discussion") { a2.chat(b.me.code)?.pinned == true }

        // Un appel pris sur l'iPhone : l'iPad cesse de sonner.
        var elsewhere: String?
        a2.onCallSignal = { from, body, _ in
            if from == a2.me.code, case let .call(op, callId, _, _, _) = body, op == "elsewhere" { elsewhere = callId }
        }
        a1.callTaken("appel-1")
        try await wait("pris ailleurs") { elsewhere == "appel-1" }

        // L'iPhone retire l'iPad : il efface tout ; Bob ne lui écrit plus.
        var gone = false
        a2.onUnlinked = { gone = true }
        await a1.unlinkDevice(a2.dev)
        try await wait("l'iPad retiré", timeout: 15) { gone }
        XCTAssertEqual(a1.devices.list.map(\.dev), [a1.dev])
        try await wait("Bob l'apprend") { b.contact(a1.me.code)?.devs.map(\.d) == [a1.dev] }
        let after = try XCTUnwrap(b.send(a1.me.code, Content(text: "Encore là ?")))
        try await wait("toujours reçu sur l'iPhone") { a1.messages(b.me.code).contains { $0.id == after.id } }
    }
}
