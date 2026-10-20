// L'iPhone (le moteur TalkaKit entier) et le navigateur (son code Rust,
// `talka_web::v3`, dans `tests/ios_v3.rs`) en protocole v3, par un vrai
// relais. Lancé par `ios/interop-v3.sh`, qui démarre le relais et les deux
// côtés ; ils s'échangent leurs codes et quelques signaux par des fichiers.

import Foundation
import XCTest
@testable import TalkaKit

@MainActor
final class V3InteropTests: XCTestCase {
    func testWithRust() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["TALKA_SERVER"].flatMap(URL.init(string:)), let dir = env["TALKA_V3_INTEROP_DIR"] else {
            throw XCTSkip("lancé par ios/interop-v3.sh")
        }
        let store = Store(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-v3-interop-\(randomId(4))"))
        Engine.createAccount(store, name: "iPhone", avatar: "")
        let e = Engine(store: store, server: server)
        e.start()
        defer { e.stop() }

        func wait(_ what: String, _ timeout: Double = 60, _ ok: @escaping () -> Bool) async throws {
            let end = Date().addingTimeInterval(timeout)
            while !ok() {
                guard Date() < end else { XCTFail("trop long : \(what)"); throw XCTSkip(what) }
                try await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        func file(_ name: String) -> String {
            ((try? String(contentsOfFile: "\(dir)/\(name)", encoding: .utf8)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        func signal(_ name: String) throws { try "1".write(toFile: "\(dir)/\(name)", atomically: true, encoding: .utf8) }

        // Connecté, le paquet publié et accepté par le relais.
        try await wait("connexion et paquet publié") { e.netState == .online && e.relay.relayV3 == true }
        try e.me.code.write(toFile: "\(dir)/swift.code", atomically: true, encoding: .utf8)
        var rust = ""
        try await wait("code du navigateur", 300) { rust = file("rust.code"); return rust.count == 10 }

        // Le premier message ouvre une session vers le paquet du navigateur,
        // vérifié par l'iPhone (lié à son code).
        try await e.addContact(rust, name: "Navigateur")
        e.ensureDirectChat(rust)
        let hello = try XCTUnwrap(e.send(rust, Content(text: "Bonjour du iPhone 📱")))
        try await wait("envoyé") { e.store.message(rust, hello.id)?.sentTo.contains(rust) == true }
        let bundle = try XCTUnwrap(e.bundleCache[rust]?.bundle, "paquet du navigateur vérifié")
        XCTAssertNotNil(bundle.pq, "hybride ML-KEM-768 (le navigateur)")
        XCTAssertTrue(e.v3?.hasSession(rust) ?? false)
        try signal("swift.sent")
        // Un gros fichier (plus de 200 Kio) : l'ancien format.
        let big = "data:application/octet-stream;base64," + String(repeating: "A", count: 320 * 1024)
        e.send(rust, Content(kind: "file", data: big, name: "gros.bin", mime: "application/octet-stream", size: 240 * 1024))

        // Le premier message du navigateur, par sa propre session : l'iPhone
        // en a maintenant deux avec lui (une dans chaque sens).
        try await wait("premier message du navigateur") { e.messages(rust).contains { $0.text == "Bonjour de Rust 🦀" } }
        XCTAssertEqual(e.v3?.peers[rust]?.sessions.count, 2)

        // Dans le désordre, et un doublon.
        let ordered = ["n°1", "n°2", "n°3"]
        try await wait("messages dans le désordre") { ordered.allSatisfy { t in e.messages(rust).contains { $0.text == t } } }
        for t in ordered { XCTAssertEqual(e.messages(rust).filter { $0.text == t }.count, 1, t) }
        e.send(rust, Content(text: "reçu dans le désordre"))

        // Des allers-retours.
        for i in 0..<18 {
            try await wait("ping \(i)") { e.messages(rust).contains { $0.text == "ping \(i)" } }
            e.send(rust, Content(text: "pong \(i)"))
        }
        // Puis de vieux messages rejoués : ignorés, sans réparation.
        try await wait("après rejeu") { e.messages(rust).contains { $0.text == "après rejeu" } }
        XCTAssertEqual(e.messages(rust).filter { $0.text == "Bonjour de Rust 🦀" }.count, 1)
        XCTAssertEqual(e.messages(rust).filter { $0.text == "n°1" }.count, 1)
        e.send(rust, Content(text: "après rejeu : rien n'a bougé"))

        // Le navigateur perd ses sessions : ce message-ci est perdu pour lui,
        // il répare (son profil, par une session neuve), l'iPhone l'accepte.
        try await wait("sessions perdues", 120) { !file("rust.lost").isEmpty }
        e.send(rust, Content(text: "après la perte"))
        try await wait("profil du navigateur, par une session neuve") { e.contact(rust)?.profileName == "Rust réparé" }
        e.send(rust, Content(text: "après la réparation"))
        try await wait("fin") { e.messages(rust).contains { $0.text == "fin" } }
        let protection = await e.protection(rust)
        XCTAssertEqual(protection, .ratchet(pq: V3.postQuantum))

        // Puis l'iPhone perd les siennes (ses accusés de réception partis) :
        // il répare au premier message illisible, le navigateur suit.
        try await Task.sleep(nanoseconds: 1_500_000_000)
        e.v3?.forget(rust)
        e.saveV3()
        try signal("swift.lost")
        try await wait("après la réparation de l'iPhone") { e.messages(rust).contains { $0.text == "re-bonjour" } }
        XCTAssertFalse(e.messages(rust).contains { $0.text == "après ta perte" }, "perdu, comme dans le navigateur")

        // Un ancien client (sans paquet) : l'ancien format, dans les deux sens.
        var old = ""
        try await wait("code de l'ancien client") { old = file("old.code"); return old.count == 10 }
        try await e.addContact(old, name: "Ancien")
        e.ensureDirectChat(old)
        e.send(old, Content(text: "pour l'ancien"))
        try await wait("réponse de l'ancien client") { e.messages(old).contains { $0.text == "de l'ancien" } }
        XCTAssertFalse(e.v3?.hasSession(old) ?? true)
        let oldProtection = await e.protection(old)
        XCTAssertEqual(oldProtection, .legacy)
        XCTAssertEqual(oldProtection.label, "Ancien chiffrement (le contact n'a pas encore la mise à jour)")

        // L'appareil v3 part dans la sauvegarde : celui du navigateur se
        // relit ici (le même paquet que celui qu'il a publié), et le nôtre
        // chez lui.
        try await wait("appareil du navigateur") { !file("rust.device").isEmpty }
        let restored = V3Engine(deviceJson: file("rust.device"), now: V3.secs(nowMs()))
        XCTAssertNil(restored.takeDirty().device, "relu, pas recréé")
        let rustBundle = try XCTUnwrap(e.bundleCache[rust]?.bundle)
        XCTAssertEqual(restored.bundle()?.identity, rustBundle.identity)
        XCTAssertEqual(restored.bundle()?.spk, rustBundle.spk)
        XCTAssertEqual(restored.bundle()?.pq?.key, rustBundle.pq?.key)
        try XCTUnwrap(e.store.readRaw("kv", V3.deviceKey)).write(toFile: "\(dir)/swift.device", atomically: true, encoding: .utf8)
        try signal("swift.done")
        try await Task.sleep(nanoseconds: 500_000_000)
    }
}
