// La clé de l'appareil, qui chiffre la base des messages au repos (`Store`) :
// 32 octets tirés une fois, gardés par le système (le trousseau d'iOS, le
// Keystore d'Android), jamais dans la base, pour cet appareil seulement.
//
// Ici, ce qui ne dépend pas du système : quelle copie de la clé lire ou
// écrire, et la clé enveloppée par le code de verrouillage de Talka
// (`cle-code`, même format que `crypto::lock_device_key` du navigateur). La
// garde elle-même (`KeyGuard`) est branchée par l'application, avec le
// Keystore d'Android ; sans elle (les tests), pas de clé : la base reste en
// clair, comme sur l'iPhone hors d'iOS.
//
// Protégée par l'empreinte ou le visage (au choix) : la clé est alors gardée
// derrière une authentification de l'utilisateur (`presence` : biométrie ou
// code de l'appareil). Chiffrée par le code de verrouillage de Talka (dès
// qu'il y en a un, comme dans le navigateur) : la clé n'est plus gardée
// qu'enveloppée par Argon2id(code) ; la biométrie peut l'ouvrir aussi, par
// une seconde copie liée aux empreintes ou visages enregistrés (`biometry`).

package talka.kit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * La garde de la clé de l'appareil : ce que le trousseau est à l'iPhone
 * (le Keystore et les préférences de l'application sur Android). Chaque
 * copie est rangée sous un nom (`cle`, `cle-protegee`, `cle-code`,
 * `cle-visage`), pour cet appareil seulement, jamais sauvegardée.
 */
interface KeyGuard {
    /**
     * Qui peut lire une copie : Talka seule (`plain`, lisible en
     * arrière-plan, quand les messages arrivent), avec la biométrie ou le
     * code de l'appareil (`presence`), avec une empreinte ou un visage
     * enregistré seulement, pas un ajouté plus tard (`biometry`).
     */
    enum class Access { plain, presence, biometry }

    /** Ce que rend une lecture. */
    sealed class Read {
        class Found(val data: ByteArray) : Read()

        /** Aucune copie sous ce nom. */
        data object NotFound : Read()

        /** La garde ne répond pas (verrouillée, refusée) : surtout ne rien écraser. */
        data object Failed : Read()
    }

    /** Une copie ; `auth` : l'authentification déjà faite, s'il en faut une. */
    fun read(account: String, auth: Any?): Read

    /** Range une copie (elle remplace la précédente du même nom) ; `false` si refusé. */
    fun add(account: String, data: ByteArray, access: Access): Boolean

    fun delete(account: String)

    /** Les réglages de la protection (les `UserDefaults` de l'iPhone). */
    fun flag(name: String): Boolean

    fun setFlag(name: String, on: Boolean)

    fun removeFlag(name: String)
}

object StoreVault {
    internal const val protectedFlag = "talka.repos.protege"
    internal const val codeFlag = "talka.repos.code"
    internal const val faceFlag = "talka.repos.visage"

    /** La garde de la clé, branchée par l'application (rien : pas de clé). */
    @Volatile
    var keyGuard: KeyGuard? = null

    /** La clé ne s'ouvre-t-elle qu'avec la biométrie (ou le code de l'appareil) ? */
    val isProtected: Boolean get() = keyGuard?.flag(protectedFlag) ?: false

    /** La clé est-elle chiffrée par le code de verrouillage de Talka ? */
    val isCodeLocked: Boolean get() = keyGuard?.flag(codeFlag) ?: false

    /** La biométrie ouvre-t-elle aussi la clé chiffrée par le code ? */
    val hasFaceUnlock: Boolean get() = isCodeLocked && (keyGuard?.flag(faceFlag) ?: false)

    /**
     * La clé enveloppée par le code : sel et réglages d'Argon2id, puis
     * nonce (12) ‖ AES-256-GCM(clé, données associées « talka-cle »).
     */
    data class CodeLock(val salt: String, val m: UInt, val t: UInt, val data: String) : JsonEncodable {
        override fun toJson(): JsonElement =
            buildJsonObject {
                put("salt", salt)
                put("m", m)
                put("t", t)
                put("data", data)
            }

        companion object : JsonDecodable<CodeLock> {
            override fun fromJson(json: JsonElement): CodeLock {
                val c = json.obj()
                return CodeLock(c.reqStr("salt"), c.reqUInt("m"), c.reqUInt("t"), c.reqStr("data"))
            }
        }
    }

    /** Enveloppe `key` par `code` (Argon2id, comme le code du navigateur). */
    fun wrap(key: ByteArray, code: String): CodeLock? {
        val salt = randomBytes(16)
        val user = TalkaCrypto.argon2id(code, salt, TalkaCrypto.argonM, TalkaCrypto.argonT) ?: return null
        val data = TalkaCrypto.lockDeviceKey(user, key) ?: return null
        return CodeLock(b64(salt), TalkaCrypto.argonM, TalkaCrypto.argonT, b64(data))
    }

    /** La clé, si `code` est le bon (sinon rien : le chiffrement l'a refusé). */
    fun unwrap(lock: CodeLock, code: String): ByteArray? {
        if (lock.m !in 8u..(1u shl 20) || lock.t !in 1u..10u) return null
        val salt = unb64(lock.salt) ?: return null
        val data = unb64(lock.data) ?: return null
        val user = TalkaCrypto.argon2id(code, salt, lock.m, lock.t) ?: return null
        return TalkaCrypto.unlockDeviceKey(user, data)
    }

    /** Une copie de la clé (32 octets), et ce qu'a répondu la garde. */
    private fun read(g: KeyGuard, account: String, auth: Any?): Pair<KeyGuard.Read, ByteArray?> {
        val r = g.read(account, auth)
        val data = (r as? KeyGuard.Read.Found)?.data
        return r to data?.takeIf { it.size == 32 }
    }

    private fun add(g: KeyGuard, account: String, key: ByteArray, protected: Boolean): Boolean =
        g.add(account, key, if (protected) KeyGuard.Access.presence else KeyGuard.Access.plain)

    /**
     * La clé de l'appareil ; rien sans garde, ou si la garde ne répond pas
     * (alors surtout ne pas en tirer une autre : `Store` passe en lecture
     * seule s'il trouve des lignes chiffrées). Protégée par la biométrie,
     * il faut `auth`, déjà authentifié.
     */
    fun key(auth: Any? = null): ByteArray? {
        val g = keyGuard ?: return null
        if (isProtected) return read(g, "cle-protegee", auth).second
        // Chiffrée par le code : il faut `key(code)` (ou `keyByFace`).
        if (isCodeLocked) return null
        val (status, key) = read(g, "cle", null)
        if (key != null) return key
        if (status != KeyGuard.Read.NotFound) return null
        val fresh = randomBytes(32)
        if (!add(g, "cle", fresh, protected = false)) {
            // Un autre processus l'a écrite entre-temps : la sienne.
            return read(g, "cle", null).second
        }
        return fresh
    }

    /** La clé chiffrée par le code, si `code` est le bon. Lent (Argon2id) : hors du fil principal. */
    fun key(code: String): ByteArray? {
        val g = keyGuard ?: return null
        if (!isCodeLocked) return null
        val data = (g.read("cle-code", null) as? KeyGuard.Read.Found)?.data ?: return null
        val lock = utf8(data)?.let { JSON.decode(CodeLock, it) } ?: return null
        return unwrap(lock, code)
    }

    /** La clé par la biométrie seule (`auth` déjà authentifié par une empreinte ou un visage). */
    fun keyByFace(auth: Any): ByteArray? {
        val g = keyGuard ?: return null
        if (!hasFaceUnlock) return null
        return read(g, "cle-visage", auth).second
    }

    /**
     * Chiffre la clé par `code` (et oublie toute copie en clair ou derrière
     * la biométrie) ; rien : la clé redevient lisible par Talka seule. La
     * nouvelle copie est écrite avant que les anciennes partent.
     */
    fun setCode(code: String?, key: ByteArray): Boolean {
        val g = keyGuard ?: return false
        if (code != null) {
            val lock = wrap(key, code) ?: return false
            if (!g.add("cle-code", JSON.encode(lock).toByteArray(), KeyGuard.Access.plain)) return false
            g.setFlag(codeFlag, true)
            g.setFlag(protectedFlag, false)
            g.delete("cle")
            g.delete("cle-protegee")
            return true
        }
        if (!add(g, "cle", key, protected = false)) return false
        g.setFlag(codeFlag, false)
        g.setFlag(faceFlag, false)
        g.delete("cle-code")
        g.delete("cle-visage")
        return true
    }

    /** La biométrie ouvre aussi la clé chiffrée par le code (ou plus). */
    fun setFaceUnlock(on: Boolean, key: ByteArray): Boolean {
        val g = keyGuard ?: return false
        if (!isCodeLocked) return false
        if (on) {
            if (!g.add("cle-visage", key, KeyGuard.Access.biometry)) return false
        } else {
            g.delete("cle-visage")
        }
        g.setFlag(faceFlag, on)
        return true
    }

    /** Tout est effacé : plus aucune copie de la clé (une nouvelle sera tirée), plus aucune protection. */
    fun reset() {
        val g = keyGuard ?: return
        for (account in listOf("cle", "cle-protegee", "cle-code", "cle-visage")) g.delete(account)
        for (flag in listOf(protectedFlag, codeFlag, faceFlag)) g.removeFlag(flag)
    }

    /**
     * Protège la clé par la biométrie, ou retire cette protection. La
     * nouvelle copie est écrite avant que l'ancienne soit effacée : jamais de
     * clé perdue en route. `false` si la garde a refusé.
     */
    fun setProtected(on: Boolean, key: ByteArray): Boolean {
        val g = keyGuard ?: return false
        if (on == isProtected) return true
        val (new, old) = if (on) "cle-protegee" to "cle" else "cle" to "cle-protegee"
        if (!add(g, new, key, protected = on)) return false
        g.setFlag(protectedFlag, on)
        g.delete(old)
        return true
    }
}
