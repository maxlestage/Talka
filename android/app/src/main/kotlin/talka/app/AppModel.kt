// L'état de l'application : le moteur (TalkaKit), le verrou, les
// notifications, les liens d'invitation, l'appel en cours.
//
// Le même que `AppModel` de l'iPhone. Ce que SwiftUI observe de lui-même
// (`@Observable`) est ici de l'état de Compose ; ce qui change dans le
// moteur (ses discussions, ses contacts…) fait avancer `revision`, que
// lisent les écrans (voir `observedEngine`).

package talka.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talka.app.call.CallEngine
import talka.app.sys.AndroidSql
import talka.app.ui.Notifier
import talka.app.ui.Sounds
import talka.kit.Body
import talka.kit.Brocolis
import talka.kit.CallEntry
import talka.kit.Chat
import talka.kit.DeviceLink
import talka.kit.Engine
import talka.kit.Mesh
import talka.kit.Message
import talka.kit.OkHttpSockets
import talka.kit.PinHash
import talka.kit.Relay
import talka.kit.SocketOpener
import talka.kit.SqlOpener
import talka.kit.Store
import talka.kit.StoreVault
import talka.kit.TalkaCode
import talka.kit.TalkaCrypto
import talka.kit.TalkaServer
import talka.kit.WidgetData
import talka.kit.WidgetShare
import talka.kit.leaveAccount
import talka.kit.nowMs
import talka.kit.randomId
import java.io.File
import java.time.LocalDate

/**
 * L'empreinte ou le visage (`BiometricPrompt`), branché par l'activité ;
 * un faux dans les tests. Ce que `LAContext` est à l'iPhone.
 */
interface Biometrics {
    /** Le nom de la biométrie de l'appareil (« Empreinte digitale »…), s'il en a une forte, enregistrée. */
    val name: String?

    /** L'appareil a-t-il un verrouillage (code, schéma, mot de passe) ? */
    val deviceSecure: Boolean

    /**
     * Demande l'empreinte ou le visage. `crypto` : le `Cipher` à ouvrir
     * (`KeystoreGuard.unlockCipher`), signé si c'est réussi ;
     * `deviceCredential` : le code de l'appareil est accepté aussi.
     */
    suspend fun authenticate(title: String, crypto: Any?, deviceCredential: Boolean): Boolean
}

/** Ce que l'application tient du système : branché par `TalkaApp`, et par les tests. */
class AppEnv(
    val context: Context,
    /** Le dossier de la base (`Store.defaultDirectory()` de l'iPhone). */
    val dir: File = File(context.filesDir, "talka"),
    val sql: SqlOpener = AndroidSql,
    val server: String = AppModel.builtInServer,
    val sockets: SocketOpener = OkHttpSockets,
    /** La copie protégée de la clé à ouvrir : le `Cipher` à faire signer (`KeystoreGuard.unlockCipher`). */
    val unlockCipher: (String) -> Any? = { null },
    /** Ce que montre la liste des appareils du compte (v4). */
    val deviceName: String = AppModel.deviceName,
    /** L'écran de chargement reste au moins ce temps (ms) : assez pour être vu. */
    val splashMs: Long = 1200,
    /** Le temps de retrouver le relais (ms) : hors ligne, on montre tout de suite les discussions gardées. */
    val netWaitMs: Long = 2500,
    /** Les appels : WebRTC (null : `WebRtcMedia`), ou une fausse liaison dans les tests. */
    val callMedia: (() -> talka.app.call.CallMedia)? = null,
    /** Les signaux d'appel : par le moteur, chiffrés (null), ou un faux réseau dans les tests. */
    val callSignals: ((String, Body) -> Unit)? = null,
) {
    /** Les réglages de l'appareil (les `UserDefaults` de l'iPhone). */
    val prefs: SharedPreferences = context.getSharedPreferences("talka.reglages", Context.MODE_PRIVATE)
}

/** Le modèle, partout dans les écrans (l'`.environment(model)` de l'iPhone). */
val LocalAppModel = staticCompositionLocalOf<AppModel> { error("pas de AppModel") }

class AppModel(val env: AppEnv) {
    enum class Tab { chats, status, calls, settings }

    /** Ce que fait l'application : au premier plan, en partie cachée, en arrière-plan (`ScenePhase`). */
    enum class Phase { active, inactive, background }

    data class Toast(val text: String, val id: Long = nextToast++)

    /** Tout ce que l'application lance elle-même, sur le fil principal. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val prefs get() = env.prefs

    /**
     * La base ; protégée par la biométrie, elle ne s'ouvre qu'après
     * `unlockVault` (jusque-là, une base en lecture seule, vide).
     */
    var store: Store
        private set

    /** La base attend l'empreinte ou le visage (ou le code de l'appareil). */
    var vaultLocked by mutableStateOf(false)

    /** La base attend le code de verrouillage de Talka (qui chiffre sa clé). */
    var codeLocked by mutableStateOf(false)
    var engine by mutableStateOf<Engine?>(null)
        private set
    var tab by mutableStateOf(Tab.chats)

    /** Les discussions ouvertes (navigation de l'onglet Discussions). */
    var path by mutableStateOf<List<String>>(emptyList())
    var toast by mutableStateOf<Toast?>(null)
    var locked by mutableStateOf(false)
    var call by mutableStateOf<CallEngine?>(null)

    /** Un code reçu par un lien d'invitation, à ajouter. */
    var invite by mutableStateOf<String?>(null)

    /** « Répondre en privé » : le message de groupe à citer dans la discussion à deux qui s'ouvre. */
    var privateReply by mutableStateOf<Message?>(null)

    /** Un résultat de recherche : le message à montrer dans la discussion qui s'ouvre. */
    var reveal by mutableStateOf<String?>(null)
    var hiddenAt: Long? = null

    /** L'étape de l'écran de chargement (`null` : Talka est prêt). */
    var loading by mutableStateOf<String?>("Ouverture de Talka…")
    private val launchedAt = System.currentTimeMillis()

    /** Avance à chaque changement dans le moteur : les écrans qui la lisent se redessinent. */
    var revision by mutableIntStateOf(0)
        private set

    /** Talka est-il au premier plan (`UIApplication.shared.applicationState == .active`) ? */
    var active by mutableStateOf(true)
        private set

    /** L'empreinte ou le visage, branché par l'activité. */
    var biometrics: Biometrics? = null

    /** Demande la permission des notifications (Android 13 et plus), branché par l'activité. */
    var askNotifications: (suspend () -> Boolean)? = null

    /** Demande une permission du système (le micro, la caméra d'un appel), branché par l'activité. */
    var askPermission: (suspend (String) -> Boolean)? = null

    /**
     * Talka part de lui-même vers une autre activité (choisir une photo, un
     * fichier, le code de l'appareil) : ce n'est pas quitter Talka, il ne se
     * verrouille pas au retour. Sur l'iPhone, ces fenêtres ne quittent pas
     * l'application.
     */
    private var goingOut = false
    private var hiddenOnPurpose = false

    init {
        if (StoreVault.isProtected || StoreVault.isCodeLocked) {
            store = Store(env.dir, env.sql, null)
            vaultLocked = StoreVault.isProtected
            codeLocked = StoreVault.isCodeLocked
            loading = null
        } else {
            store = Store(env.dir, env.sql, StoreVault.key())
            locked = hasPin
            forgetChosenServer()
            if (Engine.hasAccount(store)) start()
            scope.launch { finishLoading() }
        }
    }

    /** L'écran de chargement : le temps de retrouver le relais. */
    suspend fun finishLoading() {
        engine?.let { e ->
            loading = "Connexion à vos contacts…"
            val end = System.currentTimeMillis() + env.netWaitMs
            while (e.netState != Relay.State.online && System.currentTimeMillis() < end) delay(100)
        }
        val shown = System.currentTimeMillis() - launchedAt
        if (shown < env.splashMs) delay(env.splashMs - shown)
        loading = null
    }

    /** Une adresse de serveur choisie autrefois est oubliée : il n'y a plus qu'un relais. */
    fun forgetChosenServer() {
        val s = store.read("kv", "settings", talka.kit.Settings) ?: return
        if (s.server.isNotEmpty()) {
            store.write("kv", "settings", s.copy(server = ""))
            store.save()
        }
    }

    // ----- Le serveur -----

    val serverString: String get() = env.server

    // ----- Démarrage -----

    fun start() {
        if (!serverString.startsWith("http://") && !serverString.startsWith("https://")) return
        Engine.deviceName = env.deviceName
        val e = Engine(store, serverString, Dispatchers.Main, env.sockets)
        e.onChange = { revision += 1 }
        e.onIncoming = { chat, message, active -> incoming(chat, message, active) }
        e.onReaction = { chat, from, emoji ->
            if (path.lastOrNull() != chat.id) show("${e.displayName(from)} a réagi $emoji à votre message")
        }
        e.onCallSignal = { from, body, live -> callSignal(from, body, live) }
        e.onTooLarge = { show("Message trop lourd : il n'a pas pu partir.") }
        // Retiré du compte par un autre appareil (v4) : tout est effacé.
        e.onUnlinked = {
            wipe()
            show("Cet appareil a été retiré de votre compte Talka.")
        }
        e.onUnprovenRelay = {
            show("Le serveur ne prouve pas son identité habituelle : Talka ignore ses ordres d'effacement.")
        }
        e.onKeysMismatch = { code ->
            show("Attention : les clés de ${TalkaCode.pretty(code)} ne correspondent pas à son lien d'invitation. Quelqu'un se fait peut-être passer pour lui.")
        }
        engine = e
        e.start()
        Sounds.prepare(env.context)
    }

    fun createAccount(name: String, avatar: String) {
        Engine.createAccount(store, name, avatar)
        store.save()
        start()
    }

    /** Restaure une sauvegarde (Argon2id, hors du fil principal) ; lève `BackupError`. */
    suspend fun restore(data: ByteArray, password: String) {
        engine?.stop()
        engine = null
        val s = store
        try {
            withContext(Dispatchers.Default) { Engine.importBackup(s, password, data) }
        } catch (x: Exception) {
            // Mauvais mot de passe, ou pas une sauvegarde : rien n'a changé,
            // Talka repart sur ce qu'il avait (l'iPhone restait sur l'accueil).
            if (Engine.hasAccount(store)) start()
            throw x
        }
        locked = hasPin
        start()
    }

    // ----- Plusieurs appareils (v4) -----

    /** Ce nouvel appareil attend d'être lié : son QR code, et ce qui arrive. */
    fun makeDeviceLink(): DeviceLink? {
        if (!serverString.startsWith("http")) return null
        return DeviceLink(store, serverString, Dispatchers.Main, env.sockets)
    }

    /** Le compte reçu par la liaison est rangé : Talka démarre. */
    fun finishLinking() {
        if (engine != null || !Engine.hasAccount(store)) return
        locked = false
        start()
        scope.launch { finishLoading() }
    }

    /**
     * « Tout effacer » : cet appareil quitte d'abord le compte (nos autres
     * appareils et nos contacts ne lui écrivent plus), puis efface tout.
     */
    suspend fun leaveAndWipe() {
        engine?.leaveAccount()
        wipe()
    }

    fun wipe() {
        val e = engine
        if (e != null) {
            e.wipeEverything()
        } else {
            // Verrouillé : la base n'est pas ouverte, on l'efface quand même.
            Engine.wipe(store)
        }
        engine = null
        // Plus aucune copie de la clé : une nouvelle, pour une base vide.
        StoreVault.reset()
        store.close()
        store = Store(env.dir, env.sql, StoreVault.key())
        path = emptyList()
        locked = false
        codeLocked = false
        vaultLocked = false
        prefs.edit().remove(faceIdKey).remove(failsKey).remove(untilKey).apply()
        faceIDState = false
        WidgetShare.clear()
        Notifier.cancelAll()
    }

    // ----- Premier plan, arrière-plan, verrou -----

    fun scene(phase: Phase) {
        val e = engine
        when (phase) {
            Phase.active -> {
                active = true
                e?.foreground = true
                // Après une veille, les connexions ont pu mourir sans le dire :
                // rouvertes (ce qui attend chez le relais arrive aussitôt).
                val at = hiddenAt
                if (at != null && System.currentTimeMillis() - at >= 20_000) e?.relay?.reopen() else e?.relay?.wake()
                path.lastOrNull()?.let { e?.markRead(it) }
                if (at != null && hasPin && !hiddenOnPurpose &&
                    System.currentTimeMillis() - at >= (e?.settings?.lockAfter ?: 0) * 1000L
                ) {
                    locked = true
                }
                hiddenAt = null
                hiddenOnPurpose = false
                goingOut = false
            }
            Phase.background -> {
                active = false
                e?.foreground = false
                if (hiddenAt == null) hiddenAt = System.currentTimeMillis()
                hiddenOnPurpose = hiddenOnPurpose || goingOut
                goingOut = false
                store.save()
            }
            Phase.inactive -> {
                active = false
                e?.foreground = false
            }
        }
    }

    /** Le réseau est revenu : le relais se reconnecte tout de suite. */
    fun networkBack() {
        engine?.relay?.wake()
    }

    /** Talka ouvre une autre activité (photo, fichier, code de l'appareil) : pas de verrou au retour. */
    fun leavingOnPurpose() {
        goingOut = true
    }

    private suspend fun authenticate(title: String, crypto: Any?, deviceCredential: Boolean): Boolean {
        val b = biometrics ?: return false
        leavingOnPurpose()
        return b.authenticate(title, crypto, deviceCredential)
    }

    /** La biométrie (ou le code de l'appareil) ouvre la clé de la base, puis Talka. */
    suspend fun unlockVault() {
        val cipher = env.unlockCipher("cle-protegee")
        if (!authenticate("Ouvrir vos messages", cipher, deviceCredential = true)) return
        val key = StoreVault.key(cipher) ?: return
        replaceStore(key)
        // On vient de montrer son visage : pas de code PIN en plus, si la
        // biométrie le remplace déjà.
        locked = hasPin && !faceID
        vaultLocked = false
        forgetChosenServer()
        if (Engine.hasAccount(store)) start()
        loading = "Ouverture de Talka…"
        finishLoading()
    }

    /** Protéger la base par la biométrie (ou retirer cette protection). */
    suspend fun protectVault(on: Boolean) {
        val key = store.key ?: return
        if (!on) {
            // Retirer la protection demande aussi le visage.
            if (!authenticate("Retirer la protection", null, deviceCredential = true)) return
        }
        if (StoreVault.setProtected(on, key)) {
            show(if (on) "Vos messages ne s'ouvrent qu'avec ${biometryName ?: "le code de l'appareil"} 🔐" else "Protection retirée")
        } else {
            show("Le trousseau de l'appareil a refusé : réessayez.")
        }
        revision += 1
    }

    val vaultProtected: Boolean get() = StoreVault.isProtected

    val hasPin: Boolean get() = store.read("kv", "pin", PinHash) != null

    /** Le code chiffre-t-il la clé de la base ? (Un code d'avant cette version ne le fait qu'après avoir été tapé une fois.) */
    val codeEncrypts: Boolean get() = StoreVault.isCodeLocked

    /** Choisir le code (ou le retirer) ; Argon2id, hors du fil principal. */
    suspend fun setPin(pin: String?) {
        val s = store
        val ok = withContext(Dispatchers.Default) {
            if (pin != null) {
                s.write("kv", "pin", TalkaCrypto.hashPin(pin))
            } else {
                s.remove("kv", "pin")
            }
            s.save()
            // La clé de la base suit le code : chiffrée par lui, ou plus.
            val key = s.key ?: return@withContext true
            StoreVault.setCode(pin, key)
        }
        if (pin == null) {
            prefs.edit().remove(faceIdKey).apply()
            faceIDState = false
        }
        val key = store.key
        if (ok) {
            if (pin != null && faceID && key != null) StoreVault.setFaceUnlock(true, key)
        } else if (pin != null) {
            show("Le trousseau de l'appareil a refusé : vos messages restent protégés par l'appareil seul.")
        }
        revision += 1
    }

    /** Le bon code ? (Argon2id, hors du fil principal.) */
    suspend fun checkPin(pin: String): Boolean {
        val s = store
        val stored = s.read("kv", "pin", PinHash) ?: return true
        val ok = withContext(Dispatchers.Default) {
            val ok = TalkaCrypto.checkPin(pin, stored)
            // Un code d'avant (PBKDF2) : rehaché avec Argon2id.
            if (ok && stored.isOld) {
                s.write("kv", "pin", TalkaCrypto.hashPin(pin))
                s.save()
            }
            // Un code d'avant cette version : il chiffre désormais la clé.
            val key = s.key
            if (ok && !StoreVault.isCodeLocked && key != null && StoreVault.setCode(pin, key) && faceID) {
                StoreVault.setFaceUnlock(true, key)
            }
            ok
        }
        return ok
    }

    /**
     * Talka est fermé et sa base chiffrée par le code : le bon code ouvre
     * la clé (Argon2id, hors du fil principal), puis tout le reste.
     */
    suspend fun unlock(code: String): Boolean {
        val key = withContext(Dispatchers.Default) { StoreVault.key(code) } ?: return false
        open(key)
        return true
    }

    /** Talka est fermé : la biométrie ouvre la copie de la clé liée aux empreintes ou visages. */
    suspend fun unlockColdWithFace() {
        if (!StoreVault.hasFaceUnlock || biometryName == null) return
        val cipher = env.unlockCipher("cle-visage") ?: return
        if (!authenticate("Ouvrir vos messages", cipher, deviceCredential = false)) return
        val key = StoreVault.keyByFace(cipher) ?: return
        open(key)
    }

    fun open(key: ByteArray) {
        replaceStore(key)
        codeLocked = false
        locked = false
        forgetChosenServer()
        if (Engine.hasAccount(store)) start()
        loading = "Ouverture de Talka…"
        scope.launch { finishLoading() }
    }

    private fun replaceStore(key: ByteArray) {
        store.close()
        store = Store(env.dir, env.sql, key)
    }

    private var faceIDState by mutableStateOf(prefs.getBoolean(faceIdKey, false))

    /** La biométrie ouvre-t-elle Talka (à la place du code) ? */
    var faceID: Boolean
        get() = faceIDState
        set(value) {
            faceIDState = value
            prefs.edit().putBoolean(faceIdKey, value).apply()
            // La copie de la clé derrière la biométrie suit le réglage.
            val key = store.key
            if (StoreVault.isCodeLocked && key != null) StoreVault.setFaceUnlock(value, key)
        }

    // ----- Les protections de l'écran -----

    private var wipeAfterFailsState by mutableStateOf(prefs.getBoolean("talka.effacer10", false))

    /** Après 10 codes faux, tout est effacé. */
    var wipeAfterFails: Boolean
        get() = wipeAfterFailsState
        set(value) {
            wipeAfterFailsState = value
            prefs.edit().putBoolean("talka.effacer10", value).apply()
        }

    private var hideWhenCapturedState by mutableStateOf(!prefs.getBoolean("talka.capture.visible", false))

    /**
     * Masquer Talka pendant une capture, un enregistrement ou une recopie
     * de l'écran (activé par défaut) : sur Android, l'écran sécurisé
     * (`FLAG_SECURE`), noir dans les enregistrements et les captures.
     */
    var hideWhenCaptured: Boolean
        get() = hideWhenCapturedState
        set(value) {
            hideWhenCapturedState = value
            prefs.edit().putBoolean("talka.capture.visible", !value).apply()
        }

    /** L'empreinte ou le visage, si l'appareil en a. */
    val biometryName: String? get() = biometrics?.name

    suspend fun unlockWithBiometry() {
        if (!faceID || biometryName == null) return
        if (authenticate("Déverrouiller Talka", null, deviceCredential = false)) locked = false
    }

    /** Les essais manqués au verrou ; ils survivent à un redémarrage de l'app. */
    var lockFails: Int
        get() = prefs.getInt(failsKey, 0)
        set(value) {
            prefs.edit().putInt(failsKey, value).apply()
        }

    /** Pas de nouvel essai avant (ms). */
    var lockUntil: Long
        get() = prefs.getLong(untilKey, 0)
        set(value) {
            prefs.edit().putLong(untilKey, value).apply()
        }

    // ----- Liens d'invitation -----

    /** `talka://add/K7Q2MX9PRT`, `https://…/c/K7Q2MX9PRT`, `…/app/#add=…`. */
    fun open(url: String) {
        // Un widget ou une notification : une discussion (talka://chat/…) ou la liste (talka://chats).
        if (url.startsWith("talka://chat")) {
            val id = WidgetData.chat(url)
            tab = Tab.chats
            path = if (id != null && engine?.chat(id) != null) listOf(id) else emptyList()
            return
        }
        val code = TalkaCode.find(url) ?: return
        TalkaCode.fingerprint(url)?.let { engine?.expectFingerprint(code, it) }
        if (engine?.contact(code)?.saved == true) {
            openChat(code)
        } else {
            invite = code
            tab = Tab.chats
        }
    }

    fun openChat(id: String) {
        engine?.ensureDirectChatIfNeeded(id)
        tab = Tab.chats
        path = listOf(id)
    }

    /**
     * `/c/<empreinte>/<code>` : l'empreinte de nos clés, vérifiée par qui
     * l'ouvre ; le code reste à la fin pour les versions qui ne la
     * connaissent pas.
     */
    fun inviteLink(code: String): String {
        val server = serverString.trim('/')
        val me = engine?.me
        if (me != null && me.code == code) {
            val fp = TalkaCode.fingerprint(me.sign, me.dh)
            return "$server/c/$fp/$code"
        }
        return if (server.isEmpty()) "talka://add/$code" else "$server/c/$code"
    }

    // ----- Notifications et sons -----

    fun show(text: String) {
        toast = Toast(text)
    }

    val unreadTotal: Int
        get() = engine?.chats?.values?.filter { !it.muted && !it.archived }?.sumOf { it.unread } ?: 0

    /** Le thème choisi : clair, sombre, ou celui du système (`null`). */
    val darkTheme: Boolean?
        get() = when (engine?.settings?.theme) {
            "light" -> false
            "dark" -> true
            else -> null
        }

    fun incoming(chat: Chat, message: Message, onScreen: Boolean) {
        val e = engine ?: return
        if (onScreen) {
            if (e.settings.sound) Sounds.sent()
            return
        }
        // En sourdine, seulement si l'on nous mentionne (« @Léa »).
        if (chat.muted && e.me.code !in message.mentions) return
        val who = e.displayName(message.from)
        val text = if (message.kind == "text" && !message.isCapsule) Brocolis.masked(message.text) else Engine.preview(message)
        val body = if (chat.group) "$who : $text" else text
        if (active) {
            if (e.settings.sound) Sounds.incoming()
            if (path.lastOrNull() != chat.id) show("${e.title(chat)} : $body")
        } else if (e.settings.notify) {
            // L'écran verrouillé ne montre que ce qu'on a choisi.
            val shown = e.settings.notification(e.title(chat), body)
            Notifier.message(shown.title, shown.body, chat.id, e.settings.sound)
        }
    }

    // ----- Appels -----

    suspend fun startCall(peer: String, video: Boolean) {
        startGroupCall(listOf(peer), video, "")
    }

    /**
     * Appeler plusieurs personnes à la fois (les membres d'un groupe, ou des
     * contacts choisis) : huit personnes au plus, nous compris.
     */
    suspend fun startGroupCall(peers: List<String>, video: Boolean, chat: String) {
        if (call != null) return show("Un appel est déjà en cours.")
        val e = engine
        if (e == null || e.netState != Relay.State.online) return show("Pas de connexion au réseau.")
        val seen = HashSet<String>()
        val list = peers.filter { it != e.me.code && !e.blocked(it) && seen.add(it) }
        val first = list.firstOrNull() ?: return show("Personne à appeler.")
        if (list.size >= Mesh.maxPeople) return show("Un appel réunit huit personnes au plus.")
        val (mesh, todo) = Mesh.outgoing(e.me.code, list)
        val c = CallEngine(e, this, first, chat, mesh, video, incoming = false, id = randomId(10))
        call = c
        c.startOutgoing(todo)
    }

    /** Appeler depuis une discussion : le contact, ou tous les membres du groupe (huit personnes au plus). */
    suspend fun callChat(chatId: String, video: Boolean) {
        val e = engine
        val chat = e?.chat(chatId)
        if (e == null || chat == null || !chat.group) return startCall(chatId, video)
        val others = chat.members.filter { it != e.me.code }
        if (others.size >= Mesh.maxPeople) {
            return show("Ce groupe compte plus de huit personnes : un appel en réunit huit au plus. Appelez depuis l'onglet Appels, en choisissant qui.")
        }
        startGroupCall(others, video, chatId)
    }

    /** Rappeler depuis le journal : la même personne, ou les mêmes personnes. */
    suspend fun callAgain(entry: CallEntry) {
        val e = engine
        if (e == null || entry.members.size <= 1) return startCall(entry.peer, entry.video)
        startGroupCall(entry.members.filter { it != e.me.code }, entry.video, entry.chat)
    }

    fun callSignal(from: String, body: Body, live: Boolean) {
        val b = body as? Body.Call ?: return
        val e = engine ?: return
        // Pris ou refusé sur un autre de nos appareils (v4) : on cesse de sonner.
        if (b.op == "elsewhere") {
            val c = call
            if (from == e.me.code && c != null && c.id == b.callId && c.incoming && !c.mesh.joined) c.takenElsewhere()
            return
        }
        // Un signal de l'appel en cours, de l'un de ses participants.
        val current = call
        if (current != null && current.id == b.callId && current.phase != CallEngine.Phase.ended) {
            scope.launch { current.signal(from, body, live) }
            return
        }
        if (b.op != "offer") return
        if (!live) return // une vieille offre : l'appel est passé
        if (call != null || e.blocked(from)) {
            e.sendCallSignal(from, Body.Call(op = "busy", callId = b.callId, video = false, sdp = "", cand = null))
            e.logCall(
                CallEntry(
                    id = b.callId, peer = from, video = b.video, incoming = true, at = nowMs(), dur = 0.0, missed = true,
                    members = b.members.filter { it != e.me.code }, chat = b.chat,
                ),
            )
            return
        }
        val mesh = Mesh.incoming(e.me.code, from, b.sdp, b.members)
        val c = CallEngine(e, this, from, b.chat, mesh, b.video, incoming = true, id = b.callId)
        call = c
        c.ring()
        if (!active && e.settings.notify) {
            val what = if (mesh.isGroup) {
                if (b.video) "📹 Appel vidéo de groupe entrant" else "📞 Appel de groupe entrant"
            } else {
                if (b.video) "📹 Appel vidéo entrant" else "📞 Appel entrant"
            }
            // La notification d'appel : elle sonne, et ouvre l'écran d'appel même verrouillé.
            c.alert(what)
        }
    }

    // ----- Sauvegarde -----

    /** Le fichier de sauvegarde chiffrée : « talka-sauvegarde-2026-10-21.talka ». */
    val backupFilename: String get() = "talka-sauvegarde-${LocalDate.now()}.talka"

    /** Arrête tout (l'activité est détruite pour de bon, ou les tests). */
    fun close() {
        engine?.stop()
        engine = null
        store.close()
    }

    companion object {
        private var nextToast = 0L
        private const val faceIdKey = "talka.faceid"
        private const val failsKey = "talka.essais"
        private const val untilKey = "talka.essais.jusqua"

        /**
         * Le relais de Talka : celui que la CI intègre (`TALKA_SERVER`), sinon
         * le relais officiel. Rien à choisir.
         */
        val builtInServer: String
            get() {
                val s = BuildConfig.TALKA_SERVER.trim()
                return if (Regex("^https?://[^/]+").containsMatchIn(s)) s else TalkaServer.official
            }

        /** Ce que montre la liste des appareils du compte (v4) : « Pixel 9 », « Samsung SM-S911B ». */
        val deviceName: String
            get() {
                val model = Build.MODEL.orEmpty().trim()
                val maker = Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.titlecase() }
                return when {
                    model.isEmpty() -> "Android"
                    maker.isEmpty() || model.startsWith(maker, ignoreCase = true) || maker.equals("Google", true) -> model
                    else -> "$maker $model"
                }
            }
    }
}

/** Ouvre la discussion d'un contact (sans en créer pour un groupe). */
fun Engine.ensureDirectChatIfNeeded(id: String) {
    if (chat(id) == null && !isGroup(id)) ensureDirectChat(id)
}

/** Une tâche de l'application, sur le fil principal. */
fun AppModel.launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
