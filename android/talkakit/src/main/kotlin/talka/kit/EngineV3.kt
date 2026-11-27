// Le protocole v3 dans le moteur : l'appareil (gardé par le système quand
// l'application a branché `V3Vault`), les sessions de chaque contact (dans
// la base, écrites avec le message qui les a fait avancer), le paquet de
// clés publié à chaque connexion et celui des contacts, demandé au relais.
// La même conception que le navigateur (`client/src/core.rs`) et que
// l'iPhone (`EngineV3.swift`).

package talka.kit

import kotlinx.coroutines.withContext
import talka.brocolis.KeyBundle

/** L'appareil v3 : relu, ou créé au premier démarrage d'une version qui sait faire v3. */
internal fun Engine.startV3() {
    try {
        val engine = V3Engine(loadV3Device(), V3.secs(nowMs()))
        // Le cliquet post-quantique (v6, §7.17), s'il y a ML-KEM.
        if (Pq6.available) engine.startPq(store.readRaw("kv", Pq6.keysKey), V3.secs(nowMs()))
        v3 = engine
        saveV3()
    } catch (e: Exception) {
        // La garde ne répond pas : l'ancien format pour cette fois, plutôt
        // qu'un appareil neuf qui remplacerait le bon.
        v3Log("v3 : appareil illisible ($e)")
    }
}

/** L'appareil gardé (son JSON), rien s'il n'y en a pas encore ; `V3Vault.Unreadable` si la garde ne répond pas. */
@Throws(V3Vault.Unreadable::class)
internal fun Engine.loadV3Device(): String? {
    if (V3Vault.isAvailable) {
        // Une sauvegarde restaurée apporte l'appareil dans la base : il passe
        // à la garde du système.
        store.readRaw("kv", V3.deviceKey)?.let { restored ->
            if (V3Vault.write(restored, me.code)) store.remove("kv", V3.deviceKey)
            return restored
        }
        return V3Vault.read(me.code)
    }
    return store.readRaw("kv", V3.deviceKey)
}

internal fun Engine.saveV3Device(json: String) {
    if (V3Vault.isAvailable && V3Vault.write(json, me.code)) {
        store.remove("kv", V3.deviceKey)
        return
    }
    store.writeRaw("kv", V3.deviceKey, json)
}

/** Écrit ce qui a changé dans l'état v3 (l'appareil, des sessions). */
internal fun Engine.saveV3() {
    saveV3Sessions()?.let { saveV3Device(it) }
}

/** Écrit les sessions qui ont changé ; renvoie l'appareil s'il a changé, à écrire ensuite (`saveV3Device`). */
internal fun Engine.saveV3Sessions(): String? {
    val dirty = v3?.takeDirty() ?: return null
    dirty.pq?.let { store.writeRaw("kv", Pq6.keysKey, it) }
    for ((code, json) in dirty.peers) {
        val key = V3.sessionPrefix + code
        if (json.isEmpty()) store.remove("kv", key) else store.writeRaw("kv", key, json)
    }
    return dirty.device
}

/** Les sessions d'un contact, lues de la base la première fois. */
internal fun Engine.loadV3(code: String) {
    val engine = v3 ?: return
    if (engine.isLoaded(code)) return
    engine.load(code, store.readRaw("kv", V3.sessionPrefix + code))
}

/**
 * Publie le paquet de clés v3, signé par la clé du code (à chaque
 * connexion : le relais ne garde rien sur disque) : celui des anciens
 * clients, puis celui de cet appareil (v4).
 */
internal fun Engine.publishV3() {
    v3?.published(me)?.let { relay.publishBundle(it.bundle, it.sig) }
    if (dev.isNotEmpty()) v3?.publishedDevice(me, dev)?.let { relay.publishBundle(it.bundle, it.sig, dev) }
}

/**
 * Le paquet v3 d'un contact (de son appareil `d` en v4), vérifié (lié à
 * son code, et à l'appareil) ; gardé une heure en mémoire (dix minutes
 * s'il n'en a pas).
 */
internal suspend fun Engine.bundleFor(peer: Contact, d: String = ""): KeyBundle? {
    val t = nowMs()
    val addr = TalkaV4.address(peer.code, d)
    bundleCache[addr]?.let { cached ->
        if (t - cached.at < (if (cached.bundle != null) 3_600_000 else 600_000)) return cached.bundle
    }
    // Pas de réponse (réseau, ancien relais) : rien n'est retenu.
    val found = relay.fetchBundle(peer.code) ?: return null
    val none = found["none"].asBool() ?: false
    // Celui des anciens clients.
    var legacy: KeyBundle? = null
    val b = found["bundle"].asString()
    val sig = found["sig"].asString()
    if (b != null && sig != null) legacy = V3.verifyBundle(peer.code, peer.sign, peer.dh, b, sig)
    // Et ceux de ses appareils (v4) : chacun gardé à son adresse.
    var mine: KeyBundle? = null
    for (item in objectList(found["devices"])) {
        val id = item["dev"].asString() ?: continue
        val ib = item["bundle"].asString() ?: continue
        val isig = item["sig"].asString() ?: continue
        val bundle = V3.verifyDeviceBundle(peer.code, peer.sign, peer.dh, id, ib, isig)
        if (bundle == null) {
            v3Log("v4 : paquet d'appareil refusé (signature ou format)")
            continue
        }
        if (id == d) mine = bundle
        bundleCache[TalkaV4.address(peer.code, id)] = CachedBundle(bundle, t)
    }
    val bundle = if (d.isEmpty()) legacy else mine
    if (bundle == null && d.isEmpty() && !none) v3Log("v3 : paquet de clés refusé (signature ou format)")
    bundleCache[addr] = CachedBundle(bundle, t)
    return bundle
}

/**
 * Oublie les absences de paquet retenues pour `code` : à son code (un
 * ancien client) et à chacun de ses appareils (`<code>/<dev>`). `true`
 * s'il y en avait. Comme `forget_missing` du navigateur.
 */
internal fun Engine.forgetMissingBundles(code: String): Boolean {
    val stale = bundleCache.filter { (it.key == code || it.key.startsWith("$code/")) && it.value.bundle == null }.keys
    for (k in stale) bundleCache.remove(k)
    return stale.isNotEmpty()
}

/** La protection de la discussion avec un contact (fiche du contact). */
suspend fun Engine.protection(code: String): V3.Protection =
    withContext(dispatcher) {
        loadV3(code)
        for (d in targets(code)) if (d.isNotEmpty()) loadV3(TalkaV4.address(code, d))
        if (v3?.pqWithAny(code) == true) return@withContext V3.Protection.RatchetPq6
        v3?.sessionProtection(code)?.let { return@withContext it }
        // v4 : la session avec l'un de ses appareils.
        for (d in targets(code)) {
            if (d.isEmpty()) continue
            val addr = TalkaV4.address(code, d)
            loadV3(addr)
            v3?.sessionProtection(addr)?.let { return@withContext it }
        }
        val peer = keys(code)
        val bundle = peer?.let { bundleFor(it) }
        if (bundle != null) return@withContext V3.Protection.Ratchet(bundle.pq != null && V3.postQuantum)
        V3.Protection.Legacy
    }

/**
 * Une enveloppe v3 illisible parce que la session est perdue (d'un côté
 * ou de l'autre) : on oublie nos sessions avec cet appareil et on lui
 * envoie notre profil (ou, pour un de nos appareils, la liste des
 * appareils), qui ouvre une session neuve ; il la prend, et ses messages
 * suivants se lisent. Au plus une fois toutes les cinq minutes par
 * appareil.
 */
internal fun Engine.repairV3(code: String, d: String = "") {
    val addr = TalkaV4.address(code, d)
    val t = nowMs()
    repairs[addr]?.let { if (t - it < 300_000) return }
    repairs[addr] = t
    v3?.forget(addr)
    saveV3()
    val body: Body = if (code == me.code) Body.Sync("devices", d = jsonValue(devices)) else profileBody(code)
    if (d.isEmpty()) {
        enqueue(code, Wire(body))
    } else {
        enqueueTo(code, d, Wire(body))
    }
}
