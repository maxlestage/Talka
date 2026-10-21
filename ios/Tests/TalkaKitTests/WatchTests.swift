// L'Apple Watch : le résumé que l'iPhone lui envoie, et ce qu'elle renvoie.
// Sans réseau : le moteur n'est pas démarré, les messages attendent dans
// la boîte d'envoi. (Des tests `async` : sous Linux, XCTest ne sait pas
// appeler une méthode synchrone isolée sur le MainActor.)

import Foundation
import XCTest
@testable import TalkaKit

@MainActor
final class WatchTests: XCTestCase {
    func phone() -> Engine {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-watch-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        return Engine(store: store, server: URL(string: "http://localhost:1")!)
    }

    func testSnapshotOrderMessagesAndRoundTrip() async throws {
        let e = phone()
        let old = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(old.id, Content(text: "Bonjour"))
        let recent = e.ensureDirectChat("K7Q2MX9PRT")
        let m = try XCTUnwrap(e.send(recent.id, Content(text: "Salut")))
        e.react(recent.id, m.id, "❤️")
        _ = e.ensureDirectChat("AAAAAAAAAA") // jamais écrit : absent

        let s = e.watchSnapshot()
        XCTAssertEqual(s.name, "Léa")
        XCTAssertEqual(s.chats.map(\.id), ["K7Q2MX9PRT", "WGSEAJNSZJ"])
        let first = s.chats[1]
        // Un contact sans nom : son code, comme sur l'iPhone.
        XCTAssertEqual(first.title, "WGSEA-JNSZJ")
        XCTAssertEqual(first.initials, "WJ")
        XCTAssertEqual(first.last, "Bonjour")
        let mine = try XCTUnwrap(s.chats[0].messages.last)
        XCTAssertTrue(mine.mine)
        XCTAssertEqual(mine.text, "Salut")
        XCTAssertEqual(mine.tick, "pending")
        XCTAssertEqual(mine.reactions, "❤️")
        XCTAssertEqual(mine.myReaction, "❤️")

        // Ce qui traverse WatchConnectivity revient à l'identique.
        let data = try XCTUnwrap(WatchLink.encode(s))
        XCTAssertEqual(WatchLink.decode(WatchSnapshot.self, data), s)
        XCTAssertNil(WatchLink.decode(WatchSnapshot.self, "pas des données"))
    }

    func testActionsFromTheWatch() async throws {
        let e = phone()
        let c = e.ensureDirectChat("WGSEAJNSZJ")
        XCTAssertTrue(e.perform(WatchAction(.send, chat: c.id, text: "  J'arrive !  ")))
        let sent = try XCTUnwrap(e.messages(c.id).last)
        XCTAssertEqual(sent.text, "J'arrive !")
        XCTAssertFalse(e.perform(WatchAction(.send, chat: c.id, text: "   ")))
        XCTAssertFalse(e.perform(WatchAction(.send, chat: "INCONNU", text: "x")))

        XCTAssertTrue(e.perform(WatchAction(.react, chat: c.id, id: sent.id, text: "👍")))
        XCTAssertEqual(e.messages(c.id).last?.reactions[e.me.code], "👍")
        XCTAssertTrue(e.perform(WatchAction(.react, chat: c.id, id: sent.id, text: "👍")))
        XCTAssertNil(e.messages(c.id).last?.reactions[e.me.code])

        e.updateChat(c.id) { $0.unread = 3 }
        XCTAssertTrue(e.perform(WatchAction(.read, chat: c.id)))
        XCTAssertEqual(e.chat(c.id)?.unread, 0)

        e.setBlocked(c.id, true)
        XCTAssertTrue(e.watchSnapshot().chats[0].readOnly)
        XCTAssertFalse(e.perform(WatchAction(.send, chat: c.id, text: "bloqué")))

        // Une capsule : lisible par son auteur sur la montre.
        let capsule = try XCTUnwrap(e.send(c.id, Content(text: "Plus tard", openAt: nowMs() + 3_600_000)))
        XCTAssertEqual(e.watchMessage(capsule, group: false).text, "Plus tard")
        var theirs = capsule
        theirs.from = "WGSEAJNSZJ"
        XCTAssertEqual(e.watchMessage(theirs, group: false).text, "⏳ Capsule temporelle")
        theirs.openAt = nowMs() - 1
        XCTAssertEqual(e.watchMessage(theirs, group: false).text, "Plus tard")

        let data = try XCTUnwrap(WatchLink.encode(WatchAction(.refresh)))
        XCTAssertEqual(WatchLink.decode(WatchAction.self, data)?.kind, .refresh)
    }

    func testMarkAllRead() async {
        let e = phone()
        for (code, n) in [("WGSEAJNSZJ", 2), ("K7Q2MX9PRT", 0), ("AAAAAAAAAA", 5)] {
            _ = e.ensureDirectChat(code)
            e.updateChat(code) { $0.unread = n }
        }
        e.updateChat("K7Q2MX9PRT") { $0.markedUnread = true; $0.archived = true }
        XCTAssertEqual(e.markAllRead(), 3)
        XCTAssertTrue(e.chats.values.allSatisfy { $0.unread == 0 && !$0.markedUnread })
        XCTAssertEqual(e.markAllRead(), 0)
    }

    /// Épingle, favoris, réponse : jamais le texte d'une capsule scellée,
    /// mais celui d'une capsule ouverte, ou écrite par soi.
    func testReadableHidesOnlySealedCapsules() async throws {
        let e = phone()
        let c = e.ensureDirectChat("WGSEAJNSZJ")
        let mine = try XCTUnwrap(e.send(c.id, Content(text: "Plus tard", openAt: nowMs() + 3_600_000)))
        XCTAssertEqual(e.readable(mine), "Plus tard")
        var theirs = mine
        theirs.from = "WGSEAJNSZJ"
        XCTAssertEqual(e.readable(theirs), "⏳ Capsule temporelle")
        XCTAssertEqual(e.readable(theirs, now: mine.openAt! + 1), "Plus tard")
        theirs.deleted = true
        XCTAssertEqual(e.readable(theirs, now: mine.openAt! + 1), "🚫 Message supprimé")
        let photo = Message(chat: c.id, id: "p", from: "WGSEAJNSZJ", at: 1, content: Content(kind: "image", text: "Vue"))
        XCTAssertEqual(e.readable(photo), "📷 Vue")
    }

    func testInitialsAndColors() async {
        XCTAssertEqual(watchInitials("Léa Martin"), "LM")
        XCTAssertEqual(watchInitials("~Léa"), "L")
        XCTAssertEqual(watchInitials("WGS-EAJ-NSZJ"), "WE")
        XCTAssertEqual(watchInitials(""), "")
        XCTAssertTrue((0..<8).contains(watchColor("WGSEAJNSZJ")))
        XCTAssertEqual(watchColor("WGSEAJNSZJ"), watchColor("WGSEAJNSZJ"))
    }
}

/// Les widgets : ce qu'ils montrent, et l'adresse qu'ouvre un toucher.
final class WidgetDataTests: XCTestCase {
    func chat(_ id: String, unread: Int, at: Double, muted: Bool = false) -> WatchChat {
        var c = WatchChat(id: id)
        c.title = id
        c.unread = unread
        c.at = at
        c.muted = muted
        c.last = "dernier de \(id)"
        return c
    }

    func testUnreadFirstThenRecentAndMutedIgnored() {
        let s = WatchSnapshot(chats: [
            chat("A", unread: 0, at: 50), chat("B", unread: 2, at: 10),
            chat("C", unread: 4, at: 5, muted: true), chat("D", unread: 1, at: 20), chat("E", unread: 0, at: 40),
        ])
        let w = WidgetData(s, hidden: false, limit: 3)
        XCTAssertEqual(w.unread, 3)
        XCTAssertEqual(w.chats.map(\.id), ["D", "B", "A"])
        XCTAssertEqual(w.summary, "3 messages non lus")
        XCTAssertEqual(w.at, 50)
        XCTAssertEqual(WidgetData(unread: 1).summary, "1 message non lu")
        XCTAssertEqual(WidgetData().summary, "Aucun message non lu")
    }

    func testHiddenKeepsOnlyTheCount() {
        let w = WidgetData(WatchSnapshot(chats: [chat("A", unread: 2, at: 1)]), hidden: true)
        XCTAssertEqual(w.unread, 2)
        XCTAssertTrue(w.chats.isEmpty)
        XCTAssertTrue(w.hidden)
    }

    func testLinksRoundTrip() {
        for id in ["WGSEAJNSZJ", "g:ab12cd"] {
            XCTAssertEqual(WidgetData.chat(in: WidgetData.link(id)), id)
        }
        XCTAssertEqual(WidgetData.link().absoluteString, "talka://chats")
        XCTAssertNil(WidgetData.chat(in: WidgetData.link()))
        XCTAssertNil(WidgetData.chat(in: URL(string: "talka://add/WGSEAJNSZJ")!))
    }

    func testOlderSnapshotsWithoutHiddenStillDecode() throws {
        let old = #"{"name":"Léa","online":true,"chats":[]}"#.data(using: .utf8)!
        let s = try XCTUnwrap(WatchLink.decode(WatchSnapshot.self, old))
        XCTAssertNil(s.hidden)
    }
}

/// La capsule temporelle : scellée pour le destinataire, jamais pour
/// l'auteur ; le champ voyage comme le navigateur l'écrit (`open_at`).
@MainActor
final class CapsuleTests: XCTestCase {
    func testSealedOnlyForTheRecipientUntilItOpens() async throws {
        let wire = #"{"kind":"text","text":"Joyeux anniversaire","open_at":5000}"#
        let content = try XCTUnwrap(JSON.decode(Content.self, wire))
        XCTAssertEqual(content.openAt, 5000)
        let m = Message(chat: "A", id: "1", from: "LEA", at: 1000, content: content)
        XCTAssertTrue(m.isCapsule)
        XCTAssertTrue(m.sealed(for: "CLEMENT", now: 4999))
        XCTAssertFalse(m.sealed(for: "CLEMENT", now: 5000))
        XCTAssertFalse(m.sealed(for: "LEA", now: 2000))
        XCTAssertEqual(Engine.preview(m), "⏳ Capsule temporelle")
        // Stocké et relu à l'identique.
        XCTAssertEqual(JSON.decode(Message.self, JSON.encode(m))?.openAt, 5000)
    }

    func testOrdinaryMessagesDoNotCarryTheField() async {
        let plain = Content(text: "Salut")
        XCTAssertFalse(JSON.encode(plain).contains("open_at"))
        let m = Message(chat: "A", id: "1", from: "LEA", at: 1, content: plain)
        XCTAssertFalse(m.isCapsule)
        XCTAssertFalse(JSON.encode(m).contains("open_at"))
    }
}

/// La sourdine pour un temps : levée d'elle-même à l'échéance, et un champ
/// facultatif (`mute_until`) comme dans le navigateur.
@MainActor
final class MuteTests: XCTestCase {
    func testTimedMuteLiftsByItselfAndForeverStays() async throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-mute-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        _ = e.ensureDirectChat("WGSEAJNSZJ")
        _ = e.ensureDirectChat("K7Q2MX9PRT")
        e.mute("WGSEAJNSZJ", seconds: 8 * 3600)
        e.mute("K7Q2MX9PRT", seconds: nil)
        XCTAssertEqual(e.chat("WGSEAJNSZJ")?.muted, true)
        XCTAssertNotNil(e.chat("WGSEAJNSZJ")?.muteUntil)
        XCTAssertNil(e.chat("K7Q2MX9PRT")?.muteUntil)
        // L'échéance passée : la sourdine se lève, « toujours » reste.
        e.updateChat("WGSEAJNSZJ") { $0.muteUntil = nowMs() - 1 }
        e.sweep()
        XCTAssertEqual(e.chat("WGSEAJNSZJ")?.muted, false)
        XCTAssertNil(e.chat("WGSEAJNSZJ")?.muteUntil)
        XCTAssertEqual(e.chat("K7Q2MX9PRT")?.muted, true)
        e.mute("K7Q2MX9PRT", seconds: 0)
        XCTAssertEqual(e.chat("K7Q2MX9PRT")?.muted, false)
        // Le format : absent sans sourdine pour un temps, relu tel quel sinon.
        XCTAssertFalse(JSON.encode(try XCTUnwrap(e.chat("K7Q2MX9PRT"))).contains("mute_until"))
        let old = try XCTUnwrap(JSON.decode(Chat.self, #"{"id":"A","muted":true}"#))
        XCTAssertTrue(old.muted)
        XCTAssertNil(old.muteUntil)
        let timed = try XCTUnwrap(JSON.decode(Chat.self, #"{"id":"A","muted":true,"mute_until":1800000000000}"#))
        XCTAssertEqual(timed.muteUntil, 1_800_000_000_000)
    }
}
