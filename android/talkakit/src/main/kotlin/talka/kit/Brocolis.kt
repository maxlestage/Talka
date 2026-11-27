// Les jetons de Brocolis 🥦 (https://github.com/maxlestage/messagerie-) : une
// seconde couche de chiffrement, par-dessus celle de Talka, avec les clés que
// l'on partage dans l'app Brocolis. Le même format que le navigateur
// (`client/src/jetons.rs`), que l'iPhone (`Brocolis.swift`) et que l'app
// Brocolis (`BrocolisCrypto.swift`) :
//
// - une clé : 32 octets (AES-256), en base64 ;
// - `[[BROCOLIS:v1:<base64>]]` : AES-256-GCM « combined » (nonce ‖ chiffré ‖
//   tag), pour une seule clé ;
// - `[[BROCOLIS:v2:<base64>]]` : une enveloppe pour plusieurs clés : `0x01`,
//   N, N clés de message emballées (60 octets chacune), puis le corps ;
// - `((…))` : seuls ces passages sont chiffrés, le reste part en clair.

package talka.kit

object Brocolis {
    const val head = "[[BROCOLIS:"
    internal const val tail = "]]"
    internal const val wrapped = 12 + 32 + 16

    /**
     * Les blancs et retours à la ligne de `CharacterSet.whitespacesAndNewlines`
     * (les séparateurs Unicode Z*, U+0009 à U+000D et U+0085).
     */
    private fun isWhitespaceOrNewline(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x85 ||
            when (Character.getType(cp).toByte()) {
                Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
                else -> false
            }

    /** Le texte sans ses blancs autour (`trimmingCharacters(in: .whitespacesAndNewlines)`). */
    private fun trimmed(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isWhitespaceOrNewline(s.codePointAt(a))) a += Character.charCount(s.codePointAt(a))
        while (b > a && isWhitespaceOrNewline(s.codePointBefore(b))) b -= Character.charCount(s.codePointBefore(b))
        return s.substring(a, b)
    }

    /** Une clé Brocolis en base64 (32 octets), ou rien. */
    fun key(base64: String): ByteArray? = unb64(trimmed(base64))?.takeIf { it.size == 32 }

    private const val separators = "«»\"'“”()[]<>"

    /**
     * Retrouve une clé dans un texte collé (l'invitation de l'app Brocolis
     * entière, par exemple) : le premier mot qui est une clé.
     */
    fun findKey(text: String): String? {
        val words = ArrayList<String>()
        val word = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isWhitespaceOrNewline(cp) || separators.indexOf(cp.toChar()) >= 0 && cp < 0x10000) {
                words.add(word.toString())
                word.setLength(0)
            } else {
                word.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        words.add(word.toString())
        return words.firstOrNull { key(it) != null }
    }

    /**
     * L'empreinte d'une clé, à comparer de vive voix (`AB12-CD34-EF56-7890`) :
     * les 8 premiers octets de son SHA-256, comme le navigateur.
     */
    fun fingerprint(keyBase64: String): String? {
        val raw = key(keyBase64) ?: return null
        val hex = sha256(raw).copyOf(8).joinToString("") { "%02X".format(it.toInt() and 0xff) }
        return hex.chunked(4).joinToString("-")
    }

    // ----- Une clé protégée par une phrase secrète -----
    //
    // Pour sortir une clé de l'appareil sans la montrer : `[[BROCOLIS-CLE:v1:…]]`,
    // le même format que le navigateur et l'app Brocolis. Décodé : sel (16) ‖
    // nonce (12) ‖ AES-256-GCM(clé brute, données associées `brocolis-cle-v1`),
    // sous PBKDF2-HMAC-SHA256(phrase, sel, 600 000 tours).

    const val keyHead = "[[BROCOLIS-CLE:v1:"
    internal val keyAD: ByteArray get() = "brocolis-cle-v1".toByteArray()
    const val phraseRounds = 600_000

    /** La phrase la plus courte acceptée pour protéger une clé. */
    const val phraseMin = 8

    fun hasProtectedKey(text: String): Boolean = text.contains(keyHead)

    internal fun protectKey(raw: ByteArray, phrase: String, salt: ByteArray, nonce: ByteArray): String? {
        val key = TalkaCrypto.pbkdf2(phrase, salt, phraseRounds)
        val box = AesGcm.sealParts(key, nonce, raw, keyAD) ?: return null
        return keyHead + b64(salt + nonce + box) + tail
    }

    /** Protège une clé (en base64) par une phrase secrète. */
    fun protectKey(keyBase64: String, phrase: String): String? {
        if (phrase.charCount() < phraseMin) return null
        val raw = key(keyBase64) ?: return null
        return protectKey(raw, phrase, randomBytes(16), randomBytes(12))
    }

    /**
     * Retrouve la clé (en base64) d'un jeton `[[BROCOLIS-CLE:v1:…]]` collé
     * (seul ou dans un texte) ; rien si la phrase est fausse ou le jeton abîmé.
     */
    fun unprotectKey(text: String, phrase: String): String? {
        val start = text.indexOf(keyHead)
        if (start < 0) return null
        val from = start + keyHead.length
        val end = text.indexOf(tail, from)
        if (end < 0) return null
        val data = unb64(text.substring(from, end))?.takeIf { it.size == 16 + 12 + 32 + 16 } ?: return null
        val key = TalkaCrypto.pbkdf2(phrase, data.copyOfRange(0, 16), phraseRounds)
        val raw = AesGcm.open(key, data, keyAD, at = 16)?.takeIf { it.size == 32 } ?: return null
        return b64(raw)
    }

    /** Une nouvelle clé, en base64. */
    fun generateKey(): String = b64(randomBytes(32))

    /** Un jeton Brocolis dans ce texte ? */
    fun hasToken(text: String): Boolean = text.contains(head)

    /**
     * Un jeton « double cliquet » (v3) du clavier Brocolis (`BrocolisPsk`
     * l'ouvre). Le même que le navigateur (`has_ratchet_token`).
     */
    fun hasRatchetToken(text: String): Boolean = text.contains(head + "v3:")

    internal fun sealRaw(plain: ByteArray, key: ByteArray): ByteArray? = AesGcm.seal(key, plain)

    internal fun openRaw(combined: ByteArray, key: ByteArray): ByteArray? {
        if (combined.size < 12 + 16) return null
        return AesGcm.open(key, combined)
    }

    /**
     * Un jeton pour `text` : `v1` pour une clé (lisible par toutes les
     * versions de Brocolis), `v2` pour plusieurs.
     */
    fun sealToken(text: String, keys: List<ByteArray>): String? {
        val first = keys.firstOrNull() ?: return null
        if (keys.size > 255) return null
        if (keys.size == 1) return sealRaw(text.toByteArray(), first)?.let { head + "v1:" + b64(it) + tail }
        val messageKey = randomBytes(32)
        val payload = java.io.ByteArrayOutputStream()
        payload.write(1)
        payload.write(keys.size)
        for (key in keys) payload.write(sealRaw(messageKey, key) ?: return null)
        payload.write(sealRaw(text.toByteArray(), messageKey) ?: return null)
        return head + "v2:" + b64(payload.toByteArray()) + tail
    }

    /**
     * Chiffre un message : les passages `((…))` s'il y en a, sinon tout le
     * texte. Un texte qui porte déjà un jeton (collé depuis le clavier
     * Brocolis) part tel quel.
     */
    fun seal(text: String, keys: List<ByteArray>): String {
        if (keys.isEmpty()) return text
        return sealWith(text) { sealToken(it, keys) }
    }

    /**
     * La règle de `seal` (les passages `((…))` s'il y en a, sinon tout le
     * texte ; un texte qui porte déjà un jeton part tel quel), avec `token`
     * pour chiffrer chaque morceau : le « double cliquet » du clavier
     * Brocolis (`BrocolisPsk`) la suit aussi. Le même que le navigateur
     * (`jetons::seal_with`).
     */
    fun sealWith(text: String, token: (String) -> String?): String {
        if (text.isEmpty() || hasToken(text)) return text
        val out = StringBuilder()
        var rest = 0
        var marked = false
        while (true) {
            val open = text.indexOf("((", rest)
            if (open < 0) break
            val close = text.indexOf("))", open + 2)
            if (close < 0) break
            out.append(text, rest, open)
            out.append(token(text.substring(open + 2, close)) ?: text.substring(open, close + 2))
            marked = true
            rest = close + 2
        }
        if (!marked) return token(text) ?: text
        return out.append(text, rest, text.length).toString()
    }

    internal fun openV2(payload: ByteArray, keys: List<ByteArray>): ByteArray? {
        if (payload.size < 2 || payload[0].toInt() != 1) return null
        val n = payload[1].toInt() and 0xff
        val bodyStart = 2 + n * wrapped
        if (payload.size < bodyStart + 12 + 16) return null
        val body = payload.copyOfRange(bodyStart, payload.size)
        for (i in 0 until n) {
            val w = payload.copyOfRange(2 + i * wrapped, 2 + (i + 1) * wrapped)
            for (key in keys) {
                val raw = openRaw(w, key)?.takeIf { it.size == 32 } ?: continue
                openRaw(body, raw)?.let { return it }
            }
        }
        return null
    }

    internal fun openToken(inner: String, keys: List<ByteArray>): String? {
        var plain: ByteArray? = null
        if (inner.startsWith("v1:")) {
            val combined = unb64(inner.substring(3))
            if (combined != null) plain = keys.firstNotNullOfOrNull { openRaw(combined, it) }
        } else if (inner.startsWith("v2:")) {
            val payload = unb64(inner.substring(3))
            if (payload != null) plain = openV2(payload, keys)
        }
        return plain?.let(::utf8)
    }

    /** Ce que donne un texte une fois ses jetons ouverts. */
    data class Opened(
        val text: String = "",
        /** Les jetons ouverts. */
        val opened: Int = 0,
        /** Ceux qu'aucune de vos clés n'ouvre (laissés tels quels). */
        val locked: Int = 0,
    )

    /** Ouvre les jetons de `text` avec toutes vos clés. */
    fun open(text: String, keys: List<ByteArray>): Opened {
        val out = StringBuilder()
        var opened = 0
        var locked = 0
        var rest = 0
        while (true) {
            val start = text.indexOf(head, rest)
            if (start < 0) break
            val end = text.indexOf(tail, start + head.length)
            if (end < 0) break
            out.append(text, rest, start)
            val plain = openToken(text.substring(start + head.length, end), keys)
            if (plain != null) {
                out.append(plain)
                opened++
            } else {
                out.append(text, start, end + tail.length)
                locked++
            }
            rest = end + tail.length
        }
        out.append(text, rest, text.length)
        return Opened(out.toString(), opened, locked)
    }

    /**
     * Un texte prêt à afficher : un jeton qu'aucune de vos clés n'ouvre
     * devient « 🔒🥦 ».
     */
    fun masked(text: String): String {
        if (!hasToken(text)) return text
        val out = StringBuilder()
        var rest = text
        while (true) {
            val start = rest.indexOf(head)
            if (start < 0) break
            val end = rest.indexOf(tail, start)
            if (end < 0) break
            out.append(rest, 0, start).append("🔒🥦")
            rest = rest.substring(end + tail.length)
        }
        return out.append(rest).toString()
    }
}
