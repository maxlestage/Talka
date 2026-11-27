// L'appareil v3 (ses clés d'identité, pré-signées et ML-KEM) : gardé par le
// système, pour cet appareil seulement (ni sauvegarde, ni nuage), comme les
// clés Brocolis (`BrocolisVault`) — pas en clair dans la base des messages.
// Sur l'iPhone, le trousseau d'iOS ; sur Android, la garde que branche
// l'application (le Keystore). Un élément par code Talka : un appareil ne
// sert jamais à deux identités (le relais pourrait les relier). Si la garde
// refuse, l'appareil reste dans la base (comme ailleurs). Sans garde (les
// tests de la JVM), il reste dans la base (`kv/v3_device`).

package talka.kit

/**
 * Une garde d'éléments secrets : ce que le trousseau est à l'iPhone (un
 * élément par service et par compte, pour cet appareil seulement, lisible
 * en arrière-plan, quand les messages arrivent). Branchée par l'application
 * (le Keystore d'Android) ; `MemorySecretGuard` dans les tests.
 */
interface SecretGuard {
    /** Un élément ; `Failed` si la garde ne répond pas (verrouillée, refus). */
    fun read(service: String, account: String): KeyGuard.Read

    /** Écrit un élément (il remplace le précédent) ; `false` si refusé. */
    fun write(service: String, account: String, data: ByteArray): Boolean

    fun delete(service: String, account: String)

    /** Efface tous les éléments du service. */
    fun clear(service: String)
}

object V3Vault {
    internal const val service = "talka.v3"

    /** La garde, branchée par l'application (rien : l'appareil reste dans la base). */
    @Volatile
    var guard: SecretGuard? = null

    /** L'appareil est-il gardé par le système (sinon : dans la base, `kv/v3_device`) ? */
    val isAvailable: Boolean get() = guard != null

    /** Écrit l'appareil (son JSON) ; `false` si la garde a refusé (ou sans garde). */
    fun write(json: String, code: String): Boolean = guard?.write(service, code, json.toByteArray()) ?: false

    /** La garde ne répond pas (verrouillée, refus) : surtout ne pas créer un autre appareil. */
    class Unreadable : Exception("la garde de l'appareil v3 ne répond pas")

    /**
     * L'appareil gardé ; rien s'il n'y en a pas (ou sans garde). Une erreur
     * si la garde ne répond pas (verrouillée, refus) : surtout ne pas en
     * créer un autre.
     */
    @Throws(Unreadable::class)
    fun read(code: String): String? {
        val g = guard ?: return null
        return when (val r = g.read(service, code)) {
            is KeyGuard.Read.Found -> utf8(r.data)
            KeyGuard.Read.NotFound -> null
            KeyGuard.Read.Failed -> throw Unreadable()
        }
    }

    /** Efface les appareils de tous les codes. */
    fun clear() {
        guard?.clear(service)
    }
}
