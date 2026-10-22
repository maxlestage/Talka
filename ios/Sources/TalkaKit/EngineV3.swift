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
    /// connexion : le relais ne garde rien sur disque) : celui des anciens
    /// clients, puis celui de cet appareil (v4).
    func publishV3() {
        if let legacy = v3?.published(me) { relay.publishBundle(legacy.bundle, sig: legacy.sig) }
        if !dev.isEmpty, let mine = v3?.publishedDevice(me, dev: dev) {
            relay.publishBundle(mine.bundle, sig: mine.sig, dev: dev)
        }
    }

    /// Le paquet v3 d'un contact (de son appareil `dev` en v4), vérifié (lié
    /// à son code, et à l'appareil) ; gardé une heure en mémoire (dix
    /// minutes s'il n'en a pas).
    func bundleFor(_ peer: Contact, dev d: String = "") async -> KeyBundle? {
        let t = nowMs()
        let addr = TalkaV4.address(peer.code, d)
        if let cached = bundleCache[addr], t - cached.at < (cached.bundle != nil ? 3_600_000 : 600_000) {
            return cached.bundle
        }
        // Pas de réponse (réseau, ancien relais) : rien n'est retenu.
        guard let found = await relay.fetchBundle(peer.code) else { return nil }
        let none = found["none"] as? Bool ?? false
        // Celui des anciens clients.
        var legacy: KeyBundle?
        if let b = found["bundle"] as? String, let sig = found["sig"] as? String {
            legacy = V3.verifyBundle(code: peer.code, sign: peer.sign, dh: peer.dh, bundle: b, sig: sig)
        }
        // Et ceux de ses appareils (v4) : chacun gardé à son adresse.
        var mine: KeyBundle?
        for item in found["devices"] as? [[String: Any]] ?? [] {
            guard let id = item["dev"] as? String, let b = item["bundle"] as? String, let sig = item["sig"] as? String
            else { continue }
            guard let bundle = V3.verifyDeviceBundle(code: peer.code, sign: peer.sign, dh: peer.dh, dev: id, bundle: b, sig: sig) else {
                v3Log("v4 : paquet d'appareil refusé (signature ou format)")
                continue
            }
            if id == d { mine = bundle }
            bundleCache[TalkaV4.address(peer.code, id)] = (bundle, t)
        }
        let bundle = d.isEmpty ? legacy : mine
        if bundle == nil, d.isEmpty, !none { v3Log("v3 : paquet de clés refusé (signature ou format)") }
        bundleCache[addr] = (bundle, t)
        return bundle
    }

    /// Oublie les absences de paquet retenues pour `code` : à son code (un
    /// ancien client) et à chacun de ses appareils (`<code>/<dev>`). `true`
    /// s'il y en avait. Comme `forget_missing` du navigateur.
    func forgetMissingBundles(_ code: String) -> Bool {
        let stale = bundleCache.filter { ($0.key == code || $0.key.hasPrefix(code + "/")) && $0.value.bundle == nil }.keys
        for k in stale { bundleCache[k] = nil }
        return !stale.isEmpty
    }

    /// La protection de la discussion avec un contact (fiche du contact).
    /// Avant iOS 26 (pas de ML-KEM), une session neuve serait classique.
    public func protection(_ code: String) async -> V3.Protection {
        loadV3(code)
        if let p = v3?.sessionProtection(code) { return p }
        // v4 : la session avec l'un de ses appareils.
        for d in targets(code) where !d.isEmpty {
            let addr = TalkaV4.address(code, d)
            loadV3(addr)
            if let p = v3?.sessionProtection(addr) { return p }
        }
        if let peer = await keys(for: code), let bundle = await bundleFor(peer) {
            return .ratchet(pq: bundle.pq != nil && V3.postQuantum)
        }
        return .legacy
    }

    /// Une enveloppe v3 illisible parce que la session est perdue (d'un côté
    /// ou de l'autre) : on oublie nos sessions avec cet appareil et on lui
    /// envoie notre profil (ou, pour un de nos appareils, la liste des
    /// appareils), qui ouvre une session neuve ; il la prend, et ses messages
    /// suivants se lisent. Au plus une fois toutes les cinq minutes par
    /// appareil.
    func repairV3(_ code: String, dev d: String = "") {
        let addr = TalkaV4.address(code, d)
        let t = nowMs()
        if let at = repairs[addr], t - at < 300_000 { return }
        repairs[addr] = t
        v3?.forget(addr)
        saveV3()
        let body: Body = code == me.code ? .sync(op: "devices", d: jsonValue(devices)) : profileBody(for: code)
        if d.isEmpty {
            enqueue(code, Wire(body))
        } else {
            enqueueTo(code, dev: d, Wire(body))
        }
    }
}
