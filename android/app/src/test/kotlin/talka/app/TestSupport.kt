// L'application dans les tests (Robolectric) : une garde des clés en
// mémoire (à la place du Keystore, absent de Robolectric), une biométrie
// qui dit oui ou non, et un faux relais, local, qui répond comme le vrai
// à l'essentiel (présentation, recherche de clés, accusés, ping).

package talka.app

import android.os.Looper
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.robolectric.Shadows.shadowOf
import talka.kit.BrocolisVault
import talka.kit.Identity
import talka.kit.KeyGuard
import talka.kit.SecretGuard
import talka.kit.Socket
import talka.kit.SocketError
import talka.kit.SocketOpener
import talka.kit.StoreVault
import talka.kit.V3Vault
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json as KJson

/** La garde des clés en mémoire : une copie protégée ne se lit qu'avec une authentification. */
class MemoryGuard : KeyGuard, SecretGuard {
    val items = ConcurrentHashMap<String, Pair<KeyGuard.Access, ByteArray>>()
    val flags = ConcurrentHashMap<String, Boolean>()
    val secrets = ConcurrentHashMap<String, ByteArray>()

    override fun read(account: String, auth: Any?): KeyGuard.Read {
        val (access, data) = items[account] ?: return KeyGuard.Read.NotFound
        if (access != KeyGuard.Access.plain && auth == null) return KeyGuard.Read.Failed
        return KeyGuard.Read.Found(data)
    }

    override fun add(account: String, data: ByteArray, access: KeyGuard.Access): Boolean {
        items[account] = access to data.copyOf()
        return true
    }

    override fun delete(account: String) {
        items.remove(account)
    }

    override fun flag(name: String): Boolean = flags[name] ?: false

    override fun setFlag(name: String, on: Boolean) {
        flags[name] = on
    }

    override fun removeFlag(name: String) {
        flags.remove(name)
    }

    override fun read(service: String, account: String): KeyGuard.Read =
        secrets["$service/$account"]?.let { KeyGuard.Read.Found(it) } ?: KeyGuard.Read.NotFound

    override fun write(service: String, account: String, data: ByteArray): Boolean {
        secrets["$service/$account"] = data.copyOf()
        return true
    }

    override fun delete(service: String, account: String) {
        secrets.remove("$service/$account")
    }

    override fun clear(service: String) {
        secrets.keys.removeAll { it.startsWith("$service/") }
    }

    /** Ce que `KeystoreGuard.unlockCipher` rendrait : de quoi ouvrir une copie protégée. */
    fun unlockCipher(account: String): Any? = if (items.containsKey(account)) "authentification:$account" else null
}

/** Une biométrie qui répond ce qu'on lui dit. */
class FakeBiometrics(var answer: Boolean = true, override val name: String? = "Empreinte digitale") : Biometrics {
    override val deviceSecure = true
    val asked = ArrayList<String>()

    override suspend fun authenticate(title: String, crypto: Any?, deviceCredential: Boolean): Boolean {
        asked.add(title)
        return answer
    }
}

/**
 * Un faux relais, sans réseau : il se présente (`challenge`), accepte qui
 * se présente (`ready`), donne les clés des codes qu'il connaît, accuse
 * réception de ce qui part, répond aux pings.
 */
class FakeRelay : SocketOpener {
    /** Les identités que le relais connaît (code → identité). */
    val known = ConcurrentHashMap<String, Identity>()
    val received = java.util.Collections.synchronizedList(ArrayList<JsonObject>())

    /** Le relais répond-il ? (Sinon, rien ne s'ouvre : hors ligne.) */
    @Volatile var up = true

    override fun open(url: String): Socket = Conn()

    private inner class Conn : Socket {
        private val inbox = Channel<String>(Channel.UNLIMITED)

        init {
            if (up) reply(buildJsonObject { put("t", "challenge"); put("nonce", "nonce-de-test") })
        }

        private fun reply(o: JsonObject) {
            inbox.trySend(o.toString())
        }

        override suspend fun send(text: String) {
            if (!up) throw SocketError()
            val msg = KJson.parseToJsonElement(text) as? JsonObject ?: return
            received.add(msg)
            val t = msg["t"]?.jsonPrimitive?.contentOrNull
            when (t) {
                "hello" -> reply(buildJsonObject { put("t", "ready"); put("sealed", false) })
                "ping" -> reply(buildJsonObject { put("t", "pong") })
                "lookup" -> {
                    val code = msg["code"]?.jsonPrimitive?.contentOrNull ?: ""
                    val id = known[code]
                    reply(
                        buildJsonObject {
                            put("t", "keys")
                            put("code", code)
                            if (id == null) put("unknown", true) else {
                                put("sign", id.sign)
                                put("dh", id.dh)
                            }
                        },
                    )
                }
                "publish-bundle" -> reply(buildJsonObject { put("t", "published"); put("ok", true) })
                "fetch-bundle" -> reply(buildJsonObject { put("t", "bundle"); put("code", msg["code"] ?: JsonPrimitive("")) })
                "send" -> msg["id"]?.let { id -> reply(buildJsonObject { put("t", "ack"); put("id", id) }) }
                else -> {}
            }
        }

        override suspend fun receive(): String =
            try {
                inbox.receive()
            } catch (e: Exception) {
                throw SocketError("closed", e)
            }

        override fun close() {
            inbox.close()
        }
    }
}

/** Ce qui est commun aux tests de l'application : la garde, un dossier, des modèles. */
open class AppTestBase {
    val guard = MemoryGuard()
    val biometrics = FakeBiometrics()
    val relay = FakeRelay()
    val dir: File = Files.createTempDirectory("talka-app").toFile()
    private val models = ArrayList<AppModel>()

    fun install() {
        StoreVault.keyGuard = guard
        V3Vault.guard = guard
        BrocolisVault.guard = guard
    }

    fun uninstall() {
        for (m in models) m.close()
        models.clear()
        idle()
        StoreVault.keyGuard = null
        V3Vault.guard = null
        BrocolisVault.guard = null
        dir.deleteRecursively()
    }

    /** Un modèle neuf sur le même dossier (Talka relancé) ; un autre dossier : un autre téléphone. */
    fun model(
        base: File = dir,
        server: String = "http://relais.test",
        sockets: talka.kit.SocketOpener = relay,
    ): AppModel {
        val env = AppEnv(
            RuntimeEnvironment.getApplication(),
            dir = base,
            server = server,
            sockets = sockets,
            unlockCipher = { guard.unlockCipher(it) },
            deviceName = "Pixel de test",
            splashMs = 0,
            netWaitMs = 0,
        )
        return AppModel(env).also {
            it.biometrics = biometrics
            models.add(it)
            idle()
        }
    }

    /** Ferme un modèle (Talka quitté), avant d'en ouvrir un autre. */
    fun quit(m: AppModel) {
        m.close()
        models.remove(m)
        idle()
    }

    /** Le fil principal fait ce qu'il a à faire. */
    fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Attend une tâche (Argon2id tourne hors du fil principal). */
    fun await(job: Job) {
        val end = System.currentTimeMillis() + 60_000
        while (!job.isCompleted) {
            idle()
            Thread.sleep(5)
            check(System.currentTimeMillis() < end) { "la tâche ne finit pas" }
        }
        idle()
    }

    /** Lance `block` sur le fil principal et attend son résultat. */
    fun <T> run(m: AppModel, block: suspend () -> T): T {
        var out: Result<T>? = null
        await(m.launch { out = runCatching { block() } })
        return out!!.getOrThrow()
    }
}
