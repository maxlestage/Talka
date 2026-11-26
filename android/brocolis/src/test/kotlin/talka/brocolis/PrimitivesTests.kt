// Les primitives (BLAKE2b, Argon2, base64 strict, contrôles Ed25519 et
// ML-KEM) contre leurs RFC, et les unitaires de la référence (kdf.rs,
// remplissage.rs, cle_protegee.rs).

package talka.brocolis

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrimitivesTests {
    // MARK: BLAKE2b et Argon2

    @Test
    fun blake2bRfc7693() {
        // RFC 7693, annexe A : BLAKE2b-512("abc").
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(Blake2b.hash(utf8("abc"), 64)),
        )
        // BLAKE2b-512("") et BLAKE2b-256("").
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419" +
                "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            hex(Blake2b.hash(ByteArray(0), 64)),
        )
        assertEquals("0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8", hex(Blake2b.hash(ByteArray(0), 32)))
        // Plusieurs blocs, dont un dernier bloc complet.
        val input = ByteArray(256) { it.toByte() }
        assertEquals(64, Blake2b.hash(input, 64).size)
        assertFalse(Blake2b.hash(input.copyOf(128), 64).contentEquals(Blake2b.hash(input.copyOf(129), 64)))
    }

    /** RFC 9106 §5 : Argon2d, Argon2i, Argon2id (m = 32, t = 3, p = 4, secret et données associées). */
    @Test
    fun argon2Rfc9106() {
        val password = repeated(0x01, 32)
        val salt = repeated(0x02, 16)
        val secret = repeated(0x03, 8)
        val ad = repeated(0x04, 12)
        val expected = listOf(
            Argon2.Variant.D to "512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb",
            Argon2.Variant.I to "c814d9d1dc7f37aa13f0d77f2494bda1c8de6b016dd388d29952a4c4672b6ce8",
            Argon2.Variant.ID to "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
        )
        for ((variant, tag) in expected) {
            val out = Argon2.hash(
                variant = variant, password = password, salt = salt, secret = secret, associatedData = ad,
                memoryKiB = 32u, passes = 3u, lanes = 4u, length = 32,
            )
            egal(hex(out), tag, "Argon2$variant RFC 9106", categorie = "rfc")
        }
        attendreErreur(Erreur.Parametres) {
            Argon2.hash(variant = Argon2.Variant.ID, password = ByteArray(0), salt = salt, memoryKiB = 31u, passes = 3u, lanes = 4u, length = 32)
        }
        attendreErreur(Erreur.Parametres) {
            Argon2.hash(variant = Argon2.Variant.ID, password = ByteArray(0), salt = salt, memoryKiB = 32u, passes = 0u, lanes = 4u, length = 32)
        }
        attendreErreur(Erreur.Parametres) {
            Argon2.hash(variant = Argon2.Variant.ID, password = ByteArray(0), salt = salt, memoryKiB = 32u, passes = 1u, lanes = 4u, length = 3)
        }
    }

    /** Une sortie longue (H' au-delà de 64 octets) et plusieurs voies. */
    @Test
    fun argon2SortieLongue() {
        val out = Argon2.hash(
            variant = Argon2.Variant.ID, password = utf8("brocoli"), salt = repeated(0x55, 16),
            memoryKiB = 64u, passes = 2u, lanes = 2u, length = 100,
        )
        assertEquals(100, out.size)
    }

    // MARK: Base64 strict

    @Test
    fun base64Strict() {
        assertContentEquals(ByteArray(0), Base64.decode(""))
        assertContentEquals(bytes(0), Base64.decode("AA=="))
        assertContentEquals(bytes(0, 0), Base64.decode("AAA="))
        assertContentEquals(bytes(0, 0, 0), Base64.decode("AAAA"))
        assertEquals("+/8=", Base64.encode(bytes(0xfb, 0xff)))
        assertContentEquals(bytes(0xfb, 0xff), Base64.decode("+/8="))
        for (bad in listOf("A", "AA", "AAA", "A===", "AB==", "AAB=", "=AAA", "AA=A", "AA==AAAA", " AAA", "AAA\n", "AA-_", "AAAA=", "AAé=")) {
            assertNull(Base64.decode(bad), bad)
        }
        for (n in 0 until 40) {
            val b = ByteArray(n) { (it * 37 + 11).toByte() }
            assertContentEquals(b, Base64.decode(Base64.encode(b)))
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), Base64.encode(b))
        }
    }

    // MARK: Primitives du protocole (kdf.rs)

    @Test
    fun hkdfRfc5869Cas1() {
        val okm = Kdf.hkdfSha256(ByteArray(13) { it.toByte() }, repeated(0x0b, 22), ByteArray(10) { (0xf0 + it).toByte() }, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex(okm))
        // Un sel nul de 32 octets vaut l'absence de sel (RFC 5869 §2.2).
        assertContentEquals(
            Kdf.hkdfSha256(ByteArray(0), utf8("ikm"), utf8("info"), 44),
            Kdf.hkdfSha256(Kdf.selNul, utf8("ikm"), utf8("info"), 44),
        )
        attendreErreur(Erreur.TropGrand) { Kdf.hkdfSha256(ByteArray(0), bytes(1), ByteArray(0), 255 * 32 + 1) }
        assertEquals(0, Kdf.hkdfSha256(ByteArray(0), bytes(1), ByteArray(0), 0).size)
    }

    @Test
    fun x25519Rfc7748() {
        val a = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(Kdf.x25519Public(a)))
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hex(Kdf.x25519(a, Kdf.x25519Public(b))))
        attendreErreur(Erreur.CleInvalide) { Kdf.x25519(a, ByteArray(32)) }
        // La même clé, bit 255 levé : refusée.
        val bPub = Kdf.x25519Public(b)
        bPub[31] = (bPub[31].toInt() or 0x80).toByte()
        attendreErreur(Erreur.CleInvalide) { Kdf.x25519(a, bPub) }
        val p = repeated(0xff, 32)
        p[31] = 0x7f
        p[0] = 0xed.toByte()
        assertFalse(Kdf.x25519IsCanonical(p))
        p[0] = 0xec.toByte()
        assertTrue(Kdf.x25519IsCanonical(p))
        assertFalse(Kdf.x25519IsCanonical(bytes(1, 2, 3)))
    }

    @Test
    fun fingerprintFormat() {
        val f = Kdf.fingerprint(repeated(7, 32))
        assertEquals("4BB0-6F8E-4E3A-7715", f)
        assertEquals(19, f.length)
    }

    // MARK: Ed25519 strict

    @Test
    fun constantesEd25519() {
        // d = −121665/121666 mod p.
        assertEquals("a3785913ca4deb75abd841414d0a700098e879777940c78c73fe6f2bee6c0352", hex(Ed25519Strict.pack(Ed25519Strict.d)))
        // √−1² = −1.
        assertContentEquals(
            Ed25519Strict.pack(Ed25519Strict.sub(Ed25519Strict.zero, Ed25519Strict.one)),
            Ed25519Strict.pack(Ed25519Strict.sq(Ed25519Strict.sqrtM1)),
        )
        // Le point de base est un point d'ordre L, pas de petit ordre.
        val base = unhex("5866666666666666666666666666666666666666666666666666666666666666")
        assertNotNull(Ed25519Strict.decode(base))
        assertFalse(Ed25519Strict.isWeak(base))
        // Et il se réécrit à l'identique ; [L]B est l'identité.
        val point = assertNotNull(Ed25519Strict.decode(base))
        assertContentEquals(base, Ed25519Strict.encode(point))
        assertContentEquals(
            Ed25519Strict.encode(Ed25519Strict.identity()),
            Ed25519Strict.encode(Ed25519Strict.scalarMult(point, Ed25519Strict.order)),
        )
    }

    /**
     * Les écritures des points de petit ordre (la liste de libsodium, bit de
     * signe compris) sont toutes refusées ; les écritures qui ne sont pas des
     * points aussi.
     */
    @Test
    fun pointsDePetitOrdre() {
        val smallOrder = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )
        for (h in smallOrder) {
            val b = unhex(h)
            assertTrue(Ed25519Strict.isWeak(b), h)
            b[31] = (b[31].toInt() or 0x80).toByte()
            assertTrue(Ed25519Strict.isWeak(b), "$h (signe)")
        }
        // y = 2 n'est pas l'ordonnée d'un point de la courbe.
        val two = ByteArray(32).also { it[0] = 2 }
        assertNull(Ed25519Strict.decode(two))
        // Une clé publique faible est refusée avant toute vérification.
        attendreErreur(Erreur.CleInvalide) { Kdf.ed25519Verify(unhex(smallOrder[2]), ByteArray(0), ByteArray(64)) }
    }

    @Test
    fun scalaireCanonique() {
        val l = Ed25519Strict.order
        assertFalse(Ed25519Strict.isCanonicalScalar(l))
        val lm1 = l.copyOf().also { it[0] = (it[0] - 1).toByte() }
        assertTrue(Ed25519Strict.isCanonicalScalar(lm1))
        val lp1 = l.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(Ed25519Strict.isCanonicalScalar(lp1))
        assertTrue(Ed25519Strict.isCanonicalScalar(ByteArray(32)))
        // Une signature valide dont on ajoute L à S : refusée (malléabilité).
        val seed = h32("ed25519/malleable")
        val msg = utf8("message")
        val sig = Kdf.ed25519Sign(seed, msg)
        Kdf.ed25519Verify(Kdf.ed25519Public(seed), msg, sig)
        val s = sig.copyOfRange(32, 64)
        var carry = 0
        for (i in 0 until 32) {
            val v = (s[i].toInt() and 0xff) + (l[i].toInt() and 0xff) + carry
            s[i] = (v and 0xff).toByte()
            carry = v shr 8
        }
        attendreErreur(Erreur.Signature) { Kdf.ed25519Verify(Kdf.ed25519Public(seed), msg, sig.copyOf(32) + s) }
        // Un autre message, une autre clé : refusés.
        attendreErreur(Erreur.Signature) { Kdf.ed25519Verify(Kdf.ed25519Public(seed), utf8("massage"), sig) }
        attendreErreur(Erreur.Signature) { Kdf.ed25519Verify(Kdf.ed25519Public(h32("autre")), msg, sig) }
    }

    /**
     * L'équation calculée ici et celle de BouncyCastle jugent de même des
     * signatures honnêtes et altérées (clés canoniques).
     */
    @Test
    fun equationCommeBouncyCastle() {
        val rng = Deterministic("ed25519/equation")
        for (i in 0 until 64) {
            val seed = rng.bytes(32)
            val pk = Kdf.ed25519Public(seed)
            val msg = rng.bytes(i * 3)
            val sig = Kdf.ed25519Sign(seed, msg)
            if (i % 2 == 1) {
                val k = (rng.nextU32() % 64u).toInt()
                sig[k] = (sig[k].toInt() xor (1 shl (i % 8))).toByte()
            }
            val bc = org.bouncycastle.math.ec.rfc8032.Ed25519.verify(sig, 0, pk, 0, msg, 0, msg.size)
            assertEquals(bc, essai { Kdf.ed25519Verify(pk, msg, sig) } != null, "cas $i")
        }
    }

    // MARK: ML-KEM (contrôle du module)

    @Test
    fun controleDuModuleMlKem() {
        val seed = ByteArray(64) { it.toByte() }
        val ek = MlKem.publicKey(seed)
        assertTrue(MlKem.isValidPublicKey(ek))
        // Un coefficient = q = 3 329 (0xD01) : refusé.
        ek[0] = 0x01
        ek[1] = ((ek[1].toInt() and 0xF0) or 0x0D).toByte()
        assertFalse(MlKem.isValidPublicKey(ek))
        attendreErreur(Erreur.CleInvalide) { MlKem.encapsulate(ek) }
        assertFalse(MlKem.isValidPublicKey(ek.copyOf(1183)))
        attendreErreur(Erreur.Format) { MlKem.publicKey(ByteArray(63)) }
        attendreErreur(Erreur.Format) { MlKem.decapsulate(seed, ByteArray(1087)) }
    }

    // MARK: Remplissage (remplissage.rs)

    @Test
    fun remplissageLongueurs() {
        for ((n, attendu) in listOf(0 to 160, 1 to 160, 158 to 160, 159 to 160, 160 to 320, 319 to 320, 320 to 480)) {
            val data = repeated(0x41, n)
            val p = Remplissage.pad(data)
            assertEquals(attendu, p.size)
            assertEquals(0x80.toByte(), p[n])
            assertTrue(p.copyOfRange(n + 1, p.size).all { it == 0.toByte() })
            assertContentEquals(data, Remplissage.unpad(p))
        }
        val data = bytes(0x80, 0x00, 0x00, 0x80, 0x00)
        assertContentEquals(data, Remplissage.unpad(Remplissage.pad(data)))
        attendreErreur(Erreur.Remplissage) { Remplissage.unpad(ByteArray(0)) }
        attendreErreur(Erreur.Remplissage) { Remplissage.unpad(ByteArray(160)) }
        val p = Remplissage.pad(utf8("abc"))
        p[3] = 0x81.toByte()
        attendreErreur(Erreur.Remplissage) { Remplissage.unpad(p) }
        attendreErreur(Erreur.Remplissage) { Remplissage.unpad(Remplissage.pad(utf8("abc")).copyOf(159)) }
    }

    // MARK: Clés protégées (cle_protegee.rs)

    private val cle = ByteArray(32) { it.toByte() }

    @Test
    fun clePhraseCourteEtJetonsAbimes() {
        attendreErreur(Erreur.PhraseTropCourte) {
            CleProtegee.protectKeyV2With(cle, "court", Argon2Params.minimum, repeated(1, 16), repeated(2, 12))
        }
        // 8 caractères Unicode (pas 8 graphèmes, pas 8 octets, pas 8 unités UTF-16).
        attendreErreur(Erreur.PhraseTropCourte) {
            CleProtegee.protectKeyV2With(cle, "🥦🥦🥦🥦🥦🥦🥦", Argon2Params.minimum, repeated(1, 16), repeated(2, 12))
        }
        attendreErreur(Erreur.JetonAbsent) { CleProtegee.unprotectKey("rien", "x") }
        attendreErreur(Erreur.Format) { CleProtegee.unprotectKey("[[BROCOLIS-CLE:v1:AAAA]]", "x") }
        attendreErreur(Erreur.Base64) { CleProtegee.unprotectKey("[[BROCOLIS-CLE:v1:pas du base64]]", "x") }
        attendreErreur(Erreur.JetonAbsent) { CleProtegee.unprotectKey("[[BROCOLIS-CLE:v2:AAAA", "x") }
        val long = "[[BROCOLIS-CLE:v2:" + "A".repeat(10_000) + "]]"
        attendreErreur(Erreur.Format) { CleProtegee.unprotectKey(long, "x") }
        assertTrue(CleProtegee.hasProtectedKey("avant [[BROCOLIS-CLE:v2:AAAA]]"))
        assertFalse(CleProtegee.hasProtectedKey("[[BROCOLIS:v1:AAAA]]"))
    }

    @Test
    fun cleV2BornesEtEnTeteAuthentifie() {
        var data = ByteArray(0)
        for ((m, t, p, ok) in listOf(
            Quad(19_455u, 2, 1, false), Quad(19_456u, 1, 1, false), Quad(1_048_577u, 3, 1, false),
            Quad(19_456u, 2, 0, false), Quad(19_456u, 17, 1, false), Quad(UInt.MAX_VALUE, 255, 255, false),
            Quad(19_456u, 2, 1, true),
        )) {
            data = Octets.concat(byteArrayOf(0x02), Octets.u32be(m), byteArrayOf(t.toByte(), p.toByte())).copyOf(83)
            assertEquals(ok, essai { CleProtegee.parseV2Params(data) } != null, "$m $t $p")
        }
        data[0] = 0x03
        attendreErreur(Erreur.Format) { CleProtegee.parseV2Params(data) }

        val token = CleProtegee.protectKeyV2With(cle, "une phrase longue", Argon2Params.minimum, repeated(1, 16), repeated(2, 12))
        assertEquals(Argon2Params.minimum, CleProtegee.v2Params(token))
        assertContentEquals(cle, CleProtegee.unprotectKey(token, "une phrase longue"))
        val raw = assertNotNull(Base64.decode(token.substring(CleProtegee.headV2.length, token.length - 2)))
        // Chaque octet de l'en-tête (paramètres, sel, nonce) et quelques
        // octets du chiffré et du tag, modifiés : refusés.
        for (i in listOf(0, 1, 4, 5, 6, 7, 22, 23, 34, 35, 50, 66, 67, 82)) {
            val d = raw.copyOf()
            d[i] = (d[i].toInt() xor 0x01).toByte()
            val t = CleProtegee.headV2 + Base64.encode(d) + "]]"
            attendreEchec("octet $i") { CleProtegee.unprotectKey(t, "une phrase longue") }
        }
    }

    private data class Quad(val m: UInt, val t: Int, val p: Int, val ok: Boolean)

    @Test
    fun protectKeyParDefaut() {
        val token = CleProtegee.protectKey(cle, "une phrase longue", Deterministic("defaut"))
        assertEquals(Argon2Params.default, CleProtegee.v2Params(token))
        assertContentEquals(cle, CleProtegee.unprotectKey(token, "une phrase longue"))
    }

    // MARK: Hasard

    @Test
    fun deterministe() {
        val a = Deterministic("x")
        val b = Deterministic("x")
        val x = a.bytes(40)
        val y = b.bytes(7) + b.bytes(33)
        assertContentEquals(x, y)
        assertContentEquals(Kdf.sha256(utf8("Brocolis_vecteurs") + utf8("x") + ByteArray(8)), x.copyOf(32))
        assertNotEquals(hex(Hasard.system().bytes(32)), hex(Hasard.system().bytes(32)))
    }

    @AfterTest
    fun bilan() {
        println("Vecteurs des RFC (Argon2) retrouvés (cumul) : ${Compteur.total("rfc")}")
    }
}
