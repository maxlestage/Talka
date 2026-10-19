// Un iPhone (TalkaKit) et un navigateur (l'application web, en Rust compilé
// en WebAssembly) dans la même conversation. Lancé par `ios/interop.mjs`,
// qui pilote le navigateur et échange les codes par deux fichiers.

import Foundation
import XCTest
@testable import TalkaKit

@MainActor
final class InteropTests: XCTestCase {
    func testWithBrowser() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["TALKA_SERVER"].flatMap(URL.init(string:)), let dir = env["TALKA_INTEROP_DIR"] else {
            throw XCTSkip("lancé par ios/interop.mjs")
        }
        let store = Store(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-interop-\(randomId(4))"))
        Engine.createAccount(store, name: "iPhone", avatar: "")
        let e = Engine(store: store, server: server)
        e.start()
        defer { e.stop() }

        func wait(_ what: String, _ timeout: Double = 30, _ ok: () -> Bool) async throws {
            let end = Date().addingTimeInterval(timeout)
            while !ok() {
                guard Date() < end else { XCTFail("trop long : \(what)"); throw XCTSkip(what) }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
        }
        try await wait("connexion") { e.netState == .online }
        try e.me.code.write(toFile: "\(dir)/swift.code", atomically: true, encoding: .utf8)
        var browser = ""
        try await wait("code du navigateur", 60) {
            browser = (try? String(contentsOfFile: "\(dir)/browser.code", encoding: .utf8)) ?? ""
            return browser.count == 10
        }

        try await e.addContact(browser, name: "Navigateur")
        e.ensureDirectChat(browser)
        let sent = try XCTUnwrap(e.send(browser, Content(text: "Bonjour du iPhone 📱")))
        // Le navigateur répond et lit : ✓✓ bleus côté iPhone.
        try await wait("réponse du navigateur", 60) { e.messages(browser).contains { $0.text == "Bonjour de l'ordinateur 💻" } }
        try await wait("lu par le navigateur") { e.store.message(browser, sent.id)?.tick == .read }
        XCTAssertEqual(e.displayName(browser), "Navigateur")
        let reply = try XCTUnwrap(e.messages(browser).first { $0.text == "Bonjour de l'ordinateur 💻" })
        e.markRead(browser)
        e.react(browser, reply.id, "👍")
        // Un groupe créé sur l'iPhone, où le navigateur écrit.
        let g = e.createGroup(name: "iPhone et navigateur", members: [browser], avatar: "")
        e.send(g.id, Content(text: "Dans le groupe"))
        try await wait("message de groupe du navigateur", 60) { e.messages(g.id).contains { $0.text == "Réponse dans le groupe" } }
        try "fini".write(toFile: "\(dir)/swift.done", atomically: true, encoding: .utf8)
        try await Task.sleep(nanoseconds: 2_000_000_000)
    }
}
