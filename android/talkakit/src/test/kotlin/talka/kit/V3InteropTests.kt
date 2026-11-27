// Android (le moteur TalkaKit entier) et le navigateur (son code Rust,
// `talka_web::v3`, dans `tests/ios_v3.rs`) en protocole v3, par un vrai
// relais : le même essai que `V3InteropTests.swift` de l'iPhone, Android à
// la place de l'iPhone (les fichiers échangés gardent leurs noms `swift.*`).
// Lancé avec le relais et le côté Rust :
//
//     TALKA_SERVER=http://127.0.0.1:3917 TALKA_V3_INTEROP_DIR=… cargo test --test ios_v3
//     TALKA_SERVER=… TALKA_V3_INTEROP_DIR=… ./gradlew :talkakit:test --tests 'talka.kit.V3InteropTests'
//
// Sans ces deux variables, il est sauté.

package talka.kit

import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class V3InteropTests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    @Test
    fun testWithRust() = phones.run {
        val server = System.getenv("TALKA_SERVER")
        val dir = System.getenv("TALKA_V3_INTEROP_DIR")
        assumeTrue(!server.isNullOrEmpty() && !dir.isNullOrEmpty(), "lancé avec le relais et `cargo test --test ios_v3`")
        val store = phones.store()
        Engine.createAccount(store, "iPhone", "")
        val e = Engine(store, server!!, phones.dispatcher, OkHttpSockets)
        e.start()
        try {
            suspend fun wait(what: String, timeout: Double = 60.0, ok: () -> Boolean) {
                val end = System.currentTimeMillis() + (timeout * 1000).toLong()
                while (!ok()) {
                    if (System.currentTimeMillis() > end) fail("trop long : $what")
                    delay(50)
                }
            }
            fun file(name: String): String = File(dir!!, name).takeIf { it.exists() }?.readText()?.trimBlanks() ?: ""
            fun signal(name: String) = File(dir!!, name).writeText("1")

            // Connecté, le paquet publié et accepté par le relais.
            wait("connexion et paquet publié") { e.netState == Relay.State.online && e.relay.relayV3 == true }
            File(dir!!, "swift.code").writeText(e.me.code)
            var rust = ""
            wait("code du navigateur", 300.0) {
                rust = file("rust.code")
                rust.length == 10
            }

            // Le premier message ouvre une session vers le paquet du navigateur,
            // vérifié par Android (lié à son code).
            e.addContact(rust, "Navigateur")
            e.ensureDirectChat(rust)
            val hello = assertNotNull(e.send(rust, Content(text = "Bonjour du iPhone 📱")))
            wait("envoyé") { e.store.message(rust, hello.id)?.sentTo?.contains(rust) == true }
            val bundle = assertNotNull(e.bundleCache[rust]?.bundle, "paquet du navigateur vérifié")
            assertNotNull(bundle.pq, "hybride ML-KEM-768 (le navigateur)")
            assertTrue(e.v3?.hasSession(rust) ?: false)
            signal("swift.sent")
            // Un gros fichier (plus de 200 Kio) : l'ancien format.
            val big = "data:application/octet-stream;base64," + "A".repeat(320 * 1024)
            e.send(rust, Content(kind = "file", data = big, name = "gros.bin", mime = "application/octet-stream", size = 240.0 * 1024))

            // Le premier message du navigateur, par sa propre session : Android
            // en a maintenant deux avec lui (une dans chaque sens).
            wait("premier message du navigateur") { e.messages(rust).any { it.text == "Bonjour de Rust 🦀" } }
            assertEquals(2, e.v3?.peers?.get(rust)?.sessions?.size)

            // Dans le désordre, et un doublon.
            val ordered = listOf("n°1", "n°2", "n°3")
            wait("messages dans le désordre") { ordered.all { t -> e.messages(rust).any { it.text == t } } }
            for (t in ordered) assertEquals(1, e.messages(rust).count { it.text == t }, t)
            e.send(rust, Content(text = "reçu dans le désordre"))

            // Des allers-retours.
            for (i in 0 until 18) {
                wait("ping $i") { e.messages(rust).any { it.text == "ping $i" } }
                e.send(rust, Content(text = "pong $i"))
            }
            // Puis de vieux messages rejoués : ignorés, sans réparation.
            wait("après rejeu") { e.messages(rust).any { it.text == "après rejeu" } }
            assertEquals(1, e.messages(rust).count { it.text == "Bonjour de Rust 🦀" })
            assertEquals(1, e.messages(rust).count { it.text == "n°1" })
            e.send(rust, Content(text = "après rejeu : rien n'a bougé"))

            // Le navigateur perd ses sessions : ce message-ci est perdu pour lui,
            // il répare (son profil, par une session neuve), Android l'accepte.
            wait("sessions perdues", 120.0) { file("rust.lost").isNotEmpty() }
            e.send(rust, Content(text = "après la perte"))
            wait("profil du navigateur, par une session neuve") { e.contact(rust)?.profileName == "Rust réparé" }
            e.send(rust, Content(text = "après la réparation"))
            wait("fin") { e.messages(rust).any { it.text == "fin" } }
            val protection = e.protection(rust)
            assertEquals(V3.Protection.Ratchet(V3.postQuantum), protection)

            // Puis Android perd les siennes (ses accusés de réception partis) :
            // il répare au premier message illisible, le navigateur suit.
            delay(1_500)
            e.v3?.forget(rust)
            e.saveV3()
            signal("swift.lost")
            wait("après la réparation d'Android") { e.messages(rust).any { it.text == "re-bonjour" } }
            assertFalse(e.messages(rust).any { it.text == "après ta perte" }, "perdu, comme dans le navigateur")

            // Un ancien client (sans paquet) : l'ancien format, dans les deux sens.
            var old = ""
            wait("code de l'ancien client") {
                old = file("old.code")
                old.length == 10
            }
            e.addContact(old, "Ancien")
            e.ensureDirectChat(old)
            e.send(old, Content(text = "pour l'ancien"))
            wait("réponse de l'ancien client") { e.messages(old).any { it.text == "de l'ancien" } }
            assertFalse(e.v3?.hasSession(old) ?: true)
            val oldProtection = e.protection(old)
            assertEquals(V3.Protection.Legacy, oldProtection)
            assertEquals("Ancien chiffrement (le contact n'a pas encore la mise à jour)", oldProtection.label)

            // L'appareil v3 part dans la sauvegarde : celui du navigateur se
            // relit ici (le même paquet que celui qu'il a publié), et le nôtre
            // chez lui.
            wait("appareil du navigateur") { file("rust.device").isNotEmpty() }
            val restored = V3Engine(file("rust.device"), V3.secs(nowMs()))
            assertNull(restored.takeDirty().device, "relu, pas recréé")
            val rustBundle = assertNotNull(e.bundleCache[rust]?.bundle)
            val mine = assertNotNull(restored.bundle())
            assertTrue(mine.identity.dh.contentEquals(rustBundle.identity.dh))
            assertTrue(mine.identity.sign.contentEquals(rustBundle.identity.sign))
            assertTrue(mine.spk.contentEquals(rustBundle.spk))
            assertTrue((mine.pq?.key ?: ByteArray(0)).contentEquals(rustBundle.pq?.key ?: ByteArray(1)))
            File(dir, "swift.device").writeText(assertNotNull(e.store.readRaw("kv", V3.deviceKey)))
            signal("swift.done")
            delay(500)
        } finally {
            e.stop()
        }
    }
}
