import XCTest

@testable import TalkaKit

/// Les règles des appels à plusieurs : les mêmes que le navigateur
/// (`client/src/mesh.rs`, ses tests).
final class MeshTests: XCTestCase {
    /// Des participants qui s'échangent les signaux, sans WebRTC.
    struct Net {
        var people: [String: Mesh] = [:]
        var queue: [(from: String, to: String, op: String, members: [String])] = []

        mutating func run(_ who: String, _ todo: [Mesh.Do]) {
            let members = people[who]!.members
            for d in todo {
                switch d {
                case let .offer(peer, ring): queue.append((who, peer, ring ? "offer" : "link", members))
                case let .answer(peer, _): queue.append((who, peer, "answer", members))
                case let .join(peer): queue.append((who, peer, "join", members))
                case let .hangup(peer): queue.append((who, peer, "end", members))
                case .close, .end: break
                }
            }
        }

        mutating func settle(accepts: Set<String>) {
            var guardCount = 0
            while !queue.isEmpty {
                guardCount += 1
                precondition(guardCount < 10_000, "les signaux ne s'arrêtent jamais")
                let (from, to, op, members) = queue.removeFirst()
                let ring = op == "offer"
                var todo: [Mesh.Do] = []
                if var m = people[to] {
                    switch op {
                    case "offer", "link":
                        todo = m.onOffer(from: from, sdp: "sdp", members: members, ring: ring)
                        if ring, !m.joined, accepts.contains(to) { todo += m.accept() }
                    case "answer": _ = m.onAnswer(from: from)
                    case "join": todo = m.onJoin(from: from, members: members)
                    case "end": todo = m.onGone(from)
                    default: break
                    }
                    people[to] = m
                } else if ring {
                    var m = Mesh.incoming(me: to, from: from, sdp: "sdp", members: members)
                    if accepts.contains(to) { todo = m.accept() }
                    people[to] = m
                }
                run(to, todo)
            }
        }

        func assertAllLinked(_ who: [String], file: StaticString = #filePath, line: UInt = #line) {
            for a in who {
                XCTAssertEqual(people[a]!.open, who.filter { $0 != a }.sorted(), "\(a) n'est pas relié à tous", file: file, line: line)
            }
        }
    }

    func testATwoPersonCallIsAPlainCall() {
        var (a, todo) = Mesh.outgoing(me: "AAAA", invitees: ["BBBB"])
        XCTAssertEqual(todo, [.offer(peer: "BBBB", ring: true)])
        XCTAssertFalse(a.isGroup)
        var b = Mesh.incoming(me: "BBBB", from: "AAAA", sdp: "sdp-a", members: [])
        XCTAssertEqual(b.accept(), [.answer(peer: "AAAA", sdp: "sdp-a")])
        XCTAssertTrue(a.onAnswer(from: "BBBB"))
        XCTAssertEqual(a.open, ["BBBB"])
        XCTAssertEqual(b.onGone("AAAA"), [.close(peer: "AAAA"), .end])
    }

    func testEveryoneWhoAnswersEndsUpLinkedToEveryone() {
        var net = Net()
        let (a, todo) = Mesh.outgoing(me: "AAAA", invitees: ["BBBB", "CCCC", "DDDD", "EEEE"])
        XCTAssertTrue(a.isGroup)
        net.people["AAAA"] = a
        net.run("AAAA", todo)
        net.settle(accepts: ["BBBB", "CCCC", "DDDD", "EEEE"])
        net.assertAllLinked(["AAAA", "BBBB", "CCCC", "DDDD", "EEEE"])
    }

    func testWhoeverAnswersLateStillJoinsEveryone() {
        var net = Net()
        let (c, todo) = Mesh.outgoing(me: "CCCC", invitees: ["AAAA", "BBBB", "DDDD"])
        net.people["CCCC"] = c
        net.run("CCCC", todo)
        net.settle(accepts: ["AAAA", "BBBB"])
        net.assertAllLinked(["CCCC", "AAAA", "BBBB"])
        var d = net.people["DDDD"]!
        let late = d.accept()
        net.people["DDDD"] = d
        net.run("DDDD", late)
        net.settle(accepts: [])
        net.assertAllLinked(["CCCC", "AAAA", "BBBB", "DDDD"])
    }

    func testSomeoneAddedDuringACallJoinsEveryone() throws {
        var net = Net()
        let (b, todo) = Mesh.outgoing(me: "BBBB", invitees: ["DDDD"])
        net.people["BBBB"] = b
        net.run("BBBB", todo)
        net.settle(accepts: ["DDDD"])
        var d = net.people["DDDD"]!
        let addA = try d.add("AAAA").get()
        net.people["DDDD"] = d
        net.run("DDDD", addA)
        net.settle(accepts: ["AAAA"])
        var b2 = net.people["BBBB"]!
        let addC = try b2.add("CCCC").get()
        net.people["BBBB"] = b2
        net.run("BBBB", addC)
        net.settle(accepts: ["CCCC"])
        net.assertAllLinked(["AAAA", "BBBB", "CCCC", "DDDD"])
    }

    func testTwoWhoAnswerAtOnceDoNotBothOffer() {
        var b = Mesh.incoming(me: "BBBB", from: "AAAA", sdp: "x", members: ["AAAA", "BBBB", "CCCC"])
        var c = Mesh.incoming(me: "CCCC", from: "AAAA", sdp: "x", members: ["AAAA", "BBBB", "CCCC"])
        XCTAssertTrue(b.accept().contains(.join(peer: "CCCC")))
        XCTAssertTrue(c.accept().contains(.join(peer: "BBBB")))
        XCTAssertEqual(b.onJoin(from: "CCCC", members: []), [.offer(peer: "CCCC", ring: false)])
        XCTAssertEqual(c.onJoin(from: "BBBB", members: []), [.join(peer: "BBBB")])
        XCTAssertEqual(c.onJoin(from: "BBBB", members: []), [])
        XCTAssertEqual(b.onJoin(from: "CCCC", members: []), [])
        XCTAssertEqual(c.onOffer(from: "BBBB", sdp: "sdp-b", members: [], ring: false), [.answer(peer: "BBBB", sdp: "sdp-b")])
    }

    func testTwoInvitationsAtOnceKeepTheSmallerOne() {
        var (a, _) = Mesh.outgoing(me: "AAAA", invitees: ["BBBB"])
        var (b, _) = Mesh.outgoing(me: "BBBB", invitees: ["AAAA"])
        XCTAssertEqual(a.onOffer(from: "BBBB", sdp: "sdp-b", members: [], ring: true), [])
        XCTAssertEqual(b.onOffer(from: "AAAA", sdp: "sdp-a", members: [], ring: true), [.close(peer: "AAAA"), .answer(peer: "AAAA", sdp: "sdp-a")])
    }

    func testACallHoldsEightPeopleAtMost() {
        let many = (0..<12).map { String(format: "P%03d", $0) }
        var (a, todo) = Mesh.outgoing(me: "AAAA", invitees: many)
        XCTAssertEqual(todo.count, Mesh.maxPeople - 1)
        XCTAssertEqual(a.members.count, Mesh.maxPeople)
        XCTAssertEqual(a.add("ZZZZ"), .failure(.full))
        XCTAssertEqual(a.add("P000"), .failure(.already))
        let b = Mesh.incoming(me: "BBBB", from: "AAAA", sdp: "x", members: many)
        XCTAssertEqual(b.members.count, Mesh.maxPeople)
        XCTAssertTrue(b.others.contains("AAAA"))
        let (c, once) = Mesh.outgoing(me: "AAAA", invitees: ["AAAA", "BBBB", "BBBB"])
        XCTAssertEqual(once.count, 1)
        XCTAssertEqual(c.others, ["BBBB"])
    }

    func testACallEndsWhenEveryoneHasLeftOrDeclined() {
        var (a, _) = Mesh.outgoing(me: "AAAA", invitees: ["BBBB", "CCCC", "DDDD"])
        XCTAssertEqual(a.onGone("BBBB"), [.close(peer: "BBBB")])
        XCTAssertTrue(a.onAnswer(from: "CCCC"))
        XCTAssertEqual(a.noAnswer(), [.hangup(peer: "DDDD")])
        XCTAssertEqual(a.open, ["CCCC"])
        XCTAssertEqual(a.onGone("CCCC"), [.close(peer: "CCCC"), .end])
        var (e, _) = Mesh.outgoing(me: "AAAA", invitees: ["BBBB"])
        XCTAssertEqual(e.noAnswer(), [.hangup(peer: "BBBB"), .end])
        var (f, _) = Mesh.outgoing(me: "AAAA", invitees: ["BBBB", "CCCC"])
        XCTAssertEqual(f.hangup(), [.hangup(peer: "BBBB"), .hangup(peer: "CCCC"), .end])
    }

    func testARingingCallIgnoresLinksAndJoins() {
        var b = Mesh.incoming(me: "BBBB", from: "AAAA", sdp: "x", members: ["AAAA", "BBBB", "CCCC"])
        XCTAssertEqual(b.onOffer(from: "CCCC", sdp: "y", members: [], ring: false), [])
        XCTAssertEqual(b.onJoin(from: "CCCC", members: []), [])
        XCTAssertNil(b.link("CCCC"))
        XCTAssertEqual(b.onOffer(from: "CCCC", sdp: "z", members: [], ring: true), [])
        let todo = b.accept()
        XCTAssertTrue(todo.contains(.answer(peer: "AAAA", sdp: "x")))
        XCTAssertTrue(todo.contains(.answer(peer: "CCCC", sdp: "z")))
        XCTAssertEqual(b.accept(), [])
    }

    /// Le signal d'un appel à plusieurs traverse le JSON : ses participants
    /// et son groupe ; un appel à deux n'en porte pas.
    func testAGroupCallSignalCarriesItsMembers() throws {
        let group = Wire(.call(op: "join", callId: "c2", video: false, sdp: "", cand: nil,
                               members: ["AAAAAAAAAA", "BBBBBBBBBB"], chat: "g:famille"))
        let json = group.json()
        let back = try XCTUnwrap(Wire.parse(json))
        guard case let .call(op, callId, _, _, _, members, chat) = back.body else { return XCTFail("call") }
        XCTAssertEqual(op, "join")
        XCTAssertEqual(callId, "c2")
        XCTAssertEqual(members, ["AAAAAAAAAA", "BBBBBBBBBB"])
        XCTAssertEqual(chat, "g:famille")
        let plain = Wire(.call(op: "end", callId: "c1", video: false, sdp: "", cand: nil)).json()
        XCTAssertFalse(plain.contains("members"))
        XCTAssertFalse(plain.contains("chat"))
    }
}
