// Une clé Brocolis (32 octets) protégée par une phrase secrète (§3.2, §7.1),
// pour la sortir de l'appareil sans la montrer (`brocolis_proto::cle_protegee`).
//
// - `[[BROCOLIS-CLE:v1:<base64>]]` (lecture seulement) : sel (16) ‖ nonce
//   (12) ‖ AES-256-GCM(clé) ‖ tag, sous PBKDF2-HMAC-SHA256(phrase, sel,
//   600 000 tours), données associées `brocolis-cle-v1` ;
// - `[[BROCOLIS-CLE:v2:<base64>]]` (par défaut) : Argon2id, format exact
//   de §3.2 (83 octets), l'en-tête et les paramètres authentifiés.

package talka.brocolis

/** Les paramètres Argon2id d'une clé protégée v2. */
data class Argon2Params(
    /** m : mémoire, en Kio. */
    val mKib: UInt,
    /** t : nombre de passes. */
    val t: UByte,
    /** p : parallélisme. */
    val p: UByte,
) {
    /**
     * Vérifie les bornes de §3.2 et §7.1 : 19 456 ≤ m ≤ 1 048 576,
     * 2 ≤ t ≤ 16, p ≥ 1, m ≥ 8p.
     */
    fun check() {
        val ok = mKib >= minimum.mKib &&
            mKib <= maxMKib &&
            t >= 2u &&
            t <= maxT &&
            p >= 1u &&
            mKib >= 8u * p.toUInt()
        if (!ok) throw Erreur.Parametres
    }

    companion object {
        /** Le défaut : 64 Mio, 3 passes, 1 voie (§3.2). */
        val default = Argon2Params(65_536u, 3u, 1u)

        /**
         * Les plus petits paramètres acceptés à l'import (19 Mio, 2 passes,
         * 1 voie : le minimum de l'OWASP), utilisés par les vecteurs rapides.
         */
        val minimum = Argon2Params(19_456u, 2u, 1u)

        /** La plus grande mémoire acceptée (1 Gio). */
        const val maxMKib: UInt = 1_048_576u

        /** Le plus grand nombre de passes accepté (§7.1). */
        val maxT: UByte = 16u
    }
}

object CleProtegee {
    /** Le début d'une clé protégée v1. */
    const val headV1 = "[[BROCOLIS-CLE:v1:"

    /** Le début d'une clé protégée v2. */
    const val headV2 = "[[BROCOLIS-CLE:v2:"
    private val end = Octets.bytes("]]")
    private val adV1 = Octets.bytes("brocolis-cle-v1")

    /** Les tours de PBKDF2 de la v1. */
    const val pbkdf2Rounds: UInt = 600_000u

    /**
     * La phrase la plus courte acceptée (en caractères Unicode, comme
     * `chars().count()`) pour protéger une clé ; à l'import, toute phrase est
     * essayée.
     */
    const val phraseMin = 8
    internal const val v1Length = 16 + 12 + 32 + 16
    internal const val v2Length = 83
    internal const val v2Header = 35

    private fun pbkdf2Key(phrase: String, salt: ByteArray): ByteArray {
        val password = Octets.bytes(phrase)
        try {
            return Pbkdf2.sha256(password, salt, pbkdf2Rounds)
        } finally {
            effacer(password)
        }
    }

    /**
     * La clé de chiffrement v2 : `Argon2id(phrase UTF-8, sel, m, t, p, 32)`
     * (version 0x13, sans secret ni données associées Argon2).
     */
    fun argon2idKey(phrase: String, salt: ByteArray, params: Argon2Params): ByteArray {
        params.check()
        if (salt.size != 16) throw Erreur.Format
        val password = Octets.bytes(phrase)
        try {
            return Argon2.hash(
                variant = Argon2.Variant.ID, password = password, salt = salt,
                memoryKiB = params.mKib, passes = params.t.toUInt(), lanes = params.p.toUInt(), length = 32,
            )
        } finally {
            effacer(password)
        }
    }

    /** Le contenu (octets) entre `head` et `]]` dans un texte collé. */
    private fun inner(text: String, head: String): ByteArray? {
        val t = Octets.bytes(text)
        val h = Octets.bytes(head)
        val s = Octets.find(t, h) ?: return null
        val start = s + h.size
        val e = Octets.find(t, end, start) ?: return null
        return t.copyOfRange(start, e)
    }

    /** Vrai si le texte contient une clé protégée (v1 ou v2). */
    fun hasProtectedKey(text: String): Boolean {
        val t = Octets.bytes(text)
        return Octets.find(t, Octets.bytes(headV1)) != null || Octets.find(t, Octets.bytes(headV2)) != null
    }

    /**
     * Écrit une clé protégée v1 avec un sel et un nonce donnés. **Héritage** :
     * sert aux vecteurs et à l'interopérabilité ; on n'écrit plus que la v2.
     */
    fun protectKeyV1With(key: ByteArray, phrase: String, salt: ByteArray, nonce: ByteArray): String {
        if (key.size != 32 || salt.size != 16 || nonce.size != 12) throw Erreur.Format
        val k = pbkdf2Key(phrase, salt)
        try {
            val ct = Kdf.aesGcmSeal(k, nonce, adV1, key)
            return headV1 + Base64.encode(Octets.concat(salt, nonce, ct)) + "]]"
        } finally {
            effacer(k)
        }
    }

    /**
     * Écrit une clé protégée v2 avec des paramètres, un sel et un nonce
     * donnés (les vecteurs ; `protectKey` tire sel et nonce au hasard).
     */
    fun protectKeyV2With(key: ByteArray, phrase: String, params: Argon2Params, salt: ByteArray, nonce: ByteArray): String {
        if (phrase.codePointCount(0, phrase.length) < phraseMin) throw Erreur.PhraseTropCourte
        if (key.size != 32 || salt.size != 16 || nonce.size != 12) throw Erreur.Format
        val header = Octets.concat(
            byteArrayOf(0x02), Octets.u32be(params.mKib), byteArrayOf(params.t.toByte(), params.p.toByte()), salt, nonce,
        )
        val k = argon2idKey(phrase, salt, params)
        try {
            val ct = Kdf.aesGcmSeal(k, nonce, header, key)
            return headV2 + Base64.encode(Octets.concat(header, ct)) + "]]"
        } finally {
            effacer(k)
        }
    }

    /**
     * Protège une clé par une phrase secrète (v2, paramètres par défaut, sel
     * et nonce aléatoires). Refuse une phrase de moins de `phraseMin`
     * caractères. Ordre de tirage : sel (16), nonce (12).
     */
    fun protectKey(key: ByteArray, phrase: String, rng: CryptoRng): String {
        val salt = rng.bytes(16)
        val nonce = rng.bytes(12)
        return protectKeyV2With(key, phrase, Argon2Params.default, salt, nonce)
    }

    /**
     * Retrouve la clé d'un jeton `[[BROCOLIS-CLE:v1:…]]` ou `v2` collé (seul
     * ou dans un texte ; la v2 est cherchée d'abord). Phrase fausse ou jeton
     * abîmé : `Erreur.Dechiffrement` ; paramètres v2 hors bornes :
     * `Erreur.Parametres` (avant tout calcul).
     */
    fun unprotectKey(text: String, phrase: String): ByteArray {
        inner(text, headV2)?.let { return unprotectV2(it, phrase) }
        inner(text, headV1)?.let { return unprotectV1(it, phrase) }
        throw Erreur.JetonAbsent
    }

    private fun decode(b: ByteArray, length: Int): ByteArray {
        // Une clé protégée fait moins de 120 caractères : rien à décoder
        // au-delà.
        if (b.size > 4 * ((length + 2) / 3)) throw Erreur.Format
        val data = Base64.decode(b) ?: throw Erreur.Base64
        if (data.size != length) throw Erreur.Format
        return data
    }

    private fun unprotectV1(b: ByteArray, phrase: String): ByteArray {
        val data = decode(b, v1Length)
        try {
            val k = pbkdf2Key(phrase, data.copyOfRange(0, 16))
            try {
                val plain = Kdf.aesGcmOpen(k, data.copyOfRange(16, 28), adV1, data.copyOfRange(28, data.size))
                if (plain.size != 32) throw Erreur.Format
                return plain
            } finally {
                effacer(k)
            }
        } finally {
            effacer(data)
        }
    }

    /** Les paramètres d'une clé protégée v2 (sans la phrase), pour prévenir l'utilisateur avant un calcul long. */
    fun v2Params(text: String): Argon2Params {
        val b = inner(text, headV2) ?: throw Erreur.JetonAbsent
        return parseV2Params(decode(b, v2Length))
    }

    internal fun parseV2Params(data: ByteArray): Argon2Params {
        if (data[0] != 0x02.toByte()) throw Erreur.Format
        val params = Argon2Params(Octets.readU32(data, 1), data[5].toUByte(), data[6].toUByte())
        params.check()
        return params
    }

    private fun unprotectV2(b: ByteArray, phrase: String): ByteArray {
        val data = decode(b, v2Length)
        try {
            val params = parseV2Params(data)
            val k = argon2idKey(phrase, data.copyOfRange(7, 23), params)
            try {
                val plain = Kdf.aesGcmOpen(
                    k, data.copyOfRange(23, 35), data.copyOfRange(0, v2Header), data.copyOfRange(v2Header, data.size),
                )
                if (plain.size != 32) throw Erreur.Format
                return plain
            } finally {
                effacer(k)
            }
        } finally {
            effacer(data)
        }
    }
}
