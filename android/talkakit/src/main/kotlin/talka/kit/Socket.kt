// Le transport du relais : une WebSocket de texte, par OkHttp (sur Android
// comme sur la JVM des tests).

package talka.kit

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException

/** Une WebSocket de texte. */
interface Socket {
    /** Envoie un message ; [SocketError] si la connexion est fermée. */
    suspend fun send(text: String)

    /** Le message suivant ; [SocketError] quand la connexion est fermée. */
    suspend fun receive(): String

    fun close()
}

/** La connexion est fermée (ou n'a jamais pu s'ouvrir). */
class SocketError(message: String = "closed", cause: Throwable? = null) : IOException(message, cause)

/** Ouvre une WebSocket vers une adresse `ws://` ou `wss://`. */
fun interface SocketOpener {
    fun open(url: String): Socket
}

/** Les WebSockets d'OkHttp, partagées par toutes les connexions. */
object OkHttpSockets : SocketOpener {
    private val client: OkHttpClient by lazy { OkHttpClient() }

    override fun open(url: String): Socket = OkHttpSocket(client, url)
}

/**
 * Une WebSocket d'OkHttp : ce qui arrive attend dans une file, lue par
 * `receive` ; ce qui part attend chez OkHttp la fin de la poignée de main.
 * Seuls les messages de texte comptent.
 */
class OkHttpSocket(client: OkHttpClient, url: String) : Socket {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val ws: WebSocket

    init {
        ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    inbox.trySend(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    inbox.close(SocketError())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    inbox.close(SocketError())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    inbox.close(SocketError("closed", t))
                }
            },
        )
    }

    override suspend fun send(text: String) {
        if (!ws.send(text)) throw SocketError()
    }

    override suspend fun receive(): String =
        try {
            inbox.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw SocketError("closed", e)
        }

    /** Ferme la connexion (`goingAway`) : ce qui attend une réponse l'apprend tout de suite. */
    override fun close() {
        ws.close(1001, null)
        inbox.close(SocketError())
    }
}
