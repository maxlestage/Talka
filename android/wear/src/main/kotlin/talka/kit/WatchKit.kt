// Ce que la montre reprend du cœur de Talka : `WatchLink.kt`, `WidgetData.kt`
// et `Json.kt`, compilés tels quels depuis talkakit (voir build.gradle.kts),
// comme l'Apple Watch reprend `WatchLink.swift` et `WidgetData.swift`. La
// montre n'a ni clés ni relais : rien d'autre du cœur ne lui sert.
//
// Il ne leur manque que ceci, qui vit ailleurs dans talkakit (Crypto.kt,
// Store.kt) : les mêmes fonctions, à l'identique.

package talka.kit

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Le texte UTF-8 d'octets, ou rien s'ils n'en sont pas (le même que celui de talkakit). */
internal fun utf8(data: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

/**
 * L'ordre des textes par points de code (celui de SQLite, `ORDER BY key`, et
 * des chaînes du Swift), pas par unités UTF-16 (le même que celui de talkakit).
 */
internal fun compareCodePoints(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val x = a[i]
        val y = b[i]
        if (x != y) {
            // Les moitiés d'un point de code au-delà de U+FFFF (D800–DFFF)
            // passent après E000–FFFF.
            fun weight(c: Char): Int = if (c.code >= 0xD800) (if (c.code <= 0xDFFF) c.code + 0x2000 else c.code - 0x800) else c.code
            return weight(x) - weight(y)
        }
    }
    return a.length - b.length
}
