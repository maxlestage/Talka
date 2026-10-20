// Les jetons de Brocolis 🥦 (https://github.com/maxlestage/messagerie-) : une
// seconde couche de chiffrement, par-dessus celle de Talka, avec les clés que
// l'on partage dans l'app Brocolis. Le même format que le navigateur
// (`client/src/jetons.rs`) et que l'app Brocolis (`BrocolisCrypto.swift`) :
//
// - une clé : 32 octets (AES-256), en base64 ;
// - `[[BROCOLIS:v1:<base64>]]` : AES-256-GCM « combined » (nonce ‖ chiffré ‖
//   tag), pour une seule clé ;
// - `[[BROCOLIS:v2:<base64>]]` : une enveloppe pour plusieurs clés : `0x01`,
//   N, N clés de message emballées (60 octets chacune), puis le corps ;
// - `((…))` : seuls ces passages sont chiffrés, le reste part en clair.

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public enum Brocolis {
    public static let head = "[[BROCOLIS:"
    static let tail = "]]"
    static let wrapped = 12 + 32 + 16

    /// Une clé Brocolis en base64 (32 octets), ou nil.
    public static func key(base64: String) -> SymmetricKey? {
        guard let raw = Data(base64Encoded: base64.trimmingCharacters(in: .whitespacesAndNewlines)), raw.count == 32 else {
            return nil
        }
        return SymmetricKey(data: raw)
    }

    /// Retrouve une clé dans un texte collé (l'invitation de l'app Brocolis
    /// entière, par exemple) : le premier mot qui est une clé.
    public static func findKey(in text: String) -> String? {
        let separators = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "«»\"'“”()[]<>"))
        return text.components(separatedBy: separators).first { key(base64: $0) != nil }
    }

    /// Une nouvelle clé, en base64.
    public static func generateKey() -> String {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }.base64EncodedString()
    }

    public static func hasToken(_ text: String) -> Bool { text.contains(head) }

    static func sealRaw(_ plain: Data, _ key: SymmetricKey) -> Data? {
        try? AES.GCM.seal(plain, using: key).combined
    }

    static func openRaw(_ combined: Data, _ key: SymmetricKey) -> Data? {
        guard combined.count >= 12 + 16, let box = try? AES.GCM.SealedBox(combined: combined) else { return nil }
        return try? AES.GCM.open(box, using: key)
    }

    /// Un jeton pour `text` : `v1` pour une clé (lisible par toutes les
    /// versions de Brocolis), `v2` pour plusieurs.
    public static func sealToken(_ text: String, keys: [SymmetricKey]) -> String? {
        guard let first = keys.first, keys.count <= 255 else { return nil }
        if keys.count == 1 {
            return sealRaw(Data(text.utf8), first).map { head + "v1:" + $0.base64EncodedString() + tail }
        }
        let messageKey = SymmetricKey(size: .bits256)
        var payload = Data([1, UInt8(keys.count)])
        for key in keys {
            guard let wrapped = sealRaw(messageKey.withUnsafeBytes { Data($0) }, key) else { return nil }
            payload.append(wrapped)
        }
        guard let body = sealRaw(Data(text.utf8), messageKey) else { return nil }
        payload.append(body)
        return head + "v2:" + payload.base64EncodedString() + tail
    }

    /// Chiffre un message : les passages `((…))` s'il y en a, sinon tout le
    /// texte. Un texte qui porte déjà un jeton (collé depuis le clavier
    /// Brocolis) part tel quel.
    public static func seal(_ text: String, keys: [SymmetricKey]) -> String {
        if text.isEmpty || keys.isEmpty || hasToken(text) { return text }
        var out = ""
        var rest = Substring(text)
        var marked = false
        while let open = rest.range(of: "(("), let close = rest[open.upperBound...].range(of: "))") {
            out += rest[..<open.lowerBound]
            out += sealToken(String(rest[open.upperBound..<close.lowerBound]), keys: keys) ?? String(rest[open.lowerBound..<close.upperBound])
            marked = true
            rest = rest[close.upperBound...]
        }
        if !marked { return sealToken(text, keys: keys) ?? text }
        return out + rest
    }

    static func openV2(_ payload: Data, _ keys: [SymmetricKey]) -> Data? {
        let bytes = [UInt8](payload)
        guard bytes.count >= 2, bytes[0] == 1 else { return nil }
        let n = Int(bytes[1])
        let bodyStart = 2 + n * wrapped
        guard bytes.count >= bodyStart + 12 + 16 else { return nil }
        let body = Data(bytes[bodyStart...])
        for i in 0..<n {
            let w = Data(bytes[(2 + i * wrapped)..<(2 + (i + 1) * wrapped)])
            for key in keys {
                if let raw = openRaw(w, key), raw.count == 32, let plain = openRaw(body, SymmetricKey(data: raw)) {
                    return plain
                }
            }
        }
        return nil
    }

    static func openToken(_ inner: Substring, _ keys: [SymmetricKey]) -> String? {
        var plain: Data?
        if inner.hasPrefix("v1:"), let combined = Data(base64Encoded: String(inner.dropFirst(3))) {
            plain = keys.lazy.compactMap { openRaw(combined, $0) }.first
        } else if inner.hasPrefix("v2:"), let payload = Data(base64Encoded: String(inner.dropFirst(3))) {
            plain = openV2(payload, keys)
        }
        return plain.flatMap { String(data: $0, encoding: .utf8) }
    }

    /// Ce que donne un texte une fois ses jetons ouverts.
    public struct Opened: Equatable, Sendable {
        public var text = ""
        /// Les jetons ouverts.
        public var opened = 0
        /// Ceux qu'aucune de vos clés n'ouvre (laissés tels quels).
        public var locked = 0
    }

    /// Ouvre les jetons de `text` avec toutes vos clés.
    public static func open(_ text: String, keys: [SymmetricKey]) -> Opened {
        var out = Opened()
        var rest = Substring(text)
        while let start = rest.range(of: head), let end = rest[start.upperBound...].range(of: tail) {
            out.text += rest[..<start.lowerBound]
            if let plain = openToken(rest[start.upperBound..<end.lowerBound], keys) {
                out.text += plain
                out.opened += 1
            } else {
                out.text += rest[start.lowerBound..<end.upperBound]
                out.locked += 1
            }
            rest = rest[end.upperBound...]
        }
        out.text += rest
        return out
    }

    /// Un texte prêt à afficher : un jeton qu'aucune de vos clés n'ouvre
    /// devient « 🔒🥦 ».
    public static func masked(_ text: String) -> String {
        guard hasToken(text) else { return text }
        var out = ""
        var rest = Substring(text)
        while let start = rest.range(of: head), let end = rest[start.lowerBound...].range(of: tail) {
            out += rest[..<start.lowerBound] + "🔒🥦"
            rest = rest[end.upperBound...]
        }
        return out + rest
    }
}
