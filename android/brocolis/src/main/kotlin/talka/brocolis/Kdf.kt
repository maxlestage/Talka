// Les primitives et chaque dérivation du protocole, exposées telles quelles
// pour que les vecteurs vérifient chaque étape intermédiaire
// (`brocolis_proto::kdf`).
//
// | Fonction | Spécification |
// |---|---|
// | `skX3dh` | §4.2 : `HKDF(0³², FF³² ‖ DH1 ‖ DH2 ‖ DH3 [‖ SS], info)` |
// | `skPsk` | §4.2 : clé partagée |
// | `kdfRk` | §4.3 `KDF_RK` |
// | `kdfCk` | §4.3 `KDF_CK` |
// | `messageKey` | §4.3 `ENCRYPT(mk)` → clé AES ‖ nonce |
// | `sessionId` | §4.4 `SHA-256(AD)[0..8]` |
//
// Les longueurs des clés passées à ces fonctions sont des invariants (32
// octets, nonce 12) : une autre longueur est une erreur de programmation
// (`require`). Toute donnée venue de l'extérieur (jeton, paquet, JSON) est
// mesurée avant d'arriver ici.
//
// Tout vient de BouncyCastle (API légère, en Java pur : le même code sur la
// JVM et sur Android, quel que soit le fournisseur JCA de l'appareil), sauf
// la vérification Ed25519 stricte (`Ed25519Strict`).

package talka.brocolis

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.GCMModeCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519

object Kdf {
    /** `"Brocolis_v3_X25519_MLKEM768_SHA-256"` : l'info de SK avec ML-KEM. */
    val infoPq: ByteArray get() = Octets.bytes("Brocolis_v3_X25519_MLKEM768_SHA-256")

    /** `"Brocolis_v3_X25519_SHA-256"` : l'info de SK sans ML-KEM (`pq = 0`). */
    val infoClassique: ByteArray get() = Octets.bytes("Brocolis_v3_X25519_SHA-256")

    /** `"Brocolis_v3_PSK_SHA-256"` : l'info de SK pour une clé partagée. */
    val infoPsk: ByteArray get() = Octets.bytes("Brocolis_v3_PSK_SHA-256")

    /**
     * `"Brocolis_v3_PSK_Ratchet"` : la clé de cliquet initiale du répondeur
     * d'une session à clé partagée (§7.4).
     */
    val infoPskRatchet: ByteArray get() = Octets.bytes("Brocolis_v3_PSK_Ratchet")

    /** `"Brocolis_v3_Ratchet"` : l'info de `KDF_RK`. */
    val infoRatchet: ByteArray get() = Octets.bytes("Brocolis_v3_Ratchet")

    /** `"Brocolis_v3_MessageKeys"` : l'info de `ENCRYPT(mk)`. */
    val infoMessage: ByteArray get() = Octets.bytes("Brocolis_v3_MessageKeys")

    /** 32 octets nuls : le sel des HKDF « sans sel » de la spécification. */
    val selNul: ByteArray get() = ByteArray(32)

    /** Le résultat de `KDF_RK` : la nouvelle clé racine et une clé de chaîne. */
    class RkCk(val rk: ByteArray, val ck: ByteArray) {
        operator fun component1() = rk
        operator fun component2() = ck
    }

    /** Le résultat de `KDF_CK` : la clé de chaîne suivante et la clé de message. */
    class CkMk(val ck: ByteArray, val mk: ByteArray) {
        operator fun component1() = ck
        operator fun component2() = mk
    }

    /** Le résultat de `ENCRYPT(mk)` : la clé AES (32) et le nonce (12). */
    class KeyNonce(val key: ByteArray, val nonce: ByteArray) {
        operator fun component1() = key
        operator fun component2() = nonce
    }

    // MARK: Primitives

    /**
     * HKDF-SHA256 (RFC 5869) de longueur quelconque ; refuse
     * (`Erreur.TropGrand`) une longueur au-delà de 255 × 32 octets.
     */
    fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        if (length < 0 || length > 255 * 32) throw Erreur.TropGrand
        if (length == 0) return ByteArray(0)
        return hkdf(salt, ikm, info, length)
    }

    /** HKDF-SHA256 de longueur fixe (≤ 64 dans tout le protocole). */
    internal fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0 && length <= 255 * 32)
        // Un sel vide vaut 32 zéros (RFC 5869 §2.2) : BouncyCastle fait de même.
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        g.generateBytes(out, 0, length)
        return out
    }

    /** HMAC-SHA256 (une clé vide est permise, comme RFC 2104). */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(32)
        mac.doFinal(out, 0)
        return out
    }

    /** SHA-256. */
    fun sha256(data: ByteArray): ByteArray {
        val d = SHA256Digest()
        d.update(data, 0, data.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    internal fun sha512(data: ByteArray): ByteArray {
        val d = SHA512Digest()
        d.update(data, 0, data.size)
        val out = ByteArray(64)
        d.doFinal(out, 0)
        return out
    }

    private fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): GCMModeCipher {
        require(key.size == 32 && nonce.size == 12) { "AES-256-GCM : clé de 32 octets, nonce de 12" }
        val c = GCMBlockCipher.newInstance(AESEngine.newInstance())
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        return c
    }

    /** AES-256-GCM, nonce de 12 octets : chiffré ‖ tag (16). */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, message: ByteArray): ByteArray {
        val c = gcm(true, key, nonce, aad)
        val out = ByteArray(c.getOutputSize(message.size))
        try {
            val n = c.processBytes(message, 0, message.size, out, 0)
            c.doFinal(out, n)
        } catch (e: Exception) {
            throw Erreur.TropGrand
        }
        return out
    }

    /**
     * Ouvre un AES-256-GCM (chiffré ‖ tag) ; le tag est vérifié en temps
     * constant par BouncyCastle.
     */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val c = gcm(false, key, nonce, aad)
        if (ciphertext.size < 16) throw Erreur.Dechiffrement
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        try {
            val n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val m = n + c.doFinal(out, n)
            return if (m == out.size) out else out.copyOf(m)
        } catch (e: InvalidCipherTextException) {
            effacer(out)
            throw Erreur.Dechiffrement
        } catch (e: IllegalStateException) {
            effacer(out)
            throw Erreur.Dechiffrement
        }
    }

    /**
     * La clé publique X25519 d'un secret de 32 octets (bruts, réduits par
     * RFC 7748 au moment du calcul : comme x25519-dalek et CryptoKit).
     */
    fun x25519Public(secret: ByteArray): ByteArray {
        require(secret.size == 32) { "X25519 : secret de 32 octets" }
        val out = ByteArray(32)
        X25519.scalarMultBase(secret, 0, out, 0)
        return out
    }

    /** Vrai si une clé publique X25519 est **canonique** : 32 octets, bit 255 nul et u < 2²⁵⁵ − 19 (§7.3). */
    fun x25519IsCanonical(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        if (publicKey[31].toInt() and 0x80 != 0) return false
        // u ≥ p ⇔ u = 2²⁵⁵ − 19 + k, k < 19 : 0x7f, 30 × 0xff, puis ≥ 0xed.
        return !(publicKey[31] == 0x7f.toByte() && (1 until 31).all { publicKey[it] == 0xff.toByte() } &&
            (publicKey[0].toInt() and 0xFF) >= 0xed)
    }

    /**
     * X25519(secret, public). Refuse (`Erreur.CleInvalide`) une clé publique
     * non canonique et un résultat nul (clé publique de petit ordre).
     */
    fun x25519(secret: ByteArray, publicKey: ByteArray): ByteArray {
        require(secret.size == 32) { "X25519 : secret de 32 octets" }
        if (!x25519IsCanonical(publicKey)) throw Erreur.CleInvalide
        val out = ByteArray(32)
        X25519.scalarMult(secret, 0, publicKey, 0, out, 0)
        if (Octets.isZero(out)) throw Erreur.CleInvalide
        return out
    }

    /** La clé publique Ed25519 d'une graine de 32 octets. */
    fun ed25519Public(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 : graine de 32 octets" }
        val out = ByteArray(32)
        Ed25519.generatePublicKey(seed, 0, out, 0)
        return out
    }

    /** Signe avec une graine Ed25519 : la signature de RFC 8032, déterministe, comme la référence. */
    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 : graine de 32 octets" }
        val out = ByteArray(64)
        Ed25519.sign(seed, 0, message, 0, message.size, out, 0)
        return out
    }

    /**
     * Vérifie une signature Ed25519 **stricte** (RFC 8032 §5.1.7, comme
     * `verify_strict` d'ed25519-dalek) : A doit être un point qui n'est pas
     * de petit ordre (sinon `Erreur.CleInvalide`) ; S canonique, R un point
     * qui n'est pas de petit ordre et l'équation vraie (sinon
     * `Erreur.Signature`).
     */
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray) {
        if (publicKey.size != 32 || Ed25519Strict.isWeak(publicKey)) throw Erreur.CleInvalide
        if (signature.size != 64) throw Erreur.Signature
        val r = signature.copyOfRange(0, 32)
        val s = signature.copyOfRange(32, 64)
        if (!Ed25519Strict.isCanonicalScalar(s) || Ed25519Strict.isWeak(r)) throw Erreur.Signature
        if (!Ed25519Strict.verifyEquation(publicKey, r, s, message)) throw Erreur.Signature
    }

    /**
     * Vrai si une clé publique Ed25519 est utilisable : un point de la
     * courbe qui n'est pas de petit ordre (`VerifyingKey::from_bytes` puis
     * `is_weak`).
     */
    fun ed25519IsValidPublic(publicKey: ByteArray): Boolean =
        publicKey.size == 32 && !Ed25519Strict.isWeak(publicKey)

    // MARK: Dérivations du protocole

    /**
     * SK de l'établissement (§4.2) : `HKDF-SHA256(sel = 32 × 0x00, ikm = 32 ×
     * 0xFF ‖ DH1 ‖ DH2 ‖ DH3 [‖ SS], info)`. Avec `ss`, l'info est
     * `infoPq` ; sans, `infoClassique`.
     */
    fun skX3dh(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, ss: ByteArray?): ByteArray {
        val ff = ByteArray(32) { 0xFF.toByte() }
        val ikm = if (ss != null) Octets.concat(ff, dh1, dh2, dh3, ss) else Octets.concat(ff, dh1, dh2, dh3)
        try {
            return hkdf(selNul, ikm, if (ss != null) infoPq else infoClassique, 32)
        } finally {
            effacer(ikm)
        }
    }

    /**
     * SK d'une session à clé partagée (§4.2) :
     * `HKDF-SHA256(sel = 32 × 0x00, ikm = clé, "Brocolis_v3_PSK_SHA-256", 32)`.
     */
    fun skPsk(key: ByteArray): ByteArray = hkdf(selNul, key, infoPsk, 32)

    /**
     * Le secret X25519 de la clé de cliquet initiale du répondeur d'une
     * session à clé partagée :
     * `HKDF-SHA256(sel = 32 × 0x00, ikm = SK, "Brocolis_v3_PSK_Ratchet", 32)`.
     */
    fun pskRatchetSecret(sk: ByteArray): ByteArray = hkdf(selNul, sk, infoPskRatchet, 32)

    /** `KDF_RK(rk, dh) = HKDF-SHA256(sel = rk, ikm = dh, "Brocolis_v3_Ratchet", 64)` → `(rk', ck)`. */
    fun kdfRk(rk: ByteArray, dh: ByteArray): RkCk {
        val out = hkdf(rk, dh, infoRatchet, 64)
        try {
            return RkCk(out.copyOfRange(0, 32), out.copyOfRange(32, 64))
        } finally {
            effacer(out)
        }
    }

    /** `KDF_CK(ck)` → `(ck', mk)` avec `mk = HMAC-SHA256(ck, 0x01)` et `ck' = HMAC-SHA256(ck, 0x02)`. */
    fun kdfCk(ck: ByteArray): CkMk {
        val mk = hmacSha256(ck, byteArrayOf(0x01))
        val next = hmacSha256(ck, byteArrayOf(0x02))
        return CkMk(next, mk)
    }

    /**
     * `ENCRYPT(mk)` : `HKDF-SHA256(sel = 32 × 0x00, ikm = mk,
     * "Brocolis_v3_MessageKeys", 44)` → clé AES (32) et nonce (12).
     */
    fun messageKey(mk: ByteArray): KeyNonce {
        val out = hkdf(selNul, mk, infoMessage, 44)
        try {
            return KeyNonce(out.copyOfRange(0, 32), out.copyOfRange(32, 44))
        } finally {
            effacer(out)
        }
    }

    /** L'identifiant de session : `SHA-256(AD)[0..8]` (§4.4). */
    fun sessionId(ad: ByteArray): ByteArray = sha256(ad).copyOfRange(0, 8)

    /**
     * L'empreinte à comparer de vive voix (`AB12-CD34-EF56-7890`) : les 8
     * premiers octets du SHA-256 des données, en hexadécimal majuscule.
     */
    fun fingerprint(data: ByteArray): String {
        val hex = Octets.hex(sha256(data).copyOfRange(0, 8)).uppercase()
        return (0 until 16 step 4).joinToString("-") { hex.substring(it, it + 4) }
    }
}
