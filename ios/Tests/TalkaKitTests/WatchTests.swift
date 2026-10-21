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

        e.setBlocked(c.id, false)
        // Un vocal enregistré sur la montre part comme celui de l'iPhone.
        let meta = WatchLink.voiceMetadata(chat: c.id, seconds: 7.4)
        let voice = try XCTUnwrap(WatchLink.voice(meta))
        XCTAssertTrue(e.sendWatchVoice(chat: voice.chat, audio: Data([1, 2, 3]), seconds: voice.seconds))
        let v = try XCTUnwrap(e.messages(c.id).last)
        XCTAssertEqual(v.kind, "voice")
        XCTAssertEqual(v.mime, "audio/mp4")
        XCTAssertEqual(v.dur, 7)
        XCTAssertEqual(v.data, "data:audio/mp4;base64,AQID")
        XCTAssertEqual(e.watchMessage(v, group: false).text, "🎙️ Message vocal (0:07)")
        XCTAssertFalse(e.sendWatchVoice(chat: c.id, audio: Data(), seconds: 3))
        XCTAssertFalse(e.sendWatchVoice(chat: c.id, audio: Data(count: WatchLink.maxVoiceBytes + 1), seconds: 3))
        XCTAssertFalse(e.sendWatchVoice(chat: "INCONNU", audio: Data([1]), seconds: 3))
        XCTAssertNil(WatchLink.voice(["kind": "autre", "chat": c.id, "dur": 3.0]))
        XCTAssertNil(WatchLink.voice(nil))
        XCTAssertEqual(WatchLink.voice(WatchLink.voiceMetadata(chat: c.id, seconds: 999))?.seconds, 120)

        let data = try XCTUnwrap(WatchLink.encode(WatchAction(.refresh)))
        XCTAssertEqual(WatchLink.decode(WatchAction.self, data)?.kind, .refresh)
    }

    func testSnapshotAlwaysFitsWatchConnectivity() async throws {
        let e = phone()
        for i in 0..<20 {
            let c = e.ensureDirectChat(String(format: "K7Q2MX%04d", i))
            for _ in 0..<15 { e.send(c.id, Content(text: String(repeating: "Bonjour ", count: 75))) }
        }
        XCTAssertGreaterThan(WatchLink.encode(e.watchSnapshot())?.count ?? 0, WatchLink.maxSnapshotBytes)
        let s = e.fittedWatchSnapshot { $0.hidden = true }
        XCTAssertLessThanOrEqual(WatchLink.encode(s)?.count ?? .max, WatchLink.maxSnapshotBytes)
        XCTAssertEqual(s.hidden, true)
        XCTAssertFalse(s.chats.isEmpty)
        XCTAssertFalse(s.chats[0].messages.isEmpty)
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

/// La recherche dans toutes les discussions, comme le navigateur.
@MainActor
final class SearchTests: XCTestCase {
    func testSearchesAllChatsIgnoringAccentsAndHidingSealed() async throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-search-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Alice", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        let bob = e.ensureDirectChat("WGSEAJNSZJ")
        let chloe = e.ensureDirectChat("K7Q2MX9PRT")
        e.send(bob.id, Content(text: "On se voit samedi au marché ?"))
        e.send(chloe.id, Content(text: "Le cinéma de Samédi est complet"))
        e.send(chloe.id, Content(text: "Rien à voir"))
        var sealed = try XCTUnwrap(e.send(chloe.id, Content(text: "Surprise samedi", openAt: nowMs() + 3_600_000)))
        sealed.from = "K7Q2MX9PRT"
        store.writeMessage(sealed)
        let found = e.searchMessages("samedi")
        XCTAssertEqual(Set(found.map(\.text)), ["On se voit samedi au marché ?", "Le cinéma de Samédi est complet"])
        XCTAssertTrue(e.searchMessages("s").isEmpty)
        e.setBlocked("WGSEAJNSZJ", true)
        XCTAssertEqual(e.searchMessages("samedi").map(\.text), ["Le cinéma de Samédi est complet"])
    }
}

final class VoiceChainTests: XCTestCase {
    func message(_ id: String, _ kind: String, deleted: Bool = false, exp: Double? = nil) -> Message {
        var m = Message(chat: "c", id: id, from: "WGSEAJNSZJ", at: 0, content: Content(kind: kind))
        m.deleted = deleted
        m.exp = exp
        return m
    }

    func testOnlyTheVoiceRightAfterChains() {
        let list = [message("a", "voice"), message("b", "voice"), message("c", "text"), message("d", "voice"),
                    message("e", "voice", deleted: true), message("f", "voice", exp: 10), message("g", "voice")]
        XCTAssertEqual(nextVoice(after: "a", in: list, now: 100), "b")
        XCTAssertNil(nextVoice(after: "b", in: list, now: 100))
        XCTAssertNil(nextVoice(after: "d", in: list, now: 100))
        XCTAssertNil(nextVoice(after: "e", in: list, now: 100))
        XCTAssertEqual(nextVoice(after: "e", in: list, now: 5), "f")
        XCTAssertNil(nextVoice(after: "g", in: list, now: 100))
        XCTAssertNil(nextVoice(after: "inconnu", in: list, now: 100))
    }
}

/// L'expéditeur scellé, dans le moteur : une enveloppe scellée arrive sans
/// expéditeur, et en ressort attribuée ; le jeton d'un profil est gardé s'il
/// est bien signé.
@MainActor
final class SealedSenderTests: XCTestCase {
    /// Notre jeton personnel va à un contact comme à un membre de groupe qui
    /// nous écrit (pas dans nos contacts) ; jamais à qui est bloqué.
    func testJetonGoesToCorrespondentsButNotBlocked() {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-jeton-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        let member = Identity.create()
        e.saveContact(Contact(code: member.code))
        guard case let .profile(_, jeton, _, pj) = e.profileBody(for: member.code) else { return XCTFail() }
        XCTAssertTrue(pj)
        XCTAssertEqual(jeton.split(separator: ".").first.map(String.init), e.me.jetonNonce(for: member.code, epoch: 0))
        e.setBlocked(member.code, true)
        guard case let .profile(_, none, _, _) = e.profileBody(for: member.code) else { return XCTFail() }
        XCTAssertEqual(none, "")
        guard case let .profile(_, stranger, _, _) = e.profileBody(for: Identity.create().code) else { return XCTFail() }
        XCTAssertEqual(stranger, "", "quelqu'un qu'on ne connaît pas du tout")
    }

    func testSealedEnvelopeAndJeton() async throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-scelle-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        let x = Identity.create()
        var c = Contact(code: x.code)
        c.sign = x.sign
        c.dh = x.dh
        c.saved = true
        e.saveContact(c)

        // Son profil, avec un jeton bien signé : gardé. (Sur l'iPhone, la
        // signature change à chaque calcul : on garde celui-ci.)
        let jeton = x.jeton
        let profile = Wire(.profile(Profile(name: "Xavier", v: 1), jeton: jeton)).json()
        XCTAssertTrue(profile.contains("\"jeton\""))
        let seal = { (plain: String) in
            TalkaCrypto.sealSender(from: x.code, to: e.me.code, toDh: e.me.dh,
                                   body: TalkaCrypto.seal(x, peerCode: e.me.code, peerDh: e.me.dh, plain: plain)!)!
        }
        await e.receive(["sealed": true, "body": seal(profile), "ts": nowMs()])
        XCTAssertEqual(e.contact(x.code)?.jeton, jeton)
        XCTAssertEqual(e.contact(x.code)?.profileName, "Xavier")
        // Le jeton d'un autre : refusé.
        let other = Identity.create()
        let bad = Wire(.profile(Profile(name: "Xavier", v: 2), jeton: other.jeton)).json()
        await e.receive(["sealed": true, "body": seal(bad), "ts": nowMs()])
        XCTAssertEqual(e.contact(x.code)?.jeton, jeton)

        // Un message scellé : attribué à son auteur.
        let msg = Wire(.msg(id: "s1", at: nowMs(), pn: "Xavier", content: Content(text: "En secret"))).json()
        await e.receive(["sealed": true, "body": seal(msg), "ts": nowMs()])
        XCTAssertEqual(e.messages(x.code).last?.text, "En secret")
        XCTAssertEqual(e.messages(x.code).last?.from, x.code)

        // Quelqu'un qui se fait passer pour lui : rien.
        let forged = TalkaCrypto.sealSender(
            from: x.code, to: e.me.code, toDh: e.me.dh,
            body: TalkaCrypto.seal(other, peerCode: e.me.code, peerDh: e.me.dh,
                                   plain: Wire(.msg(id: "s2", at: nowMs(), pn: "", content: Content(text: "Faux"))).json())!)!
        await e.receive(["sealed": true, "body": forged, "ts": nowMs()])
        XCTAssertNil(e.messages(x.code).first { $0.id == "s2" })

        // Le contact garde son jeton dans le stockage ; l'ancien format se relit.
        let stored = try XCTUnwrap(String(data: JSONEncoder().encode(e.contact(x.code)), encoding: .utf8))
        XCTAssertTrue(stored.contains("\"jeton\""))
        let old = try JSONDecoder().decode(Contact.self, from: Data(#"{"code":"WGSEAJNSZJ"}"#.utf8))
        XCTAssertEqual(old.jeton, "")
        XCTAssertFalse(String(data: try JSONEncoder().encode(old), encoding: .utf8)!.contains("jeton"))
    }
}

/// Les messages de plus de 3 mois s'effacent (sauf les importants).
@MainActor
final class KeepDaysTests: XCTestCase {
    func testOldMessagesGoExceptStarred() async throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-garde-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        XCTAssertEqual(e.settings.keepDays, 90)
        let c = e.ensureDirectChat("WGSEAJNSZJ")
        let now = nowMs(), day = 86_400_000.0
        func put(_ id: String, daysAgo: Double, starred: Bool = false) {
            var m = Message(chat: c.id, id: id, from: e.me.code, at: now - daysAgo * day, content: Content(text: id))
            m.starred = starred
            e.store.writeMessage(m)
        }
        put("vieux", daysAgo: 120)
        put("vieux-important", daysAgo: 120, starred: true)
        put("récent", daysAgo: 10)
        // Une capsule écrite il y a 4 mois, qui s'ouvre dans un mois : gardée.
        var capsule = Message(chat: c.id, id: "capsule", from: e.me.code, at: now - 120 * day, content: Content(text: "plus tard"))
        capsule.openAt = now + 30 * day
        e.store.writeMessage(capsule)
        e.sweep()
        XCTAssertEqual(Set(e.messages(c.id).map(\.id)), ["vieux-important", "récent", "capsule"])
        e.store.removeMessage(c.id, "capsule")

        // « Toujours » : plus rien ne part.
        put("très-vieux", daysAgo: 1000)
        e.saveSettings { $0.keepDays = 0 }
        XCTAssertTrue(e.messages(c.id).contains { $0.id == "très-vieux" })
        // 1 mois : tout ce qui dépasse s'efface aussitôt.
        e.saveSettings { $0.keepDays = 30 }
        XCTAssertEqual(Set(e.messages(c.id).map(\.id)), ["vieux-important", "récent"])

        // Un réglage d'avant cette version : 3 mois.
        let old = try JSONDecoder().decode(Settings.self, from: Data(#"{"theme":"dark"}"#.utf8))
        XCTAssertEqual(old.keepDays, 90)
    }
}

/// « Exporter la discussion » : le même texte que le navigateur.
@MainActor
final class ExportChatTests: XCTestCase {
    func testExportedText() async throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-export-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Alice", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        let c = e.ensureDirectChat("WGSEAJNSZJ")
        e.send(c.id, Content(text: "Bonjour\nsur deux lignes"))
        e.send(c.id, Content(text: "Plus tard", openAt: nowMs() + 3_600_000))
        var theirs = Message(chat: c.id, id: "t1", from: "WGSEAJNSZJ", at: nowMs(), content: Content(kind: "voice", dur: 7))
        theirs.at += 1
        e.store.writeMessage(theirs)
        let (name, text) = try XCTUnwrap(e.exportChat(c.id))
        XCTAssertEqual(name, "Talka - WGSEA-JNSZJ.txt")
        XCTAssertTrue(text.hasPrefix("Discussion Talka « WGSEA-JNSZJ », exportée le "))
        XCTAssertTrue(text.contains(" - Alice : Bonjour\nsur deux lignes\n"))
        XCTAssertTrue(text.contains(" - Alice : Plus tard\n"), "sa propre capsule se lit")
        XCTAssertTrue(text.contains(" - WGSEA-JNSZJ : 🎙️ Message vocal (0:07)\n"))
        XCTAssertEqual(exportLine("21/10/2026 23:21", author: "Léa", text: "Bonjour"), "21/10/2026 23:21 - Léa : Bonjour")
        XCTAssertEqual(exportLine("21/10/2026 23:22", author: nil, text: "Léa a rejoint le groupe."), "21/10/2026 23:22 - Léa a rejoint le groupe.")
        XCTAssertEqual(exportFilename("Les amis / 2026 : été?"), "Talka - Les amis _ 2026 _ été_.txt")
        XCTAssertEqual(exportStamp(0).count, 16)
    }
}
