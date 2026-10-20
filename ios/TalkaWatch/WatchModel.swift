// L'état de Talka sur la montre : le dernier résumé reçu de l'iPhone
// (gardé pour l'afficher même loin de lui), et l'envoi des actions.

import Foundation
import Observation
import WatchConnectivity
import WidgetKit

@MainActor
@Observable
final class WatchModel: NSObject {
    private(set) var snapshot: WatchSnapshot?
    /// L'iPhone répond-il en ce moment ?
    private(set) var reachable = false

    private let session: WCSession? = WCSession.isSupported() ? .default : nil
    private static let savedKey = "talka.watch.snapshot"

    override init() {
        super.init()
        snapshot = WatchLink.decode(WatchSnapshot.self, UserDefaults.standard.data(forKey: Self.savedKey))
        session?.delegate = self
        session?.activate()
    }

    func chat(_ id: String) -> WatchChat? {
        snapshot?.chats.first { $0.id == id }
    }

    func receive(_ data: Data?) {
        guard let s = WatchLink.decode(WatchSnapshot.self, data), s != snapshot else { return }
        snapshot = s
        UserDefaults.standard.set(data, forKey: Self.savedKey)
        updateWidgets()
    }

    /// Les complications : rechargées seulement si elles ont changé.
    private func updateWidgets() {
        guard let s = snapshot else { return }
        if WidgetShare.write(WidgetData(s, hidden: s.hidden ?? false)) {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    // ----- Les actions -----

    func send(_ chat: String, _ text: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        // Affiché tout de suite, avec l'horloge : l'iPhone confirme ensuite.
        update(chat) { c in
            var m = WatchMessage(id: "montre-\(UUID().uuidString)")
            m.mine = true
            m.text = text
            m.at = Date().timeIntervalSince1970 * 1000
            m.tick = "pending"
            c.messages.append(m)
            c.last = text
            c.at = m.at
        }
        perform(WatchAction(.send, chat: chat, text: text))
    }

    func react(_ chat: String, _ id: String, _ emoji: String) {
        perform(WatchAction(.react, chat: chat, id: id, text: emoji))
    }

    func markRead(_ chat: String) {
        guard let c = self.chat(chat), c.unread > 0 else { return }
        update(chat) { $0.unread = 0 }
        updateWidgets()
        perform(WatchAction(.read, chat: chat))
    }

    func refresh() {
        perform(WatchAction(.refresh))
    }

    private func update(_ chat: String, _ f: (inout WatchChat) -> Void) {
        guard var s = snapshot, let i = s.chats.firstIndex(where: { $0.id == chat }) else { return }
        f(&s.chats[i])
        snapshot = s
    }

    /// Tout de suite si l'iPhone répond ; sinon, mis en file : livré dès
    /// qu'il est de nouveau là, dans l'ordre.
    private func perform(_ action: WatchAction) {
        guard let session, let data = WatchLink.encode(action) else { return }
        let message = [WatchLink.actionKey: data]
        if session.activationState == .activated, session.isReachable {
            session.sendMessage(message, replyHandler: { reply in
                let snapshot = reply[WatchLink.snapshotKey] as? Data
                Task { @MainActor in self.receive(snapshot) }
            }, errorHandler: { _ in
                if action.kind != .refresh { session.transferUserInfo(message) }
            })
        } else if action.kind != .refresh {
            session.transferUserInfo(message)
        }
    }
}

extension WatchModel: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        let context = session.receivedApplicationContext[WatchLink.snapshotKey] as? Data
        let reachable = session.isReachable
        Task { @MainActor in
            self.reachable = reachable
            self.receive(context)
            self.refresh()
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        Task { @MainActor in
            self.reachable = reachable
            if reachable { self.refresh() }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        let data = context[WatchLink.snapshotKey] as? Data
        Task { @MainActor in self.receive(data) }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        let data = message[WatchLink.snapshotKey] as? Data
        Task { @MainActor in self.receive(data) }
    }
}
