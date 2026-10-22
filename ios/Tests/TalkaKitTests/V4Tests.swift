// La v4 sans réseau (`docs/SECURITE.md` §7.11 à §7.13), comme les tests du
// navigateur : la liste des appareils, une session par appareil, la session
// ouverte avant de connaître les appareils qui passe à l'adresse de
// l'appareil, et la liaison (ce qui part, ce qui est rangé).

import Foundation
import XCTest
import BrocolisProto
@testable import TalkaKit

final class V4Tests: XCTestCase {
    func device(_ dev: String, _ name: String = "iPhone") -> AccountDevice {
        AccountDevice(dev: dev, name: name, at: 1)
    }

    func testDevicesMerge() {
        var mine = Devices(v: 2, list: [device("00000000000000a1"), device("00000000000000a2")])
        // Plus ancienne ou égale : ignorée.
        XCTAssertFalse(mine.merge(Devices(v: 2, list: [device("00000000000000a1")])))
        XCTAssertFalse(mine.merge(Devices(v: 1, list: [])))
        // Mal formée : ignorée.
        XCTAssertFalse(mine.merge(Devices(v: 3, list: [device("pas-un-appareil")])))
        let seven = (1...7).map { device(String(format: "00000000000000%02x", $0)) }
        XCTAssertFalse(mine.merge(Devices(v: 3, list: seven)))
        XCTAssertEqual(mine.v, 2)
        // Plus récente : gardée.
        XCTAssertTrue(mine.merge(Devices(v: 3, list: [device("00000000000000a1")])))
        XCTAssertEqual(mine.list.map(\.dev), ["00000000000000a1"])
        XCTAssertTrue(mine.has("00000000000000a1"))
        XCTAssertFalse(mine.has("00000000000000a2"))
        XCTAssertEqual(mine.announced, [DevInfo(d: "00000000000000a1")])
        // Le JSON du navigateur (`v`, `list`, `nk` seulement s'il y en a une).
        let json = JSON.encode(Devices(v: 1, list: [AccountDevice(dev: "00000000000000a1", name: "iPad", at: 5)]))
        let obj = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
        XCTAssertEqual(obj?["v"] as? Int, 1)
        let first = (obj?["list"] as? [[String: Any]])?.first
        XCTAssertEqual(first.map { Set($0.keys) }, ["dev", "name", "at"])
        XCTAssertEqual(JSON.decode(Devices.self, #"{"v":4,"list":[{"dev":"00000000000000b1","name":"Chrome sur Mac","at":1.5,"nk":"x"}]}"#)?.list.first?.nk, "x")
        XCTAssertEqual(JSON.decode(Devices.self, "{}"), Devices())
        XCTAssertNil(obj?["gone"], "sans appareil retiré, le JSON est celui d'avant")
    }

    /// Les appareils retirés sont retenus (seize au plus), comme dans le
    /// navigateur : chaque appareil les redit au relais à chaque connexion.
    func testRemovedDevicesAreRemembered() {
        var list = Devices(v: 3, list: [device("00000000000000a1"), device("00000000000000a2")])
        list.remove("00000000000000a2")
        XCTAssertEqual(list.v, 4)
        XCTAssertFalse(list.has("00000000000000a2"))
        XCTAssertEqual(list.gone, ["00000000000000a2"])
        list.remove("00000000000000a2")
        XCTAssertEqual(list.gone.count, 1)
        for i in 0..<20 { list.remove(String(format: "%016x", i)) }
        XCTAssertEqual(list.gone.count, maxGone)
        XCTAssertEqual(list.gone.first, String(format: "%016x", 19))
        // Le JSON du navigateur, dans les deux sens.
        let back = JSON.decode(Devices.self, JSON.encode(list))
        XCTAssertEqual(back?.gone, list.gone)
        XCTAssertEqual(JSON.decode(Devices.self, #"{"v":2,"list":[],"gone":["00000000000000c1"]}"#)?.gone, ["00000000000000c1"])
        // Reçue avec des retirés mal formés, ou trop : refusée.
        var mine = Devices()
        var bad = list
        bad.gone.append("pas-un-appareil")
        XCTAssertFalse(mine.merge(bad))
        XCTAssertTrue(mine.merge(list))
        XCTAssertEqual(mine.gone, list.gone)
    }

    final class Side {
        let id: Identity
        let engine: V3Engine
        init(id: Identity = Identity.create()) {
            self.id = id
            engine = V3Engine(deviceJson: nil, now: 1_000)
        }
    }

    func deviceBundle(_ s: Side, _ dev: String) -> KeyBundle {
        let p = s.engine.publishedDevice(s.id, dev: dev)!
        return V3.verifyDeviceBundle(code: s.id.code, sign: s.id.sign, dh: s.id.dh, dev: dev, bundle: p.bundle, sig: p.sig)!
    }

    /// Le paquet d'un appareil est lié à cet appareil ; une session par
    /// appareil d'un même compte (les adresses `<code>/<dev>`).
    func testDeviceBundlesAndSessionsPerDevice() throws {
        let d1 = "00000000000000b1", d2 = "00000000000000b2"
        let a = Side(), b1 = Side()
        // B2 : un autre appareil du compte de B1 (la même identité, son propre
        // appareil v3).
        let b2 = Side(id: b1.id)
        let b = b1.id
        let p = try XCTUnwrap(b1.engine.publishedDevice(b, dev: d1))
        XCTAssertNotNil(V3.verifyDeviceBundle(code: b.code, sign: b.sign, dh: b.dh, dev: d1, bundle: p.bundle, sig: p.sig))
        // Présenté pour un autre appareil, ou comme un paquet d'avant : refusé.
        XCTAssertNil(V3.verifyDeviceBundle(code: b.code, sign: b.sign, dh: b.dh, dev: d2, bundle: p.bundle, sig: p.sig))
        XCTAssertNil(V3.verifyBundle(code: b.code, sign: b.sign, dh: b.dh, bundle: p.bundle, sig: p.sig))
        // A écrit à chacun : une session par adresse, l'enveloppe des codes.
        let (addr1, addr2) = ("\(b.code)/\(d1)", "\(b.code)/\(d2)")
        let m1 = try XCTUnwrap(a.engine.sealTo(a.id, addr: addr1, peerCode: b.code, peerDh: b.dh, bundle: deviceBundle(b1, d1), plain: #"{"k":1}"#))
        let m2 = try XCTUnwrap(a.engine.sealTo(a.id, addr: addr2, peerCode: b.code, peerDh: b.dh, bundle: deviceBundle(b2, d2), plain: #"{"k":2}"#))
        XCTAssertTrue(m1.v3 && m2.v3)
        let from = "\(a.id.code)/00000000000000a1"
        func open(_ side: Side, _ body: String) -> V3.Opened {
            side.engine.openFrom(side.id, addr: from, peerCode: a.id.code, peerDh: a.id.dh, body: body, now: 2_000)
        }
        XCTAssertEqual(open(b1, m1.body), .v3(#"{"k":1}"#))
        XCTAssertEqual(open(b2, m2.body), .v3(#"{"k":2}"#))
        // Chacun ne lit que la sienne.
        guard case .failed = open(b1, m2.body) else { return XCTFail("lu par le mauvais appareil") }
        XCTAssertTrue(a.engine.hasSession(addr1) && a.engine.hasSession(addr2))
        XCTAssertFalse(a.engine.hasSession(b.code))
    }

    /// B (un appareil) écrit à A sans connaître ses appareils (à son code) ;
    /// A, qui sait de quel appareil vient B, range la session sous son
    /// adresse ; la réponse de A, dans cette session, se lit chez B, qui la
    /// cherche sous l'adresse de l'appareil de A : la session y passe.
    func testASessionOpenedBeforeKnowingDevicesMovesToTheDevice() throws {
        let a = Side(), b = Side()
        let aDev = "\(a.id.code)/00000000000000a1"
        let bDev = "\(b.id.code)/00000000000000b1"
        let bundleA = try XCTUnwrap(a.engine.published(a.id))
        let first = try XCTUnwrap(b.engine.seal(b.id, peerCode: a.id.code, peerDh: a.id.dh,
                                                bundle: V3.verifyBundle(code: a.id.code, sign: a.id.sign, dh: a.id.dh, bundle: bundleA.bundle, sig: bundleA.sig),
                                                plain: #"{"k":1}"#))
        XCTAssertEqual(a.engine.openFrom(a.id, addr: bDev, peerCode: b.id.code, peerDh: b.id.dh, body: first.body, now: 2_000), .v3(#"{"k":1}"#))
        let reply = try XCTUnwrap(a.engine.sealTo(a.id, addr: bDev, peerCode: b.id.code, peerDh: b.id.dh, bundle: nil, plain: #"{"k":2}"#))
        XCTAssertTrue(reply.v3)
        _ = b.engine.takeDirty()
        XCTAssertEqual(b.engine.openFrom(b.id, addr: aDev, peerCode: a.id.code, peerDh: a.id.dh, body: reply.body, now: 2_000), .v3(#"{"k":2}"#))
        XCTAssertTrue(b.engine.hasSession(aDev))
        XCTAssertFalse(b.engine.hasSession(a.id.code))
        // Les deux adresses sont à réécrire (l'ancienne, vidée).
        let dirty = b.engine.takeDirty().peers
        XCTAssertEqual(Set(dirty.map(\.code)), [a.id.code, aDev])
        XCTAssertEqual(dirty.first { $0.code == a.id.code }?.json, "")
        let again = try XCTUnwrap(b.engine.sealTo(b.id, addr: aDev, peerCode: a.id.code, peerDh: a.id.dh, bundle: nil, plain: #"{"k":3}"#))
        XCTAssertTrue(again.v3)
        XCTAssertEqual(a.engine.openFrom(a.id, addr: bDev, peerCode: b.id.code, peerDh: b.id.dh, body: again.body, now: 2_000), .v3(#"{"k":3}"#))
    }

    func tempStore() -> Store {
        Store(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-v4-\(randomId(4))"))
    }

    /// Cet appareil a son identifiant, gardé ; lié depuis un autre appareil,
    /// il prend son vrai nom dans la liste.
    @MainActor
    func testEnsureDeviceAndItsName() throws {
        let store = tempStore()
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        XCTAssertTrue(TalkaV4.isDev(e.dev))
        XCTAssertEqual(e.devices.list.map(\.dev), [e.dev])
        XCTAssertEqual(e.devices.v, 1)
        XCTAssertTrue(e.siblings.isEmpty)
        let again = Engine(store: store, server: URL(string: "http://localhost:1")!)
        XCTAssertEqual(again.dev, e.dev)
        XCTAssertEqual(again.devices.v, 1)
        // Le profil annonce nos appareils et nos boîtes.
        guard case let .profile(_, _, _, _, devs, dv, bx) = again.profileBody(for: Identity.create().code) else { return XCTFail() }
        XCTAssertEqual(devs, [DevInfo(d: e.dev)])
        XCTAssertEqual(dv, 1)
        XCTAssertTrue(bx)

        var list = again.devices
        list.list[0].name = Engine.newDeviceName
        list.list.append(AccountDevice(dev: "00000000000000f1", name: "Chrome sur Mac", at: 1))
        store.write("kv", "appareils", list)
        Engine.deviceName = "iPad"
        defer { Engine.deviceName = "iPhone" }
        let renamed = Engine(store: store, server: URL(string: "http://localhost:1")!)
        XCTAssertEqual(renamed.devices.list.first { $0.dev == e.dev }?.name, "iPad")
        XCTAssertEqual(renamed.devices.v, 2)
        XCTAssertEqual(renamed.siblings, ["00000000000000f1"])
        XCTAssertEqual(renamed.targets(renamed.me.code), ["00000000000000f1"])
    }

    /// Ce que la liaison emporte (et pas : ce qui est propre à l'appareil),
    /// puis ce que le nouvel appareil range.
    @MainActor
    func testLinkDumpAndImport() throws {
        let store = tempStore()
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        let bob = Identity.create()
        var c = Contact(code: bob.code)
        c.name = "Bob"; c.saved = true; c.sign = bob.sign; c.dh = bob.dh
        e.saveContact(c)
        e.ensureDirectChat(bob.code)
        let small = e.send(bob.code, Content(kind: "text", text: "Salut"))
        var bigContent = Content(kind: "image", text: "")
        bigContent.data = "data:image/jpeg;base64," + String(repeating: "A", count: 600 * 1024)
        let big = e.send(bob.code, bigContent)
        store.write("kv", "pin", TalkaCrypto.hashPin("123456"))
        store.writeRaw("kv", V3.sessionPrefix + bob.code, "{}")
        let dump = e.linkDump()
        let keys = (dump["kv"] as? [[Any]] ?? []).compactMap { $0.first as? String }
        XCTAssertTrue(keys.contains("identity"))
        XCTAssertTrue(keys.contains("profile"))
        for own in ["dev", "pin", "appareils", V3.sessionPrefix + bob.code, V3.deviceKey] {
            XCTAssertFalse(keys.contains(own), own)
        }
        XCTAssertEqual((dump["outbox"] as? [[Any]])?.count, 0)
        let rows = dump["messages"] as? [[Any]] ?? []
        XCTAssertEqual(rows.count, 2)
        let kept = rows.compactMap { ($0[1] as? String).flatMap { JSON.decode(Message.self, $0) } }
        XCTAssertEqual(kept.first { $0.id == small?.id }?.text, "Salut")
        XCTAssertEqual(kept.first { $0.id == big?.id }?.data, "", "une pièce jointe de plus de 512 Kio reste là")

        // Le nouvel appareil range le compte, avec son propre identifiant.
        let newDev = "00000000000000c3"
        var list = e.devices
        list.list.append(AccountDevice(dev: newDev, name: Engine.newDeviceName, at: 2))
        list.v += 1
        func payload(_ code: String, _ devices: Devices) throws -> Data {
            try JSONSerialization.data(withJSONObject: [
                "talka": "lien", "v": 1, "code": code,
                "devices": try JSONSerialization.jsonObject(with: Data(JSON.encode(devices).utf8)),
                "data": dump,
            ] as [String: Any])
        }
        let other = tempStore()
        // Pas pour cet appareil, ou pas l'identité annoncée : refusé.
        XCTAssertFalse(Engine.importLink(try payload(e.me.code, e.devices), dev: newDev, into: other))
        XCTAssertFalse(Engine.importLink(try payload(bob.code, list), dev: newDev, into: other))
        XCTAssertFalse(Engine.hasAccount(other))
        XCTAssertTrue(Engine.importLink(try payload(e.me.code, list), dev: newDev, into: other))
        let linked = Engine(store: other, server: URL(string: "http://localhost:1")!)
        XCTAssertEqual(linked.me, e.me)
        XCTAssertEqual(linked.dev, newDev)
        XCTAssertEqual(linked.devices.list.map(\.dev), [e.dev, newDev])
        XCTAssertEqual(linked.devices.list.last?.name, Engine.deviceName, "il prend son vrai nom")
        XCTAssertEqual(linked.siblings, [e.dev])
        XCTAssertEqual(linked.contact(bob.code)?.name, "Bob")
        XCTAssertEqual(linked.messages(bob.code).count, 2)
        XCTAssertNil(other.read("kv", "pin", as: PinHash.self))
        XCTAssertTrue(other.all("outbox", as: OutItem.self).isEmpty)
    }

    /// L'état d'une discussion qui suit sur nos autres appareils : le JSON du
    /// navigateur (`mute_until` à 0 : pour toujours).
    func testChatStateHasTheBrowserShape() throws {
        var c = Chat(id: "WGSEAJNSZJ")
        c.pinned = true
        c.muted = true
        let state = ChatState(c)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(JSON.encode(state).utf8)) as? [String: Any])
        XCTAssertEqual(json["mute_until"] as? Double, 0)
        XCTAssertEqual(json["pinned"] as? Bool, true)
        var other = Chat(id: "WGSEAJNSZJ")
        other.muteUntil = 5
        try XCTUnwrap(JSON.decode(ChatState.self, #"{"muted":true,"mute_until":0,"pin_msg":"m1"}"#)).apply(&other)
        XCTAssertTrue(other.muted)
        XCTAssertNil(other.muteUntil)
        XCTAssertEqual(other.pinMsg, "m1")
    }
}
