// Brocolis 🥦 : ce que Talka sait déjà des jetons du clavier Brocolis, sans
// les ouvrir (le reste de `Brocolis.swift` viendra avec les clés).

package talka.kit

object Brocolis {
    const val head = "[[BROCOLIS:"
    internal const val tail = "]]"

    /** Un jeton Brocolis dans ce texte ? */
    fun hasToken(text: String): Boolean = text.contains(head)

    /**
     * Un jeton « double cliquet » (v3) du clavier Brocolis, que Talka
     * n'ouvre pas encore. Le même que le navigateur (`has_ratchet_token`).
     */
    fun hasRatchetToken(text: String): Boolean = text.contains(head + "v3:")

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
