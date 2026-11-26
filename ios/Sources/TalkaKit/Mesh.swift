// Les appels à plusieurs : chacun relié à chacun, de pair à pair (WebRTC),
// huit personnes au plus. Ce fichier ne fait que décider : qui fait sonner
// qui, qui propose une liaison à qui, à qui répondre, quand l'appel est
// fini. `CallEngine` exécute ces décisions avec WebRTC. Les mêmes règles
// que le navigateur (`client/src/mesh.rs`), qui y sont expliquées :
//
// - `offer` fait sonner (une invitation, avec `members` à plusieurs) ;
// - `link` propose une liaison sans faire sonner, entre deux personnes
//   déjà dans l'appel ;
// - `join` : « je suis dans l'appel », à ceux qu'on n'a pas encore ; entre
//   deux personnes dans l'appel, le plus petit code propose la liaison,
//   l'autre lui renvoie `join` une fois.

import Foundation

public struct Mesh: Sendable {
    /// Huit personnes au plus dans un appel (nous compris).
    public static let maxPeople = 8

    /// Où en est la liaison avec un participant.
    public enum Link: Equatable, Sendable {
        /// Nous l'avons fait sonner (`offer`) : en attente de sa réponse.
        case inviting
        /// Nous lui avons proposé une liaison (`link`) : en attente.
        case offered
        /// Il nous a fait sonner ou proposé une liaison : son offre, à
        /// reprendre pour répondre.
        case incoming(String)
        /// Reliés.
        case open
    }

    /// Ce qu'il faut faire, dans l'ordre.
    public enum Do: Equatable, Sendable {
        /// Préparer une liaison et lui envoyer une offre (`ring` : la faire
        /// sonner, `offer` ; sinon `link`).
        case offer(peer: String, ring: Bool)
        /// Préparer une liaison, y poser son offre et lui répondre.
        case answer(peer: String, sdp: String)
        /// Lui dire que nous sommes dans l'appel.
        case join(peer: String)
        /// Lui dire que nous partons (`end`) et fermer la liaison.
        case hangup(peer: String)
        /// Fermer la liaison sans rien lui dire.
        case close(peer: String)
        /// Plus personne : l'appel est fini.
        case end
    }

    public let me: String
    /// Tous ceux de l'appel, invités ou reliés (nous non compris).
    private var roster: Set<String> = []
    private var links: [String: Link] = [:]
    /// Nous sommes dans l'appel : appelant, ou appelé qui a répondu.
    public private(set) var joined = false
    /// Ceux à qui nous avons déjà renvoyé `join`.
    private var joinedBack: Set<String> = []

    private init(me: String) { self.me = me }

    /// Nous appelons `invitees` (un seul : un appel à deux).
    public static func outgoing(me: String, invitees: [String]) -> (Mesh, [Do]) {
        var mesh = Mesh(me: me)
        mesh.joined = true
        var todo: [Do] = []
        for peer in invitees where mesh.admit(peer) && mesh.links[peer] == nil {
            mesh.links[peer] = .inviting
            todo.append(.offer(peer: peer, ring: true))
        }
        return (mesh, todo)
    }

    /// `from` nous fait sonner, avec son offre et ceux qu'il appelle avec
    /// nous (vide : un appel à deux).
    public static func incoming(me: String, from: String, sdp: String, members: [String]) -> Mesh {
        var mesh = Mesh(me: me)
        _ = mesh.admit(from)
        mesh.links[from] = .incoming(sdp)
        mesh.absorb(members)
        return mesh
    }

    /// Le compte dans l'appel, s'il y a de la place (vrai s'il y est).
    private mutating func admit(_ peer: String) -> Bool {
        if peer.isEmpty || peer == me { return false }
        if roster.contains(peer) { return true }
        if roster.count + 1 >= Self.maxPeople { return false }
        roster.insert(peer)
        return true
    }

    private mutating func absorb(_ members: [String]) {
        // Dans l'ordre, comme le navigateur (une liste triée).
        for m in members.sorted() { _ = admit(m) }
    }

    /// Ceux de l'appel, nous compris (triés) : ce qu'une invitation annonce.
    public var members: [String] { (Array(roster) + [me]).sorted() }

    /// Les autres participants, invités ou reliés (triés).
    public var others: [String] { roster.sorted() }

    /// Un appel à plusieurs (plus d'un autre participant).
    public var isGroup: Bool { roster.count > 1 }

    public func link(_ peer: String) -> Link? { links[peer] }

    /// Ceux avec qui nous sommes reliés (triés).
    public var open: [String] { links.filter { $0.value == .open }.map(\.key).sorted() }

    /// Une offre de `from` pour cet appel : une invitation (`ring`) ou une
    /// liaison.
    public mutating func onOffer(from: String, sdp: String, members: [String], ring: Bool) -> [Do] {
        if !joined {
            if ring, admit(from), links[from] != .open {
                links[from] = .incoming(sdp)
                absorb(members)
            }
            return []
        }
        guard admit(from) else { return [] }
        absorb(members)
        switch links[from] {
        case .open: return []
        case .inviting, .offered:
            // Nous lui avons aussi proposé : l'offre du plus petit code l'emporte.
            if me < from { return [] }
            links[from] = .open
            return [.close(peer: from), .answer(peer: from, sdp: sdp)]
        default:
            links[from] = .open
            return [.answer(peer: from, sdp: sdp)]
        }
    }

    /// Nous répondons : une réponse à chaque offre reçue, puis `join` à ceux
    /// de l'appel que nous n'avons pas encore.
    public mutating func accept() -> [Do] {
        if joined { return [] }
        joined = true
        var todo: [Do] = []
        for peer in links.keys.sorted() {
            if case let .incoming(sdp) = links[peer] {
                todo.append(.answer(peer: peer, sdp: sdp))
                links[peer] = .open
            }
        }
        for peer in roster.sorted() where links[peer] == nil {
            todo.append(.join(peer: peer))
        }
        return todo
    }

    /// `from` est dans l'appel et ne nous a pas encore.
    public mutating func onJoin(from: String, members: [String]) -> [Do] {
        guard admit(from) else { return [] }
        absorb(members)
        if !joined || links[from] != nil { return [] }
        if me < from {
            links[from] = .offered
            return [.offer(peer: from, ring: false)]
        }
        if joinedBack.insert(from).inserted { return [.join(peer: from)] }
        return []
    }

    /// Sa réponse à notre offre : vrai si nous l'attendions.
    public mutating func onAnswer(from: String) -> Bool {
        switch links[from] {
        case .inviting, .offered:
            links[from] = .open
            return true
        default: return false
        }
    }

    /// `from` s'en va, ne vient pas, ou sa liaison a échoué.
    public mutating func onGone(_ from: String) -> [Do] {
        var todo: [Do] = []
        if links.removeValue(forKey: from) != nil { todo.append(.close(peer: from)) }
        roster.remove(from)
        joinedBack.remove(from)
        if links.isEmpty { todo.append(.end) }
        return todo
    }

    /// Personne n'a répondu à temps : les invitations en attente s'arrêtent.
    public mutating func noAnswer() -> [Do] {
        var todo: [Do] = []
        for peer in links.keys.sorted() where links[peer] == .inviting {
            links.removeValue(forKey: peer)
            roster.remove(peer)
            todo.append(.hangup(peer: peer))
        }
        if links.isEmpty { todo.append(.end) }
        return todo
    }

    /// Nous raccrochons (ou refusons) : au revoir à chacun.
    public mutating func hangup() -> [Do] {
        let todo: [Do] = links.keys.sorted().map { .hangup(peer: $0) } + [.end]
        links.removeAll()
        return todo
    }

    public enum AddError: Error, Equatable { case notInCall, already, full }

    /// Faire sonner quelqu'un de plus, pendant l'appel.
    public mutating func add(_ peer: String) -> Result<[Do], AddError> {
        guard joined else { return .failure(.notInCall) }
        if peer == me || links[peer] != nil { return .failure(.already) }
        guard admit(peer) else { return .failure(.full) }
        links[peer] = .inviting
        return .success([.offer(peer: peer, ring: true)])
    }
}
