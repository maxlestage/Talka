// Le remplissage (§3.3, §7.2) : la longueur d'un message ne se lit pas sur
// le jeton. Avant chiffrement : texte ‖ `0x80` ‖ des `0x00` jusqu'au
// multiple de 160 octets suivant (comme Signal). Au déchiffrement : retirer
// les `0x00` de fin puis le `0x80` ; absent → refusé.

package talka.brocolis

object Remplissage {
    /** La taille des blocs de remplissage. */
    const val bloc = 160

    /**
     * `données ‖ 0x80 ‖ 0x00…` jusqu'au multiple de 160 suivant (au moins un
     * octet de remplissage : 159 octets donnent 160, 160 en donnent 320).
     */
    fun pad(data: ByteArray): ByteArray {
        val out = ByteArray((data.size / bloc + 1) * bloc)
        data.copyInto(out)
        out[data.size] = 0x80.toByte()
        return out
    }

    /**
     * Retire le remplissage. Refuse (`Erreur.Remplissage`) une longueur qui
     * n'est pas un multiple non nul de 160 ou l'absence du `0x80`.
     */
    fun unpad(padded: ByteArray): ByteArray {
        if (padded.isEmpty() || padded.size % bloc != 0) throw Erreur.Remplissage
        val end = padded.indexOfLast { it != 0.toByte() }
        if (end < 0 || padded[end] != 0x80.toByte()) throw Erreur.Remplissage
        return padded.copyOfRange(0, end)
    }
}
