// La clé de l'appareil, qui chiffre la base des messages au repos : ici, ce
// qui ne dépend pas du système, la clé enveloppée par le code de
// verrouillage de Talka (`cle-code`, même format que
// `crypto::lock_device_key` du navigateur). Le trousseau (le Keystore
// d'Android) viendra avec l'application.

package talka.kit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object StoreVault {
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
}
