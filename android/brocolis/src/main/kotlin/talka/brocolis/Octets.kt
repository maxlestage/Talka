// Les outils sur les octets : base64 strict, entiers gros-boutistes,
// comparaison en temps constant, recherche d'un motif, UTF-8 validé et
// effacement des secrets.
//
// Tout le texte est traité en octets UTF-8, comme la référence Rust (`str`
// et ses `find`) : chercher un `((` dans les octets, jamais dans les `Char`
// UTF-16 de Kotlin, donne exactement les mêmes positions qu'en Rust.

package talka.brocolis

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// MARK: - Base64 standard strict

/**
 * Le base64 standard (RFC 4648 §4, `+` `/`, avec `=`) **strict**, comme le
 * moteur `STANDARD` de la crate `base64` : remplissage canonique exigé, bits
 * de fin nuls, aucun espace. Le même texte est donc accepté ou refusé sur
 * toutes les plateformes (`java.util.Base64` ne l'est pas autant).
 */
object Base64 {
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toByteArray(Charsets.US_ASCII)
    private val table = IntArray(256) { 0xFF }.also { t -> alphabet.forEachIndexed { i, c -> t[c.toInt() and 0xFF] = i } }
    private const val EQ = '='.code.toByte()

    /** Encode en base64 standard avec `=`. */
    fun encode(data: ByteArray): String {
        val out = ByteArray((data.size + 2) / 3 * 4)
        var i = 0
        var o = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8) or (data[i + 2].toInt() and 0xFF)
            out[o++] = alphabet[n ushr 18 and 63]
            out[o++] = alphabet[n ushr 12 and 63]
            out[o++] = alphabet[n ushr 6 and 63]
            out[o++] = alphabet[n and 63]
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = data[i].toInt() and 0xFF shl 16
                out[o++] = alphabet[n ushr 18 and 63]
                out[o++] = alphabet[n ushr 12 and 63]
                out[o++] = EQ
                out[o] = EQ
            }
            2 -> {
                val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8)
                out[o++] = alphabet[n ushr 18 and 63]
                out[o++] = alphabet[n ushr 12 and 63]
                out[o++] = alphabet[n ushr 6 and 63]
                out[o] = EQ
            }
        }
        return String(out, Charsets.US_ASCII)
    }

    /** Décode du base64 standard strict, ou `null`. */
    fun decode(text: String): ByteArray? = decode(text.toByteArray(Charsets.UTF_8))

    /** Décode les octets ASCII d'un base64 standard strict, ou `null`. */
    fun decode(s: ByteArray): ByteArray? {
        if (s.size % 4 != 0) return null
        val out = java.io.ByteArrayOutputStream(s.size / 4 * 3)
        var i = 0
        while (i < s.size) {
            val last = i + 4 == s.size
            val c0 = table[s[i].toInt() and 0xFF]
            val c1 = table[s[i + 1].toInt() and 0xFF]
            if (c0 == 0xFF || c1 == 0xFF) return null
            val p2 = s[i + 2] == EQ
            val p3 = s[i + 3] == EQ
            if (p2 || p3) {
                // Le remplissage n'est permis qu'au dernier quartet, et `=`
                // en position 2 impose `=` en position 3.
                if (!last || !p3) return null
                if (p2) {
                    // Un octet : les 4 bits de fin de c1 doivent être nuls.
                    if (c1 and 0x0F != 0) return null
                    out.write(c0 shl 2 or (c1 shr 4))
                } else {
                    val c2 = table[s[i + 2].toInt() and 0xFF]
                    if (c2 == 0xFF || c2 and 0x03 != 0) return null
                    out.write(c0 shl 2 or (c1 shr 4))
                    out.write((c1 shl 4 or (c2 shr 2)) and 0xFF)
                }
            } else {
                val c2 = table[s[i + 2].toInt() and 0xFF]
                val c3 = table[s[i + 3].toInt() and 0xFF]
                if (c2 == 0xFF || c3 == 0xFF) return null
                out.write(c0 shl 2 or (c1 shr 4))
                out.write((c1 shl 4 or (c2 shr 2)) and 0xFF)
                out.write((c2 shl 6 or c3) and 0xFF)
            }
            i += 4
        }
        return out.toByteArray()
    }
}

// MARK: - Octets

internal object Octets {
    /** Un `u32` gros-boutiste. */
    fun u32be(v: UInt): ByteArray {
        val x = v.toInt()
        return byteArrayOf((x ushr 24).toByte(), (x ushr 16).toByte(), (x ushr 8).toByte(), x.toByte())
    }

    /** Lit un `u32` gros-boutiste à l'indice `i` (4 octets présents). */
    fun readU32(b: ByteArray, i: Int): UInt =
        ((b[i].toInt() and 0xFF shl 24) or (b[i + 1].toInt() and 0xFF shl 16) or
            (b[i + 2].toInt() and 0xFF shl 8) or (b[i + 3].toInt() and 0xFF)).toUInt()

    /** Un `u64` gros-boutiste. */
    fun u64be(v: ULong): ByteArray = ByteArray(8) { (v shr (56 - 8 * it)).toByte() }

    /** Égalité en temps constant (la longueur, elle, n'est pas secrète). */
    fun ctEq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    /** Vrai si tous les octets sont nuls (temps constant). */
    fun isZero(a: ByteArray): Boolean {
        var d = 0
        for (x in a) d = d or x.toInt()
        return d == 0
    }

    /** La première position de `needle` dans `hay` à partir de `from`. */
    fun find(hay: ByteArray, needle: ByteArray, from: Int = 0): Int? {
        if (needle.isEmpty() || hay.size < needle.size || from > hay.size - needle.size) return null
        var i = from
        val first = needle[0]
        while (i <= hay.size - needle.size) {
            if (hay[i] == first) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return null
    }

    /** Vrai si `hay` commence par `prefix`. */
    fun hasPrefix(hay: ByteArray, prefix: ByteArray): Boolean =
        hay.size >= prefix.size && (prefix.indices).all { hay[it] == prefix[it] }

    /** Vrai si `hay` finit par `suffix`. */
    fun hasSuffix(hay: ByteArray, suffix: ByteArray): Boolean {
        val o = hay.size - suffix.size
        return o >= 0 && (suffix.indices).all { hay[o + it] == suffix[it] }
    }

    /**
     * Le texte de ces octets s'ils sont de l'UTF-8 valide (comme
     * `String::from_utf8` : ni séquence trop longue, ni moitié de paire de
     * substitution), sinon `null`.
     */
    fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** Le texte d'octets découpés à des frontières ASCII d'un texte valide. */
    fun string(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /** Les octets UTF-8 d'un texte. */
    fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** Hexadécimal minuscule. */
    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(digits[b.toInt() shr 4 and 0x0F])
            out.append(digits[b.toInt() and 0x0F])
        }
        return out.toString()
    }

    /** `a ‖ b ‖ …`. */
    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }
}

/** `a + b` saturé (`u64::saturating_add`). */
internal fun satAdd(a: ULong, b: ULong): ULong {
    val r = a + b
    return if (r < a) ULong.MAX_VALUE else r
}

// MARK: - Effacement

/**
 * Efface des octets en mémoire (au mieux : la JVM a pu en garder une copie
 * ailleurs, lors d'un déplacement par le ramasse-miettes, et ces copies-là
 * échappent à l'effacement).
 */
internal fun effacer(bytes: ByteArray?) {
    bytes?.fill(0)
}
