// L'Apple Watch, côté iPhone : WatchConnectivity.
//
// L'iPhone reste l'appareil Talka (les clés, le relais) : il envoie à la
// montre le résumé des discussions récentes dès qu'il change, et exécute ce
// que l'on fait sur la montre (répondre, réagir, marquer comme lu). Voir
// TalkaKit/WatchLink.swift.

import Foundation
import TalkaKit
import WatchConnectivity

@MainActor
final class WatchBridge: NSObject {
    static let shared = WatchBridge()

    private weak var model: AppModel?
    private let session: WCSession? = WCSession.isSupported() ? .default : nil
    private var lastSent: Data?
    private var timer: Timer?

    func start(_ model: AppModel) {
        self.model = model
        guard let session, timer == nil else { return }
        session.delegate = self
        session.activate()
        // Le résumé n'est recalculé que si une montre a l'application ; il
        // ne part que s'il a changé.
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in
            Task { @MainActor in WatchBridge.shared.push() }
        }
    }

    var watchReady: Bool {
        guard let session, session.activationState == .activated else { return false }
        return session.isPaired && session.isWatchAppInstalled
    }

    func push(force: Bool = false) {
        guard let session, watchReady, let e = model?.engine,
              let data = WatchLink.encode(e.watchSnapshot()) else { return }
        guard force || data != lastSent else { return }
        lastSent = data
        // Le dernier résumé, que la montre retrouve même en se réveillant plus tard…
        try? session.updateApplicationContext([WatchLink.snapshotKey: data])
        // … et tout de suite, si elle est à l'écran.
        if session.isReachable {
            session.sendMessage([WatchLink.snapshotKey: data], replyHandler: nil, errorHandler: nil)
        }
    }

    /// Une action venue de la montre ; renvoie le résumé à jour.
    func handle(_ data: Data?) -> Data? {
        guard let e = model?.engine else { return nil }
        if let action = WatchLink.decode(WatchAction.self, data) {
            e.perform(action)
            // Réveillé en arrière-plan par la montre : le message part tout de suite.
            if action.kind == .send { e.relay.wake() }
        }
        let snapshot = WatchLink.encode(e.watchSnapshot())
        lastSent = snapshot
        if let snapshot, let session, watchReady {
            try? session.updateApplicationContext([WatchLink.snapshotKey: snapshot])
        }
        return snapshot
    }
}

extension WatchBridge: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        Task { @MainActor in self.push(force: true) }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    /// Une autre montre a été choisie : on se relie à elle.
    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.push(force: true) }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in self.push(force: true) }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                             replyHandler: @escaping ([String: Any]) -> Void) {
        let data = message[WatchLink.actionKey] as? Data
        Task { @MainActor in
            let snapshot = self.handle(data)
            replyHandler(snapshot.map { [WatchLink.snapshotKey: $0] } ?? [:])
        }
    }

    /// Les actions faites quand l'iPhone n'était pas joignable, livrées dans l'ordre.
    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        let data = userInfo[WatchLink.actionKey] as? Data
        Task { @MainActor in _ = self.handle(data) }
    }
}
