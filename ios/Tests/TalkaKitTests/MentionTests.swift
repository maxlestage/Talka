// Les mentions dans les groupes (« @Léa ») : comme `model::mentions` du
// navigateur, et la réception par le moteur.
import XCTest
@testable import TalkaKit

final class MentionTests: XCTestCase {
    func testQueryAndFound() throws {
        XCTAssertEqual(Mentions.query("Salut @Lé"), "Lé")
        XCTAssertEqual(Mentions.query("@"), "")
        XCTAssertNil(Mentions.query("écris à moi@exemple.fr"), "pas une adresse")
        XCTAssertNil(Mentions.query("Salut @Léa\nça va"))
        XCTAssertNil(Mentions.query("Salut"))
        let members: [(code: String, label: String)] = [
            ("AAAAAAAAAA", "Léa Martin"), ("BBBBBBBBBB", "Bob"), ("CCCCCCCCCC", ""),
        ]
        XCTAssertEqual(Mentions.found(in: "Merci @lea martin et @Bob ! @Bob", members: members),
                       ["AAAAAAAAAA", "BBBBBBBBBB"])
        XCTAssertTrue(Mentions.found(in: "Bonjour Bob", members: members).isEmpty)
        XCTAssertTrue(Mentions.found(in: "@ tout le monde", members: members).isEmpty)
        // Un ancien client ignore le champ ; un message sans mention ne
        // l'écrit pas.
        let c = try JSONDecoder().decode(Content.self, from: Data(#"{"kind":"text","text":"x"}"#.utf8))
        XCTAssertTrue(c.mentions.isEmpty)
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(c), as: UTF8.self).contains("mentions"))
    }

    /// Mentionné dans un groupe : « @ » jusqu'à la lecture ; on ne garde
    /// que les membres du groupe.
    @MainActor
    func testMentionMarksChatUntilRead() async {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-mention-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        let x = Identity.create()
        let stranger = Identity.create().code
        let grp = GroupInfo(id: "mention", name: "Les amis", members: [x.code, e.me.code], admins: [x.code], avatar: "", v: 1)
        var content = Content(text: "Salut @Léa")
        content.mentions = [e.me.code, stranger]
        e.handle(x.code, Wire(.msg(id: "m1", at: nowMs(), pn: "", content: content), grp: grp), ts: nowMs(), live: true)
        XCTAssertEqual(e.chat("g:mention")?.mentioned, true)
        XCTAssertEqual(e.messages("g:mention").last?.mentions, [e.me.code])
        XCTAssertEqual(e.mentionLabel(e.me.code), "Léa")
        e.markRead("g:mention")
        XCTAssertEqual(e.chat("g:mention")?.mentioned, false)

        // Sans nous mentionner : pas de « @ ».
        content.mentions = [x.code]
        e.handle(x.code, Wire(.msg(id: "m2", at: nowMs(), pn: "", content: content), g: "mention"), ts: nowMs(), live: true)
        XCTAssertEqual(e.chat("g:mention")?.unread, 1)
        XCTAssertEqual(e.chat("g:mention")?.mentioned, false)
    }

    /// « X vous a ajouté » reste avant le premier message du groupe, même
    /// quand l'annonce a quitté l'expéditeur après qu'il a écrit ce message
    /// (l'annonce est datée par le relais, le message par l'expéditeur).
    @MainActor
    func testAddedNoticeStaysFirst() async {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-ajout-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        let x = Identity.create()
        let grp = GroupInfo(id: "ordre", name: "Les amis", members: [x.code, e.me.code], admins: [x.code], avatar: "", v: 1)
        let t = nowMs()
        e.handle(x.code, Wire(.group(op: "create", note: ""), grp: grp), ts: t, live: true)
        e.handle(x.code, Wire(.msg(id: "m1", at: t - 800, pn: "", content: Content(text: "Bienvenue")), g: "ordre"), ts: t + 50, live: true)
        let list = e.messages("g:ordre")
        XCTAssertEqual(list.map(\.kind), ["system", "text"])
        XCTAssertGreaterThan(list[1].at, list[0].at)
    }
}

final class GroupsInCommonTests: XCTestCase {
    /// Les mêmes cas que `groups_in_common_are_shared_and_sorted` (navigateur).
    func testGroupsInCommonAreSharedAndSorted() {
        func g(_ id: String, _ name: String, _ members: [String], left: Bool = false) -> Chat {
            var c = Chat(id: id)
            c.group = true; c.name = name; c.members = members; c.left = left
            return c
        }
        let chats = [
            g("g:1", "Voisins", ["MOI", "LEA"]),
            g("g:2", "amis", ["MOI", "LEA", "BOB"]),
            g("g:3", "Travail", ["MOI", "BOB"]),
            g("g:4", "Ancien", ["MOI", "LEA"], left: true),
            g("g:5", "Sans moi", ["LEA", "BOB"]),
            Chat(id: "LEA"),
        ]
        XCTAssertEqual(Chat.groupsInCommon(chats, me: "MOI", code: "LEA").map(\.id), ["g:2", "g:1"])
        XCTAssertTrue(Chat.groupsInCommon(chats, me: "MOI", code: "ZOE").isEmpty)
    }

    /// La fiche montre les photos de la plus récente à la plus ancienne, sans
    /// les supprimées ni une capsule encore scellée (comme le navigateur).
    func testPhotosNewestFirstWithoutHiddenOnes() {
        func m(_ id: String, _ kind: String, _ from: String, deleted: Bool = false, openAt: Double? = nil) -> Message {
            var x = Message(chat: "LEA", id: id, from: from, at: 0, content: Content())
            x.kind = kind; x.data = "data:image/jpeg;base64,AA=="
            x.deleted = deleted; x.openAt = openAt
            return x
        }
        let msgs = [
            m("1", "image", "LEA"),
            m("2", "text", "LEA"),
            m("3", "image", "LEA", deleted: true),
            m("4", "image", "LEA", openAt: 5_000),
            m("5", "image", "MOI", openAt: 5_000),
            m("6", "image", "MOI"),
        ]
        XCTAssertEqual(Message.photos(msgs, me: "MOI", now: 1_000).map(\.id), ["6", "5", "1"])
        XCTAssertEqual(Message.photos(msgs, me: "MOI", now: 9_000).map(\.id), ["6", "5", "4", "1"])
    }

    /// Les documents de la fiche : les fichiers seulement, avec leur contenu.
    func testDocumentsAreTheFilesOnly() {
        func m(_ id: String, _ kind: String, _ data: String) -> Message {
            var x = Message(chat: "LEA", id: id, from: "LEA", at: 0, content: Content())
            x.kind = kind; x.data = data
            return x
        }
        let msgs = [
            m("1", "file", "data:application/pdf;base64,AA=="),
            m("2", "image", "data:image/jpeg;base64,AA=="),
            m("3", "file", ""),
            m("4", "voice", "data:audio/webm;base64,AA=="),
            m("5", "file", "data:text/plain;base64,AA=="),
        ]
        XCTAssertEqual(Message.documents(msgs, me: "MOI", now: 0).map(\.id), ["5", "1"])
    }

    /// « Derniers messages » compte les messages des autres arrivés depuis.
    func testNewMessagesBelowCountTheOthersOnly() {
        func m(_ id: String, _ from: String) -> Message { Message(chat: "c", id: id, from: from, at: 0, content: Content()) }
        let msgs = [m("1", "LEA"), m("2", "MOI"), m("3", "LEA"), m("4", "system"), m("5", "MOI"), m("6", "BOB")]
        XCTAssertEqual(Message.newBelow(msgs, seen: "2", me: "MOI"), 2)
        XCTAssertEqual(Message.newBelow(msgs, seen: "6", me: "MOI"), 0)
        XCTAssertEqual(Message.newBelow(msgs, seen: "inconnu", me: "MOI"), 0)
    }

    /// Les liens de la fiche : la règle des bulles, chacun une fois, le plus
    /// récent d'abord (comme le navigateur).
    func testLinksLikeTheBubbles() {
        XCTAssertEqual(Message.findLinks("Regarde https://talka.example/a?b=1, et (http://x.org/y)."),
                       ["https://talka.example/a?b=1", "http://x.org/y"])
        XCTAssertEqual(Message.findLinks("«https://a.fr»"), ["https://a.fr"])
        XCTAssertTrue(Message.findLinks("pas un lien : xhttps://a.fr ni https://").isEmpty)
        func m(_ id: String, _ from: String, _ text: String, deleted: Bool = false, openAt: Double? = nil) -> Message {
            var x = Message(chat: "LEA", id: id, from: from, at: 0, content: Content())
            x.text = text; x.deleted = deleted; x.openAt = openAt
            return x
        }
        let msgs = [
            m("1", "LEA", "https://a.fr"),
            m("2", "LEA", "https://b.fr https://a.fr"),
            m("3", "LEA", "https://supprime.fr", deleted: true),
            m("4", "LEA", "https://capsule.fr", openAt: 5_000),
            m("5", "system", "https://systeme.fr"),
        ]
        let got = Message.links(msgs, me: "MOI", now: 1_000)
        XCTAssertEqual(got.map(\.url), ["https://b.fr", "https://a.fr"])
        XCTAssertEqual(got.map(\.message.id), ["2", "2"])
    }
}

final class PresenceTests: XCTestCase {
    /// Hors ligne, plus personne n'est « en ligne » ; les « vu à » restent.
    @MainActor
    func testOfflineForgetsWhoIsOnline() async {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("talka-presence-\(randomId(4))")
        let store = Store(directory: dir)
        Engine.createAccount(store, name: "Léa", avatar: "")
        let e = Engine(store: store, server: URL(string: "http://localhost:1")!)
        e.relay.onPresence?("AAAAAAAAAA", true, nil)
        e.relay.onPresence?("BBBBBBBBBB", false, 1_000)
        XCTAssertEqual(e.presence["AAAAAAAAAA"]?.online, true)
        e.onNet(.offline)
        XCTAssertNil(e.presence["AAAAAAAAAA"])
        XCTAssertEqual(e.presence["BBBBBBBBBB"]?.seen, 1_000)
    }
}
