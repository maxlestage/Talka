// Le relais authentifié (v6, `docs/SECURITE.md` §7.16) sans réseau : la clé
// prouvée à la première connexion est retenue, un ordre d'effacement non
// signé d'elle est ignoré, et Talka le dit une fois.

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation
import XCTest
@testable import TalkaKit

final class V6RelayKeyTests: XCTestCase {
    let server = P256.Signing.PrivateKey()
    var serverKey: String { server.publicKey.x963Representation.base64EncodedString() }

    func sig(_ key: P256.Signing.PrivateKey, _ message: Data) -> String {
        (try! key.signature(for: message)).rawRepresentation.base64EncodedString()
    }

    func json(_ object: [String: Any]) -> String {
        String(data: try! JSONSerialization.data(withJSONObject: object), encoding: .utf8)!
    }

    @MainActor
    func relay() -> (Relay, Identity) {
        let me = Identity.create()
        let relay = Relay(identity: me, server: URL(string: "https://talka.example")!)
        relay.dev = "00000000000000a1"
        relay.receive(json(["t": "challenge", "nonce": "n1", "time": 1.0]))
        return (relay, me)
    }

    @MainActor
    func testTheFirstProvenKeyIsPinnedAndSignedRemovalsObeyed() async {
        let (relay, me) = relay()
        var pinned: String?
        var unlinked = 0
        var warned = 0
        relay.onRelayKey = { pinned = $0 }
        relay.onUnlinked = { unlinked += 1 }
        relay.onUnprovenRelay = { warned += 1 }
        let ready = RelayKey.readyMessage(nonce: "n1", code: me.code, dev: "00000000000000a1")
        relay.receive(json(["t": "ready", "relais": serverKey, "relais_sig": sig(server, ready)]))
        XCTAssertEqual(pinned, serverKey)
        XCTAssertEqual(relay.relayKey, serverKey)
        // Non signé, ou signé d'une autre clé : ignoré, et dit une fois.
        relay.receive(json(["t": "unlinked"]))
        let removal = RelayKey.unlinkedMessage(code: me.code, dev: "00000000000000a1")
        relay.receive(json(["t": "unlinked", "sig": sig(P256.Signing.PrivateKey(), removal)]))
        XCTAssertEqual(unlinked, 0)
        XCTAssertEqual(warned, 1)
        // Signé de la clé retenue : obéi.
        relay.receive(json(["t": "unlinked", "sig": sig(server, removal)]))
        XCTAssertEqual(unlinked, 1)
    }

    @MainActor
    func testAnotherKeyIsReportedAndNotPinned() async {
        let (relay, me) = relay()
        relay.relayKey = serverKey
        var pinned: String?
        var warned = 0
        relay.onRelayKey = { pinned = $0 }
        relay.onUnprovenRelay = { warned += 1 }
        let other = P256.Signing.PrivateKey()
        let ready = RelayKey.readyMessage(nonce: "n1", code: me.code, dev: "00000000000000a1")
        relay.receive(json(["t": "ready", "relais": other.publicKey.x963Representation.base64EncodedString(),
                            "relais_sig": sig(other, ready)]))
        XCTAssertNil(pinned)
        XCTAssertEqual(relay.relayKey, serverKey)
        XCTAssertEqual(warned, 1)
        // Plus de preuve du tout : pareil (sans redire).
        relay.receive(json(["t": "ready"]))
        XCTAssertEqual(warned, 1)
    }

    @MainActor
    func testWithoutAKeyNothingChanges() async {
        let (relay, _) = relay()
        var unlinked = 0
        var warned = 0
        relay.onUnlinked = { unlinked += 1 }
        relay.onUnprovenRelay = { warned += 1 }
        relay.receive(json(["t": "ready"]))
        relay.receive(json(["t": "unlinked"]))
        XCTAssertEqual(unlinked, 1)
        XCTAssertEqual(warned, 0)
        XCTAssertEqual(relay.relayKey, "")
    }
}
