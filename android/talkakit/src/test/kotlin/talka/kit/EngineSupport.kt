// Le moteur dans les tests de la JVM : un seul fil, comme le `@MainActor`
// de l'iPhone (le test tourne sur ce fil, et ce que le moteur lance y attend
// que le test lui laisse la main : `delay`, `yield`), et pas de réseau (le
// relais n'est pas démarré ; ses connexions ne s'ouvrent jamais).

package talka.kit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.Executors

/** Une connexion qui ne s'ouvre jamais : rien n'arrive, rien ne part. */
object NoNetwork : SocketOpener {
    override fun open(url: String): Socket =
        object : Socket {
            override suspend fun send(text: String) {}

            override suspend fun receive(): String = awaitCancellation()

            override fun close() {}
        }
}

/** Des moteurs sur un fil à eux, et leurs mémoires, effacées à la fin. */
class Phones : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "moteur").apply { isDaemon = true } }
    val dispatcher = executor.asCoroutineDispatcher()
    val stores = Stores()

    /** Le corps d'un test, sur le fil du moteur. */
    fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking(dispatcher) { block() }

    /** Une mémoire neuve (`Store(directory:)` dans un dossier temporaire). */
    fun store(): Store = stores.open(stores.dir())

    /** Une mémoire rouverte (une autre ouverture du même dossier). */
    fun reopen(dir: File): Store = stores.open(dir)

    /** Un moteur sur cette mémoire, sans réseau. */
    fun engine(store: Store, server: String = "http://localhost:1"): Engine = Engine(store, server, dispatcher, NoNetwork)

    /** Un téléphone : un compte neuf, et son moteur. */
    fun phone(name: String = "Léa"): Engine {
        val store = store()
        Engine.createAccount(store, name, "")
        return engine(store)
    }

    override fun close() {
        stores.closeAll()
        executor.shutdownNow()
    }
}

/** Une enveloppe du relais, comme `[String: Any]` dans les tests de l'iPhone. */
fun envelope(vararg pairs: Pair<String, Any?>): JsonObject = jsonOf(*pairs)
