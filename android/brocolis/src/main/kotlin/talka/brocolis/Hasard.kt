// Le hasard. En production : le CSPRNG du système (`SecureRandom`), et rien
// d'autre (`Hasard.system()`). Toutes les fonctions qui tirent du hasard le
// reçoivent en paramètre (`CryptoRng`), ce qui permet aux vecteurs de test
// d'injecter `Deterministic` : **jamais en production**.

package talka.brocolis

import java.security.SecureRandom

/**
 * Une source d'octets aléatoires. Les fonctions du protocole tirent leurs
 * octets **dans l'ordre documenté** de la référence Rust (« Ordre de
 * tirage »), ce qui permet de rejouer les vecteurs avec `Deterministic`.
 */
interface CryptoRng {
    /** Remplit `buffer` d'octets aléatoires. */
    fun fill(buffer: ByteArray)

    /** `count` octets aléatoires. */
    fun bytes(count: Int): ByteArray = ByteArray(count).also { fill(it) }

    /** Un `u32` gros-boutiste (4 octets). */
    fun nextU32(): UInt = Octets.readU32(bytes(4), 0)
}

/** Le hasard du protocole. */
object Hasard {
    /** Le CSPRNG du système. */
    fun system(): SystemRng = SystemRng()
}

/**
 * Le CSPRNG du système (`SecureRandom` : `/dev/urandom` ou getrandom sur la
 * JVM, le générateur du noyau sur Android). Un seul, partagé : `SecureRandom`
 * est sûr entre fils d'exécution, et le créer coûte.
 */
class SystemRng : CryptoRng {
    override fun fill(buffer: ByteArray) {
        if (buffer.isNotEmpty()) random.nextBytes(buffer)
    }

    private companion object {
        val random = SecureRandom()
    }
}

/**
 * Un générateur **déterministe**, pour les vecteurs de test et les tests :
 * le bloc `i` (32 octets) vaut `SHA-256("Brocolis_vecteurs" ‖ graine ‖
 * i u64 gros-boutiste)`, `i` partant de 0, et les octets sont servis dans
 * l'ordre, sans en jeter (`hasard::Deterministic` de la référence). Il ne
 * doit **jamais** servir en production.
 */
class Deterministic(seed: ByteArray) : CryptoRng {
    private val seed = seed.copyOf()
    private var counter: ULong = 0uL
    private var block = ByteArray(32)
    private var used = 32

    /** Un générateur à partir d'une étiquette (ses octets UTF-8). */
    constructor(label: String) : this(Octets.bytes(label))

    private fun refill() {
        val old = block
        block = Kdf.sha256(Octets.concat(prefix, seed, Octets.u64be(counter)))
        effacer(old)
        counter++
        used = 0
    }

    override fun fill(buffer: ByteArray) {
        for (i in buffer.indices) {
            if (used == 32) refill()
            buffer[i] = block[used]
            used++
        }
    }

    private companion object {
        val prefix = Octets.bytes("Brocolis_vecteurs")
    }
}
