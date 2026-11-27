// Les vecteurs Wycheproof (C2SP/wycheproof, `testvectors_v1`, Apache 2.0 :
// src/test/resources/wycheproof/LICENSE) des primitives du protocole, passés
// aux fonctions du paquet elles-mêmes, avec la même politique que la
// référence (proto/tests/wycheproof.rs). Chaque cas « invalid » doit être
// refusé.

package talka.brocolis

import org.junit.jupiter.api.AfterAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.math.ec.rfc8032.Ed25519
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WycheproofTests {
    private fun tests(v: JsonObject): List<Pair<JsonObject, JsonObject>> =
        v.arr("testGroups").flatMap { g -> g.jsonObject.arr("tests").map { g.jsonObject to it.jsonObject } }

    private fun flags(t: JsonObject): List<String> = t["flags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun compter(n: Int, quoi: String) {
        Compteur.ajouter("wycheproof", n)
        println("Wycheproof $quoi : $n cas")
    }

    private fun JsonObject.id(): Int = this["tcId"]?.jsonPrimitive?.content?.toInt() ?: -1

    private fun JsonObject.valid(): Boolean = str("result") == "valid"

    @Test
    fun aes256Gcm() {
        var n = 0
        for ((g, t) in tests(Depot.wycheproof("aes_gcm_test.json"))) {
            // Le protocole n'utilise qu'AES-256, nonce 96 bits, tag 128 bits.
            if (g.int("keySize") != 256 || g.int("ivSize") != 96 || g.int("tagSize") != 128) continue
            val id = t.id()
            val key = unhex(t, "key")
            val iv = unhex(t, "iv")
            val aad = unhex(t, "aad")
            val msg = unhex(t, "msg")
            val ct = unhex(t, "ct") + unhex(t, "tag")
            if (t.valid()) {
                assertContentEquals(ct, Kdf.aesGcmSeal(key, iv, aad, msg), "tcId $id")
                assertContentEquals(msg, Kdf.aesGcmOpen(key, iv, aad, ct), "tcId $id")
            } else {
                attendreEchec("tcId $id") { Kdf.aesGcmOpen(key, iv, aad, ct) }
            }
            n++
        }
        assertTrue(n >= 60)
        compter(n, "AES-256-GCM")
    }

    @Test
    fun x25519() {
        var n = 0
        for ((_, t) in tests(Depot.wycheproof("x25519_test.json"))) {
            val id = t.id()
            val priv = unhex(t, "private")
            val pub = unhex(t, "public")
            val shared = unhex(t, "shared")
            val f = flags(t)
            val r = essai { Kdf.x25519(priv, pub) }
            // Notre politique : refuser un secret nul (petit ordre) et une clé
            // publique non canonique (bit 255 levé ou u ≥ p). Tout le reste
            // doit donner exactement le secret attendu.
            if ("ZeroSharedSecret" in f || "NonCanonicalPublic" in f) {
                assertNull(r, "tcId $id $f")
            } else if (!t.valid() && t.str("result") == "invalid") {
                assertTrue(r == null || !r.contentEquals(shared), "tcId $id")
            } else {
                assertContentEquals(shared, r, "tcId $id $f")
            }
            n++
        }
        assertTrue(n > 500)
        compter(n, "X25519")
    }

    @Test
    fun hkdfSha256() {
        var n = 0
        for ((_, t) in tests(Depot.wycheproof("hkdf_sha256_test.json"))) {
            val size = t.int("size")
            val id = t.id()
            val r = essai { Kdf.hkdfSha256(unhex(t, "salt"), unhex(t, "ikm"), unhex(t, "info"), size) }
            if (t.valid()) {
                assertContentEquals(unhex(t, "okm"), r, "tcId $id")
            } else {
                assertNull(r, "tcId $id")
            }
            n++
        }
        assertTrue(n > 80)
        compter(n, "HKDF-SHA256")
    }

    @Test
    fun hmacSha256() {
        var n = 0
        for ((g, t) in tests(Depot.wycheproof("hmac_sha256_test.json"))) {
            val bits = g.int("tagSize")
            val tag = Kdf.hmacSha256(unhex(t, "key"), unhex(t, "msg"))
            val same = tag.copyOf(bits / 8).contentEquals(unhex(t, "tag"))
            assertEquals(t.valid(), same, "tcId ${t.id()}")
            n++
        }
        compter(n, "HMAC-SHA256")
    }

    /**
     * La vérification **stricte** : chaque cas « invalid » de Wycheproof est
     * refusé (dont S non canonique et les points de petit ordre), chaque cas
     * « valid » accepté.
     */
    @Test
    fun ed25519Strict() {
        var n = 0
        var lents = 0
        for ((g, t) in tests(Depot.wycheproof("ed25519_test.json"))) {
            val pk = unhex(g.obj("publicKey"), "pk")
            val sig = unhex(t, "sig")
            val ok = essai { Kdf.ed25519Verify(pk, unhex(t, "msg"), sig) } != null
            assertEquals(t.valid(), ok, "tcId ${t.id()} ${t["comment"]}")
            // L'équation calculée ici juge comme celle de BouncyCastle (une
            // fois A, R et S contrôlés).
            if (pk.size == 32 && sig.size == 64 && !Ed25519Strict.isWeak(pk)) {
                val r = sig.copyOf(32)
                val s = sig.copyOfRange(32, 64)
                if (Ed25519Strict.isCanonicalScalar(s) && !Ed25519Strict.isWeak(r)) {
                    assertEquals(
                        Ed25519Strict.verifyEquation(pk, r, s, unhex(t, "msg")),
                        Ed25519Strict.verifyEquationSlow(pk, r, s, unhex(t, "msg")),
                        "tcId ${t.id()} (équation)",
                    )
                    lents++
                }
            }
            n++
        }
        assertTrue(n > 140)
        assertTrue(lents > 50)
        println("Wycheproof Ed25519 : équation d'ici comparée à BouncyCastle sur $lents cas")
        compter(n, "Ed25519")
    }

    /**
     * Ce que la vérification de BouncyCastle seule accepterait (ou refuserait)
     * autrement que la vérification stricte de la référence : la preuve que
     * les contrôles d'ici servent, et la signature « universelle » refusée.
     */
    @Test
    fun ed25519BibliothequeSeule() {
        fun raw(pk: ByteArray, msg: ByteArray, sig: ByteArray): Boolean =
            pk.size == 32 && sig.size == 64 && Ed25519.verify(sig, 0, pk, 0, msg, 0, msg.size)
        var rawWrong = 0
        for ((g, t) in tests(Depot.wycheproof("ed25519_test.json"))) {
            val pk = unhex(g.obj("publicKey"), "pk")
            if (raw(pk, unhex(t, "msg"), unhex(t, "sig")) != t.valid()) rawWrong++
        }
        println("Wycheproof Ed25519 : $rawWrong cas mal jugés par BouncyCastle seul, 0 en strict")
        // A = R = l'identité (petit ordre), S = 0 : l'équation
        // [S]B = R + [k]A tient pour tout message. La vérification stricte la
        // refuse.
        val identity = ByteArray(32).also { it[0] = 1 }
        val forged = identity + ByteArray(32)
        val msg = utf8("n'importe quel message")
        println("Signature universelle (A = R = identité, S = 0) acceptée par BouncyCastle seul : ${raw(identity, msg, forged)}")
        attendreErreur(Erreur.CleInvalide) { Kdf.ed25519Verify(identity, msg, forged) }
        // S + L (malléabilité) sur une vraie signature.
        val seed = h32("ed25519/s+l")
        val pk = Kdf.ed25519Public(seed)
        val sig = Kdf.ed25519Sign(seed, msg)
        val s = sig.copyOfRange(32, 64)
        var carry = 0
        val l = Ed25519Strict.order
        for (i in 0 until 32) {
            val v = (s[i].toInt() and 0xff) + (l[i].toInt() and 0xff) + carry
            s[i] = (v and 0xff).toByte()
            carry = v shr 8
        }
        println("S + L accepté par BouncyCastle seul : ${raw(pk, msg, sig.copyOf(32) + s)}")
        attendreErreur(Erreur.Signature) { Kdf.ed25519Verify(pk, msg, sig.copyOf(32) + s) }
        // R de petit ordre avec A valide : refusé.
        attendreErreur(Erreur.Signature) { Kdf.ed25519Verify(pk, msg, identity + sig.copyOfRange(32, 64)) }
        // La vraie signature passe, ici et dans BouncyCastle.
        Kdf.ed25519Verify(pk, msg, sig)
        assertTrue(raw(pk, msg, sig))
    }

    @Test
    fun mlKem768() {
        var n = 0
        for ((_, t) in tests(Depot.wycheproof("mlkem_768_test.json"))) {
            val id = t.id()
            val seed = unhex(t, "seed")
            val valid = t.valid()
            if (seed.size != 64) {
                // Graine de mauvaise longueur : refusée.
                assertTrue(!valid, "tcId $id")
                attendreErreur(Erreur.Format, "tcId $id") { MlKem.decapsulate(seed, unhex(t, "c")) }
                n++
                continue
            }
            if (t["ek"] != null) {
                assertContentEquals(unhex(t, "ek"), MlKem.publicKey(seed), "tcId $id")
                assertTrue(MlKem.isValidPublicKey(unhex(t, "ek")), "tcId $id")
            }
            val r = essai { MlKem.decapsulate(seed, unhex(t, "c")) }
            if (valid) {
                assertContentEquals(unhex(t, "K"), r, "tcId $id")
            } else {
                assertNull(r, "tcId $id")
            }
            n++
        }
        assertTrue(n > 190)
        compter(n, "ML-KEM-768")
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun bilan() {
            println("Cas Wycheproof vérifiés : ${Compteur.total("wycheproof")}")
        }
    }
}
