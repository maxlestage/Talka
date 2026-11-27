// Les clés Brocolis 🥦 : gardées par le système, pour cet appareil seulement
// (ni sauvegarde, ni nuage) — pas dans la base des messages. Sur l'iPhone,
// le trousseau d'iOS ; sur Android, la garde que branche l'application (le
// Keystore, `SecretGuard`). Sans garde (les tests de la JVM), elles restent
// dans la base.

package talka.kit

import kotlinx.serialization.json.JsonArray

object BrocolisVault {
    internal const val service = "talka.brocolis"
    internal const val account = "keys"

    /** La garde, branchée par l'application (rien : les clés restent dans la base). */
    @Volatile
    var guard: SecretGuard? = null

    /** Les clés sont-elles gardées par le système (sinon : dans la base) ? */
    val isAvailable: Boolean get() = guard != null

    /** Écrit ; `false` si la garde a refusé (ou sans garde). */
    fun write(keys: List<BrocolisKey>): Boolean {
        val g = guard ?: return false
        return g.write(service, account, JSON.write(JsonArray(keys.map { it.toJson() })).toByteArray())
    }

    /** Les clés gardées ; rien s'il n'y en a pas, si la garde ne répond pas, ou si elles sont illisibles. */
    fun read(): List<BrocolisKey>? {
        val g = guard ?: return null
        val data = (g.read(service, account) as? KeyGuard.Read.Found)?.data ?: return null
        val a = utf8(data)?.let { JSON.parse(it) } as? JsonArray ?: return null
        return a.map { JSON.decode(BrocolisKey, it) ?: return null }
    }

    fun clear() {
        guard?.delete(service, account)
    }
}
