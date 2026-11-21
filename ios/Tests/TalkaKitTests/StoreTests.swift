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

    // ----- Lire sans tout charger -----

    /// Une discussion pas ouverte : un message seul, les derniers, ceux
    /// d'avant une date se lisent par l'index, et une écriture ne charge
    /// rien. Les mêmes réponses qu'une fois la discussion chargée.
    func testReadsWithoutLoadingTheChat() {
        for key in [nil, SymmetricKey(size: .bits256)] {
            let dir = tempDir()
            let ats: [Double] = [50, 10, 30, 30, 20, 40, 30]
            do {
                let s = Store(directory: dir, key: key)
                for (i, at) in ats.enumerated() { s.writeMessage(message("AMI", "m\(i)", at: at, "texte \(i)")) }
                s.writeMessage(message("AUTRE", "m0", at: 25, "ailleurs"))
                // Écrire n'a rien chargé.
                XCTAssertNil(s.messages["AMI"])
            }
            let want = ["m1", "m4", "m2", "m3", "m6", "m5", "m0"]
            let cold = Store(directory: dir, key: key)
            XCTAssertEqual(cold.message("AMI", "m3")?.text, "texte 3")
            XCTAssertNil(cold.message("AMI", "absent"))
            XCTAssertNil(cold.message("AUTRE", "m3"))
            XCTAssertEqual(cold.messagesOf("AMI", limit: 1).map(\.id), ["m0"])
            XCTAssertEqual(cold.messagesOf("AMI", limit: 4).map(\.id), Array(want.suffix(4)))
            XCTAssertEqual(cold.messagesOf("AMI", limit: 6).map(\.id), Array(want.suffix(6)))
            XCTAssertTrue(cold.messagesOf("AMI", limit: 0).isEmpty)
            XCTAssertEqual(cold.messagesBefore("AMI", 30).map(\.id), ["m1", "m4"])
            XCTAssertEqual(cold.messagesBefore("AMI", 30.5).map(\.id), ["m1", "m4", "m2", "m3", "m6"])
            XCTAssertTrue(cold.messagesBefore("AMI", 10).isEmpty)
            XCTAssertNil(cold.messages["AMI"], "rien de tout cela ne charge la discussion")

            // Écrire et effacer sans charger : lu aussitôt, et gardé.
            var edited = message("AMI", "m3", at: 30, "corrigé")
            edited.edited = true
            cold.writeMessage(edited)
            cold.writeMessage(message("AMI", "m7", at: 60, "nouveau"))
            cold.removeMessage("AMI", "m1")
            XCTAssertEqual(cold.message("AMI", "m3")?.text, "corrigé")
            XCTAssertNil(cold.message("AMI", "m1"))
            XCTAssertEqual(cold.messagesOf("AMI", limit: 2).map(\.id), ["m0", "m7"])
            XCTAssertEqual(cold.messagesBefore("AMI", 30).map(\.id), ["m4"])
            XCTAssertNil(cold.messages["AMI"])

            // Chargée (tous ses messages demandés) : les mêmes réponses.
            let after = ["m4", "m2", "m3", "m6", "m5", "m0", "m7"]
            XCTAssertEqual(cold.messagesOf("AMI").map(\.id), after)
            XCTAssertNotNil(cold.messages["AMI"])
            XCTAssertEqual(cold.message("AMI", "m3")?.text, "corrigé")
            XCTAssertNil(cold.message("AMI", "m1"))
            XCTAssertEqual(cold.messagesOf("AMI", limit: 2).map(\.id), ["m0", "m7"])
            XCTAssertEqual(cold.messagesBefore("AMI", 30).map(\.id), ["m4"])
            XCTAssertEqual(cold.messagesBefore("AMI", 30.5).map(\.id), ["m4", "m2", "m3", "m6"])
            // Et après une réouverture.
            XCTAssertEqual(Store(directory: dir, key: key).messagesOf("AMI").map(\.text),
                           ["texte 4", "texte 2", "corrigé", "texte 6", "texte 5", "texte 0", "nouveau"])
        }
    }

    /// La liste triée d'une discussion chargée suit chaque écriture (ajout,
    /// modification, changement d'heure, suppression) : toujours celle qu'un
    /// tri complet donnerait, et celle que relit une base rouverte.
    func testOrderedListFollowsEveryWrite() {
        let dir = tempDir()
        let s = Store(directory: dir)
        var ref: [String: Message] = [:]
        var seed: UInt64 = 42
        func next(_ n: Int) -> Int {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Int((seed >> 33) % UInt64(n))
        }
        func sorted() -> [Message] { ref.values.sorted { ($0.at, $0.id) < ($1.at, $1.id) } }
        XCTAssertTrue(s.messagesOf("g:x").isEmpty)
        for step in 0..<600 {
            let id = "m\(next(120))"
            switch next(5) {
            case 0 where ref[id] != nil:
                s.removeMessage("g:x", id)
                ref[id] = nil
            case 1 where ref[id] != nil:
                // Modifié, à la même heure.
                var m = ref[id]!
                m.text = "étape \(step)"
                s.writeMessage(m)
                ref[id] = m
            default:
                // Nouveau, ou la même identité à une autre heure (souvent la
                // même que d'autres : l'identifiant départage).
                let m = message("g:x", id, at: Double(next(25)), "étape \(step)")
                s.writeMessage(m)
                ref[id] = m
            }
            XCTAssertEqual(s.messagesOf("g:x"), sorted(), "étape \(step)")
            if step % 50 == 0 {
                let want = sorted()
                XCTAssertEqual(s.messagesOf("g:x", limit: 7), Array(want.suffix(7)))
                XCTAssertEqual(s.messagesBefore("g:x", 12), want.filter { $0.at < 12 })
                // Une base rouverte, sans la charger : les mêmes, par l'index.
                let cold = Store(directory: dir)
                XCTAssertEqual(cold.messagesOf("g:x", limit: 7), Array(want.suffix(7)))
                XCTAssertEqual(cold.messagesBefore("g:x", 12), want.filter { $0.at < 12 })
                XCTAssertEqual(cold.messagesOf("g:x"), want)
            }
        }
        s.deleteMessages(of: "g:x")
        XCTAssertTrue(s.messagesOf("g:x").isEmpty)
        s.writeMessage(message("g:x", "seul", at: 3, "seul"))
        XCTAssertEqual(s.messagesOf("g:x").map(\.id), ["seul"])
    }

    /// Les derniers messages d'une discussion pas chargée sont gardés d'une
    /// lecture à l'autre (la montre les redemande sans cesse), mais jamais
    /// périmés : après chaque écriture, ce sont ceux qu'un tri complet
    /// donnerait, quelle que soit la limite demandée.
    func testRecentMessagesAreKeptButNeverStale() {
        let s = Store(directory: tempDir(), key: SymmetricKey(size: .bits256))
        var ref: [String: [String: Message]] = [:]
        var seed: UInt64 = 99
        func next(_ n: Int) -> Int {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Int((seed >> 33) % UInt64(n))
        }
        func want(_ chat: String, _ limit: Int) -> [Message] {
            Array((ref[chat] ?? [:]).values.sorted { ($0.at, $0.id) < ($1.at, $1.id) }.suffix(limit))
        }
        // Plus de discussions que la mémoire n'en garde à la fois.
        let chats = (0..<40).map { "AMI\($0)" }
        for step in 0..<1500 {
            let chat = chats[next(chats.count)]
            let id = "m\(next(30))"
            switch next(6) {
            case 0 where ref[chat]?[id] != nil:
                s.removeMessage(chat, id)
                ref[chat]?[id] = nil
            case 1:
                // Seulement relire, deux fois de suite, avec des limites différentes.
                break
            default:
                let m = message(chat, id, at: Double(next(40)), "étape \(step)")
                s.writeMessage(m)
                ref[chat, default: [:]][id] = m
            }
            for limit in [[15, 8, 4, 2], [1, 20], [64, 3], [70, 15]][next(4)] {
                XCTAssertEqual(s.messagesOf(chat, limit: limit), want(chat, limit), "étape \(step), limite \(limit)")
            }
        }
        XCTAssertLessThanOrEqual(s.windows.count, Store.windowChats)
        XCTAssertEqual(Set(s.windowOrder), Set(s.windows.keys))
        // Vidée, puis chargée : plus rien de gardé à part.
        s.deleteMessages(of: "AMI0")
        XCTAssertTrue(s.messagesOf("AMI0", limit: 15).isEmpty)
        XCTAssertEqual(s.messagesOf("AMI1").count, ref["AMI1"]?.count ?? 0)
        XCTAssertNil(s.windows["AMI1"])
        XCTAssertEqual(s.messagesOf("AMI1", limit: 5), want("AMI1", 5))
    }

    /// Lire quelques messages un par un ne charge pas la discussion, ni
    /// chercher des messages absents (chaque message qui arrive demande s'il
    /// est déjà là) ; en relire sans fin plus que la mémoire n'en garde la
    /// charge une fois, avec les mêmes réponses.
    func testAChatReadOverAndOverIsLoadedOnce() {
        let dir = tempDir()
        do {
            let s = Store(directory: dir)
            s.transaction {
                for i in 0..<300 { s.writeMessage(message("AMI", "m\(i)", at: Double(i), "texte \(i)")) }
            }
        }
        // Quelques messages, relus cent fois : tenus en mémoire, rien n'est chargé.
        let few = Store(directory: dir)
        for _ in 0..<100 {
            for i in 0..<Store.singlesMax { XCTAssertEqual(few.message("AMI", "m\(i)")?.text, "texte \(i)") }
        }
        XCTAssertNil(few.messages["AMI"])
        // Des messages absents, autant qu'on veut.
        for i in 0..<1000 { XCTAssertNil(few.message("AMI", "absent\(i)")) }
        XCTAssertNil(few.messages["AMI"])
        // Plus que la mémoire n'en garde, relus en boucle : chargée, une fois.
        let many = Store(directory: dir)
        for pass in 0..<3 {
            for i in 0..<200 { XCTAssertEqual(many.message("AMI", "m\(i)")?.text, "texte \(i)", "passage \(pass)") }
        }
        XCTAssertEqual(many.messages["AMI"]?.count, 300)
        XCTAssertNil(many.message("AMI", "absent"))
        XCTAssertTrue(many.singleReads.isEmpty)
        XCTAssertTrue(many.singles.isEmpty)
        // Vidée avant d'être chargée : le compte repart de zéro.
        let cleared = Store(directory: dir)
        for i in 0..<50 { _ = cleared.message("AMI", "m\(i)") }
        cleared.deleteMessages(of: "AMI")
        XCTAssertNil(cleared.message("AMI", "m0"))
        XCTAssertTrue(cleared.singleReads.isEmpty)
    }

    /// Une longue discussion est décodée sur plusieurs cœurs : les mêmes
    /// messages, tous, qu'une lecture ligne à ligne ; chiffrée ou d'avant le
    /// chiffrement, avec des photos (des paquets coupés par le poids), et une
    /// ligne abîmée simplement sautée.
    func testALongChatIsDecodedInParallelExactly() {
        let photo = "data:image/jpeg;base64," + String(repeating: "/9j/4AAQ", count: 40_000)
        for key in [nil, SymmetricKey(size: .bits256)] {
            let dir = tempDir()
            var want: [Message] = []
            do {
                let s = Store(directory: dir, key: key)
                s.transaction {
                    for i in 0..<3000 {
                        var content = Content(text: "Message \(i) — é, 🥦, \"guillemets\" et \\ barre")
                        if i % 97 == 0 { content.kind = "image"; content.data = photo }
                        // Des heures en désordre, et des heures égales.
                        var m = Message(chat: "AMI", id: "m\(i)", from: "WGSEAJNSZJ", at: Double((i * 7919) % 500), content: content)
                        m.reactions = i % 5 == 0 ? ["WGSEAJNSZJ": "👍"] : [:]
                        s.writeMessage(m)
                        want.append(m)
                    }
                    s.writeMessage(message("AUTRE", "m0", at: 1, "ailleurs"))
                }
            }
            want.sort { ($0.at, $0.id) < ($1.at, $1.id) }
            // À froid, ligne à ligne (une par une, sans charger) : la référence.
            let cold = Store(directory: dir, key: key)
            for i in stride(from: 0, to: 3000, by: 331) {
                XCTAssertEqual(cold.message("AMI", "m\(i)"), want.first { $0.id == "m\(i)" })
            }
            XCTAssertNil(cold.messages["AMI"])
            // Chargée d'un coup, en parallèle.
            let loaded = Store(directory: dir, key: key)
            XCTAssertEqual(loaded.messagesOf("AMI"), want)
            XCTAssertEqual(loaded.messagesOf("AUTRE").map(\.text), ["ailleurs"])
            // Une ligne abîmée au milieu : sautée, les autres restent.
            let broken = Store(directory: dir, key: key)
            broken.run("UPDATE rows SET json = ? WHERE store = 'messages' AND key = ?", [Data(repeating: 7, count: 64), "AMI|m1500"])
            XCTAssertEqual(broken.messagesOf("AMI"), want.filter { $0.id != "m1500" })
        }
        // Un paquet décodé garde l'ordre de ses lignes, quel que soit le partage.
        let s = Store(directory: tempDir(), key: SymmetricKey(size: .bits256))
        let msgs = (0..<1000).map { message("AMI", "m\($0)", at: Double($0), "texte \($0)") }
        var rows = msgs.map { Store.MessageRow(key: $0.key, sealed: s.seal("messages", $0.key, JSON.encode($0))) }
        rows[10] = Store.MessageRow(key: "AMI|m10", json: JSON.encode(msgs[10]))
        rows[20] = Store.MessageRow(key: "AMI|m20", sealed: Data(repeating: 1, count: 80))
        XCTAssertEqual(s.decode(rows), msgs.filter { $0.id != "m20" })
        XCTAssertEqual(s.decode(Array(rows.prefix(5))), Array(msgs.prefix(5)))
        XCTAssertTrue(s.decode([Store.MessageRow]()).isEmpty)
    }

    /// Une liste lourde (les contacts et leurs photos, au lancement) est
    /// décodée sur plusieurs cœurs : les mêmes objets, dans le même ordre,
    /// les lignes illisibles sautées.
    func testAHeavyListIsDecodedInParallelExactly() {
        let avatar = "data:image/jpeg;base64," + String(repeating: "/9j/4AAQ", count: 5_000)
        var contacts: [Contact] = []
        for i in 0..<400 {
            var c = Contact(code: "CODE\(i)")
            c.name = "Contact \(i) — é 🥦"
            if i % 3 == 0 { c.avatar = avatar }
            contacts.append(c)
        }
        var jsons = contacts.map { JSON.encode($0) }
        jsons[7] = "{abîmé"
        jsons[200] = ""
        let want = contacts.enumerated().filter { $0.offset != 7 && $0.offset != 200 }.map(\.element)
        XCTAssertEqual(Store.decodeAll(jsons) as [Contact], want)
        XCTAssertEqual(Store.decodeAll(Array(jsons.prefix(3))) as [Contact], Array(contacts.prefix(3)))
        XCTAssertTrue((Store.decodeAll([]) as [Contact]).isEmpty)
        // Par la mémoire : tous, quels que soient leur nombre et leur poids.
        let s = Store(directory: tempDir(), key: SymmetricKey(size: .bits256))
        s.transaction { for c in contacts { s.write("contacts", c.code, c) } }
        let all = s.all("contacts", as: Contact.self)
        XCTAssertEqual(all.count, 400)
        XCTAssertEqual(Dictionary(uniqueKeysWithValues: all.map { ($0.code, $0) }), Dictionary(uniqueKeysWithValues: contacts.map { ($0.code, $0) }))
        XCTAssertEqual(s.all("chats", as: Chat.self).count, 0)
    }

    /// Une longue discussion ne se lit pas en entier pour montrer sa fin :
    /// la mémoire en garde les derniers messages (une fenêtre), tenue à jour
    /// à chaque écriture. Après des centaines d'écritures tirées au hasard
    /// (nouveaux messages, modifications, heures changées vers l'avant ou
    /// l'arrière, suppressions), elle rend toujours ce qu'un tri complet
    /// donnerait, dit juste s'il reste des messages plus anciens, et une base
    /// rouverte est d'accord.
    func testTheEndOfALongChatFollowsEveryWrite() {
        let dir = tempDir()
        let s = Store(directory: dir, key: SymmetricKey(size: .bits256))
        var ref: [String: Message] = [:]
        var seed: UInt64 = 2026
        func next(_ n: Int) -> Int {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Int((seed >> 33) % UInt64(n))
        }
        func sorted() -> [Message] { ref.values.sorted { ($0.at, $0.id) < ($1.at, $1.id) } }
        // 600 messages, puis la discussion vit.
        s.transaction {
            for i in 0..<600 {
                let m = message("LONGUE", "m\(i)", at: Double(i * 10), "texte \(i)")
                s.writeMessage(m)
                ref[m.id] = m
            }
        }
        XCTAssertNil(s.messages["LONGUE"])
        var fresh = 600
        for step in 0..<700 {
            switch next(8) {
            case 0, 1:
                // Un message arrive, à la fin.
                let m = message("LONGUE", "n\(fresh)", at: Double(6000 + step * 3), "nouveau \(fresh)")
                fresh += 1
                s.writeMessage(m)
                ref[m.id] = m
            case 2:
                // Un message récent modifié (un accusé, une réaction), à la même heure.
                let all = sorted()
                var m = all[all.count - 1 - next(min(40, all.count))]
                m.text = "modifié à l'étape \(step)"
                s.writeMessage(m)
                ref[m.id] = m
            case 3:
                // Un message n'importe où change d'heure, vers l'avant ou l'arrière.
                let all = sorted()
                var m = all[next(all.count)]
                m.at = Double(next(9000))
                s.writeMessage(m)
                ref[m.id] = m
            case 4:
                // Un message n'importe où est supprimé.
                let all = sorted()
                let m = all[next(all.count)]
                s.removeMessage("LONGUE", m.id)
                ref[m.id] = nil
            case 5:
                // Un vieux message, hors de toute fenêtre, arrive (une synchronisation).
                let m = message("LONGUE", "v\(step)", at: Double(next(50)), "ancien \(step)")
                s.writeMessage(m)
                ref[m.id] = m
            default:
                break
            }
            let want = sorted()
            let limit = [1, 15, 50, 120, 300][next(5)]
            XCTAssertEqual(s.messagesOf("LONGUE", limit: limit), Array(want.suffix(limit)), "étape \(step), limite \(limit)")
            let end = s.recentMessages("LONGUE", limit: limit)
            XCTAssertGreaterThanOrEqual(end.list.count, min(limit, want.count), "étape \(step)")
            XCTAssertEqual(end.list, Array(want.suffix(end.list.count)), "étape \(step), limite \(limit)")
            XCTAssertEqual(end.more, want.count > end.list.count, "étape \(step)")
            // Un message de la fin ; de temps en temps, un plus ancien que
            // la fenêtre (lus un par un sans fin, ils la feraient charger).
            let probe = step % 12 == 0 ? want[next(want.count)] : want[want.count - 1 - next(min(100, want.count))]
            XCTAssertEqual(s.message("LONGUE", probe.id), probe, "étape \(step)")
            XCTAssertNil(s.message("LONGUE", "absent\(step)"))
            if step % 100 == 0 {
                let cold = Store(directory: dir, key: s.key)
                XCTAssertEqual(cold.recentMessages("LONGUE", limit: 120).list, Array(want.suffix(120)))
                XCTAssertEqual(cold.messagesOf("LONGUE", limit: 15), Array(want.suffix(15)))
            }
        }
        // Jamais chargée en entier : seule sa fin est en mémoire.
        XCTAssertNil(s.messages["LONGUE"])
        XCTAssertNotNil(s.windows["LONGUE"])
        XCTAssertLessThan(s.windows["LONGUE"]?.count ?? .max, ref.count)
        // Demandée en entier (une recherche, un saut vers un vieux message) : chargée, la fenêtre s'efface.
        XCTAssertEqual(s.recentMessages("LONGUE", limit: .max).list, sorted())
        XCTAssertFalse(s.recentMessages("LONGUE", limit: .max).more)
        XCTAssertNotNil(s.messages["LONGUE"])
        XCTAssertNil(s.windows["LONGUE"])
        XCTAssertEqual(s.recentMessages("LONGUE", limit: 50).list, Array(sorted().suffix(50)))
        XCTAssertTrue(s.recentMessages("LONGUE", limit: 50).more)
    }

    /// Une discussion qui tient en entier dans ce qu'on lui demande est
    /// simplement chargée ; une discussion vide n'a rien de plus ancien ; et
    /// la mémoire ne garde la fin que des discussions demandées récemment.
    func testShortChatsAreLoadedAndOldWindowsForgotten() {
        let s = Store(directory: tempDir())
        for i in 0..<20 { s.writeMessage(message("COURTE", "m\(i)", at: Double(i), "texte \(i)")) }
        let short = s.recentMessages("COURTE", limit: 300)
        XCTAssertEqual(short.list.map(\.id), (0..<20).map { "m\($0)" })
        XCTAssertFalse(short.more)
        XCTAssertNotNil(s.messages["COURTE"])
        XCTAssertTrue(s.recentMessages("VIDE", limit: 300).list.isEmpty)
        XCTAssertFalse(s.recentMessages("VIDE", limit: 300).more)
        XCTAssertTrue(s.recentMessages("COURTE", limit: 0).list.isEmpty)
        // Juste à la limite : 20 messages pour 20 demandés, rien de plus ancien.
        let exact = Store(directory: s.dir)
        XCTAssertFalse(exact.recentMessages("COURTE", limit: 20).more)
        XCTAssertTrue(Store(directory: s.dir).recentMessages("COURTE", limit: 19).more)
        // Plus de discussions que la mémoire ne garde de fenêtres.
        s.transaction {
            for c in 0..<(Store.windowChats + 8) {
                for i in 0..<30 { s.writeMessage(message("C\(c)", "m\(i)", at: Double(i), "texte \(i)")) }
            }
        }
        for c in 0..<(Store.windowChats + 8) {
            XCTAssertEqual(s.messagesOf("C\(c)", limit: 5).map(\.id), ["m25", "m26", "m27", "m28", "m29"])
        }
        XCTAssertEqual(s.windows.count, Store.windowChats)
        XCTAssertNil(s.windows["C0"])
        XCTAssertNotNil(s.windows["C\(Store.windowChats + 7)"])
        // Oubliée, elle se relit, juste.
        XCTAssertEqual(s.messagesOf("C0", limit: 3).map(\.id), ["m27", "m28", "m29"])
        XCTAssertEqual(Set(s.windowOrder), Set(s.windows.keys))
    }

    /// Des lignes illisibles à la fin d'une discussion (une base abîmée) :
    /// la fenêtre est plus courte que ce qu'on lui demande, mais elle n'est
    /// pas relue dans SQLite à chaque fois pour autant, et elle suit toujours
    /// les écritures.
    func testAWindowWithUnreadableRowsIsNotReadAgain() {
        let dir = tempDir()
        do {
            let s = Store(directory: dir)
            s.transaction {
                for i in 0..<60 { s.writeMessage(message("AMI", String(format: "m%02d", i), at: Double(i), "texte \(i)")) }
            }
            for id in ["m52", "m57"] {
                s.run("UPDATE rows SET json = ? WHERE store = 'messages' AND key = ?", ["{abîmé", "AMI|\(id)"])
            }
        }
        let s = Store(directory: dir)
        // 21 lignes lues (une de plus que demandé), dont deux illisibles.
        let want = (39..<60).filter { $0 != 52 && $0 != 57 }.map { String(format: "m%02d", $0) }
        let first = s.recentMessages("AMI", limit: 20)
        XCTAssertEqual(first.list.map(\.id), want)
        XCTAssertTrue(first.more)
        XCTAssertEqual(s.windowCovers["AMI"], 21)
        // Une ligne retirée dans le dos de la mémoire : si la fenêtre était
        // relue, elle le verrait. Elle ne l'est pas.
        s.run("DELETE FROM rows WHERE store = 'messages' AND key = ?", ["AMI|m45"])
        XCTAssertEqual(s.recentMessages("AMI", limit: 20).list.map(\.id), want)
        XCTAssertEqual(s.messagesOf("AMI", limit: 20).map(\.id), want)
        XCTAssertEqual(s.messagesOf("AMI", limit: 5).map(\.id), Array(want.suffix(5)))
        // Elle suit toujours les écritures : un message arrive, un autre part.
        s.writeMessage(message("AMI", "m60", at: 60, "nouveau"))
        s.removeMessage("AMI", "m59")
        XCTAssertEqual(s.recentMessages("AMI", limit: 20).list.map(\.id), want.filter { $0 != "m59" } + ["m60"])
        XCTAssertEqual(s.windowCovers["AMI"], 21)
        // Demander plus que ce qu'elle couvre : relue (et la ligne retirée se voit).
        let wider = s.recentMessages("AMI", limit: 30).list.map(\.id)
        XCTAssertFalse(wider.contains("m45"))
        XCTAssertEqual(wider.count, 29)
        XCTAssertEqual(s.windowCovers["AMI"], 31)
        s.deleteMessages(of: "AMI")
        XCTAssertNil(s.windowCovers["AMI"])
    }

    /// Les messages éphémères échus sont retirés sans charger la discussion,
    /// et elle n'est relue que lorsqu'une échéance a pu passer.
    func testExpiredMessagesLeaveWithoutLoadingTheChat() {
        func ephemeral(_ id: String, at: Double, exp: Double?) -> Message {
            var m = message("AMI", id, at: at, "texte \(id)")
            m.exp = exp
            return m
        }
        for key in [nil, SymmetricKey(size: .bits256)] {
            let dir = tempDir()
            do {
                let s = Store(directory: dir, key: key)
                s.transaction {
                    for i in 0..<200 { s.writeMessage(message("AMI", String(format: "m%03d", i), at: Double(i), "texte \(i)")) }
                    s.writeMessage(ephemeral("tard", at: 50.5, exp: 5_000))
                    s.writeMessage(ephemeral("échu2", at: 120.5, exp: 900))
                    s.writeMessage(ephemeral("échu1", at: 10.5, exp: 999))
                    s.writeMessage(ephemeral("juste", at: 60.5, exp: 1_000))
                    s.writeMessage(message("AUTRE", "x", at: 1, "ailleurs"))
                }
            }
            let s = Store(directory: dir, key: key)
            // Les échus, du plus ancien au plus récent ; une échéance qui
            // tombe pile à l'heure attend le passage suivant.
            XCTAssertEqual(s.removeExpired("AMI", before: 1_000).map(\.0), ["échu1", "échu2"])
            XCTAssertNil(s.messages["AMI"])
            XCTAssertEqual(s.expNext["AMI"], 1_000)
            XCTAssertNil(s.message("AMI", "échu1"))
            XCTAssertNotNil(s.message("AMI", "juste"))
            // Rien n'a pu échoir : la discussion n'est pas relue (une ligne
            // changée dans le dos de la mémoire ne se voit pas).
            s.run("DELETE FROM rows WHERE store = 'messages' AND key = ?", ["AMI|juste"])
            XCTAssertTrue(s.removeExpired("AMI", before: 1_000).isEmpty)
            XCTAssertEqual(s.expNext["AMI"], 1_000)
            // L'échéance passée : relue (« juste » n'y est plus).
            XCTAssertTrue(s.removeExpired("AMI", before: 1_001).isEmpty)
            XCTAssertEqual(s.expNext["AMI"], 5_000)
            // Un message écrit avec une échéance plus proche la rapproche,
            // un autre plus lointaine non.
            s.writeMessage(ephemeral("loin", at: 300, exp: 9_000))
            XCTAssertEqual(s.expNext["AMI"], 5_000)
            s.writeMessage(ephemeral("proche", at: 301, exp: 2_000))
            XCTAssertEqual(s.expNext["AMI"], 2_000)
            XCTAssertTrue(s.removeExpired("AMI", before: 2_000).isEmpty)
            XCTAssertEqual(s.removeExpired("AMI", before: 2_001).map(\.1), [301])
            XCTAssertEqual(s.expNext["AMI"], 5_000)
            XCTAssertNil(s.messages["AMI"])
            // La fin en mémoire suit les retraits.
            XCTAssertEqual(s.recentMessages("AMI", limit: 3).list.map(\.id), ["m198", "m199", "loin"])
            XCTAssertEqual(s.removeExpired("AMI", before: 9_500).map(\.0), ["tard", "loin"])
            XCTAssertEqual(s.recentMessages("AMI", limit: 3).list.map(\.id), ["m197", "m198", "m199"])
            XCTAssertEqual(s.expNext["AMI"], .infinity)
            // Une discussion chargée : la même chose, depuis la mémoire.
            XCTAssertEqual(s.messagesOf("AMI").count, 200)
            s.writeMessage(ephemeral("chargé", at: 400, exp: 20_000))
            s.writeMessage(ephemeral("chargé2", at: 401, exp: 30_000))
            XCTAssertTrue(s.removeExpired("AMI", before: 20_000).isEmpty)
            XCTAssertEqual(s.removeExpired("AMI", before: 20_001).map(\.0), ["chargé"])
            XCTAssertEqual(s.expNext["AMI"], 30_000)
            XCTAssertEqual(s.messagesOf("AMI").count, 201)
            // Sans message éphémère : rien, et retenu.
            XCTAssertTrue(s.removeExpired("AUTRE", before: 1e15).isEmpty)
            XCTAssertEqual(s.expNext["AUTRE"], .infinity)
            // Une discussion vidée ou une sauvegarde restaurée : tout est à revoir.
            s.deleteMessages(of: "AMI")
            XCTAssertNil(s.expNext["AMI"])
            let dump = s.dump()
            s.restore(dump)
            XCTAssertTrue(s.expNext.isEmpty)
            // Ce qui reste sur le disque est bien ce qu'on croit.
            XCTAssertEqual(Store(directory: dir, key: key).messagesOf("AUTRE").map(\.id), ["x"])
        }
    }

    /// La sauvegarde garde chaque ligne, dans l'ordre, et se restaure.
    func testDumpKeepsEveryRow() {
        let s = Store(directory: tempDir(), key: SymmetricKey(size: .bits256))
        for i in 0..<300 { s.writeMessage(message("AMI", "m\(i)", at: Double(i), "texte \(i)")) }
        s.write("kv", "profile", Profile(name: "Léa"))
        let dump = s.dump()
        XCTAssertEqual((dump["messages"] as? [[Any]])?.count, 300)
        XCTAssertEqual((dump["kv"] as? [[Any]])?.count, 1)
        XCTAssertEqual((dump["contacts"] as? [[Any]])?.count, 0)
        let other = Store(directory: tempDir())
        other.writeMessage(message("AMI", "avant", at: 1, "effacé par la restauration"))
        _ = other.messagesOf("AMI")
        other.restore(dump)
        XCTAssertEqual(other.messagesOf("AMI").count, 300)
        XCTAssertNil(other.message("AMI", "avant"))
        XCTAssertEqual(other.read("kv", "profile", as: Profile.self)?.name, "Léa")
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

    /// Les actus d'un contact en sourdine : le même champ que le navigateur
    /// (`status_muted`), absent des anciens contacts.
    func testMutedStatusesField() {
        XCTAssertEqual(JSON.decode(Contact.self, #"{"code":"WGSEAJNSZJ"}"#)?.statusMuted, false)
        var c = Contact(code: "WGSEAJNSZJ")
        XCTAssertFalse(JSON.encode(c).contains("status_muted"))
        c.statusMuted = true
        XCTAssertTrue(JSON.encode(c).contains("\"status_muted\":true"))
        XCTAssertEqual(JSON.decode(Contact.self, JSON.encode(c))?.statusMuted, true)
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
