// « Enregistrer » une pièce jointe : le même nom que le navigateur
// (`Message::save_name`), avec l'extension de son vrai type.

import XCTest
@testable import TalkaKit

final class SaveNameTests: XCTestCase {
    func message(_ kind: String, name: String = "", mime: String = "", data: String = "") -> Message {
        var m = Message(chat: "c", id: "m", from: "x", at: 0, content: Content())
        m.kind = kind; m.name = name; m.mime = mime; m.data = data
        return m
    }

    func testSaveNameFollowsTheRealType() {
        XCTAssertEqual(message("file", name: "devis.pdf").saveName, "devis.pdf")
        XCTAssertEqual(message("image", data: "data:image/jpeg;base64,AA").saveName, "photo-talka.jpg")
        XCTAssertEqual(message("image", mime: "image/png").saveName, "photo-talka.png")
        XCTAssertEqual(message("voice", data: "data:audio/mp4;base64,AA").saveName, "vocal-talka.m4a")
        XCTAssertEqual(message("voice", mime: "audio/webm;codecs=opus").saveName, "vocal-talka.webm")
        XCTAssertEqual(message("voice").saveName, "vocal-talka.webm")
        XCTAssertEqual(message("file").saveName, "fichier-talka")
    }

    /// Sous le nom d'un document : l'extension seulement s'il y en a une vraie.
    func testFileDetailShowsARealExtensionOnly() {
        func f(_ name: String) -> Message {
            var m = Message(chat: "c", id: "m", from: "x", at: 0, content: Content())
            m.name = name
            return m
        }
        XCTAssertEqual(f("devis.pdf").fileDetail(size: "12 Ko"), "12 Ko · PDF")
        XCTAssertEqual(f("archive.tar.gz").fileDetail(size: "3 Mo"), "3 Mo · GZ")
        XCTAssertEqual(f("README").fileDetail(size: "1 Ko"), "1 Ko")
        XCTAssertEqual(f("").fileDetail(size: "1 Ko"), "1 Ko")
        XCTAssertEqual(f(".profile").fileDetail(size: "1 Ko"), "1 Ko")
        XCTAssertEqual(f("fin.").fileDetail(size: "1 Ko"), "1 Ko")
        XCTAssertEqual(f("v1.2 compte rendu final").fileDetail(size: "1 Ko"), "1 Ko")
        XCTAssertEqual(f("v1.2 fin").fileDetail(size: "1 Ko"), "1 Ko")
    }

    /// Une taille s'affiche comme dans le navigateur (par 1024, virgule).
    func testSizesLikeTheBrowser() {
        XCTAssertEqual(formatBytes(0), "0 o")
        XCTAssertEqual(formatBytes(25), "25 o")
        XCTAssertEqual(formatBytes(1023), "1023 o")
        XCTAssertEqual(formatBytes(1024), "1,0 Ko")
        XCTAssertEqual(formatBytes(12_000), "11,7 Ko")
        XCTAssertEqual(formatBytes(1_572_864), "1,5 Mo")
        XCTAssertEqual(formatBytes(-3), "0 o")
    }

    /// « Gérer le stockage » compte comme le navigateur.
    func testStorageCountsTheAttachments() {
        func m(_ kind: String, _ data: String, _ size: Double, deleted: Bool = false) -> Message {
            var x = Message(chat: "c", id: UUID().uuidString, from: "x", at: 0, content: Content())
            x.kind = kind; x.data = data; x.size = size; x.deleted = deleted
            return x
        }
        let st = Storage.of([
            m("image", "data:image/jpeg;base64,AAAAAAAA", 0),
            m("image", "data:image/jpeg;base64,AAAA", 0),
            m("file", "data:application/pdf;base64,AA==", 1000),
            m("voice", "data:audio/webm;base64,AAAA", 500),
            m("voice", "data:audio/webm;base64,AAAA", 500, deleted: true),
            m("text", "", 0),
        ])
        XCTAssertEqual(st, Storage(bytes: 6 + 3 + 1000 + 500, photos: 2, files: 1, voices: 1))
        XCTAssertEqual(st.detail, "2 photos · 1 document · 1 vocal")
        XCTAssertEqual(Storage().detail, "")
    }
}
