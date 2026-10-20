// La mémoire de l'iPhone (SQLite) : lecture, écriture, ordre des messages,
// réouverture, sauvegarde au format du navigateur, reprise des fichiers JSON
// des premières versions.

import Foundation
import XCTest
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
