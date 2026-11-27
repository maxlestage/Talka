// Le cliquet post-quantique de Talka (v6, docs/SECURITE.md §7.17), comme
// `client/src/pq6.rs` et l'iPhone (`Pq6.swift`) : chaque appareil a une clé
// ML-KEM-768 qui tourne (une neuve dès que la courante a servi et a plus
// d'une heure, les anciennes gardées 30 jours), l'annonce dans ses messages
// v3 (le champ `pq6` du `Wire`), et chaque jeton v3 vers un appareil dont on
// connaît la clé est enveloppé par une encapsulation neuve :
// `[[TALKA:PQ6:<base64(1 ‖ id ‖ CT ‖ nonce ‖ AES-256-GCM(K, jeton))>]]`,
// `K = HKDF-SHA256(SS, info = "talka-pq6-cle:" ‖ id ‖ CT)`.
//
// ML-KEM vient de BouncyCastle (module `brocolis`) : toujours disponible sur
// Android, contrairement à l'iPhone d'avant iOS 26.

package talka.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import talka.brocolis.MlKem

object Pq6 {
    const val head = "[[TALKA:PQ6:"

    /** Les clés de cet appareil dans `kv` : propres à l'appareil. */
    const val keysKey = "pq6"
    const val rotateSecs: ULong = 3_600uL // 60 × 60
    const val keepSecs: ULong = 2_592_000uL // 30 × 24 × 60 × 60
    const val maxKeys = 200
    internal const val version: Byte = 1

    /** Cet appareil sait-il faire le cliquet post-quantique ? */
    val available: Boolean get() = MlKem.isAvailable

    /** `SHA-256("talka-pq6-id:" ‖ ek)[0..8]`, en hexadécimal. */
    fun keyId(ek: ByteArray): String = hex(sha256("talka-pq6-id:".toByteArray() + ek).copyOf(8))

    fun isWrapped(text: String): Boolean = text.startsWith(head) && text.endsWith("]]")

    internal fun aad(id: ByteArray): ByteArray = "talka-pq6:".toByteArray() + id

    internal fun key(ss: ByteArray, id: ByteArray, ct: ByteArray): ByteArray =
        hkdfSha256(ss, ByteArray(0), "talka-pq6-cle:".toByteArray() + id + ct)

    /** Enveloppe `token` pour la clé `ek` (base64). */
    fun wrap(ek: String, token: String): String? {
        val ekBytes = unb64(ek) ?: return null
        val kem =
            try {
                MlKem.encapsulate(ekBytes)
            } catch (e: Exception) {
                return null
            }
        val idBytes = unhex(keyId(ekBytes)) ?: return null
        val nonce = randomBytes(12)
        val box = AesGcm.sealParts(key(kem.shared, idBytes, kem.ciphertext), nonce, token.toByteArray(), aad(idBytes))
            ?: return null
        val bytes = byteArrayOf(version) + idBytes + kem.ciphertext + nonce + box
        return head + b64(bytes) + "]]"
    }

    /** La clé annoncée par un appareil. */
    data class Advert(val id: String, val ek: String, val at: ULong)

    /** La clé annoncée par un appareil : `(id, ek, at)`, si elle est bien formée. */
    fun parseAdvert(v: JsonElement?): Advert? {
        val o = v as? JsonObject ?: return null
        val id = o["id"].asString() ?: return null
        val ek = o["ek"].asString() ?: return null
        val bytes = unb64(ek) ?: return null
        if (bytes.size != MlKem.publicKeyLength || keyId(bytes) != id) return null
        return Advert(id, ek, number(o["at"]) ?: 0uL)
    }

    /**
     * Un nombre JSON en entier non signé, comme `NSNumber.uint64Value` (un
     * booléen compte pour 0 ou 1, une virgule est tronquée) ; rien si ce
     * n'est pas un nombre.
     */
    private fun number(e: JsonElement?): ULong? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        when (p.content) {
            "true" -> return 1uL
            "false" -> return 0uL
        }
        p.content.toLongOrNull()?.let { return it.toULong() }
        p.content.toULongOrNull()?.let { return it }
        val d = p.content.toDoubleOrNull() ?: return null
        return if (d < 0) d.toLong().toULong() else d.toULong()
    }

    /** Ajoute l'annonce au `Wire` JSON `plain` (s'il en est un). */
    internal fun withAdvert(plain: String, advert: JsonObject): String? {
        val o = JSON.parse(plain) as? JsonObject ?: return null
        return JSON.write(JsonObject(o + ("pq6" to advert)))
    }

    /** Une clé ML-KEM de cet appareil. */
    data class Key(
        val id: String,
        val seed: String,
        val at: ULong,
        var replaced: ULong? = null,
        var used: Boolean = false,
    ) : JsonEncodable {
        internal val seedBytes: ByteArray? get() = unb64(seed)

        override fun toJson(): JsonElement =
            buildJsonObject {
                put("id", id)
                put("seed", seed)
                put("at", JsonPrimitive(at.toLong()))
                replaced?.let { put("replaced", JsonPrimitive(it.toLong())) }
                put("used", used)
            }

        companion object : JsonDecodable<Key> {
            /** La clé d'une graine ML-KEM (64 octets) ; rien si elle n'en est pas une. */
            fun fromSeed(seed: ByteArray, at: ULong): Key? {
                val ek =
                    try {
                        MlKem.publicKey(seed)
                    } catch (e: Exception) {
                        return null
                    }
                return Key(keyId(ek), b64(seed), at)
            }

            /** Comme `Codable` : `id`, `seed` et `at` obligatoires ; un champ du mauvais type, illisible. */
            override fun fromJson(json: JsonElement): Key {
                val c = json.obj()
                val replaced = c["replaced"]?.takeIf { it !is JsonNull }?.let { u64(it) ?: throw JSONError("replaced") }
                val used = c["used"]?.takeIf { it !is JsonNull }?.let { it.asBool() ?: throw JSONError("used") } ?: false
                return Key(c.reqStr("id"), c.reqStr("seed"), u64(c["at"]) ?: throw JSONError("at manque"), replaced, used)
            }
        }
    }

    /** Les clés de cet appareil (`kv/pq6`) : la dernière est la courante. */
    class Keys(keys: List<Key> = emptyList()) : JsonEncodable {
        val keys: MutableList<Key> = keys.map { it.copy() }.toMutableList()

        val current: Key? get() = keys.lastOrNull()

        /**
         * Une clé neuve si aucune, ou si la courante a servi et a plus d'une
         * heure ; les clés remplacées depuis plus de 30 jours sont effacées.
         * `true` : quelque chose a changé.
         */
        fun maintain(now: ULong, seed: () -> ByteArray = { randomBytes(64) }): Boolean {
            var changed = false
            val stale = current?.let { it.used && now >= satAdd(it.at, rotateSecs) } ?: true
            if (stale) {
                val fresh = Key.fromSeed(seed(), now)
                if (fresh != null) {
                    if (keys.isNotEmpty()) keys[keys.size - 1].replaced = now
                    keys.add(fresh)
                    changed = true
                }
            }
            val before = keys.size
            keys.removeAll { k -> k.replaced?.let { now >= satAdd(it, keepSecs) } ?: false }
            if (keys.size > maxKeys) repeat(keys.size - maxKeys) { keys.removeAt(0) }
            return changed || keys.size != before
        }

        /** `{"id","ek","at"}` de la clé courante. */
        val advert: JsonObject?
            get() {
                val k = current ?: return null
                val seed = k.seedBytes ?: return null
                val ek =
                    try {
                        MlKem.publicKey(seed)
                    } catch (e: Exception) {
                        return null
                    }
                return buildJsonObject {
                    put("id", k.id)
                    put("ek", b64(ek))
                    put("at", JsonPrimitive(k.at.toLong()))
                }
            }

        enum class OpenError {
            /** La clé est inconnue (effacée, ou d'avant une restauration). */
            unknownKey,

            /** Le message est abîmé. */
            damaged,
        }

        /** Ce que donne l'ouverture d'un jeton enveloppé. */
        sealed class Opened {
            /** Le jeton, l'id de la clé, et si c'est la première fois qu'elle sert. */
            data class Success(val token: String, val id: String, val first: Boolean) : Opened()

            data class Failure(val error: OpenError) : Opened()
        }

        /** Ouvre un jeton enveloppé : (jeton, id de la clé, première fois qu'elle sert). */
        fun open(wrapped: String): Opened {
            val damaged = Opened.Failure(OpenError.damaged)
            if (!isWrapped(wrapped)) return damaged
            val bytes = unb64(wrapped.substring(head.length, wrapped.length - 2)) ?: return damaged
            if (bytes.size < 1 + 8 + MlKem.ciphertextLength + 12 + 16 || bytes[0] != version) return damaged
            val id = bytes.copyOfRange(1, 9)
            val ctEnd = 9 + MlKem.ciphertextLength
            val ct = bytes.copyOfRange(9, ctEnd)
            val nonce = bytes.copyOfRange(ctEnd, ctEnd + 12)
            val sealed = bytes.copyOfRange(ctEnd + 12, bytes.size)
            val idHex = hex(id)
            val i = keys.indexOfFirst { it.id == idHex }
            val seed = if (i >= 0) keys[i].seedBytes else null
            if (seed == null) return Opened.Failure(OpenError.unknownKey)
            val ss =
                try {
                    MlKem.decapsulate(seed, ct)
                } catch (e: Exception) {
                    return damaged
                }
            val plain = AesGcm.openParts(key(ss, id, ct), nonce, sealed, aad(id)) ?: return damaged
            val text = utf8(plain) ?: return damaged
            val first = !keys[i].used
            keys[i].used = true
            return Opened.Success(text, idHex, first)
        }

        override fun toJson(): JsonElement = buildJsonObject { putList("keys", keys) }

        override fun equals(other: Any?): Boolean = other is Keys && keys == other.keys

        override fun hashCode(): Int = keys.hashCode()

        override fun toString(): String = "Keys(${keys.map { it.id }})"

        companion object : JsonDecodable<Keys> {
            /** Comme `Codable` : une clé illisible rend tout illisible. */
            override fun fromJson(json: JsonElement): Keys {
                val c = json.obj()
                val list = c["keys"]?.takeIf { it !is JsonNull } ?: return Keys()
                val a = list as? JsonArray ?: throw JSONError("keys")
                return Keys(a.map { Key.fromJson(it) })
            }
        }
    }

    internal fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** Un entier non signé de 64 bits en JSON (`UInt64` de `Codable`) ; rien s'il n'en est pas un. */
internal fun u64(e: JsonElement?): ULong? {
    val p = e as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    p.content.toULongOrNull()?.let { return it }
    return e.asLong()?.takeIf { it >= 0 }?.toULong()
}

/** Une addition qui s'arrête au maximum au lieu de déborder. */
internal fun satAdd(a: ULong, b: ULong): ULong = if (ULong.MAX_VALUE - a < b) ULong.MAX_VALUE else a + b

/**
 * Le cliquet post-quantique avec un appareil : sa clé annoncée, la dernière
 * des nôtres dont il s'est servi, la dernière que nous lui avons annoncée.
 */
internal data class PqPeer(
    var id: String = "",
    var ek: String = "",
    var at: ULong = 0uL,
    var told: String = "",
    var sent: String = "",
    var sentAt: ULong = 0uL,
) : JsonEncodable {
    val isEmpty: Boolean get() = this == PqPeer()

    override fun toJson(): JsonElement =
        buildJsonObject {
            put("id", id)
            put("ek", ek)
            put("at", JsonPrimitive(at.toLong()))
            put("told", told)
            put("sent", sent)
            put("sent_at", JsonPrimitive(sentAt.toLong()))
        }

    companion object : JsonDecodable<PqPeer> {
        /** Chaque champ est facultatif ; présent mais du mauvais type : illisible (comme `Codable`). */
        override fun fromJson(json: JsonElement): PqPeer {
            val c = json.obj()
            fun s(k: String): String = c[k]?.takeIf { it !is JsonNull }?.let { it.asString() ?: throw JSONError(k) } ?: ""
            fun n(k: String): ULong = c[k]?.takeIf { it !is JsonNull }?.let { u64(it) ?: throw JSONError(k) } ?: 0uL
            return PqPeer(s("id"), s("ek"), n("at"), s("told"), s("sent"), n("sent_at"))
        }
    }
}
