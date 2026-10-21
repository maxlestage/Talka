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
}
