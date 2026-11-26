// proto/vectors.json, retrouvé octet pour octet : chaque dérivation, chaque
// clé protégée, chaque jeton, chaque paquet, chaque étape des trois
// sessions (hybride, classique, clé partagée) et du groupe. Le scénario des
// sessions est celui du générateur (`proto/examples/vectors.rs`) : A envoie
// deux premiers messages, B les reçoit dans le désordre, répond, etc., avec
// les mêmes générateurs déterministes.
//
// Avec BouncyCastle, tout se compare : les signatures Ed25519 (RFC 8032,
// déterministes) et les chiffrés ML-KEM (l'aléa `m` des vecteurs est
// imposé), là où le portage Swift ne peut vérifier certains que par relecture.

package talka.brocolis

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VecteursTests {
    private val now: ULong = 1_790_000_000uL

    // MARK: Primitives

    @Test
    fun primitives() {
        val p = Depot.vecteurs().obj("primitives")
        val rkv = p.obj("kdf_rk")
        val (rk, ck) = Kdf.kdfRk(unhex(rkv, "rk"), unhex(rkv, "dh"))
        egal(hex(rk), rkv.str("rk_out"), "KDF_RK rk")
        egal(hex(ck), rkv.str("ck_out"), "KDF_RK ck")
        val ckv = p.obj("kdf_ck")
        val (ck2, mk) = Kdf.kdfCk(unhex(ckv, "ck"))
        egal(hex(ck2), ckv.str("ck_out"), "KDF_CK ck")
        egal(hex(mk), ckv.str("mk"), "KDF_CK mk")
        val mkv = p.obj("message_key")
        val (key, nonce) = Kdf.messageKey(unhex(mkv, "mk"))
        egal(hex(key), mkv.str("key"), "ENCRYPT(mk) clé")
        egal(hex(nonce), mkv.str("nonce"), "ENCRYPT(mk) nonce")
        for ((name, withSs) in listOf("sk_classic" to false, "sk_hybrid" to true)) {
            val v = p.obj(name)
            val sk = Kdf.skX3dh(unhex(v, "dh1"), unhex(v, "dh2"), unhex(v, "dh3"), if (withSs) unhex(v, "ss") else null)
            egal(hex(sk), v.str("sk"), name)
        }
        val psk = p.obj("sk_psk")
        val sk = Kdf.skPsk(unhex(psk, "key"))
        egal(hex(sk), psk.str("sk"), "SK PSK")
        val r = Kdf.pskRatchetSecret(sk)
        egal(hex(r), psk.str("psk_ratchet_secret"), "cliquet PSK")
        egal(hex(Kdf.x25519Public(r)), psk.str("psk_ratchet_public"), "cliquet PSK public")
        val sid = p.obj("session_id")
        egal(hex(Kdf.sessionId(unhex(sid, "ad"))), sid.str("id"), "id de session")
        val fp = p.obj("fingerprint")
        egal(Kdf.fingerprint(unhex(fp, "data")), fp.str("fingerprint"), "empreinte")
        val x = p.obj("x25519")
        egal(hex(Kdf.x25519Public(unhex(x, "secret"))), x.str("public"), "X25519 public")
        egal(hex(Kdf.x25519Public(unhex(x, "peer_secret"))), x.str("peer_public"), "X25519 public (pair)")
        egal(hex(Kdf.x25519(unhex(x, "secret"), unhex(x, "peer_public"))), x.str("shared"), "X25519")
        val m = p.obj("mlkem768")
        egal(hex(MlKem.publicKey(unhex(m, "seed"))), m.str("encapsulation_key"), "ML-KEM ek")
        egal(hex(MlKem.decapsulate(unhex(m, "seed"), unhex(m, "ciphertext"))), m.str("shared"), "ML-KEM décapsulation")
        // L'encapsulation avec l'aléa `m` des vecteurs : le même chiffré.
        val enc = MlKem.encapsulate(unhex(m, "encapsulation_key"), unhex(m, "m"))
        egal(hex(enc.ciphertext), m.str("ciphertext"), "ML-KEM encapsulation (chiffré)")
        egal(hex(enc.shared), m.str("shared"), "ML-KEM encapsulation (secret)")
        // L'encapsulation à l'aléa du système se relit aussi.
        val random = MlKem.encapsulate(unhex(m, "encapsulation_key"))
        assertContentEquals(random.shared, MlKem.decapsulate(unhex(m, "seed"), random.ciphertext))
    }

    // MARK: Remplissage

    @Test
    fun remplissage() {
        val p = Depot.vecteurs().obj("padding")
        assertEquals(Remplissage.bloc, p.int("block"))
        for (c in p.arr("cases")) {
            val o = c as kotlinx.serialization.json.JsonObject
            egal(hex(Remplissage.pad(unhex(o, "data"))), o.str("padded"), "remplissage")
            egal(hex(Remplissage.unpad(unhex(o, "padded"))), o.str("data"), "retrait")
        }
        for (bad in p.arr("invalid")) {
            attendreErreur(Erreur.Remplissage, bad.str()) { Remplissage.unpad(unhex(bad.str())) }
            Compteur.ajouter("vecteurs")
        }
    }

    // MARK: Clés protégées

    @Test
    fun clesProtegees() {
        val p = Depot.vecteurs().obj("protected_key")
        val key = unhex(p, "key")
        val v1 = p.obj("v1")
        val phrase1 = v1.str("phrase")
        egal(CleProtegee.protectKeyV1With(key, phrase1, unhex(v1, "salt"), unhex(v1, "nonce")), v1.str("token"), "clé protégée v1")
        val token1 = v1.str("token")
        egal(CleProtegee.unprotectKey("Voici : $token1 !", phrase1), key, "clé v1 relue")
        attendreErreur(Erreur.Dechiffrement) { CleProtegee.unprotectKey(token1, v1.str("wrong_phrase")) }
        for (e in p.arr("v2")) {
            val v = e as kotlinx.serialization.json.JsonObject
            val params = Argon2Params(v.int("m_kib").toUInt(), v.int("t").toUByte(), v.int("p").toUByte())
            val phrase = v.str("phrase")
            val name = v.str("name")
            val debut = System.nanoTime()
            val k = CleProtegee.argon2idKey(phrase, unhex(v, "salt"), params)
            println("Argon2id $name (m = ${params.mKib} Kio, t = ${params.t}) : ${"%.2f".format((System.nanoTime() - debut) / 1e9)} s")
            egal(hex(k), v.str("argon2id_key"), "Argon2id $name")
            val token = CleProtegee.protectKeyV2With(key, phrase, params, unhex(v, "salt"), unhex(v, "nonce"))
            egal(token, v.str("token"), "clé protégée v2 $name")
            egal(CleProtegee.v2Params(token), params, "paramètres v2 $name")
            egal(CleProtegee.unprotectKey(token, phrase), key, "clé v2 relue $name")
            attendreErreur(Erreur.Dechiffrement) { CleProtegee.unprotectKey(token, v.str("wrong_phrase")) }
        }
        // Les paramètres refusés : avant tout calcul.
        for (e in p.arr("v2_rejected_params")) {
            val r = e as kotlinx.serialization.json.JsonObject
            val m = r.int("m_kib").toUInt()
            val t = r.int("t").toUByte()
            val par = r.int("p").toUByte()
            attendreErreur(Erreur.Parametres) { Argon2Params(m, t, par).check() }
            val data = Octets.concat(byteArrayOf(0x02), Octets.u32be(m), byteArrayOf(t.toByte(), par.toByte())).copyOf(83)
            val token = CleProtegee.headV2 + Base64.encode(data) + "]]"
            attendreErreur(Erreur.Parametres) { CleProtegee.unprotectKey(token, "brocoli vert 2026") }
            Compteur.ajouter("vecteurs")
        }
    }

    // MARK: Jetons v1/v2

    @Test
    fun anciensJetons() {
        val l = Depot.vecteurs().obj("legacy_tokens")
        val keys = l.arr("keys").map { unhex(it.str()) }
        val rng = Deterministic(l.str("rng_seed"))
        val v1 = l.obj("v1")
        val v2 = l.obj("v2")
        val marked = l.obj("marked")
        // Le même générateur, dans le même ordre : les mêmes jetons.
        egal(Jetons.sealWith(v1.str("text"), listOf(keys[0]), rng), v1.str("token"), "jeton v1")
        egal(Jetons.sealWith(v2.str("text"), keys, rng), v2.str("token"), "jeton v2")
        egal(Jetons.sealWith(marked.str("input"), listOf(keys[0]), rng), marked.str("sealed"), "passages (( ))")
        egal(Jetons.open(v1.str("token"), keys).text, v1.str("text"), "v1 ouvert")
        egal(Jetons.open(v2.str("token"), listOf(keys[1])).text, v2.str("text"), "v2 ouvert")
        egal(Jetons.open(marked.str("sealed"), keys).text, marked.str("opened"), "(( )) ouverts")
    }

    // MARK: Sessions

    @Test
    fun sessions() {
        val cases = Depot.vecteurs().arr("sessions")
        assertEquals(3, cases.size)
        for (c in cases) session(c as kotlinx.serialization.json.JsonObject)
    }

    /** Un appareil des vecteurs, vérifié champ par champ. */
    private fun device(v: kotlinx.serialization.json.JsonObject, pq: Boolean): Device {
        val identity = IdentitySecret(unhex(v, "ik_x25519_secret"), unhex(v, "ik_ed25519_seed"))
        val spkId = v.int("spk_id").toUInt()
        val pqPart = if (pq) v.int("pq_id").toUInt() to unhex(v, "pq_seed") else null
        val d = Device.fromSecrets(identity, spkId to unhex(v, "spk_secret"), pqPart, now)
        val b = d.bundle()
        egal(hex(b.identity.dh), v.str("ik_x25519_public"), "IK X25519")
        egal(hex(b.identity.sign), v.str("ik_ed25519_public"), "IK Ed25519")
        egal(hex(b.spk), v.str("spk_public"), "SPK")
        egal(b.identity.fingerprint(), v.str("fingerprint"), "empreinte d'identité")
        if (pq) {
            egal(hex(assertNotNull(b.pq).key), v.str("pq_public"), "PQPK")
        } else {
            assertNull(b.pq)
        }
        val expected = unhex(v, "bundle")
        // Ed25519 de BouncyCastle est celui de RFC 8032 : les signatures aussi.
        egal(b.toBytes(), expected, "paquet")
        egal(hex(b.spkSignature), v.str("spk_signature"), "signature SPK")
        if (pq) egal(hex(assertNotNull(b.pq).signature), v.str("pq_signature"), "signature PQPK")
        // Le paquet des vecteurs se relit (signatures Ed25519 strictes).
        val parsed = KeyBundle.fromBytes(expected)
        egal(parsed.identity.fingerprint(), v.str("fingerprint"), "empreinte du paquet relu")
        egal(parsed.toBytes(), expected, "paquet relu")
        return d
    }

    /** Les clés du prochain message envoyé par `s`, comparées aux vecteurs. */
    private fun checkKeys(s: Session, keys: kotlinx.serialization.json.JsonObject, quoi: String) {
        val ck = assertNotNull(s.debugChainKeys.sending)
        egal(hex(ck), keys.str("ck"), "$quoi ck")
        val (ck2, mk) = Kdf.kdfCk(ck)
        egal(hex(mk), keys.str("mk"), "$quoi mk")
        egal(hex(ck2), keys.str("ck_next"), "$quoi ck suivant")
        val (key, nonce) = Kdf.messageKey(mk)
        egal(hex(key), keys.str("key"), "$quoi clé AES")
        egal(hex(nonce), keys.str("nonce"), "$quoi nonce")
    }

    private fun send(s: Session, step: kotlinx.serialization.json.JsonObject, from: String): Token {
        val text = step.str("text")
        assertEquals(from, step.str("from"))
        checkKeys(s, step.obj("keys"), text)
        val t = s.encryptToken(text)
        egal(t.encode(), step.str("token"), "jeton « $text »")
        egal(t.type.toInt(), step.int("type"), "type « $text »")
        egal(hex(Remplissage.pad(utf8(text))), step.str("padded"), "remplissage « $text »")
        val h = step.obj("header")
        val env = when (t) {
            is Token.Initial -> t.initial.envelope
            is Token.Message -> t.envelope
            else -> throw Erreur.Format
        }
        egal(hex(env.header.dh), h.str("dh"), "en-tête DH")
        egal(env.header.pn.toInt(), h.int("pn"), "en-tête PN")
        egal(env.header.n.toInt(), h.int("n"), "en-tête N")
        egal(hex(env.header.toBytes()), h.str("header"), "en-tête")
        egal(hex(env.ciphertext), h.str("ciphertext"), "chiffré")
        egal(hex(s.associatedData + env.header.toBytes()), step.str("aad"), "données associées")
        // Le jeton relu se réécrit à l'identique.
        val back = Token.decode(step.str("token"))
        egal(back.encode(), step.str("token"), "jeton relu")
        return t
    }

    private fun session(c: kotlinx.serialization.json.JsonObject) {
        val name = c.str("name")
        val mode = c.str("mode")
        val setup = c.obj("setup")
        val steps = c.arr("steps").map { it as kotlinx.serialization.json.JsonObject }
        assertEquals(now.toLong(), c["now"]!!.str().toLong())
        val rngA = Recorder("$name/alice/cliquet")
        val rngB = Recorder("$name/bob/cliquet")
        val a: Session
        val bobSide: (Initial) -> Pair<Session, Content>

        if (mode == "psk") {
            val key = unhex(setup, "key")
            egal(Base64.encode(key), setup.str("key_b64"), "clé PSK en base64")
            egal(hex(unhex(setup, "ek_secret")), hex(h32("$name/ek")), "EK")
            a = Session.initiatePskWith(key, unhex(setup, "ek_secret"))
            egal(hex(Kdf.x25519Public(unhex(setup, "ek_secret"))), setup.str("ek_public"), "EK public")
            val sk = Kdf.skPsk(key)
            egal(hex(sk), setup.str("sk"), "SK")
            val r = Kdf.pskRatchetSecret(sk)
            egal(hex(r), setup.str("psk_ratchet_secret"), "cliquet PSK")
            egal(hex(Kdf.x25519Public(r)), setup.str("psk_ratchet_public"), "cliquet PSK public")
            egal(hex(Kdf.x25519(unhex(setup, "ek_secret"), Kdf.x25519Public(r))), setup.str("alice_first_dh"), "premier DH")
            bobSide = { i -> Session.acceptPsk(listOf(key), i, ReplayGuard(), now, rngB) }
        } else {
            val pq = mode == "hybrid"
            val alice = device(setup.obj("alice"), pq)
            val bob = device(setup.obj("bob"), pq)
            val bobV = setup.obj("bob")
            val bundle = KeyBundle.fromBytes(unhex(bobV, "bundle"))
            val ek = unhex(setup, "ek_secret")
            egal(hex(Kdf.x25519Public(ek)), setup.str("ek_public"), "EK public")
            val ratchet = unhex(setup, "alice_ratchet_secret")
            egal(hex(Kdf.x25519Public(ratchet)), setup.str("alice_ratchet_public"), "cliquet d'Alice")
            val ika = alice.identitySecret
            val dh1 = Kdf.x25519(ika.dh, bundle.spk)
            val dh2 = Kdf.x25519(ek, bundle.identity.dh)
            val dh3 = Kdf.x25519(ek, bundle.spk)
            egal(hex(dh1), setup.str("dh1"), "DH1")
            egal(hex(dh2), setup.str("dh2"), "DH2")
            egal(hex(dh3), setup.str("dh3"), "DH3")
            var kem: KemEncapsulation? = null
            if (pq) {
                // L'encapsulation avec le `m` des vecteurs donne leur chiffré ;
                // la décapsulation de Bob, leur secret.
                val enc = MlKem.encapsulate(assertNotNull(bundle.pq).key, unhex(setup, "mlkem_m"))
                egal(hex(enc.ciphertext), setup.str("mlkem_ciphertext"), "ML-KEM CT")
                val ss = MlKem.decapsulate(unhex(bobV, "pq_seed"), enc.ciphertext)
                egal(hex(ss), setup.str("mlkem_shared"), "ML-KEM SS")
                egal(enc.shared, ss, "ML-KEM SS (encapsulation)")
                kem = enc
            } else {
                assertTrue(setup.isNull("mlkem_ciphertext"))
            }
            val sk = Kdf.skX3dh(dh1, dh2, dh3, kem?.shared)
            egal(hex(sk), setup.str("sk"), "SK")
            a = Session.initiateWith(ika, bundle, ek, ratchet, kem)
            egal(a.isPostQuantum, pq, "mode")
            egal(hex(Kdf.x25519(ratchet, bundle.spk)), setup.str("alice_first_dh"), "premier DH")
            bobSide = { i -> bob.accept(i, now, rngB) }
        }
        egal(hex(a.associatedData), setup.str("ad"), "AD")
        egal(hex(a.sessionId), setup.str("session_id"), "id de session")
        egal(hex(a.debugRootKey), setup.str("alice_root_key"), "racine d'Alice")
        egal(hex(assertNotNull(a.debugChainKeys.sending)), setup.str("alice_sending_chain"), "chaîne d'Alice")

        // A → B : deux premiers messages (0x01), le second reçu d'abord.
        val t1 = send(a, steps[0], "alice")
        val t2 = send(a, steps[1], "alice")
        val (b, c2) = bobSide(assertNotNull(t2.initialMessage))
        egal(texte(c2), "Tu me lis ?", "B lit le message 2")
        val ev2 = steps[2]
        egal(hex(rngB.take()), ev2.str("bob_ratchet_secret_drawn"), "secret tiré par Bob")
        egal(texte(b.decrypt(t1, now, rngB)), "Salut Bob 🥦", "B lit le message 1")
        egal(hex(b.ratchetPublic), ev2.str("bob_ratchet_public"), "cliquet de Bob")
        egal(hex(b.debugRootKey), ev2.str("bob_root_key"), "racine de Bob")
        assertEquals(0, rngB.take().size)
        // B → A (0x02) : A fait tourner le cliquet.
        val t3 = send(b, steps[3], "bob")
        assertFalse(a.isConfirmed)
        egal(texte(a.decrypt(t3, now, rngA)), "Oui, cinq sur cinq.", "A lit la réponse")
        assertTrue(a.isConfirmed)
        val ev4 = steps[4]
        egal(hex(rngA.take()), ev4.str("alice_ratchet_secret_drawn"), "secret tiré par Alice")
        egal(hex(a.ratchetPublic), ev4.str("alice_ratchet_public"), "cliquet d'Alice")
        egal(hex(a.debugRootKey), ev4.str("alice_root_key"), "racine d'Alice")
        // A → B (0x02), puis B → A.
        val t4 = send(a, steps[5], "alice")
        egal(texte(b.decrypt(t4, now, rngB)), "Parfait.", "B lit")
        val ev6 = steps[6]
        egal(hex(rngB.take()), ev6.str("bob_ratchet_secret_drawn"), "secret tiré par Bob")
        egal(hex(b.ratchetPublic), ev6.str("bob_ratchet_public"), "cliquet de Bob")
        egal(hex(b.debugRootKey), ev6.str("bob_root_key"), "racine de Bob")
        val t5 = send(b, steps[7], "bob")
        egal(texte(a.decrypt(t5, now, rngA)), "", "A lit le texte vide")
        egal(hex(rngA.take()), steps[8].str("alice_ratchet_secret_drawn"), "secret tiré par Alice")
        assertEquals(9, steps.size)
        // Rejeux : ignorés.
        attendreErreur(Erreur.Rejoue) { b.decrypt(t4, now, rngB) }
        attendreErreur(Erreur.Rejoue) { a.decrypt(t3, now, rngA) }
        println("Session « $name » des vecteurs retrouvée")
    }

    // MARK: Groupe

    @Test
    fun groupe() {
        val g = Depot.vecteurs().obj("group")
        val gid = unhex(g, "group_id")
        egal(gid, h32("groupe/id").copyOf(16), "id de groupe")
        val s = SenderKeyState.generateWith(gid, unhex(g, "chain_key"), unhex(g, "signing_seed"))
        val d = s.distribution()
        egal(hex(d.signingPublic), g.str("signing_public"), "clé publique de la chaîne")
        egal(hex(d.senderId), g.str("sender_id"), "id d'expéditeur")
        egal(hex(d.toBytes()), g.str("distribution"), "distribution")
        val received = ReceivedSenderKey.fromDistribution(SenderKeyDistribution.fromBytes(unhex(g, "distribution")))
        var chain = unhex(g, "chain_key")
        for (e in g.arr("messages")) {
            val m = e as kotlinx.serialization.json.JsonObject
            val text = m.str("text")
            egal(hex(chain), m.str("chain_key"), "chaîne $text")
            val (next, mk) = Kdf.kdfCk(chain)
            egal(hex(mk), m.str("mk"), "mk $text")
            val (key, nonce) = Kdf.messageKey(mk)
            egal(hex(key), m.str("key"), "clé $text")
            egal(hex(nonce), m.str("nonce"), "nonce $text")
            val t = s.encryptToken(text)
            val gm = assertNotNull(t.groupMessage)
            egal(gm.iteration.toInt(), m.int("iteration"), "itération $text")
            egal(hex(gm.prefix()), m.str("aad"), "données associées $text")
            egal(hex(gm.ciphertext), m.str("ciphertext"), "chiffré $text")
            egal(hex(gm.signature), m.str("signature"), "signature $text")
            egal(t.encode(), m.str("token"), "jeton $text")
            // Le jeton des vecteurs se lit (signature stricte vérifiée).
            val theirs = assertNotNull(Token.decode(m.str("token")).groupMessage)
            egal(received.decrypt(theirs, now), text, "lu $text")
            chain = next
        }
        // La distribution par une session à clé partagée (type 0x03).
        val dv = g.obj("distribution_via_psk_session")
        val key = unhex(dv, "key")
        val ek = unhex(dv, "ek_secret")
        val sess = Session.initiatePskWith(key, ek)
        egal(sess.encryptDistribution(d), dv.str("token"), "distribution 0x03")
        // Le répondeur de cette session (§7.4) la lit.
        val sk = Kdf.skPsk(key)
        val r = Kdf.pskRatchetSecret(sk)
        val responder = Session.newResponder(
            Mode.Psk, sk, Kdf.x25519Public(ek) + Kdf.x25519Public(r), r, Kdf.x25519Public(ek), null,
        )
        val content = responder.decryptText(dv.str("token"), now, Deterministic("x"))
        egal(content, Content.Distribution(d), "distribution lue")
        // Relue comme un 0x02, elle est refusée (type authentifié).
        val env = (Token.decode(dv.str("token")) as Token.Distribution).envelope
        val other = Session.newResponder(
            Mode.Psk, sk, Kdf.x25519Public(ek) + Kdf.x25519Public(r), r, Kdf.x25519Public(ek), null,
        )
        attendreErreur(Erreur.Dechiffrement) { other.decrypt(Token.Message(env), now, Deterministic("x")) }
    }

    @AfterTest
    fun bilan() {
        println("Valeurs des vecteurs retrouvées (cumul) : ${Compteur.total("vecteurs")}")
    }
}
