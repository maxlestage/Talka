// Le protocole v3 dans le moteur : l'appareil (gardé dans le trousseau sur
// iPhone), les sessions de chaque contact (dans la base, écrites avec le
// message qui les a fait avancer), le paquet de clés publié à chaque
// connexion et celui des contacts, demandé au relais. La même conception que
// le navigateur (`client/src/core.rs`).

import Foundation
import BrocolisProto

extension Engine {
    /// L'appareil v3 : relu, ou créé au premier démarrage d'une version qui
    /// sait faire v3.
    func startV3() {
        do {
            v3 = V3Engine(deviceJson: try loadV3Device(), now: V3.secs(nowMs()))
            saveV3()
        } catch {
            // Le trousseau ne répond pas : l'ancien format pour cette fois,
            // plutôt qu'un appareil neuf qui remplacerait le bon.
            v3Log("v3 : appareil illisible (\(error))")
        }
    }

    /// L'appareil gardé (son JSON), `nil` s'il n'y en a pas encore.
    func loadV3Device() throws -> String? {
        #if os(iOS)
        // Une sauvegarde restaurée apporte l'appareil dans la base : il passe
        // au trousseau.
        if let restored = store.readRaw("kv", V3.deviceKey) {
            if V3Vault.write(restored, code: me.code) { store.remove("kv", V3.deviceKey) }
            return restored
        }
        return try V3Vault.read(code: me.code)
        #else
        return store.readRaw("kv", V3.deviceKey)
        #endif
    }

    func saveV3Device(_ json: String) {
        #if os(iOS)
        if V3Vault.write(json, code: me.code) {
            store.remove("kv", V3.deviceKey)
            return
        }
        #endif
        store.writeRaw("kv", V3.deviceKey, json)
    }

    /// Écrit ce qui a changé dans l'état v3 (l'appareil, des sessions).
    func saveV3() {
        if let device = saveV3Sessions() { saveV3Device(device) }
    }

    /// Écrit les sessions qui ont changé ; renvoie l'appareil s'il a changé,
    /// à écrire ensuite (`saveV3Device`).
    func saveV3Sessions() -> String? {
        guard let dirty = v3?.takeDirty() else { return nil }
        for (code, json) in dirty.peers {
            let key = V3.sessionPrefix + code
            if json.isEmpty { store.remove("kv", key) } else { store.writeRaw("kv", key, json) }
        }
        return dirty.device
    }

    /// Les sessions d'un contact, lues de la base la première fois.
    func loadV3(_ code: String) {
        guard let v3, !v3.isLoaded(code) else { return }
        v3.load(code, json: store.readRaw("kv", V3.sessionPrefix + code))
    }

    /// Publie le paquet de clés v3, signé par la clé du code (à chaque
    /// connexion : le relais ne garde rien sur disque).
    func publishV3() {
        guard let (bundle, sig) = v3?.published(me) else { return }
        relay.publishBundle(bundle, sig: sig)
    }

    /// Le paquet v3 d'un contact, vérifié (lié à son code) ; gardé une heure
    /// en mémoire (dix minutes s'il n'en a pas).
    func bundleFor(_ peer: Contact) async -> KeyBundle? {
        let t = nowMs()
        if let cached = bundleCache[peer.code], t - cached.at < (cached.bundle != nil ? 3_600_000 : 600_000) {
            return cached.bundle
        }
        // Pas de réponse (réseau, ancien relais) : rien n'est retenu.
        guard let found = await relay.fetchBundle(peer.code) else { return nil }
        let none = found["none"] as? Bool ?? false
        var bundle: KeyBundle?
        if !none, let b = found["bundle"] as? String, let sig = found["sig"] as? String {
            bundle = V3.verifyBundle(code: peer.code, sign: peer.sign, dh: peer.dh, bundle: b, sig: sig)
        }
        if bundle == nil, !none { v3Log("v3 : paquet de clés refusé (signature ou format)") }
        bundleCache[peer.code] = (bundle, t)
        return bundle
    }

    /// La protection de la discussion avec un contact (fiche du contact).
    /// Avant iOS 26 (pas de ML-KEM), une session neuve serait classique.
    public func protection(_ code: String) async -> V3.Protection {
        loadV3(code)
        if let p = v3?.sessionProtection(code) { return p }
        if let peer = await keys(for: code), let bundle = await bundleFor(peer) {
            return .ratchet(pq: bundle.pq != nil && V3.postQuantum)
        }
        return .legacy
    }

    /// Une enveloppe v3 illisible parce que la session est perdue (d'un côté
    /// ou de l'autre) : on oublie nos sessions avec ce contact et on lui
    /// envoie notre profil, qui ouvre une session neuve ; il la prend, et ses
    /// messages suivants se lisent. Au plus une fois toutes les cinq minutes
    /// par contact.
    func repairV3(_ code: String) {
        let t = nowMs()
        if let at = repairs[code], t - at < 300_000 { return }
        repairs[code] = t
        v3?.forget(code)
        saveV3()
        enqueue(code, Wire(profileBody))
    }
}
