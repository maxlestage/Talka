// L'Apple Watch, côté iPhone : le résumé envoyé à la montre et les actions
// qu'elle renvoie (voir WatchLink.swift).

import Foundation

extension Engine {
    /// Les discussions récentes pour la montre : les épinglées d'abord, puis
    /// les plus récentes, sans les archivées ; les derniers messages de chacune.
    public func watchSnapshot(chats limit: Int = 20, messages perChat: Int = 15) -> WatchSnapshot {
        let list = chats.values
            .filter { !$0.archived && ($0.last != nil || $0.group) }
            .sorted { a, b in
                if a.pinned != b.pinned { return a.pinned }
                return max(a.lastAt, a.last?.at ?? 0) > max(b.lastAt, b.last?.at ?? 0)
            }
            .prefix(limit)
        let now = nowMs()
        return WatchSnapshot(
            name: profile.name,
            online: netState == .online,
            chats: list.map { c in
                var w = WatchChat(id: c.id)
                w.title = title(c)
                w.initials = watchInitials(w.title)
                w.color = watchColor(c.id)
                w.group = c.group
                w.unread = c.unread
                w.muted = c.muted
                w.pinned = c.pinned
                w.readOnly = c.group ? (c.left || !c.members.contains(me.code)) : blocked(c.id)
                w.at = max(c.lastAt, c.last?.at ?? 0)
                w.last = c.last.map { l in
                    c.group && l.from != me.code && l.from != "system" ? "\(displayName(l.from)) : \(l.text)" : l.text
                } ?? ""
                w.messages = messages(c.id, limit: perChat)
                    .filter { $0.exp.map { $0 > now } ?? true }
                    .map { watchMessage($0, group: c.group) }
                return w
            }
        )
    }

    func watchMessage(_ m: Message, group: Bool) -> WatchMessage {
        var w = WatchMessage(id: m.id)
        w.mine = m.from == me.code
        w.system = m.kind == "system" || m.from == "system"
        w.author = group && !w.mine && !w.system ? displayName(m.from) : ""
        // Une capsule ouverte (ou écrite par soi) se lit en entier ; scellée,
        // elle ne montre que « ⏳ Capsule temporelle ».
        let text = m.kind == "text" && !m.deleted && !m.sealed(for: me.code) ? m.text : Self.preview(m)
        w.text = text.count > 600 ? String(text.prefix(600)) + "…" : text
        w.at = m.at
        if w.mine, !m.deleted { w.tick = m.tick.rawValue }
        let counts = Dictionary(grouping: m.reactions.values, by: { $0 })
            .map { ($0.key, $0.value.count) }
            .sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0 < $1.0 }
        w.reactions = counts.map { $0.1 > 1 ? "\($0.0)\($0.1)" : $0.0 }.joined(separator: " ")
        w.myReaction = m.reactions[me.code] ?? ""
        return w
    }

    /// Exécute ce que l'on a fait sur la montre. `false` : impossible (la
    /// discussion n'existe plus, ou on ne peut plus y écrire).
    @discardableResult
    public func perform(_ action: WatchAction) -> Bool {
        switch action.kind {
        case .refresh:
            return true
        case .read:
            guard chats[action.chat] != nil else { return false }
            markRead(action.chat)
            return true
        case .react:
            guard chats[action.chat] != nil, store.message(action.chat, action.id) != nil else { return false }
            react(action.chat, action.id, action.text)
            return true
        case .send:
            let text = action.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, let c = chats[action.chat] else { return false }
            let readOnly = c.group ? (c.left || !c.members.contains(me.code)) : blocked(c.id)
            guard !readOnly else { return false }
            return send(c.id, Content(text: text)) != nil
        }
    }
}

/// Les initiales d'un nom : « Léa Martin » → « LM », « ~Léa » → « L ».
func watchInitials(_ name: String) -> String {
    let words = name.split(whereSeparator: { !$0.isLetter && !$0.isNumber })
    return words.prefix(2).compactMap { $0.first.map { String($0).uppercased() } }.joined()
}

/// La couleur d'avatar d'un identifiant (8 couleurs, comme partout ailleurs).
func watchColor(_ id: String) -> Int {
    Int(id.unicodeScalars.reduce(UInt32(0)) { $0 &* 31 &+ $1.value } % 8)
}
