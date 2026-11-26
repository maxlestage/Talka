// Les jetons de Brocolis v1 et v2, et les passages `((…))` : exactement le
// comportement de `brocolis_proto::jetons` (et de `talka-web::jetons`,
// TalkaKit `Brocolis`, `BrocolisCrypto`), lisibles pour toujours (§4.6).
//
// - une clé : 32 octets (AES-256), en base64 standard ;
// - `[[BROCOLIS:v1:<base64>]]` : AES-256-GCM « combined »
//   (nonce 12 octets ‖ chiffré ‖ tag 16 octets), pour une seule clé ;
// - `[[BROCOLIS:v2:<base64>]]` : une enveloppe pour plusieurs clés :
//   `0x01`, N, puis N clés de message emballées (60 octets chacune), puis
//   le corps chiffré sous la clé de message ;
// - `((…))` : seuls ces passages sont chiffrés, le reste part en clair.
//
// Pas de remplissage ni de données associées : ce sont les formats
// historiques, tels quels. Les jetons v3 sont ceux de `Token` ; ici, ils
// restent « verrouillés ». Tout le texte est traité en octets UTF-8.

package talka.brocolis

object Jetons {
    /** Le début de tout jeton Brocolis. */
    const val head = "[[BROCOLIS:"
    private val headBytes = Octets.bytes(head)
    private val v1 = Octets.bytes("v1:")
    private val v2 = Octets.bytes("v2:")
    private val end = Octets.bytes("]]")
    private val markOpen = Octets.bytes("((")
    private val markClose = Octets.bytes("))")
    private const val wrapped = 12 + 32 + 16

    /** Le texte affiché à la place d'un jeton qu'aucune clé n'ouvre. */
    const val verrou = "🔒🥦"

    /**
     * Les espaces de Rust (`char::is_whitespace` : la propriété Unicode
     * White_Space), pour retrouver une clé comme la référence.
     */
    internal fun isWhitespace(c: Int): Boolean = when (c) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    internal fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b) {
            val c = s.codePointAt(a)
            if (!isWhitespace(c)) break
            a += Character.charCount(c)
        }
        while (b > a) {
            val c = s.codePointBefore(b)
            if (!isWhitespace(c)) break
            b -= Character.charCount(c)
        }
        return s.substring(a, b)
    }

    /** Une clé Brocolis en base64 (32 octets), ou `null`. */
    fun keyFromB64(text: String): ByteArray? {
        val k = Base64.decode(trim(text)) ?: return null
        return if (k.size == 32) k else null
    }

    /**
     * Retrouve une clé dans un texte collé (l'invitation de l'app Brocolis
     * entière, par exemple) : le premier mot qui est une clé.
     */
    fun findKey(text: String): String? {
        val separators = "«»\"'“”()[]<>".codePoints().toArray().toSet()
        val word = StringBuilder()
        fun check(): String? {
            val w = word.toString()
            word.setLength(0)
            return if (keyFromB64(w) != null) trim(w) else null
        }
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            if (isWhitespace(c) || c in separators) {
                check()?.let { return it }
            } else {
                word.appendCodePoint(c)
            }
        }
        return check()
    }

    /**
     * L'empreinte d'une clé, à comparer de vive voix (`AB12-CD34-EF56-7890`) :
     * les 8 premiers octets de son SHA-256.
     */
    fun fingerprint(keyB64: String): String? {
        val k = keyFromB64(keyB64) ?: return null
        return Kdf.fingerprint(k)
    }

    /** Une nouvelle clé, en base64 (32 octets tirés). */
    fun generateKey(rng: CryptoRng): String {
        val k = rng.bytes(32)
        try {
            return Base64.encode(k)
        } finally {
            effacer(k)
        }
    }

    /** Vrai si le texte contient un jeton Brocolis (toute version). */
    fun hasToken(text: String): Boolean = Octets.find(Octets.bytes(text), headBytes) != null

    private fun sealRaw(key: ByteArray, plain: ByteArray, rng: CryptoRng): ByteArray? {
        val nonce = rng.bytes(12)
        val ct = try {
            Kdf.aesGcmSeal(key, nonce, ByteArray(0), plain)
        } catch (e: Erreur) {
            return null
        }
        return Octets.concat(nonce, ct)
    }

    private fun openRaw(key: ByteArray, combined: ByteArray): ByteArray? {
        if (key.size != 32 || combined.size < 12 + 16) return null
        return try {
            Kdf.aesGcmOpen(key, combined.copyOfRange(0, 12), ByteArray(0), combined.copyOfRange(12, combined.size))
        } catch (e: Erreur) {
            null
        }
    }

    /**
     * Un jeton pour `text` : `v1` pour une clé (lisible par toutes les
     * versions de Brocolis), `v2` pour plusieurs (255 au plus). Chaque clé
     * fait 32 octets (sinon `null`).
     *
     * Ordre de tirage : v1, nonce (12) ; v2, clé de message (32), puis un
     * nonce (12) par clé, puis celui du corps (12).
     */
    fun sealToken(text: String, keys: List<ByteArray>, rng: CryptoRng): String? {
        if (keys.isEmpty() || keys.size > 255 || !keys.all { it.size == 32 }) return null
        if (keys.size == 1) {
            val raw = sealRaw(keys[0], Octets.bytes(text), rng) ?: return null
            return head + "v1:" + Base64.encode(raw) + "]]"
        }
        val messageKey = rng.bytes(32)
        try {
            val payload = java.io.ByteArrayOutputStream()
            payload.write(1)
            payload.write(keys.size)
            for (key in keys) {
                val w = sealRaw(key, messageKey, rng) ?: return null
                payload.write(w)
            }
            val body = sealRaw(messageKey, Octets.bytes(text), rng) ?: return null
            payload.write(body)
            return head + "v2:" + Base64.encode(payload.toByteArray()) + "]]"
        } finally {
            effacer(messageKey)
        }
    }

    /**
     * Chiffre un message : les passages `((…))` s'il y en a, sinon tout le
     * texte. Un texte qui porte déjà un jeton part tel quel.
     */
    fun sealWith(text: String, keys: List<ByteArray>, rng: CryptoRng): String {
        if (text.isEmpty() || keys.isEmpty() || hasToken(text)) return text
        val bytes = Octets.bytes(text)
        val out = java.io.ByteArrayOutputStream()
        var rest = 0
        var marked = false
        while (true) {
            val start = Octets.find(bytes, markOpen, rest) ?: break
            val after = start + markOpen.size
            val end = Octets.find(bytes, markClose, after) ?: break
            out.write(bytes, rest, start - rest)
            val inner = Octets.string(bytes.copyOfRange(after, end))
            val token = sealToken(inner, keys, rng)
            if (token != null) {
                out.write(Octets.bytes(token))
            } else {
                out.write(bytes, start, end + markClose.size - start)
            }
            marked = true
            rest = end + markClose.size
        }
        if (!marked) {
            return sealToken(text, keys, rng) ?: text
        }
        out.write(bytes, rest, bytes.size - rest)
        return Octets.string(out.toByteArray())
    }

    /** `sealWith` avec le hasard du système. */
    fun seal(text: String, keys: List<ByteArray>): String = sealWith(text, keys, Hasard.system())

    private fun openV2(payload: ByteArray, keys: List<ByteArray>): ByteArray? {
        if (payload.size < 2) return null
        val format = payload[0].toInt() and 0xFF
        val n = payload[1].toInt() and 0xFF
        val rest = payload.size - 2
        if (format != 1 || rest < n * wrapped + 12 + 16) return null
        val body = payload.copyOfRange(2 + n * wrapped, payload.size)
        for (i in 0 until n) {
            val s = 2 + i * wrapped
            val w = payload.copyOfRange(s, s + wrapped)
            for (key in keys) {
                val messageKey = openRaw(key, w) ?: continue
                try {
                    openRaw(messageKey, body)?.let { return it }
                } finally {
                    effacer(messageKey)
                }
            }
        }
        return null
    }

    private fun openToken(inner: ByteArray, keys: List<ByteArray>): String? {
        val plain: ByteArray = if (Octets.hasPrefix(inner, v1)) {
            val combined = Base64.decode(inner.copyOfRange(v1.size, inner.size)) ?: return null
            keys.asSequence().mapNotNull { openRaw(it, combined) }.firstOrNull() ?: return null
        } else if (Octets.hasPrefix(inner, v2)) {
            val payload = Base64.decode(inner.copyOfRange(v2.size, inner.size)) ?: return null
            openV2(payload, keys) ?: return null
        } else {
            return null
        }
        try {
            return Octets.utf8(plain)
        } finally {
            effacer(plain)
        }
    }

    /** Ce que donne un texte une fois ses jetons ouverts. */
    data class Opened(
        /** Le texte, jetons ouverts remplacés par leur contenu. */
        val text: String,
        /** Les jetons ouverts. */
        val opened: Int,
        /** Ceux qu'aucune des clés n'ouvre (laissés tels quels). */
        val locked: Int,
    )

    /** Ouvre les jetons v1/v2 de `text` avec toutes les clés. */
    fun open(text: String, keys: List<ByteArray>): Opened {
        val bytes = Octets.bytes(text)
        val out = java.io.ByteArrayOutputStream()
        var opened = 0
        var locked = 0
        var rest = 0
        while (true) {
            val start = Octets.find(bytes, headBytes, rest) ?: break
            val after = start + headBytes.size
            val e = Octets.find(bytes, end, after) ?: break
            out.write(bytes, rest, start - rest)
            val plain = openToken(bytes.copyOfRange(after, e), keys)
            if (plain != null) {
                out.write(Octets.bytes(plain))
                opened++
            } else {
                out.write(bytes, start, e + end.size - start)
                locked++
            }
            rest = e + end.size
        }
        out.write(bytes, rest, bytes.size - rest)
        return Opened(Octets.string(out.toByteArray()), opened, locked)
    }

    /** Un texte prêt à afficher : un jeton qu'aucune clé n'ouvre devient « 🔒🥦 ». */
    fun masked(text: String): String {
        val bytes = Octets.bytes(text)
        if (Octets.find(bytes, headBytes) == null) return text
        val out = java.io.ByteArrayOutputStream()
        var rest = 0
        while (true) {
            val start = Octets.find(bytes, headBytes, rest) ?: break
            val e = Octets.find(bytes, end, start) ?: break
            out.write(bytes, rest, start - rest)
            out.write(Octets.bytes(verrou))
            rest = e + end.size
        }
        out.write(bytes, rest, bytes.size - rest)
        return Octets.string(out.toByteArray())
    }
}
