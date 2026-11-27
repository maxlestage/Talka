// Interopérabilité avec la référence Rust au-delà de vectors.json :
// reference-rust.json (copié de proto-swift/Tests/BrocolisProtoTests/Fixtures/)
// a été produit par la référence elle-même (programme reference-rust.rs, à
// côté de l'original) :
//
// - des états JSON écrits par Rust (appareils, sessions hybride et PSK,
//   chaînes de groupe, mémoire des premiers messages) : Kotlin les relit, les
//   réécrit sous la même forme JSON, et continue les conversations ;
// - le décodage des points Ed25519 de curve25519-dalek (point ou non, petit
//   ordre ou non), comparé à `Ed25519Strict` ;
// - Argon2id de la crate `argon2` (plusieurs voies, sortie longue).

package talka.brocolis

import org.junit.jupiter.api.AfterAll
import kotlinx.serialization.json.Json as KJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReferenceRustTests {
    private fun fixture(): JsonObject = Depot.referenceRust()

    private fun jsonText(o: JsonObject, k: String): String = assertNotNull(o[k]).toString()

    /** Même forme JSON : les deux textes donnent le même objet (l'ordre des champs n'y fait rien). */
    private fun memeForme(a: String, b: String, quoi: String) {
        assertEquals(KJson.parseToJsonElement(b), KJson.parseToJsonElement(a), quoi)
        Compteur.ajouter("rust")
    }

    @Test
    fun argon2idCommeLaCrateArgon2() {
        for (e in fixture().arr("argon2id")) {
            val c = e.jsonObject
            val m = c.int("m").toUInt()
            val t = c.int("t").toUInt()
            val p = c.int("p").toUInt()
            val len = c.int("len")
            val out = Argon2.hash(
                variant = Argon2.Variant.ID, password = utf8("brocoli"), salt = repeated(0x55, 16),
                memoryKiB = m, passes = t, lanes = p, length = len,
            )
            egal(hex(out), c.str("out"), "Argon2id m=$m t=$t p=$p $len octets", categorie = "rust")
        }
    }

    @Test
    fun pointsEd25519CommeDalek() {
        val cases = fixture().arr("ed25519_points")
        var points = 0
        var small = 0
        for (e in cases) {
            val c = e.jsonObject
            val enc = unhex(c, "enc")
            val isPoint = c["point"]!!.jsonPrimitive.bool()
            val isSmall = c["small"]!!.jsonPrimitive.bool()
            val decoded = Ed25519Strict.decode(enc)
            assertEquals(isPoint, decoded != null, hex(enc))
            if (decoded != null) assertEquals(isSmall, Ed25519Strict.isSmallOrder(decoded), hex(enc))
            // Une clé publique utilisable : un point, pas de petit ordre.
            assertEquals(isPoint && !isSmall, Kdf.ed25519IsValidPublic(enc), hex(enc))
            if (isPoint) points++
            if (isSmall) small++
            Compteur.ajouter("rust")
        }
        println("Points Ed25519 comparés à curve25519-dalek : ${cases.size} ($points points, $small de petit ordre)")
    }

    @Test
    fun etatsEtJetonsDeLaReference() {
        val e = fixture().obj("etats")
        val rng = Deterministic("phase2")
        val texts = e.arr("texts").map { it.str() }
        val tokens = e.arr("tokens").map { it.str() }

        // Les états relus se réécrivent sous la même forme JSON.
        val bobJson = jsonText(e, "bob_device")
        val bob = Device.fromJson(bobJson)
        memeForme(bob.toJson(), bobJson, "appareil")
        memeForme(Device.fromJson(jsonText(e, "alice_device")).toJson(), jsonText(e, "alice_device"), "appareil")
        val aliceJson = jsonText(e, "alice_session")
        val alice = Session.fromJson(aliceJson)
        memeForme(alice.toJson(), aliceJson, "session (préambule hybride compris)")
        val pskJson = jsonText(e, "psk_session")
        val pskA = Session.fromJson(pskJson)
        memeForme(pskA.toJson(), pskJson, "session PSK")
        val chainJson = jsonText(e, "chain")
        val chain = SenderKeyState.fromJson(chainJson)
        memeForme(chain.toJson(), chainJson, "chaîne de groupe")
        val receivedJson = jsonText(e, "received")
        val received = ReceivedSenderKey.fromJson(receivedJson)
        memeForme(received.toJson(), receivedJson, "chaîne reçue")
        val guardJson = jsonText(e, "replay_guard")
        val replay = ReplayGuard.fromJson(guardJson)
        memeForme(replay.toJson(), guardJson, "mémoire des premiers messages")
        assertTrue(replay.seen(repeated(9, 32)))

        // Une session riche (clés sautées, cliquets passés) : même forme, et
        // le message en retard s'ouvre par sa clé sautée gardée par Rust.
        val rich = e.obj("rich")
        val dJson = jsonText(rich, "d")
        val cJson = jsonText(rich, "c")
        val dSession = Session.fromJson(dJson)
        val cSession = Session.fromJson(cJson)
        memeForme(dSession.toJson(), dJson, "session avec clés sautées")
        memeForme(cSession.toJson(), cJson, "session avec cliquets passés")
        memeForme(Device.fromJson(jsonText(rich, "dave_device")).toJson(), jsonText(rich, "dave_device"), "appareil après un premier message")
        assertEquals(2, dSession.skippedCount)
        egal(texte(dSession.decryptText(rich.str("pending"), T0, rng)), rich.str("pending_text"), "clé sautée gardée par Rust", categorie = "rust")
        assertEquals(1, dSession.skippedCount)
        egal(texte(cSession.decryptText(dSession.encrypt("d2"), T0, rng)), "d2", "suite", categorie = "rust")
        val richRecvJson = jsonText(rich, "received")
        val richRecv = ReceivedSenderKey.fromJson(richRecvJson)
        memeForme(richRecv.toJson(), richRecvJson, "chaîne reçue avec clé sautée")
        egal(
            richRecv.decrypt(assertNotNull(Token.decode(rich.str("late_group")).groupMessage), T0),
            rich.str("late_group_text"), "message de groupe en retard", categorie = "rust",
        )

        // Le paquet de Bob (signé par Rust) se vérifie ; Kotlin le refait à l'identique.
        val bundle = KeyBundle.fromBase64(e.str("bob_bundle"))
        egal(bob.bundle(), bundle, "paquet refait par Kotlin", categorie = "rust")

        // Bob (Kotlin, état Rust) accepte les premiers messages d'Alice (Rust),
        // dans le désordre.
        val i2 = assertNotNull(Token.decode(tokens[1]).initialMessage)
        val (b, c2) = bob.accept(i2, T0, rng)
        egal(texte(c2), texts[1], "Rust → Kotlin (2)", categorie = "rust")
        egal(texte(b.decryptText(tokens[0], T0, rng)), texts[0], "Rust → Kotlin (1)", categorie = "rust")
        assertTrue(b.isPostQuantum)
        // La distribution (0x03) envoyée par Alice, puis le groupe.
        val d = (b.decryptText(e.str("distribution"), T0, rng) as? Content.Distribution)?.distribution
        val recv = ReceivedSenderKey.fromDistribution(assertNotNull(d, "distribution attendue"))
        for ((i, g) in e.arr("group_tokens").map { it.str() }.withIndex()) {
            egal(recv.decrypt(assertNotNull(Token.decode(g).groupMessage), T0), "groupe ${i + 1}", "groupe", categorie = "rust")
        }
        // La chaîne Rust (relue) continue ; la chaîne reçue Rust (relue) lit.
        val g3 = assertNotNull(chain.encryptToken("groupe 3").groupMessage)
        egal(received.decrypt(g3, T0), "groupe 3", "chaîne relue", categorie = "rust")
        egal(recv.decrypt(g3, T0), "groupe 3", "chaîne reçue", categorie = "rust")
        // Alice (Kotlin, session Rust) lit la réponse de Bob (Kotlin).
        val r1 = b.encrypt("Kotlin → Rust 1")
        egal(texte(alice.decryptText(r1, T0, rng)), "Kotlin → Rust 1", "réponse", categorie = "rust")
        assertTrue(alice.isConfirmed)
        // PSK : le premier message de Rust, accepté par Kotlin.
        val g = ReplayGuard()
        val pskKey = unhex(e, "psk_key")
        val (pskB, pc) = Session.acceptPsk(
            listOf(pskKey), assertNotNull(Token.decode(e.str("psk_token")).initialMessage), g, T0, rng,
        )
        egal(texte(pc), "PSK de Rust", "PSK", categorie = "rust")
        egal(texte(pskA.decryptText(pskB.encrypt("PSK de Kotlin"), T0, rng)), "PSK de Kotlin", "PSK retour", categorie = "rust")
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun bilan() {
            println("Valeurs comparées à la référence Rust : ${Compteur.total("rust")}")
        }
    }
}
