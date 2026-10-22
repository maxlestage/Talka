// La mémoire de l'iPhone (SQLite) : lecture, écriture, ordre des messages,
// réouverture, sauvegarde au format du navigateur, reprise des fichiers JSON
// des premières versions.

import Foundation
import XCTest
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
@testable import TalkaKit

final class StoreTests: XCTestCase {
    func tempDir() -> URL {
        URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-store-\(randomId(6))")
    }

    func message(_ chat: String, _ id: String, at: Double, _ text: String) -> Message {
        Message(chat: chat, id: id, from: "WGSEAJNSZJ", at: at, content: Content(text: text))
    }

    func testReadWriteAndMessagesInOrder() {
        let s = Store(directory: tempDir())
        s.write("kv", "profile", Profile(name: "Léa"))
        XCTAssertEqual(s.read("kv", "profile", as: Profile.self)?.name, "Léa")
        for (id, at) in [("m1", 30.0), ("m2", 10.0), ("m3", 20.0)] {
            s.writeMessage(message("g:x", id, at: at, id))
        }
        s.writeMessage(message("AUTRE", "m9", at: 5, "m9"))
        XCTAssertEqual(s.messagesOf("g:x").map(\.id), ["m2", "m3", "m1"])
        XCTAssertEqual(s.messagesOf("g:x", limit: 2).map(\.id), ["m3", "m1"])
        s.removeMessage("g:x", "m3")
        XCTAssertEqual(s.messagesOf("g:x").map(\.id), ["m2", "m1"])
        s.deleteMessages(of: "g:x")
        XCTAssertTrue(s.messagesOf("g:x").isEmpty)
        XCTAssertEqual(s.allMessages().map(\.id), ["m9"])
        s.remove("kv", "profile")
        XCTAssertNil(s.read("kv", "profile", as: Profile.self))
    }

    /// Les octets de la base sur le disque (avec son journal).
    func disk(_ dir: URL) -> Data {
        ["", "-wal"].reduce(Data()) { $0 + ((try? Data(contentsOf: dir.appendingPathComponent("talka.sqlite" + $1))) ?? Data()) }
    }

    /// Chiffrée au repos : rien de lisible sur le disque, tout en clair pour
    /// Talka ; les lignes d'avant sont reprises ; sans la clé, rien ne se lit
    /// et rien ne s'écrase.
    func testEncryptedAtRest() {
        let dir = tempDir()
        do {
            // Une base d'avant, en clair.
            let old = Store(directory: dir)
            old.write("contacts", "WGSEAJNSZJ", Contact(code: "WGSEAJNSZJ"))
            old.writeMessage(message("WGSEAJNSZJ", "m1", at: 1, "Le code du portail est 4521"))
        }
        XCTAssertNotNil(disk(dir).range(of: Data("portail".utf8)))
        let key = SymmetricKey(size: .bits256)
        do {
            let s = Store(directory: dir, key: key)
            XCTAssertEqual(s.messagesOf("WGSEAJNSZJ").map(\.text), ["Le code du portail est 4521"])
            s.writeMessage(message("WGSEAJNSZJ", "m2", at: 2, "Rendez-vous à la gare"))
            s.write("kv", "profile", Profile(name: "Léa Secrète"))
            s.save()
        }
        let bytes = disk(dir)
        for word in ["portail", "gare", "Secrète", "WGSEAJNSZJ\"}"] {
            XCTAssertNil(bytes.range(of: Data(word.utf8)), "« \(word) » en clair sur le disque")
        }
        let again = Store(directory: dir, key: key)
        XCTAssertEqual(again.messagesOf("WGSEAJNSZJ").map(\.text), ["Le code du portail est 4521", "Rendez-vous à la gare"])
        XCTAssertEqual(again.read("kv", "profile", as: Profile.self)?.name, "Léa Secrète")
        XCTAssertEqual(again.allMessages().count, 2)
        XCTAssertEqual((again.dump()["messages"] as? [[Any]])?.count, 2, "la sauvegarde garde son format")

        // Une autre clé : rien de lisible.
        XCTAssertTrue(Store(directory: dir, key: SymmetricKey(size: .bits256)).messagesOf("WGSEAJNSZJ").isEmpty)
        // Pas de clé (trousseau muet) : lecture seule, rien n'est écrasé.
        let locked = Store(directory: dir)
        XCTAssertNil(locked.read("kv", "profile", as: Profile.self))
        locked.write("kv", "profile", Profile(name: "Autre"))
        XCTAssertEqual(Store(directory: dir, key: key).read("kv", "profile", as: Profile.self)?.name, "Léa Secrète")
    }

    func testEverythingSurvivesAReopen() {
        let dir = tempDir()
        do {
            let s = Store(directory: dir)
            s.write("contacts", "WGSEAJNSZJ", Contact(code: "WGSEAJNSZJ"))
            s.writeMessage(message("WGSEAJNSZJ", "m1", at: 1, "Bonjour"))
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.appendingPathComponent("talka.sqlite").path))
        let again = Store(directory: dir)
        XCTAssertEqual(again.all("contacts", as: Contact.self).map(\.code), ["WGSEAJNSZJ"])
        XCTAssertEqual(again.messagesOf("WGSEAJNSZJ").map(\.text), ["Bonjour"])
    }

    func testBackupKeepsTheBrowserFormat() {
        let s = Store(directory: tempDir())
        s.write("calls", "old", CallEntry(id: "old", peer: "X", video: false, incoming: true, at: 1, dur: 0, missed: true))
        let backup: [String: Any] = [
            "kv": [["identity", "{\"code\":\"X\"}"]],
            "chats": [["g:1", "{\"id\":\"g:1\"}"]],
            "messages": [["g:1|m1", JSON.encode(message("g:1", "m1", at: 12.5, "salut")), "g:1", 12.5]],
        ]
        s.restore(backup)
        let dump = s.dump()
        XCTAssertEqual((dump["calls"] as? [[Any]])?.count, 0)
        XCTAssertEqual((dump["kv"] as? [[Any]])?.first?.first as? String, "identity")
        let m = (dump["messages"] as? [[Any]])?.first
        XCTAssertEqual(m?[2] as? String, "g:1")
        XCTAssertEqual(m?[3] as? Double, 12.5)
        XCTAssertEqual(s.messagesOf("g:1").map(\.text), ["salut"])
        s.wipe()
        XCTAssertTrue(s.allMessages().isEmpty)
        XCTAssertNil(s.read("kv", "identity", as: Identity.self))
    }

    /// Les fichiers JSON des premières versions sont repris, puis effacés.
    func testMigratesTheOldJSONFiles() throws {
        let dir = tempDir()
        let fm = FileManager.default
        try fm.createDirectory(at: dir.appendingPathComponent("messages"), withIntermediateDirectories: true)
        let contacts = ["WGSEAJNSZJ": JSON.encode(Contact(code: "WGSEAJNSZJ"))]
        try JSONEncoder().encode(contacts).write(to: dir.appendingPathComponent("contacts.json"))
        let m = message("WGSEAJNSZJ", "m1", at: 3, "d'avant")
        try JSONEncoder().encode(["WGSEAJNSZJ|m1": JSON.encode(m)])
            .write(to: dir.appendingPathComponent("messages").appendingPathComponent("WGSEAJNSZJ.json"))

        let s = Store(directory: dir)
        XCTAssertEqual(s.all("contacts", as: Contact.self).map(\.code), ["WGSEAJNSZJ"])
        XCTAssertEqual(s.messagesOf("WGSEAJNSZJ").map(\.text), ["d'avant"])
        XCTAssertFalse(fm.fileExists(atPath: dir.appendingPathComponent("contacts.json").path))
        XCTAssertFalse(fm.fileExists(atPath: dir.appendingPathComponent("messages").path))
        // Et une seule fois : rouvrir ne duplique rien.
        XCTAssertEqual(Store(directory: dir).allMessages().count, 1)
    }
}

/// Un membre (ou un ancien membre) ne prend pas un groupe en main ; comme
/// `only_admins_change_a_group` du navigateur.
final class GroupRulesTests: XCTestCase {
    func group(_ members: [String], _ admins: [String]) -> Chat {
        var c = Chat(id: "g:g1")
        c.group = true; c.name = "Les amis"; c.members = members; c.admins = admins; c.v = 5
        return c
    }

    func info(_ c: Chat, _ v: Int) -> GroupInfo {
        GroupInfo(id: "g1", name: c.name, members: c.members, admins: c.admins, avatar: c.avatar, v: v)
    }

    func testOnlyAdminsChangeAGroup() {
        let c = group(["A", "B", "C"], ["A"])
        var byAdmin = info(c, 6)
        byAdmin.members.append("D")
        XCTAssertTrue(c.accepts("A", byAdmin))
        var coup = info(c, 6)
        coup.admins = ["B"]
        XCTAssertFalse(c.accepts("B", coup))
        XCTAssertFalse(c.accepts("Z", byAdmin))
        XCTAssertFalse(c.accepts("A", info(c, .max)))
        XCTAssertFalse(c.accepts("A", info(c, 5)))
        var leave = info(c, 6)
        leave.members = ["A", "C"]
        XCTAssertTrue(c.accepts("B", leave))
        leave.name = "À moi"
        XCTAssertFalse(c.accepts("B", leave))
        // … sans toucher à la description (absente : inchangée).
        leave.name = c.name
        leave.desc = "Mon groupe"
        XCTAssertFalse(c.accepts("B", leave))
        leave.desc = nil
        XCTAssertTrue(c.accepts("B", leave))
        // … ni à qui peut écrire.
        leave.adminsOnly = true
        XCTAssertFalse(c.accepts("B", leave))
        leave.adminsOnly = nil
        XCTAssertTrue(c.accepts("B", leave))
        var last = info(c, 6)
        last.members = ["B", "C"]; last.admins = ["B"]
        XCTAssertTrue(c.accepts("A", last))
    }

    /// « Seuls les admins peuvent écrire » : comme `only_admins_write_when_asked`
    /// du navigateur ; reçu d'un admin, gardé ; absent, inchangé.
    @MainActor
    func testOnlyAdminsWrite() async throws {
        var g = group(["A", "B"], ["A"])
        XCTAssertTrue(g.canWrite("B"))
        g.adminsOnly = true
        XCTAssertTrue(g.canWrite("A"))
        XCTAssertFalse(g.canWrite("B"))
        g.left = true
        XCTAssertFalse(g.canWrite("A"))
        XCTAssertTrue(Chat(id: "X").canWrite("B"))
        let e = Engine(store: Store(directory: FileManager.default.temporaryDirectory.appendingPathComponent("ecrire-\(randomId(4))")), server: URL(string: "http://127.0.0.1:9")!)
        let admin = "ADMIN00000"
        var grp = GroupInfo(id: "ga", name: "Annonces", members: [admin, e.me.code], admins: [admin], avatar: "", v: 1, adminsOnly: true)
        XCTAssertEqual(e.groupChat(admin, grp, at: 1).chat?.canWrite(e.me.code), false)
        grp.v = 2; grp.adminsOnly = nil
        _ = e.groupChat(admin, grp, at: 2)
        XCTAssertEqual(e.chat("g:ga")?.adminsOnly, true, "absent : inchangé")
        grp.v = 3; grp.adminsOnly = false
        _ = e.groupChat(admin, grp, at: 3)
        XCTAssertEqual(e.chat("g:ga")?.canWrite(e.me.code), true)
        XCTAssertTrue(JSON.encode(e.groupInfo(try XCTUnwrap(e.chat("g:ga")))).contains("\"admins_only\":false"))
    }

    /// « Garder les discussions archivées » : un message reçu la laisse dans
    /// les archives ; sans le réglage, elle en sort. Comme le navigateur
    /// (`archived_chats_come_back_unless_kept`).
    @MainActor
    func testKeepArchived() async throws {
        let e = Engine(store: Store(directory: FileManager.default.temporaryDirectory.appendingPathComponent("archive-\(randomId(4))")), server: URL(string: "http://127.0.0.1:9")!)
        var c = Chat(id: "WGSEAJNSZJ")
        c.archived = true
        e.saveChat(c)
        e.saveSettings { $0.keepArchived = true }
        e.storeMessage(Message(chat: c.id, id: "m1", from: c.id, at: 1, content: Content(text: "Coucou")))
        XCTAssertEqual(e.chat(c.id)?.archived, true, "gardée archivée")
        // Le réglage s'écrit comme dans le navigateur.
        XCTAssertTrue(JSON.encode(e.settings).contains("\"keep_archived\":true"))
        e.saveSettings { $0.keepArchived = false }
        e.storeMessage(Message(chat: c.id, id: "m2", from: c.id, at: 2, content: Content(text: "Encore")))
        XCTAssertEqual(e.chat(c.id)?.archived, false, "sans le réglage, elle ressort")
        XCTAssertEqual(JSON.decode(Settings.self, #"{"theme":"dark"}"#)?.keepArchived, false)
    }

    /// La description d'un groupe : envoyée par les admins, gardée telle que
    /// le navigateur la garde ; absente (un ancien client), elle ne change pas.
    @MainActor
    func testGroupDescription() async throws {
        let e = Engine(store: Store(directory: FileManager.default.temporaryDirectory.appendingPathComponent("desc-\(randomId(4))")), server: URL(string: "http://127.0.0.1:9")!)
        let admin = "ADMIN00000"
        var grp = GroupInfo(id: "gd", name: "Sorties", members: [admin, e.me.code], admins: [admin], avatar: "", v: 1,
                            desc: "  Le samedi matin  ")
        XCTAssertEqual(e.groupChat(admin, grp, at: 1).chat?.desc, "Le samedi matin")
        grp.v = 2; grp.name = "Sorties du samedi"; grp.desc = nil
        XCTAssertTrue(e.groupChat(admin, grp, at: 2).applied)
        XCTAssertEqual(e.chat("g:gd")?.desc, "Le samedi matin", "absente : inchangée")
        grp.v = 3; grp.desc = String(repeating: "a", count: 600)
        _ = e.groupChat(admin, grp, at: 3)
        XCTAssertEqual(e.chat("g:gd")?.desc.count, maxGroupDesc)
        grp.v = 4; grp.desc = ""
        _ = e.groupChat(admin, grp, at: 4)
        XCTAssertEqual(e.chat("g:gd")?.desc, "")
        // Ce qui part : la description, même vide (pour qu'on puisse l'effacer).
        let sent = try XCTUnwrap(JSON.decode([String: JSONValue].self, JSON.encode(e.groupInfo(try XCTUnwrap(e.chat("g:gd"))))))
        XCTAssertEqual(sent["desc"], .string(""))
        XCTAssertNil(JSON.decode([String: JSONValue].self, JSON.encode(GroupInfo(id: "g", name: "", members: [], admins: [], avatar: "", v: 1)))?["desc"])
    }
}
