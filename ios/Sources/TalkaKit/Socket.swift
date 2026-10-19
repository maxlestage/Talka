// Le transport du relais : une WebSocket de texte.
//
// Sur iPhone (et Mac) : `URLSessionWebSocketTask`. Sous Linux, Foundation ne
// sait pas ouvrir de WebSocket (libcurl sans WebSocket) : un petit client
// minimal (`ws://` seulement) suffit aux tests contre un serveur local.

import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Une WebSocket de texte.
protocol Socket: AnyObject, Sendable {
    func send(_ text: String) async throws
    /// Le message suivant ; une erreur quand la connexion est fermée.
    func receive() async throws -> String
    func close()
}

enum SocketError: Error { case closed, unsupported }

func openSocket(_ url: URL) -> Socket {
    #if os(Linux)
    LinuxSocket(url)
    #else
    AppleSocket(url)
    #endif
}

#if !os(Linux)
final class AppleSocket: Socket, @unchecked Sendable {
    let task: URLSessionWebSocketTask

    init(_ url: URL) {
        task = URLSession.shared.webSocketTask(with: url)
        task.maximumMessageSize = 16 * 1024 * 1024
        task.resume()
    }

    func send(_ text: String) async throws { try await task.send(.string(text)) }

    func receive() async throws -> String {
        while true {
            if case .string(let text) = try await task.receive() { return text }
        }
    }

    func close() { task.cancel(with: .goingAway, reason: nil) }
}
#else
import Glibc

/// Un client WebSocket minimal (RFC 6455) sur une socket TCP : texte, ping,
/// fermeture. Pour les tests sous Linux seulement.
final class LinuxSocket: Socket, @unchecked Sendable {
    var fd: Int32 = -1
    let lock = NSLock()
    var inbox: [String] = []
    var waiting: [CheckedContinuation<String, Error>] = []
    var closed = false

    init(_ url: URL) {
        let host = url.host ?? "127.0.0.1", port = url.port ?? 80
        let path = url.path.isEmpty ? "/" : url.path
        Thread { [self] in run(host: host, port: port, path: path) }.start()
    }

    func run(host: String, port: Int, path: String) {
        var hints = addrinfo(ai_flags: 0, ai_family: AF_INET, ai_socktype: Int32(SOCK_STREAM.rawValue), ai_protocol: 0, ai_addrlen: 0, ai_addr: nil, ai_canonname: nil, ai_next: nil)
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &res) == 0, let ai = res else { return finish() }
        defer { freeaddrinfo(res) }
        fd = socket(ai.pointee.ai_family, ai.pointee.ai_socktype, ai.pointee.ai_protocol)
        guard fd >= 0, connect(fd, ai.pointee.ai_addr, ai.pointee.ai_addrlen) == 0 else { return finish() }
        let key = Data((0..<16).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
        let request = "GET \(path) HTTP/1.1\r\nHost: \(host):\(port)\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: \(key)\r\nSec-WebSocket-Version: 13\r\n\r\n"
        guard write(Array(request.utf8)) else { return finish() }
        // L'en-tête de la réponse.
        var header: [UInt8] = []
        while !header.suffix(4).elementsEqual([13, 10, 13, 10]) {
            guard let b = read(1) else { return finish() }
            header += b
        }
        guard String(decoding: header, as: UTF8.self).hasPrefix("HTTP/1.1 101") else { return finish() }
        // Les trames.
        var message: [UInt8] = []
        while let h = read(2) {
            let fin = h[0] & 0x80 != 0, op = h[0] & 0x0f
            var len = Int(h[1] & 0x7f)
            if len == 126 { guard let l = read(2) else { break }; len = Int(l[0]) << 8 | Int(l[1]) }
            if len == 127 { guard let l = read(8) else { break }; len = l.reduce(0) { $0 << 8 | Int($1) } }
            guard let payload = read(len) else { break }
            switch op {
            case 0x1, 0x0:
                message += payload
                if fin { deliver(String(decoding: message, as: UTF8.self)); message = [] }
            case 0x8: return finish()
            case 0x9: _ = frame(0xA, payload)
            default: break
            }
        }
        finish()
    }

    func read(_ n: Int) -> [UInt8]? {
        var out = [UInt8](repeating: 0, count: n)
        var got = 0
        while got < n {
            let r = out.withUnsafeMutableBytes { Glibc.read(fd, $0.baseAddress! + got, n - got) }
            if r <= 0 { return nil }
            got += r
        }
        return out
    }

    func write(_ bytes: [UInt8]) -> Bool {
        var sent = 0
        while sent < bytes.count {
            let r = bytes.withUnsafeBytes { Glibc.send(fd, $0.baseAddress! + sent, bytes.count - sent, Int32(MSG_NOSIGNAL)) }
            if r <= 0 { return false }
            sent += r
        }
        return true
    }

    /// Une trame du client : toujours masquée.
    func frame(_ op: UInt8, _ payload: [UInt8]) -> Bool {
        var out: [UInt8] = [0x80 | op]
        let n = payload.count
        if n < 126 { out.append(0x80 | UInt8(n)) }
        else if n < 65536 { out += [UInt8(0x80 | 126), UInt8(n >> 8), UInt8(n & 0xff)] }
        else { out.append(0x80 | 127); out += (0..<8).reversed().map { UInt8((n >> ($0 * 8)) & 0xff) } }
        let mask = (0..<4).map { _ in UInt8.random(in: 0...255) }
        out += mask
        out += payload.enumerated().map { $0.element ^ mask[$0.offset % 4] }
        lock.lock(); defer { lock.unlock() }
        return write(out)
    }

    func deliver(_ text: String) {
        lock.lock()
        if !waiting.isEmpty { let c = waiting.removeFirst(); lock.unlock(); c.resume(returning: text); return }
        inbox.append(text)
        lock.unlock()
    }

    func finish() {
        lock.lock()
        closed = true
        let list = waiting
        waiting = []
        lock.unlock()
        for c in list { c.resume(throwing: SocketError.closed) }
        if fd >= 0 { Glibc.close(fd) }
    }

    func send(_ text: String) async throws {
        // Attendre la fin de la poignée de main.
        for _ in 0..<100 where fd < 0 { try await Task.sleep(nanoseconds: 20_000_000) }
        guard !closed, frame(0x1, Array(text.utf8)) else { throw SocketError.closed }
    }

    func receive() async throws -> String {
        try await withCheckedThrowingContinuation { c in
            lock.lock()
            if !inbox.isEmpty { let t = inbox.removeFirst(); lock.unlock(); c.resume(returning: t); return }
            if closed { lock.unlock(); c.resume(throwing: SocketError.closed); return }
            waiting.append(c)
            lock.unlock()
        }
    }

    func close() { shutdown(fd, Int32(SHUT_RDWR)) }
}
#endif
