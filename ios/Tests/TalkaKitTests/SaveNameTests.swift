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
}
