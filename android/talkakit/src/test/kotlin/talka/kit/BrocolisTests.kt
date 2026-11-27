// Brocolis 🥦 : les jetons du navigateur s'ouvrent ici, et l'inverse (même
// format que l'app Brocolis) ; une discussion et un message gardent leur
// réglage Brocolis d'un appareil à l'autre. Comme `BrocolisTests.swift` de
// l'iPhone (sans ce qui passe par le moteur : il viendra avec `Engine`).

package talka.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import talka.brocolis.Hasard
import talka.brocolis.ReplayGuard
import talka.brocolis.Session
import talka.brocolis.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BrocolisTokenTests {
    private fun vectors(): JsonObject = Vectors.v.o("brocolis")

    private fun keys(v: JsonObject): List<BrocolisKey> = v["keys"]!!.jsonArray.map { assertNotNull(JSON.decode(BrocolisKey, it)) }

    @Test
    fun testOpensTheBrowsersTokens() {
        val v = vectors()
        val ks = keys(v).mapNotNull { Brocolis.key(it.key) }
        assertEquals(2, ks.size)
        for (name in listOf("v1", "v2", "marked")) {
            val opened = Brocolis.open(v.s(name), ks)
            assertEquals(v.s("${name}_plain"), opened.text, name)
            assertEquals(0, opened.locked, name)
        }
        // La seule clé « Travail » n'ouvre pas le jeton v1 de « Bob ».
        val other = Brocolis.open(v.s("v1"), listOf(ks[1]))
        assertEquals(0, other.opened)
        assertEquals("🔒🥦", Brocolis.masked(other.text))
        // Le v2 s'ouvre avec n'importe laquelle des deux.
        assertEquals("À tous 🥦", Brocolis.open(v.s("v2"), listOf(ks[1])).text)
    }

    @Test
    fun testSealsLikeTheBrowser() {
        val k1 = assertNotNull(Brocolis.key(Brocolis.generateKey()))
        val k2 = assertNotNull(Brocolis.key(Brocolis.generateKey()))
        val one = Brocolis.seal("Salut", listOf(k1))
        assertTrue(one.startsWith("[[BROCOLIS:v1:") && one.endsWith("]]"))
        assertEquals("Salut", Brocolis.open(one, listOf(k2, k1)).text)
        val both = Brocolis.seal("À tous", listOf(k1, k2))
        assertTrue(both.startsWith("[[BROCOLIS:v2:"))
        assertEquals("À tous", Brocolis.open(both, listOf(k2)).text)
        val marked = Brocolis.seal("Rendez-vous ((au parc)) à ((18 h)) !", listOf(k1))
        assertTrue(marked.startsWith("Rendez-vous [[BROCOLIS:v1:") && marked.endsWith("]] !"))
        assertFalse(marked.contains("parc"))
        assertEquals(Brocolis.Opened("Rendez-vous au parc à 18 h !", 2, 0), Brocolis.open(marked, listOf(k1)))
        assertEquals(one, Brocolis.seal(one, listOf(k2)), "un jeton déjà là part tel quel")
        assertEquals("clair", Brocolis.seal("clair", emptyList()))
    }

    /**
     * Le « double cliquet, clé partagée » produit par le navigateur (le même
     * code Rust que la référence du clavier Brocolis) s'ouvre ici : le
     * premier message ouvre la session, les suivants passent par elle.
     */
    @Test
    fun testOpensTheKeyboardsDoubleRatchetFromTheBrowser() {
        val v = vectors()
        val ks = keys(v).mapNotNull { Brocolis.key(it.key) }
        val psk3 = v.o("psk3")
        val tokens = psk3.texts("tokens")
        val plain = psk3.texts("plain")
        val p = BrocolisPsk()
        // La seule clé « Bob » n'ouvre pas le premier message.
        assertEquals(0, p.open(tokens[0], listOf(ks[0]), 100uL).opened)
        assertEquals(plain[0], p.open(tokens[0], ks, 100uL).text)
        assertTrue(p.takeDirty())
        // L'état survit à une relecture ; la suite s'ouvre sans clé.
        val back = BrocolisPsk(p.toJson())
        assertEquals(plain[1], back.open(tokens[1], emptyList(), 101uL).text)
        assertEquals(plain[2], back.open(tokens[2], emptyList(), 102uL).text)
        // Un premier message rejoué ne rouvre rien.
        assertEquals(0, back.open(tokens[0], ks, 103uL).opened)
        // Les messages d'avant ne se rouvrent qu'une fois.
        assertTrue(back.firstScan())
        assertFalse(BrocolisPsk(back.toJson()).firstScan())
    }

    /**
     * Ce que Talka écrit s'ouvre comme le clavier l'ouvre (la même
     * bibliothèque, BrocolisProto) ; sa réponse revient par la même session.
     */
    @Test
    fun testTalkaWritesWhatTheKeyboardReads() {
        val key = ByteArray(32) { 7 }
        val talka = BrocolisPsk()
        val first = assertNotNull(talka.seal("Depuis Talka 🥦", key))
        val second = assertNotNull(talka.seal("Rendez-vous ((au parc)) !", key))
        assertTrue(first.startsWith("[[BROCOLIS:v3:"))
        assertTrue(second.startsWith("Rendez-vous [[BROCOLIS:v3:") && !second.contains("parc"))
        val i = (Token.decode(first) as? Token.Initial)?.initial ?: fail("pas un premier message")
        val replay = ReplayGuard()
        val (kb, c) = Session.acceptPsk(listOf(key), i, replay, 0uL, Hasard.system())
        assertEquals("Depuis Talka 🥦", BrocolisPsk.text(c))
        val token = second.removePrefix("Rendez-vous ").removeSuffix(" !")
        assertEquals("au parc", BrocolisPsk.text(kb.decryptText(token, 0uL, Hasard.system())))
        val reply = kb.encrypt("Bien reçu")
        assertEquals("Bien reçu", talka.open(reply, emptyList(), 1uL).text)
        assertNotNull(talka.seal("Encore", key))
        assertEquals(1, talka.sessions.size)
        // Les jetons d'origine survivent à une relecture.
        talka.remember("c/1", first)
        assertEquals(first, BrocolisPsk(talka.toJson()).original("c/1"))
    }

    /** Une session ouverte par le clavier : Talka y répond. */
    @Test
    fun testAKeyboardSessionIsAnsweredInIt() {
        val key = ByteArray(32) { 9 }
        val kb = Session.initiatePsk(key, Hasard.system())
        val talka = BrocolisPsk()
        assertEquals(1, talka.open(kb.encrypt("Salut"), listOf(key), 0uL).opened)
        val answer = assertNotNull(talka.seal("Salut à toi", key))
        assertEquals("Salut à toi", BrocolisPsk.text(kb.decryptText(answer, 0uL, Hasard.system())))
        assertEquals(1, talka.sessions.size)
    }

    @Test
    fun testRatchetTokensAreToldApart() {
        assertTrue(Brocolis.hasRatchetToken("[[BROCOLIS:v3:AQID]]"))
        assertTrue(Brocolis.hasToken("[[BROCOLIS:v3:AQID]]"))
        assertFalse(Brocolis.hasRatchetToken("[[BROCOLIS:v1:AQID]]"))
        assertFalse(Brocolis.hasRatchetToken("rien"))
    }

    @Test
    fun testFindsTheKeyInAnInvitation() {
        val key = Brocolis.generateKey()
        assertEquals(key, Brocolis.findKey("3) Colle CETTE clé :\n\n$key\n\n(C'est notre clé commune.)"))
        assertNull(Brocolis.findKey("pas de clé ici"))
    }

    @Test
    fun testBrokenTokensStayAsTheyAre() {
        val k = assertNotNull(Brocolis.key(Brocolis.generateKey()))
        for (text in listOf("[[BROCOLIS:v1:pas-du-base64]]", "[[BROCOLIS:v3:AAAA]]", "[[BROCOLIS:v1:AAAA")) {
            assertEquals(text, Brocolis.open(text, listOf(k)).text)
        }
    }

    @Test
    fun testAStoredChatAndMessageKeepBrocolis() {
        val store = Vectors.v.o("store")
        val chat = assertNotNull(JSON.decode(Chat, store.s("chat")))
        assertEquals(listOf("k1"), chat.brocolis)
        val message = assertNotNull(JSON.decode(Message, store.s("message")))
        assertTrue(message.bro)
    }

    @Test
    fun testALockedTokenDoesNotMakeItRain() {
        val token = Brocolis.seal("secret", listOf(assertNotNull(Brocolis.key(Brocolis.generateKey()))))
        val m = Message("c", "m", "K7Q2MX9PRT", 1.0, Content(text = token))
        assertFalse(m.summonsBrocoli("WGSEAJNSZJ"))
    }
}

/**
 * Le seul test de `BrocolisEngineTests` (iPhone) qui ne demande pas le
 * moteur : l'empreinte d'une clé, comme le navigateur.
 */
class BrocolisEngineTests {
    @Test
    fun testFingerprintMatchesTheBrowser() {
        val v = Vectors.v.o("brocolis")
        val k1 = assertNotNull((v["keys"] as? JsonArray)?.firstOrNull()?.jsonObject?.s("key"))
        assertEquals(v.s("fingerprint_k1"), Brocolis.fingerprint(k1))
        assertNull(Brocolis.fingerprint("pas une clé"))
    }
}

/** Une clé protégée par une phrase secrète : le vecteur commun avec le navigateur et l'app Brocolis (iOS, Android). */
class BrocolisProtectedKeyTests {
    @Test
    fun testMatchesTheSharedVector() {
        val raw = ByteArray(32) { it.toByte() }
        val token = assertNotNull(Brocolis.protectKey(raw, "brocoli vert 2026", ByteArray(16) { 0x11 }, ByteArray(12) { 0x22 }))
        assertEquals(
            "[[BROCOLIS-CLE:v1:ERERERERERERERERERERESIiIiIiIiIiIiIiIsq57j18BXkqq/4VdIw82nQqzrax9Cfn2mfUJjWH8l327NAf2rWaSYCJh3BfGH4eWA==]]",
            token,
        )
        assertEquals(b64(raw), Brocolis.unprotectKey("Voici : $token !", "brocoli vert 2026"))
        assertNull(Brocolis.unprotectKey(token, "brocoli vert 2025"))
    }

    @Test
    fun testNeedsAPhraseAndHidesTheKey() {
        val k = Brocolis.generateKey()
        assertNull(Brocolis.protectKey(k, "court"))
        val token = assertNotNull(Brocolis.protectKey(k, "une phrase longue"))
        assertTrue(Brocolis.hasProtectedKey(token))
        assertFalse(token.contains(k))
        assertEquals(k, Brocolis.unprotectKey(token, "une phrase longue"))
        assertNull(Brocolis.unprotectKey(token.replace("v1:", "v1:AAAA"), "une phrase longue"))
        assertNull(Brocolis.findKey(token), "un jeton protégé n'est pas une clé en clair")
    }
}

/**
 * Les gardes du système (le trousseau de l'iPhone, le Keystore d'Android) :
 * l'appareil v3 et les clés Brocolis s'y écrivent et s'y relisent ; une
 * garde qui ne répond pas n'est jamais prise pour une garde vide.
 */
class SecretGuardTests {
    @Test
    fun testTheVaultsKeepTheirItems() {
        val g = MemorySecretGuard()
        V3Vault.guard = g
        BrocolisVault.guard = g
        try {
            assertNull(V3Vault.read("K7Q2MX9PRT"))
            assertTrue(V3Vault.write("{\"v\":1}", "K7Q2MX9PRT"))
            assertTrue(V3Vault.write("{\"v\":2}", "WGSEAJNSZJ"))
            assertEquals("{\"v\":1}", V3Vault.read("K7Q2MX9PRT"))
            val keys = listOf(BrocolisKey("k1", "Nous", Brocolis.generateKey()), BrocolisKey("k2", "Eux", Brocolis.generateKey(), retired = true))
            assertTrue(BrocolisVault.write(keys))
            assertEquals(keys, BrocolisVault.read())
            // Verrouillée : une erreur, pas « rien ».
            g.failing = true
            try {
                V3Vault.read("K7Q2MX9PRT")
                fail("la garde ne répond pas")
            } catch (e: V3Vault.Unreadable) {
            }
            assertNull(BrocolisVault.read())
            g.failing = false
            // Effacer les appareils ne touche pas aux clés Brocolis.
            V3Vault.clear()
            assertNull(V3Vault.read("K7Q2MX9PRT"))
            assertNull(V3Vault.read("WGSEAJNSZJ"))
            assertEquals(keys, BrocolisVault.read())
            BrocolisVault.clear()
            assertNull(BrocolisVault.read())
        } finally {
            V3Vault.guard = null
            BrocolisVault.guard = null
        }
        // Sans garde : rien n'est gardé par le système.
        assertFalse(V3Vault.isAvailable)
        assertFalse(V3Vault.write("{}", "K7Q2MX9PRT"))
        assertNull(V3Vault.read("K7Q2MX9PRT"))
        assertFalse(BrocolisVault.write(emptyList()))
        assertNull(BrocolisVault.read())
    }
}
