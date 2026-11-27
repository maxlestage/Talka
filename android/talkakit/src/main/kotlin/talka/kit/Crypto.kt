// Les codes, les clés et le chiffrement de Talka — exactement comme le
// navigateur (`client/src/crypto.rs`) et l'iPhone (`Crypto.swift`), octet
// pour octet :
//
// - l'identité : une clé ECDSA P-256 (signer le défi du relais) et une clé
//   ECDH P-256 (chiffrer), tirées sur l'appareil ;
// - le code : 50 bits de SHA-256(signature ‖ chiffrement) en 10 caractères ;
// - une conversation à deux : ECDH → HKDF-SHA256 → AES-256-GCM, avec les deux
//   codes en données associées ;
// - le code de verrouillage et la sauvegarde : Argon2id (PBKDF2-SHA256 pour
//   ceux d'avant).
//
// Les courbes et Argon2id viennent de BouncyCastle (le même code sur Android
// et sur la JVM) ; AES-GCM et SHA-256 de la JCA.

package talka.kit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Les codes Talka : ce qui remplace le numéro de téléphone. */
object TalkaCode {
    /** Les 32 caractères d'un code : ni `0`/`O`, ni `1`/`I`. */
    const val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** Le code de deux clés publiques brutes (65 octets chacune). */
    fun of(sign: ByteArray, dh: ByteArray): String {
        val digest = sha256(sign + dh)
        val out = StringBuilder()
        for (i in 0 until 10) {
            var v = 0
            for (b in 0 until 5) {
                val bit = i * 5 + b
                v = (v shl 1) or ((digest[bit / 8].toInt() shr (7 - bit % 8)) and 1)
            }
            out.append(alphabet[v])
        }
        return out.toString()
    }

    /** `K7Q2MX9PRT` → `K7Q2M-X9PRT`. */
    fun pretty(code: String): String {
        if (code.length != 10) return code
        return "${code.take(5)}-${code.takeLast(5)}"
    }

    /** Ce que l'on tape (minuscules, tirets, espaces) → un code, ou rien. */
    fun normalize(text: String): String? {
        val code = text.uppercase().filter { it != '-' && it != ' ' }
        if (code.length != 10 || !code.all { it in alphabet }) return null
        return code
    }

    /**
     * Un code trouvé dans un lien d'invitation (`…/c/K7Q2MX9PRT`,
     * `…/app/#add=K7Q2MX9PRT`) ou tapé tel quel.
     */
    fun find(text: String): String? {
        normalize(text)?.let { return it }
        val tail = text.split('/', '=', '#').lastOrNull { it.isNotEmpty() } ?: ""
        return normalize(tail)
    }

    /**
     * L'empreinte des clés d'un code (96 bits, base64url, 16 caractères),
     * comme `crypto::fingerprint` du navigateur : dans les liens
     * d'invitation, elle démasque d'autres clés donnant le même code.
     */
    fun fingerprint(sign: String, dh: String): String =
        TalkaV4.b64url(sha256("talka-empreinte:$sign:$dh".toByteArray()).copyOf(12))

    internal fun validFingerprint(fp: String): Boolean =
        fp.length == 16 && fp.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    /**
     * L'empreinte d'un lien d'invitation (`/c/<empreinte>/<code>` ou
     * `#k=<empreinte>&add=<code>`), s'il en a une.
     */
    fun fingerprint(text: String): String? {
        val parts = text.split('/', '#', '&', '?').filter { it.isNotEmpty() }
        val i = parts.indexOf("c")
        if (i >= 0 && i + 2 < parts.size && validFingerprint(parts[i + 1])) return parts[i + 1]
        return parts.firstOrNull { it.startsWith("k=") }?.drop(2)?.takeIf { validFingerprint(it) }
    }
}

/** Le base64 standard, avec son remplissage (comme `Data.base64EncodedString`). */
fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

/**
 * Le base64 standard, strict comme `Data(base64Encoded:)` : remplissage
 * obligatoire, aucun caractère étranger.
 */
fun unb64(text: String): ByteArray? {
    if (text.length % 4 != 0) return null
    return try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val random = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

/** Un identifiant aléatoire, en hexadécimal. */
fun randomId(bytes: Int = 12): String = hex(randomBytes(bytes))

internal fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Le texte UTF-8 d'octets, ou rien s'ils n'en sont pas (comme `String(data:encoding:)`). */
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

// ----- Les briques : SHA-256, HMAC, HKDF, AES-GCM, P-256 -----------------------

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** HMAC-SHA256 ; une clé vide est permise (la JCA la refuse). */
internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = HMac(SHA256Digest())
    mac.init(KeyParameter(key))
    mac.update(data, 0, data.size)
    return ByteArray(32).also { mac.doFinal(it, 0) }
}

internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
    val g = HKDFBytesGenerator(SHA256Digest())
    g.init(HKDFParameters(ikm, salt, info))
    return ByteArray(length).also { g.generateBytes(it, 0, length) }
}

/** AES-256-GCM, nonce de 12 octets, étiquette de 16 (comme `AES.GCM`). */
internal object AesGcm {
    // Un chiffreur par fil, réinitialisé à chaque usage (nouvelle clé, nouveau
    // nonce) : `Cipher.getInstance` cherche son fournisseur à chaque appel,
    // ce qui pèse sur les milliers de lignes de la mémoire (`Store`).
    private val ciphers: ThreadLocal<Cipher> = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, nonceAt: Int, aad: ByteArray?): Cipher {
        val spec = SecretKeySpec(key, "AES")
        val params = GCMParameterSpec(128, nonce, nonceAt, 12)
        val c = try {
            ciphers.get().also { it.init(mode, spec, params) }
        } catch (e: java.security.InvalidAlgorithmParameterException) {
            // Le chiffreur d'un fil refuse de rechiffrer avec la clé et le
            // nonce qu'il vient d'employer (un nonce donné par l'appelant : les
            // vecteurs) ; un chiffreur neuf l'accepte, comme avant.
            Cipher.getInstance("AES/GCM/NoPadding").also { it.init(mode, spec, params) }
        }
        if (aad != null) c.updateAAD(aad)
        return c
    }

    /** Chiffré ‖ étiquette. */
    fun sealParts(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray? =
        if (key.size != 32 || nonce.size != 12) null else cipher(Cipher.ENCRYPT_MODE, key, nonce, 0, aad).doFinal(plain)

    /** Nonce ‖ chiffré ‖ étiquette (`SealedBox.combined`). */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray? {
        if (key.size != 32) return null
        // Le nonce, puis le chiffré écrit juste derrière : une seule copie.
        val out = ByteArray(12 + plain.size + 16)
        randomBytes(12).copyInto(out)
        cipher(Cipher.ENCRYPT_MODE, key, out, 0, aad).doFinal(plain, 0, plain.size, out, 12)
        return out
    }

    fun openParts(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray? {
        if (nonce.size != 12) return null
        return openAt(key, nonce, 0, sealed, 0, sealed.size, aad)
    }

    /** Nonce ‖ chiffré ‖ étiquette, lus à partir de `at` dans `data`, sans copie. */
    fun open(key: ByteArray, data: ByteArray, aad: ByteArray? = null, at: Int = 0): ByteArray? {
        if (at < 0 || data.size - at < 12 + 16) return null
        return openAt(key, data, at, data, at + 12, data.size - at - 12, aad)
    }

    private fun openAt(key: ByteArray, nonce: ByteArray, nonceAt: Int, sealed: ByteArray, from: Int, length: Int, aad: ByteArray?): ByteArray? {
        if (key.size != 32 || length < 16) return null
        return try {
            cipher(Cipher.DECRYPT_MODE, key, nonce, nonceAt, aad).doFinal(sealed, from, length)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }
}

/**
 * P-256 : clés privées brutes (32 octets), publiques en X9.63 non compressé
 * (65 octets), signatures `r ‖ s` (64 octets), comme CryptoKit.
 */
internal object P256 {
    private val params = CustomNamedCurves.getByName("secp256r1")
    val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)

    /** Une clé privée valable : 32 octets, entre 1 et n − 1. */
    fun scalar(raw: ByteArray): BigInteger? {
        if (raw.size != 32) return null
        val d = BigInteger(1, raw)
        return if (d.signum() > 0 && d < domain.n) d else null
    }

    /** Un point de la courbe (vérifié), ou rien. */
    fun point(x963: ByteArray): ECPoint? {
        if (x963.size != 65 || x963[0] != 4.toByte()) return null
        return try {
            domain.curve.decodePoint(x963).takeIf { it.isValid && !it.isInfinity }
        } catch (e: RuntimeException) {
            null
        }
    }

    fun publicKey(priv: ByteArray): ByteArray? =
        scalar(priv)?.let { FixedPointCombMultiplier().multiply(domain.g, it).normalize().getEncoded(false) }

    /** Une clé privée neuve (32 octets). */
    fun newPrivate(): ByteArray {
        while (true) {
            val raw = randomBytes(32)
            if (scalar(raw) != null) return raw
        }
    }

    /** Le secret partagé (l'abscisse, 32 octets), comme `sharedSecretFromKeyAgreement`. */
    fun ecdh(priv: ByteArray, peer: ByteArray): ByteArray? {
        val d = scalar(priv) ?: return null
        val q = point(peer) ?: return null
        val s = q.multiply(d).normalize()
        if (s.isInfinity) return null
        return s.affineXCoord.encoded
    }

    /** ECDSA P-256 / SHA-256 : `r ‖ s`, `s` réduit (accepté partout). */
    fun sign(priv: ByteArray, message: ByteArray): ByteArray? {
        val d = scalar(priv) ?: return null
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, domain))
        val (r, s0) = signer.generateSignature(sha256(message))
        val half = domain.n.shiftRight(1)
        val s = if (s0 > half) domain.n - s0 else s0
        return BigIntegers.asUnsignedByteArray(32, r) + BigIntegers.asUnsignedByteArray(32, s)
    }

    fun verify(pub: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != 64) return false
        val q = point(pub) ?: return false
        val r = BigInteger(1, sig.copyOfRange(0, 32))
        val s = BigInteger(1, sig.copyOfRange(32, 64))
        val verifier = ECDSASigner()
        verifier.init(false, ECPublicKeyParameters(q, domain))
        return verifier.verifySignature(sha256(message), r, s)
    }
}

/**
 * L'identité de l'appareil, telle qu'elle est gardée (et sauvegardée) : le
 * même JSON que le navigateur.
 */
data class Identity(
    val code: String,
    /** Les clés publiques brutes (65 octets), en base64. */
    val sign: String,
    val dh: String,
    /** Les clés privées (32 octets), en base64. */
    val signPriv: String,
    val dhPriv: String,
    val createdAt: Double,
) : JsonEncodable {
    internal val signKey: ByteArray? get() = unb64(signPriv)?.takeIf { P256.scalar(it) != null }
    internal val dhKey: ByteArray? get() = unb64(dhPriv)?.takeIf { P256.scalar(it) != null }

    /** Les clés de cette identité vont-elles bien ensemble ? */
    val isValid: Boolean
        get() {
            val sr = signKey?.let(P256::publicKey) ?: return false
            val dr = dhKey?.let(P256::publicKey) ?: return false
            return b64(sr) == sign && b64(dr) == dh && TalkaCode.of(sr, dr) == code
        }

    /** La signature du défi du relais : `talka-auth:<nonce>`, `r ‖ s` en base64. */
    fun signChallenge(nonce: String): String = sign("talka-auth:$nonce".toByteArray())

    /**
     * Le jeton de remise de ce code (`nonce.signature`), comme
     * `Identity::jeton` du navigateur : le nonce est tiré de la clé privée
     * (toujours le même) ; la signature, elle, change à chaque calcul.
     */
    val jeton: String
        get() {
            val dh = unb64(dhPriv) ?: return ""
            return jeton(b64(sha256("talka-jeton-nonce:".toByteArray() + dh).copyOf(16)))
        }

    /**
     * Le nonce du jeton de remise donné à `contact`, à l'époque `epoch`
     * (elle avance quand on le bloque : l'ancien jeton est révoqué), comme
     * `Identity::jeton_nonce` du navigateur.
     */
    fun jetonNonce(contact: String, epoch: Int): String {
        val dh = unb64(dhPriv) ?: return ""
        return b64(sha256("talka-jeton-nonce:".toByteArray() + dh + ":$contact:$epoch".toByteArray()).copyOf(16))
    }

    /** Le jeton de remise pour `contact` : un par contact, révocable. */
    fun jeton(contact: String, epoch: Int): String = jeton(jetonNonce(contact, epoch))

    internal fun jeton(nonce: String): String {
        if (nonce.isEmpty()) return ""
        val sig = sign(TalkaCrypto.jetonMessage(code, nonce))
        return if (sig.isEmpty()) "" else "$nonce.$sig"
    }

    /**
     * Signe un message (ECDSA P-256, SHA-256) : `r ‖ s` en base64, comme
     * `Identity::sign` du navigateur ; vide si la clé est illisible.
     */
    fun sign(message: ByteArray): String = signKey?.let { P256.sign(it, message) }?.let(::b64) ?: ""

    override fun toJson(): JsonElement =
        buildJsonObject {
            put("code", code)
            put("sign", sign)
            put("dh", dh)
            put("sign_priv", signPriv)
            put("dh_priv", dhPriv)
            put("created_at", createdAt)
        }

    /** Les clés privées ne s'affichent pas (journaux). */
    override fun toString(): String = "Identity(code=$code, sign=$sign, dh=$dh, createdAt=$createdAt)"

    companion object : JsonDecodable<Identity> {
        fun create(now: Double = nowMs()): Identity {
            val sign = P256.newPrivate()
            val dh = P256.newPrivate()
            val signRaw = P256.publicKey(sign)!!
            val dhRaw = P256.publicKey(dh)!!
            return Identity(
                code = TalkaCode.of(signRaw, dhRaw),
                sign = b64(signRaw), dh = b64(dhRaw),
                signPriv = b64(sign), dhPriv = b64(dh),
                createdAt = now,
            )
        }

        override fun fromJson(json: JsonElement): Identity {
            val c = json.obj()
            return Identity(
                c.reqStr("code"), c.reqStr("sign"), c.reqStr("dh"),
                c.reqStr("sign_priv"), c.reqStr("dh_priv"), c.reqDouble("created_at"),
            )
        }
    }
}

object TalkaCrypto {
    /** Les clés publiques d'un participant (numéro de sécurité). */
    data class Keys(val code: String, val sign: String, val dh: String)

    /** Une enveloppe scellée ouverte : l'expéditeur et l'enveloppe ordinaire. */
    data class Opened(val from: String, val body: String)

    /** Pareil, avec l'appareil de l'expéditeur (vide pour un ancien client). */
    data class OpenedFrom(val from: String, val fd: String, val body: String)

    /** Un aperçu ouvert : qui l'envoie, et son JSON. */
    data class Apercu(val from: String, val plain: String)

    /** Vérifie une signature `r ‖ s` (comme le relais). */
    fun verify(sign: String, message: ByteArray, signature: String): Boolean {
        val k = unb64(sign) ?: return false
        val s = unb64(signature) ?: return false
        return P256.verify(k, message, s)
    }

    /** Les clés publiques d'un contact correspondent-elles à son code ? */
    fun verifyKeys(code: String, sign: String, dh: String): Boolean {
        val s = unb64(sign) ?: return false
        val d = unb64(dh) ?: return false
        if (s.size != 65 || d.size != 65 || P256.point(d) == null) return false
        return TalkaCode.of(s, d) == code
    }

    /**
     * Le numéro de sécurité d'une conversation, le même des deux côtés. Il
     * porte sur les clés publiques complètes (code, `sign`, `dh` de chacun),
     * pas seulement sur les codes (50 bits), comme le navigateur.
     */
    fun safetyNumber(a: Keys, b: Keys): String {
        val (x, y) = if (a.code < b.code) a to b else b to a
        val text = "talka-safety-v2:${x.code}:${x.sign}:${x.dh}|${y.code}:${y.sign}:${y.dh}"
        val h = sha256(text.toByteArray())
        return (0 until 12).joinToString(" ") { i ->
            val value = (((h[i * 2].toInt() and 0xff) shl 8) or (h[i * 2 + 1].toInt() and 0xff)) % 100_000
            "%05d".format(value)
        }
    }

    private val pairKeys = ConcurrentHashMap<String, ByteArray>()

    /** La clé d'une conversation à deux : ECDH, puis HKDF-SHA256. */
    internal fun pairKey(me: Identity, peerCode: String, peerDh: String): ByteArray? {
        val cacheKey = "${me.code}:$peerCode:$peerDh"
        pairKeys[cacheKey]?.let { return it }
        val raw = unb64(peerDh) ?: return null
        val secret = me.dhKey?.let { P256.ecdh(it, raw) } ?: return null
        val (a, b) = if (me.code < peerCode) me.code to peerCode else peerCode to me.code
        val key = hkdfSha256(secret, "talka:$a:$b".toByteArray(), "talka-message-v1".toByteArray())
        pairKeys[cacheKey] = key
        return key
    }

    /** Chiffre un texte (du JSON) pour un contact : base64(iv ‖ chiffré ‖ tag). */
    fun seal(me: Identity, peerCode: String, peerDh: String, plain: String): String? {
        val key = pairKey(me, peerCode, peerDh) ?: return null
        return AesGcm.seal(key, plain.toByteArray(), "${me.code}>$peerCode".toByteArray())?.let(::b64)
    }

    /** Déchiffre ce qu'un contact a chiffré pour nous. */
    fun open(me: Identity, peerCode: String, peerDh: String, body: String): String? {
        val key = pairKey(me, peerCode, peerDh) ?: return null
        val data = unb64(body)?.takeIf { it.size >= 28 } ?: return null
        return AesGcm.open(key, data, "$peerCode>${me.code}".toByteArray())?.let(::utf8)
    }

    // ----- L'expéditeur scellé (v4) -----
    //
    // Comme `client/src/crypto.rs` : une seconde enveloppe autour de
    // l'enveloppe ordinaire (clé éphémère P-256, ECDH avec la clé de
    // chiffrement du destinataire, HKDF-SHA256, AES-256-GCM) ; elle porte le
    // code de l'expéditeur, que l'enveloppe intérieure prouve ensuite. Le
    // jeton de remise, signé par la clé du code, ouvre la connexion anonyme
    // du relais vers ce code.

    /** Ce que signe un code pour son jeton de remise. */
    internal fun jetonMessage(code: String, nonce: String): ByteArray = "talka-jeton:$code:$nonce".toByteArray()

    /** Le jeton est-il bien signé par la clé du code `to` ? */
    fun verifyJeton(to: String, sign: String, dh: String, jeton: String): Boolean {
        // Comme `split(separator: ".", maxSplits: 1)` : sans morceau vide.
        val parts = jeton.split(".", limit = 2).filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0].length > 64 || !verifyKeys(to, sign, dh)) return false
        return verify(sign, jetonMessage(to, parts[0]), parts[1])
    }

    /** Le clair d'une enveloppe scellée : `from`, `fd` (v4), `body`, `p` (v6). */
    private class SealedSender(val from: String, val fd: String, val body: String, val p: String = "") {
        fun json(): String =
            JSON.write(
                buildJsonObject {
                    put("from", from)
                    if (fd.isNotEmpty()) put("fd", fd)
                    put("body", body)
                    if (p.isNotEmpty()) put("p", p)
                },
            )

        companion object {
            fun parse(data: ByteArray): SealedSender? {
                val c = utf8(data)?.let(JSON::parse) as? JsonObject ?: return null
                val from = c.strOrNull("from") ?: return null
                val body = c.strOrNull("body") ?: return null
                return SealedSender(from, c.str("fd"), body)
            }
        }
    }

    /**
     * Les paliers de taille des enveloppes scellées (v6, docs/SECURITE.md
     * §7.15) : 1 Kio, 2, 4… 256 Kio, puis des multiples de 256 Kio, sans
     * dépasser 5 Mio. Comme `sealed_bucket` du navigateur.
     */
    fun sealedBucket(n: Int): Int {
        val step = 256 * 1024
        val max = 5 * 1024 * 1024
        var b = 1024
        if (n <= step) {
            while (b < n) b *= 2
        } else {
            b = (n + step - 1) / step * step
        }
        return if (b > max) n else b
    }

    /** Le clair d'une enveloppe scellée, rempli jusqu'à son palier. */
    internal fun sealedPlain(from: String, fd: String, body: String): ByteArray {
        val base = SealedSender(from, fd, body).json().toByteArray().size
        // `,"p":""` : 7 octets, puis autant de « 0 » qu'il faut (un au moins).
        val target = sealedBucket(base + 8)
        return SealedSender(from, fd, body, "0".repeat(target - base - 7)).json().toByteArray()
    }

    private fun sealedKey(secret: ByteArray, eph: ByteArray, toDh: ByteArray, to: String): ByteArray =
        hkdfSha256(secret, eph + toDh, "talka-scelle-v1:$to".toByteArray())

    // ----- L'aperçu des notifications -----------------------------------------
    //
    // Chiffré pour la clé de notification du destinataire (son navigateur la
    // garde, non exportable, dans son service worker) : le relais le
    // transmet sans le lire. Authentifié : la clé mêle un ECDH éphémère et
    // un ECDH avec notre clé d'identité ; le service worker n'affiche que
    // l'aperçu d'un contact. Comme `crypto::seal_apercu` du navigateur :
    // base64(0x02 ‖ code (10) ‖ éphémère (65) ‖ iv (12) ‖ chiffré ‖ tag).

    /** Une clé de notification valable : un point P-256 (65 octets, base64). */
    fun validNk(nk: String): Boolean {
        val raw = unb64(nk) ?: return false
        return raw.size == 65 && P256.point(raw) != null
    }

    private fun apercuKey(e: ByteArray, s: ByteArray, eph: ByteArray, nk: ByteArray, from: String): ByteArray =
        hkdfSha256(e + s, eph + nk, "talka-apercu-v2:$from".toByteArray())

    /** Chiffre un aperçu (du JSON) de `me` pour la clé `nk`. */
    fun sealApercu(me: Identity, nk: String, plain: String): String? {
        val codeBytes = me.code.toByteArray()
        if (codeBytes.size != 10) return null
        val nkRaw = unb64(nk) ?: return null
        if (P256.point(nkRaw) == null) return null
        val mine = me.dhKey ?: return null
        val eph = P256.newPrivate()
        val ephRaw = P256.publicKey(eph) ?: return null
        val e = P256.ecdh(eph, nkRaw) ?: return null
        val st = P256.ecdh(mine, nkRaw) ?: return null
        val box = AesGcm.seal(apercuKey(e, st, ephRaw, nkRaw, me.code), plain.toByteArray(), "talka-apercu:${me.code}".toByteArray())
            ?: return null
        return b64(byteArrayOf(2) + codeBytes + ephRaw + box)
    }

    /**
     * Ouvre un aperçu avec la clé privée de notification (32 octets) : ce
     * que fait le service worker du navigateur ; ici pour les tests.
     * `dhOf` : la clé `dh` d'un contact connu.
     */
    fun openApercu(nkPriv: ByteArray, sealed: String, dhOf: (String) -> String?): Apercu? {
        val data = unb64(sealed) ?: return null
        if (data.size < 1 + 10 + 65 + 12 + 16 || data[0] != 2.toByte()) return null
        val from = utf8(data.copyOfRange(1, 11)) ?: return null
        val dh = dhOf(from)?.let(::unb64) ?: return null
        val nkPub = P256.publicKey(nkPriv) ?: return null
        val ephRaw = data.copyOfRange(11, 76)
        val e = P256.ecdh(nkPriv, ephRaw) ?: return null
        val st = P256.ecdh(nkPriv, dh) ?: return null
        val plain = AesGcm.open(apercuKey(e, st, ephRaw, nkPub, from), data, "talka-apercu:$from".toByteArray(), at = 76)
            ?: return null
        return utf8(plain)?.let { Apercu(from, it) }
    }

    /**
     * Scelle `body` (une enveloppe ordinaire de `from` pour `to`) :
     * base64(éphémère (65) ‖ iv (12) ‖ chiffré ‖ tag).
     */
    fun sealSender(from: String, to: String, toDh: String, body: String): String? = sealSender(from, "", to, toDh, body)

    /** Comme `sealSender`, en disant aussi de quel appareil (`fd`, v4) vient l'enveloppe. */
    fun sealSender(from: String, fd: String, to: String, toDh: String, body: String): String? {
        val toRaw = unb64(toDh) ?: return null
        if (P256.point(toRaw) == null) return null
        val eph = P256.newPrivate()
        val ephRaw = P256.publicKey(eph) ?: return null
        val secret = P256.ecdh(eph, toRaw) ?: return null
        val box = AesGcm.seal(sealedKey(secret, ephRaw, toRaw, to), sealedPlain(from, fd, body), "talka-scelle:$to".toByteArray())
            ?: return null
        return b64(ephRaw + box)
    }

    /**
     * Ouvre une enveloppe scellée pour nous : (code de l'expéditeur,
     * enveloppe ordinaire). L'expéditeur n'est prouvé qu'en ouvrant celle-ci.
     */
    fun openSealed(me: Identity, sealed: String): Opened? = openSealedFrom(me, sealed)?.let { Opened(it.from, it.body) }

    /**
     * Ouvre une enveloppe scellée : (code de l'expéditeur, son appareil
     * (vide pour un ancien client), enveloppe ordinaire).
     */
    fun openSealedFrom(me: Identity, sealed: String): OpenedFrom? {
        val data = unb64(sealed) ?: return null
        if (data.size < 65 + 12 + 16) return null
        val myDh = unb64(me.dh) ?: return null
        val ephRaw = data.copyOfRange(0, 65)
        val secret = me.dhKey?.let { P256.ecdh(it, ephRaw) } ?: return null
        val plain = AesGcm.open(sealedKey(secret, ephRaw, myDh, me.code), data, "talka-scelle:${me.code}".toByteArray(), at = 65)
            ?: return null
        val s = SealedSender.parse(plain) ?: return null
        if (TalkaCode.normalize(s.from) != s.from) return null
        return OpenedFrom(s.from, if (TalkaV4.isDev(s.fd)) s.fd else "", s.body)
    }

    // ----- Le code de verrouillage et la sauvegarde -----

    const val pinRounds = 120_000
    const val backupRounds = 200_000

    /** PBKDF2-HMAC-SHA256, 32 octets. */
    fun pbkdf2(secret: String, salt: ByteArray, rounds: Int): ByteArray {
        val g = PKCS5S2ParametersGenerator(SHA256Digest())
        g.init(secret.toByteArray(), salt, rounds)
        return (g.generateDerivedParameters(256) as KeyParameter).key
    }

    /**
     * Argon2id, comme le navigateur (`crypto::argon2id`) : 19 Mio, 2 passes
     * pour le code ; 46 Mio, 1 passe pour la sauvegarde.
     */
    val argonM: UInt = 19_456u
    val argonT: UInt = 2u
    val backupM: UInt = 47_104u
    val backupT: UInt = 1u

    /** Les nouveaux codes : 6 chiffres au moins (4 ou 5 restent acceptés). */
    const val pinMin = 6
    const val pinMax = 12

    fun argon2id(secret: String, salt: ByteArray, m: UInt, t: UInt): ByteArray? =
        try {
            val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(m.toInt())
                .withIterations(t.toInt())
                .withParallelism(1)
                .withSalt(salt)
                .build()
            val g = Argon2BytesGenerator()
            g.init(p)
            ByteArray(32).also { g.generateBytes(secret.toByteArray(), it) }
        } catch (e: RuntimeException) {
            null
        }

    /**
     * La clé de l'appareil enveloppée par la clé tirée du code : nonce (12)
     * ‖ AES-256-GCM, données associées « talka-cle » (comme
     * `crypto::lock_device_key` du navigateur).
     */
    fun lockDeviceKey(userKey: ByteArray, deviceKey: ByteArray): ByteArray? {
        if (userKey.size != 32) return null
        return AesGcm.seal(userKey, deviceKey, "talka-cle".toByteArray())
    }

    /** La clé de l'appareil, si `userKey` est la bonne. */
    fun unlockDeviceKey(userKey: ByteArray, locked: ByteArray): ByteArray? {
        if (userKey.size != 32 || locked.size != 12 + 32 + 16) return null
        return AesGcm.open(userKey, locked, "talka-cle".toByteArray())
    }

    fun hashPin(pin: String): PinHash {
        val salt = randomBytes(16)
        return PinHash(salt = b64(salt), hash = b64(argon2id(pin, salt, argonM, argonT) ?: ByteArray(0)), v = 2u, m = argonM, t = argonT)
    }

    /** Comparaison en temps constant. */
    internal fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun checkPin(pin: String, stored: PinHash): Boolean {
        val salt = unb64(stored.salt) ?: return false
        val hash = unb64(stored.hash) ?: return false
        return when {
            stored.v == 0u || stored.v == 1u -> same(pbkdf2(pin, salt, pinRounds), hash)
            stored.v == 2u && stored.m in 8u..(1u shl 20) && stored.t in 1u..10u ->
                argon2id(pin, salt, stored.m, stored.t)?.let { same(it, hash) } ?: false
            else -> false
        }
    }

    fun encryptBackup(password: String, plain: String): Backup? {
        val salt = randomBytes(16)
        val key = argon2id(password, salt, backupM, backupT) ?: return null
        val nonce = randomBytes(12)
        val box = AesGcm.sealParts(key, nonce, plain.toByteArray()) ?: return null
        return Backup(talka = 3, salt = b64(salt), iv = b64(nonce), data = b64(box), m = backupM, t = backupT)
    }

    sealed class BackupError(message: String) : Exception(message) {
        class NotBackup : BackupError("Ce fichier n'est pas une sauvegarde Talka.")

        class WrongPassword : BackupError("Mot de passe incorrect.")
    }

    /** Le contenu d'une sauvegarde ; lève [BackupError]. */
    fun decryptBackup(password: String, file: Backup): String {
        val salt = unb64(file.salt) ?: throw BackupError.NotBackup()
        val iv = unb64(file.iv)?.takeIf { it.size == 12 } ?: throw BackupError.NotBackup()
        val data = unb64(file.data)?.takeIf { it.size >= 16 } ?: throw BackupError.NotBackup()
        val key = when {
            file.talka == 2 -> pbkdf2(password, salt, backupRounds)
            file.talka == 3 && file.m in 8u..(1u shl 21) && file.t in 1u..10u ->
                argon2id(password, salt, file.m, file.t) ?: throw BackupError.NotBackup()
            else -> throw BackupError.NotBackup()
        }
        val plain = AesGcm.openParts(key, iv, data) ?: throw BackupError.WrongPassword()
        return utf8(plain) ?: throw BackupError.NotBackup()
    }
}

/** Le code de verrouillage, haché : jamais gardé en clair. */
data class PinHash(
    val salt: String,
    val hash: String,
    /** 2 : Argon2id (`m`, `t`) ; 0 : PBKDF2 (les codes d'avant). */
    val v: UInt = 0u,
    val m: UInt = 0u,
    val t: UInt = 0u,
) : JsonEncodable {
    /** Un code d'avant (PBKDF2), à rehacher. */
    val isOld: Boolean get() = v < 2u

    /** Comme le navigateur : `v`, `m`, `t` seulement s'il y en a. */
    override fun toJson(): JsonElement =
        buildJsonObject {
            put("salt", salt)
            put("hash", hash)
            if (v != 0u) put("v", v)
            if (m != 0u) put("m", m)
            if (t != 0u) put("t", t)
        }

    companion object : JsonDecodable<PinHash> {
        override fun fromJson(json: JsonElement): PinHash {
            val c = json.obj()
            return PinHash(c.reqStr("salt"), c.reqStr("hash"), c.uint("v"), c.uint("m"), c.uint("t"))
        }
    }
}

/** Une sauvegarde chiffrée : le même fichier que celle du navigateur. */
data class Backup(
    val talka: Int,
    val salt: String,
    val iv: String,
    val data: String,
    /** Argon2id (`talka` 3) : mémoire (Kio) et passes. */
    val m: UInt = 0u,
    val t: UInt = 0u,
) : JsonEncodable {
    override fun toJson(): JsonElement =
        buildJsonObject {
            put("talka", talka)
            put("salt", salt)
            put("iv", iv)
            put("data", data)
            if (m != 0u) put("m", m)
            if (t != 0u) put("t", t)
        }

    companion object : JsonDecodable<Backup> {
        override fun fromJson(json: JsonElement): Backup {
            val c = json.obj()
            return Backup(c.reqInt("talka"), c.reqStr("salt"), c.reqStr("iv"), c.reqStr("data"), c.uint("m"), c.uint("t"))
        }
    }
}

// ----- Plusieurs appareils et boîtes tournantes (v4) -------------------------
//
// Comme `client/src/crypto.rs` (`docs/SECURITE.md` §7.11 à §7.13) : les
// identifiants d'appareil, les boîtes aux lettres tournantes (une adresse qui
// change chaque jour, que seuls l'expéditeur et le destinataire savent
// calculer) et la liaison d'un nouvel appareil par QR code.

object TalkaV4 {
    /**
     * Le relais de Talka : un seul. Son adresse entre dans la dérivation des
     * boîtes tournantes, qui ne valent que pour lui.
     */
    const val relay = TalkaServer.official

    /** Un identifiant d'appareil : 16 caractères hexadécimaux minuscules. */
    fun isDev(dev: String): Boolean = dev.length == 16 && dev.all { it in '0'..'9' || it in 'a'..'f' }

    /** Un nouvel identifiant d'appareil (8 octets aléatoires). */
    fun newDev(): String = randomId(8)

    /** Le jour d'un horodatage (ms Unix, UTC) : les boîtes tournantes changent chaque jour. */
    fun day(ms: Double): Int = (maxOf(ms, 0.0) / 86_400_000).toInt()

    /** L'adresse d'une session v3 : `<code>/<dev>` (v4), ou le code. */
    fun address(code: String, dev: String): String = if (dev.isEmpty()) code else "$code/$dev"

    /**
     * La clé des boîtes entre deux codes : ECDH de leurs clés d'identité,
     * puis HKDF-SHA256 lié au relais (§7.13). Seuls les deux savent la
     * calculer.
     */
    fun boitesKey(me: Identity, peerCode: String, peerDh: String): ByteArray? {
        val raw = unb64(peerDh) ?: return null
        val secret = me.dhKey?.let { P256.ecdh(it, raw) } ?: return null
        val (a, b) = if (me.code < peerCode) me.code to peerCode else peerCode to me.code
        return hkdfSha256(secret, "talka-boites-v1:$relay".toByteArray(), "$a:$b".toByteArray())
    }

    /**
     * Une boîte de couverture (v6, §7.15) : à nous seuls, indiscernable
     * d'une boîte tournante. Comme `cover_box` du navigateur.
     */
    fun coverBox(secret: ByteArray, day: Int, i: Int): String =
        b64url(hmacSha256(secret, "talka-couverture:$day:$i".toByteArray()).copyOf(16))

    /**
     * La boîte de `day` où l'on écrit à l'appareil `dev` de `to` :
     * base64url(HMAC-SHA256(clé, "talka-boite:<to>/<dev>:<jour>")[0..16]).
     */
    fun boite(key: ByteArray, to: String, dev: String, day: Int): String =
        b64url(hmacSha256(key, "talka-boite:$to/$dev:$day".toByteArray()).copyOf(16))

    fun b64url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun unb64url(text: String): ByteArray? {
        var s = text.trimEnd('=')
        if (s.contains('+') || s.contains('/') || s.length % 4 == 1) return null
        s = s.replace('-', '+').replace('_', '/')
        s += "=".repeat((4 - s.length % 4) % 4)
        return unb64(s)
    }

    // ----- La liaison d'un appareil (§7.12) -----

    /** Le préfixe d'un lien de liaison (QR code). */
    const val linkPrefix = "talka-lien:"

    /**
     * Ce que montre le nouvel appareil : son `dev`, l'identifiant de liaison
     * et sa clé publique éphémère.
     */
    class LinkOffer(val dev: String, val lid: ByteArray, val lk: ByteArray) {
        /** `talka-lien:<base64url(0x01 ‖ dev ‖ lid ‖ lk)>`. */
        val text: String
            get() {
                val devBytes = (0 until dev.length / 2).mapNotNull { dev.substring(it * 2, it * 2 + 2).toIntOrNull(16)?.toByte() }
                return linkPrefix + b64url(byteArrayOf(1) + devBytes.toByteArray() + lid + lk)
            }

        /** La boîte de liaison, où le nouvel appareil attend. */
        val boite: String get() = b64url(sha256("talka-lien:".toByteArray() + lid).copyOf(16))

        override fun equals(other: Any?): Boolean =
            other is LinkOffer && other.dev == dev && other.lid.contentEquals(lid) && other.lk.contentEquals(lk)

        override fun hashCode(): Int = (dev.hashCode() * 31 + lid.contentHashCode()) * 31 + lk.contentHashCode()

        override fun toString(): String = "LinkOffer(dev=$dev, lid=${hex(lid)}, lk=${b64(lk)})"

        companion object {
            /** Lit un lien de liaison (collé, ou lu dans un QR code). */
            fun parse(text: String): LinkOffer? {
                val t = text.trim()
                if (!t.startsWith(linkPrefix)) return null
                val bytes = unb64url(t.substring(linkPrefix.length)) ?: return null
                if (bytes.size != 1 + 8 + 16 + 65 || bytes[0] != 1.toByte()) return null
                val lk = bytes.copyOfRange(25, bytes.size)
                if (P256.point(lk) == null) return null
                return LinkOffer(hex(bytes.copyOfRange(1, 9)), bytes.copyOfRange(9, 25), lk)
            }
        }
    }

    /**
     * Le côté secret de la liaison, gardé par le nouvel appareil le temps
     * qu'elle se fasse.
     */
    class LinkSecret private constructor(val offer: LinkOffer, private val privateKey: ByteArray) {
        /** Une offre neuve. */
        constructor() : this(P256.newPrivate())

        private constructor(key: ByteArray) : this(LinkOffer(newDev(), randomBytes(16), P256.publicKey(key)!!), key)

        /** La clé de liaison, à partir de la clé éphémère de l'appareil du compte. */
        fun key(ek: ByteArray): ByteArray? {
            val shared = P256.ecdh(privateKey, ek) ?: return null
            return linkKey(shared, offer.lid, ek, offer.lk)
        }

        companion object {
            /** Une offre déjà montrée et sa clé privée (32 octets) : les tests. */
            fun of(offer: LinkOffer, privateKey: ByteArray): LinkSecret? {
                val pub = P256.publicKey(privateKey) ?: return null
                if (!pub.contentEquals(offer.lk)) return null
                return LinkSecret(offer, privateKey.copyOf())
            }
        }
    }

    internal fun linkKey(shared: ByteArray, lid: ByteArray, ek: ByteArray, lk: ByteArray): ByteArray =
        hkdfSha256(shared, lid, "talka-lien-v1:".toByteArray() + ek + lk)

    /** La réponse de l'appareil du compte : sa clé éphémère publique, et la clé de liaison. */
    class LinkAnswer(val ek: ByteArray, val key: ByteArray)

    /** L'appareil du compte répond à une offre. */
    fun linkAnswer(offer: LinkOffer): LinkAnswer? {
        if (P256.point(offer.lk) == null) return null
        val eph = P256.newPrivate()
        val ek = P256.publicKey(eph) ?: return null
        val shared = P256.ecdh(eph, offer.lk) ?: return null
        return LinkAnswer(ek, linkKey(shared, offer.lid, ek, offer.lk))
    }

    /** Le code de vérification d'une liaison, le même sur les deux appareils. */
    fun linkVerification(ek: ByteArray, lk: ByteArray): String {
        val d = sha256("talka-lien-verif:".toByteArray() + ek + lk)
        val n = ByteBuffer.wrap(d, 0, 4).int.toLong().and(0xFFFF_FFFFL) % 1_000_000
        return "%03d %03d".format(n / 1000, n % 1000)
    }

    /** La taille d'un morceau de liaison (en clair). */
    const val linkChunk = 2 * 1024 * 1024

    /**
     * Chiffre ce que la liaison transporte, en morceaux : `{"c","e","i","n"}`
     * (JSON, clés triées), à déposer dans la boîte de liaison.
     */
    fun linkSeal(key: ByteArray, lid: ByteArray, ek: ByteArray, plain: ByteArray): List<String>? {
        val chunks = mutableListOf<ByteArray>()
        var start = 0
        do {
            val end = minOf(start + linkChunk, plain.size)
            chunks.add(plain.copyOfRange(start, end))
            start = end
        } while (start < plain.size)
        val n = chunks.size
        val lid64 = b64url(lid)
        return chunks.mapIndexed { i, chunk ->
            val box = AesGcm.seal(key, chunk, "talka-lien:$lid64:$i/$n".toByteArray()) ?: return null
            JSON.write(
                buildJsonObject {
                    put("c", b64(box))
                    put("e", b64(ek))
                    put("i", i)
                    put("n", n)
                },
            )
        }
    }

    /** Un morceau de liaison ouvert : son indice, leur nombre, le clair. */
    class LinkPart(val i: Int, val n: Int, val plain: ByteArray)

    /** Ouvre un morceau de liaison. */
    fun linkOpen(key: ByteArray, lid: ByteArray, chunk: JsonObject): LinkPart? {
        val i = chunk["i"].asInt() ?: return null
        val n = chunk["n"].asInt() ?: return null
        if (n <= 0 || i < 0 || i >= n || n > 64) return null
        val bytes = chunk["c"].asString()?.let(::unb64)?.takeIf { it.size >= 12 + 16 } ?: return null
        val plain = AesGcm.open(key, bytes, "talka-lien:${b64url(lid)}:$i/$n".toByteArray()) ?: return null
        return LinkPart(i, n, plain)
    }
}
