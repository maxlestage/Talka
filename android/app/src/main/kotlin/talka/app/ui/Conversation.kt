// Une conversation : les bulles (texte, photo, vocal, fichier, contact), les
// réponses, réactions et ✓✓, le menu d'un message, et la zone de saisie.
// Le portage de `Conversation.swift` (la capsule, les vocaux et la pluie de
// brocolis sont dans `Capsule.kt`, `Voice.kt` et `Brocoli.kt`).
//
// L'onglet Discussions l'ouvre pour `model.path.last` ; le retour (flèche
// ou geste) enlève la discussion de `model.path`.

package talka.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyCharacterMap
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talka.app.LocalAppModel
import talka.app.LocalDarkTheme
import talka.app.LocalWide
import talka.app.TalkaColors
import talka.app.ensureDirectChatIfNeeded
import talka.app.launch
import talka.kit.Brocolis
import talka.kit.Chat
import talka.kit.Content
import talka.kit.Engine
import talka.kit.Mentions
import talka.kit.Message
import talka.kit.Relay
import talka.kit.Reply
import talka.kit.TalkaCode
import talka.kit.dayMs
import talka.kit.formatBytes
import talka.kit.formatDuration
import talka.kit.maxMessageText
import talka.kit.nextVoice
import talka.kit.nowMs
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Une page de messages : une longue discussion s'ouvre sur sa fin, les plus anciens viennent en remontant. */
private const val page = 300

/** « 3 Mo » : encodé deux fois en base64 (message, puis chiffré), un fichier tient dans la limite de 6 Mo du relais. */
private const val maxFileBytes = 3 * 1024 * 1024

/** Les réactions du menu d'un message. */
private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * L'état d'une discussion ouverte, gardé tant qu'elle l'est (sa fiche peut
 * passer par-dessus, puis s'en aller) : la saisie, la réponse ou la
 * modification en cours, ce qui est affiché, la recherche…
 */
private class ConversationState(e: Engine, val chatId: String) {
    var text by mutableStateOf(TextFieldValue(e.chat(chatId)?.draft ?: ""))
    var reply by mutableStateOf<Message?>(null)
    var editing by mutableStateOf<Message?>(null)
    var forwarding by mutableStateOf<Message?>(null)
    var info by mutableStateOf<Message?>(null)
    var deleting by mutableStateOf<Message?>(null)
    var viewing by mutableStateOf<Message?>(null)
    var reactions by mutableStateOf<Message?>(null)
    var showInfo by mutableStateOf(false)
    var sharingContact by mutableStateOf(false)
    var composingCapsule by mutableStateOf(false)
    var choosingBrocolis by mutableStateOf(false)
    var managingBrocolis by mutableStateOf(false)

    /** Combien de messages sont affichés, à partir des derniers. */
    var shown by mutableIntStateOf(page)

    /** La dernière fois que des messages plus anciens ont été demandés. */
    var grewAt = 0L

    /** Le message qui était en haut quand les plus anciens ont été demandés : la lecture y reste. */
    var keep by mutableStateOf<String?>(null)

    /** La recherche dans la discussion (`null` : fermée). */
    var search by mutableStateOf<String?>(null)

    /** Le message vers lequel faire défiler (épingle, réponse, recherche). */
    var jumpTo by mutableStateOf<String?>(null)

    /** Un message plus ancien que ce qui était affiché : on y va dès qu'il l'est. */
    var pendingJump by mutableStateOf<String?>(null)

    /** « N messages non lus » : le premier non lu à l'ouverture, et leur nombre (comme le navigateur). */
    var unreadMark by mutableStateOf<Pair<String, Int>?>(null)

    /** La pluie de brocolis en cours (l'œuf de Pâques 🥦). */
    var rain by mutableStateOf<Long?>(null)

    /** Le dernier message affiché quand on a quitté le bas : ce qui arrive ensuite se compte sur le bouton. */
    var seenAtLeave by mutableStateOf<String?>(null)

    /** Le dernier message déjà vu par l'écran (ce qui arrive ensuite y fait descendre). */
    var lastId: String? = null
    var list: LazyListState? = null
    val chain = VoiceChain()
    val focus = FocusRequester()
    val searchFocus = FocusRequester()

    init {
        // La discussion d'avant « lu » : ses non-lus d'avant l'ouverture.
        val n = e.chat(chatId)?.unread ?: 0
        val now = nowMs()
        val end = e.recentMessages(chatId, page)
        var list = end.list.filter { m -> m.exp?.let { it > now } ?: true }
        // Plus de non-lus qu'une page : ils sont tous affichés.
        if (end.more && n > list.size) {
            shown = n + 20
            list = e.recentMessages(chatId, n + 20).list
        }
        if (n > 0 && list.size >= n) unreadMark = list[list.size - n].id to n
        lastId = list.lastOrNull()?.id
    }
}

/** Une ligne du fil : le haut, un jour, le bandeau des non-lus, un message, le bas. */
private class Entry(val key: String, val kind: Int, val m: Message? = null, val text: String = "", val first: Boolean = false, val count: Int = 0) {
    companion object {
        const val top = 0
        const val day = 1
        const val unread = 2
        const val message = 3
        const val bottom = 4
    }
}

@Composable
fun ConversationView(chatId: String) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val s = remember(chatId) { ConversationState(e, chatId) }
    DisposableEffect(chatId) {
        e.activeChat = chatId
        e.markRead(chatId)
        Notifier.cancel(chatId)
        onDispose {
            if (e.activeChat == chatId) e.activeChat = null
            // Le brouillon reste (pas pendant une modification), « écrit… » s'arrête.
            val draft = if (s.editing == null) s.text.text else ""
            if (e.chat(chatId) != null && draft != e.chat(chatId)?.draft) e.updateChat(chatId) { it.copy(draft = draft) }
            e.setTyping(chatId, false)
        }
    }
    // Arrivé par « Répondre en privé » : la réponse est prête.
    LaunchedEffect(chatId) {
        val m = model.privateReply
        if (m != null && m.from == chatId) {
            model.privateReply = null
            s.reply = m
            delay(100)
            requestFocus(s.focus)
        }
    }
    // Arrivé par un résultat de recherche, ou un message choisi dans sa
    // fiche (ses messages importants) : retour au fil, sur le message.
    LaunchedEffect(chatId, model.reveal) {
        val id = model.reveal ?: return@LaunchedEffect
        if (e.store.message(chatId, id) == null) return@LaunchedEffect
        model.reveal = null
        val fromInfo = s.showInfo
        s.showInfo = false
        delay(if (fromInfo) 450 else 350)
        s.jumpTo = id
    }
    val chat = e.chat(chatId)
    // Sur une fenêtre large, la discussion est à droite de la liste : pas de retour.
    val wide = LocalWide.current
    val back = { model.path = model.path.dropLast(1) }
    when {
        chat == null -> {
            if (wide) {
                EmptyState("Discussion introuvable", modifier = Modifier.fillMaxSize().padding(top = 80.dp))
            } else {
                Page("", back) { padding -> EmptyState("Discussion introuvable", modifier = Modifier.padding(padding).padding(top = 80.dp)) }
            }
        }
        s.showInfo -> if (chat.group) GroupInfoView(chatId, onBack = { s.showInfo = false }) else ContactInfoView(chatId, onBack = { s.showInfo = false })
        s.managingBrocolis -> BrocolisKeysView(onBack = { s.managingBrocolis = false })
        else -> {
            if (!wide) BackHandler(onBack = back)
            // Les vocaux de cette discussion s'enchaînent (l'`.environment(voiceChain)` de l'iPhone).
            CompositionLocalProvider(LocalVoiceChain provides s.chain) {
                // Une autre discussion : tout repart de zéro (l'enregistreur aussi).
                key(chatId) { ConversationScreen(e, chat, s, if (wide) null else back) }
            }
        }
    }
}

private fun requestFocus(f: FocusRequester) {
    try {
        f.requestFocus()
    } catch (x: IllegalStateException) {
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(e: Engine, chat: Chat, s: ConversationState, onBack: (() -> Unit)?) {
    // Ce qui change dans le moteur (une réaction, une étoile, une coche) redessine la discussion.
    LocalAppModel.current.revision
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatId = chat.id
    val me = e.me.code
    // Les messages éphémères expirés disparaissent tout de suite, comme dans le navigateur.
    val now = nowMs()
    val query = (s.search ?: "").trimSpaces()
    // La fin de la discussion (une page, plus ce qu'on a remonté) ; une
    // recherche fouille toute la discussion (`Engine.searchChat`).
    val end = if (query.isEmpty()) e.recentMessages(chatId, s.shown) else null
    val all = end?.list ?: e.searchChat(chatId, query)
    val more = end?.more ?: false
    val messages = if (all.none { it.exp != null }) all else all.filter { m -> m.exp?.let { it > now } ?: true }
    val mark = s.unreadMark
    val entries = remember(messages, more, mark, query.isEmpty()) {
        val out = ArrayList<Entry>(messages.size * 2 + 2)
        out.add(Entry("fil-haut", Entry.top))
        for ((i, m) in messages.withIndex()) {
            val prev = if (i > 0) messages[i - 1] else null
            if (prev == null || !Dates.sameDay(prev.at, m.at)) out.add(Entry("d:" + m.id, Entry.day, text = Dates.separator(m.at)))
            if (mark != null && mark.first == m.id && query.isEmpty()) out.add(Entry("unread-separator", Entry.unread, count = mark.second))
            val first = prev == null || prev.from != m.from || prev.kind == "system"
            out.add(Entry("m:" + m.id, Entry.message, m, first = first))
        }
        out.add(Entry("fil-bas", Entry.bottom))
        out
    }
    // La discussion s'ouvre sur sa fin, ou sur le bandeau des non-lus.
    val listState = s.list ?: LazyListState(
        entries.indexOfFirst { it.kind == Entry.unread }.takeIf { it >= 0 } ?: entries.lastIndex,
    ).also { s.list = it }
    val entriesNow by rememberUpdatedState(entries)
    // Le bas de la discussion est à l'écran : un message qui arrive y fait
    // descendre ; sinon le bouton « Derniers messages » apparaît.
    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount == 0 || info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }
    val bottomNow = atBottom
    val lastIdNow by rememberUpdatedState(messages.lastOrNull()?.id)
    LaunchedEffect(listState) {
        snapshotFlow { atBottom }.collect { s.seenAtLeave = if (it) null else lastIdNow }
    }
    // Le bandeau des non-lus, un peu sous le haut du fil : la date qui y
    // reste affichée ne doit pas le recouvrir.
    LaunchedEffect(listState) {
        if (s.unreadMark == null || listState.firstVisibleItemIndex != entries.indexOfFirst { it.kind == Entry.unread }) return@LaunchedEffect
        val h = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        listState.scrollBy(-0.08f * h)
    }
    // Un message arrive ou part.
    val last = messages.lastOrNull()
    LaunchedEffect(last?.id) {
        val m = last ?: return@LaunchedEffect
        if (m.id == s.lastId) return@LaunchedEffect
        s.lastId = m.id
        // Comme le navigateur : on descend si l'on était en bas, ou pour
        // notre propre message ; pas pendant la lecture plus haut.
        if (bottomNow || m.from == me) listState.animateScrollToItem(entriesNow.lastIndex)
        e.markRead(chatId)
        // Seulement pour un message qui arrive ou part maintenant.
        if (nowMs() - m.at < 10_000 && m.summonsBrocoli(me)) {
            if (!reduceMotion(context)) {
                s.rain = System.nanoTime()
                model.show("🥦 Chiffré au brocoli (et en AES-256-GCM, quand même)")
            }
        }
    }
    // Les vocaux qui s'enchaînent : le suivant d'après la discussion ; il démarre, la discussion le montre.
    LaunchedEffect(chatId) {
        s.chain.next = { id -> nextVoice(id, e.recentMessages(chatId, page).list) }
    }
    LaunchedEffect(s.chain.autoplay) {
        val id = s.chain.autoplay ?: return@LaunchedEffect
        val i = entriesNow.indexOfFirst { it.key == "m:$id" }
        if (i >= 0) land(listState, i, center = true, animated = true)
    }
    // Aller à un message : à l'écran, ou plus ancien que ce qui est affiché.
    LaunchedEffect(s.jumpTo) {
        val id = s.jumpTo ?: return@LaunchedEffect
        s.jumpTo = null
        val i = entriesNow.indexOfFirst { it.key == "m:$id" }
        if (i >= 0) {
            // Hors de l'effet : remettre `jumpTo` à zéro ne doit pas arrêter le défilement.
            scope.launch { land(listState, i, center = true, animated = true) }
        } else if (more && e.store.message(chatId, id) != null) {
            // Plus ancien que ce qui est affiché : toute la discussion, puis le message.
            s.shown = Int.MAX_VALUE
            s.pendingJump = id
        } else {
            model.show("Message introuvable")
        }
    }
    LaunchedEffect(s.pendingJump, entries.size) {
        val id = s.pendingJump ?: return@LaunchedEffect
        val i = entries.indexOfFirst { it.key == "m:$id" }
        if (i >= 0) {
            s.pendingJump = null
            scope.launch { land(listState, i, center = true, animated = false) }
        }
    }
    // Des messages plus anciens sont arrivés au-dessus : la lecture reste sur
    // le message qui était en haut.
    LaunchedEffect(s.keep, entries.size) {
        val id = s.keep ?: return@LaunchedEffect
        val i = entries.indexOfFirst { it.key == "m:$id" }
        if (i > 0) {
            s.keep = null
            scope.launch { listState.scrollToItem(i) }
        }
    }
    // La recherche s'ouvre : le champ prend la main.
    LaunchedEffect(s.search != null) {
        if (s.search != null) {
            delay(50)
            requestFocus(s.searchFocus)
        }
    }
    LaunchedEffect(s.rain) {
        val id = s.rain ?: return@LaunchedEffect
        delay(5000)
        if (s.rain == id) s.rain = null
    }

    // ----- Ce qui part -----

    fun send(content: Content) {
        var c = content
        s.reply?.let { r ->
            val plain = r.kind == "text" && !r.sealed(me)
            c = c.copy(reply = Reply(r.id, r.from, (if (plain) r.text else Engine.preview(r)).firstChars(160)))
            s.reply = null
        }
        e.setTyping(chatId, false)
        if (e.send(chatId, c) != null && e.settings.sound) Sounds.sent()
    }

    fun submit() {
        val t = s.text.text.trim()
        if (t.isEmpty()) return
        val editing = s.editing
        if (editing != null) {
            if (t != editing.text) e.edit(chatId, editing.id, t)
            s.editing = null
        } else {
            var content = Content(text = t.firstChars(maxMessageText))
            val c = e.chat(chatId)
            if (c != null && c.group) content = content.copy(mentions = Mentions.found(content.text, e.mentionCandidates(c)))
            send(content)
        }
        s.text = TextFieldValue("")
        e.updateChat(chatId) { it.copy(draft = "") }
    }

    fun forward(m: Message, to: String) {
        s.forwarding = null
        e.ensureDirectChatIfNeeded(to)
        e.forward(m, listOf(to))
        model.show("Message transféré")
    }

    val save = rememberSaver()
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        scope.launch {
            for (uri in uris) {
                val image = withContext(Dispatchers.Default) { Images.load(context, uri)?.let { Images.compressed(it) } } ?: continue
                send(Content(kind = "image", data = image.first, mime = "image/jpeg", w = image.second, h = image.third))
            }
        }
    }
    val camera = rememberCameraPicker { bitmap ->
        Images.compressed(bitmap)?.let { send(Content(kind = "image", data = it.first, mime = "image/jpeg", w = it.second, h = it.third)) }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readPicked(context, uri) }
            when {
                picked == null -> {}
                picked.data.size > maxFileBytes -> model.show("Fichier trop lourd (3 Mo au plus).")
                else -> send(Content(kind = "file", data = picked.data.dataURL(picked.mime), name = picked.name, mime = picked.mime, size = picked.data.size.toDouble()))
            }
        }
    }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    fun startRecording() {
        if (!recorder.start()) model.show("Le micro n'est pas autorisé.")
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else model.show("Le micro n'est pas autorisé.")
    }
    val attach = ComposerActions(
        photos = {
            model.leavingOnPurpose()
            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        camera = if (cameraAvailable(context)) camera else null,
        file = {
            model.leavingOnPurpose()
            files.launch(arrayOf("*/*"))
        },
        contact = { s.sharingContact = true },
        capsule = { s.composingCapsule = true },
        voice = {
            if (recorder.recording) {
                val dur = recorder.elapsed()
                recorder.stop()?.let { send(VoiceRecorder.content(it, dur)) }
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                model.leavingOnPurpose()
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        submit = { submit() },
    )

    // ----- L'écran -----

    // Un contact, ou un groupe dont on est encore membre.
    val calls = if (chat.group) !chat.left && chat.members.size > 1 else !e.blocked(chatId)
    val head = TalkaColors.head()
    Box(Modifier.fillMaxSize()) {
        // Le fond couvre tout l'écran, sous la barre du haut et la saisie.
        Wallpaper(if (chat.wallpaper.isEmpty()) e.settings.wallpaper else chat.wallpaper)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // L'en-tête du navigateur : vert, le nom et les boutons en blanc.
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = head,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    navigationIcon = {
                        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                    },
                    title = {
                        val title = e.title(chat)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = "Infos de $title") { s.showInfo = true }
                                .semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PresenceAvatar(e, chatId, 34.dp)
                            Column {
                                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(subtitle(e, chat), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    actions = {
                        // Appel vidéo, appel audio et le menu, comme le navigateur.
                        if (calls) {
                            IconButton(onClick = { model.launch { model.callChat(chatId, video = true) } }) { Icon(Icons.Filled.Videocam, "Appel vidéo") }
                            IconButton(onClick = { model.launch { model.callChat(chatId, video = false) } }) { Icon(Icons.Filled.Call, "Appel audio") }
                        }
                        ConversationMenu(e, chat, s, onExport = {
                            e.exportChat(chatId)?.let { save(it.text.toByteArray(), it.filename, "text/plain") }
                        })
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if (s.search != null) SearchBar(s, found = messages.size, active = query.isNotEmpty())
                e.pinnedMessage(chatId)?.let { PinBar(e, it, onOpen = { s.jumpTo = it.id }, onUnpin = { e.setPin(chatId, "") }) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    ) {
                        items(entries, key = { it.key }, contentType = { it.kind }) { en ->
                            when (en.kind) {
                                Entry.top -> FilTop(more, onOlder = {
                                    // Pas deux demandes coup sur coup (un double toucher).
                                    val t = System.currentTimeMillis()
                                    if (t - s.grewAt > 500) {
                                        s.grewAt = t
                                        // Autant que ce qui est déjà affiché (300, puis 600, 1 200… 2 400 au plus à la fois).
                                        val n = min(max(page, s.shown), 2_400)
                                        s.keep = messages.firstOrNull()?.id
                                        s.shown = if (s.shown > Int.MAX_VALUE - n) Int.MAX_VALUE else s.shown + n
                                    }
                                })
                                Entry.day -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { DayChip(en.text) }
                                Entry.unread -> UnreadSeparator(en.count)
                                Entry.message -> {
                                    val m = en.m!!
                                    MessageRow(
                                        e, chat, m, en.first,
                                        onReply = {
                                            s.reply = m
                                            requestFocus(s.focus)
                                        },
                                        onOpen = { s.viewing = m },
                                        onScrollTo = { id ->
                                            if (messages.any { it.id == id }) {
                                                s.jumpTo = id
                                            } else if (more && e.store.message(chatId, id) != null) {
                                                s.jumpTo = id
                                            }
                                        },
                                        onReactions = { s.reactions = m },
                                    ) { close -> MessageMenu(e, chat, m, s, save, close) }
                                }
                                else -> Spacer(Modifier.height(1.dp).fillMaxWidth())
                            }
                        }
                    }
                    // La date du message tout en haut reste affichée pendant le défilement.
                    val topDay by remember(listState) {
                        derivedStateOf {
                            val visible = listState.layoutInfo.visibleItemsInfo
                            val top = visible.firstOrNull { entriesNow.getOrNull(it.index)?.kind == Entry.message && it.offset + it.size > 0 }
                            top?.let { entriesNow.getOrNull(it.index)?.m?.at }?.let { Dates.separator(it) }
                        }
                    }
                    topDay?.let {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).clearAndSetSemantics {}) { DayChip(it) }
                    }
                    if (!bottomNow) {
                        val below = s.seenAtLeave?.let { Message.newBelow(messages, it, me) } ?: 0
                        LatestButton(below, Modifier.align(Alignment.BottomEnd)) {
                            scope.launch { listState.animateScrollToItem(entries.lastIndex) }
                        }
                    }
                }
                Composer(e, chat, s, recorder, attach)
            }
        }
        s.rain?.let { id -> androidx.compose.runtime.key(id) { BrocoliRain() } }
    }

    // ----- Les feuilles et les questions -----

    if (s.composingCapsule) {
        CapsuleComposer(onDismiss = { s.composingCapsule = false }) { body, openAt ->
            s.composingCapsule = false
            send(Content(text = body, openAt = openAt))
            model.show("Capsule scellée ⏳")
        }
    }
    if (s.sharingContact) {
        ContactPicker("Partager un contact", onDismiss = { s.sharingContact = false }) { code ->
            s.sharingContact = false
            val c = e.contact(code)
            send(Content(kind = "contact", name = c?.name?.ifEmpty { null } ?: c?.profileName ?: "", code = code))
        }
    }
    s.forwarding?.let { m ->
        ContactPicker(
            "Transférer à",
            onDismiss = { s.forwarding = null },
            header = {
                // Seulement là où l'on peut écrire (pas un groupe réservé aux admins).
                val groups = e.chats.values.filter { it.group && it.canWrite(me) }
                if (groups.isNotEmpty()) {
                    Column {
                        SectionHeader("Groupes")
                        for (g in groups) {
                            ListItem(
                                leadingContent = { EngineAvatar(e, g.id, 40.dp) },
                                headlineContent = { Text(e.title(g)) },
                                modifier = Modifier.clickable { forward(m, g.id) },
                            )
                        }
                    }
                }
            },
        ) { code -> forward(m, code) }
    }
    s.info?.let { m -> MessageInfoSheet(e, m, onDismiss = { s.info = null }) }
    s.reactions?.let { m -> ReactionsSheet(e, m, onDismiss = { s.reactions = null }) }
    s.viewing?.let { m -> ImageViewer(e, m, onDismiss = { s.viewing = null }) }
    if (s.choosingBrocolis) ChatBrocolisSheet(chatId, onDismiss = { s.choosingBrocolis = false }, onManage = {
        s.choosingBrocolis = false
        s.managingBrocolis = true
    })
    s.deleting?.let { m ->
        val red = MaterialTheme.colorScheme.error
        AlertDialog(
            onDismissRequest = { s.deleting = null },
            title = { Text("Supprimer le message ?") },
            text = {
                Column {
                    if (m.from == me && !m.deleted && nowMs() - m.at < 2 * dayMs) {
                        TextButton(onClick = {
                            s.deleting = null
                            e.delete(chatId, m.id, forEveryone = true)
                        }) { Text("Supprimer pour tout le monde", color = red) }
                    }
                    TextButton(onClick = {
                        s.deleting = null
                        e.delete(chatId, m.id, forEveryone = false)
                    }) { Text("Supprimer pour moi", color = red) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { s.deleting = null }) { Text("Annuler") } },
        )
    }
}

/** Défile jusqu'à une ligne du fil ; au milieu de l'écran si `center`. */
private suspend fun land(state: LazyListState, index: Int, center: Boolean, animated: Boolean) {
    if (animated) state.animateScrollToItem(index) else state.scrollToItem(index)
    if (!center) return
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val delta = item.offset - (viewport - item.size) / 2f
    if (animated) state.animateScrollBy(delta) else state.scrollBy(delta)
}

/** « 14 Ko », un nom, un type : un fichier choisi. */
private class Picked(val data: ByteArray, val name: String, val mime: String)

/** Lit un fichier choisi (au plus un octet de plus que la limite : un trop gros n'est pas lu en entier). */
private fun readPicked(context: Context, uri: Uri): Picked? =
    try {
        val resolver = context.contentResolver
        var name = ""
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: ""
        }
        if (name.isEmpty()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri)?.takeIf { it.isNotEmpty() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val data = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() <= maxFileBytes) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        data?.let { Picked(it, name, mime) }
    } catch (x: Exception) {
        null
    }

/**
 * « Enregistrer » une pièce jointe (le sélecteur de fichiers d'Android :
 * Téléchargements, Drive…), sous son nom : le partage de l'iPhone.
 */
@Composable
private fun rememberSaver(): (ByteArray, String, String) -> Unit {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<Pair<ByteArray, String>>(1) }
    val contract = remember {
        object : ActivityResultContracts.CreateDocument("*/*") {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).setType(pending[0]?.second?.ifEmpty { null } ?: "application/octet-stream")
        }
    }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        val data = pending[0]?.first
        pending[0] = null
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) } != null
                } catch (x: Exception) {
                    false
                }
            }
            model.show(if (ok) "Fichier enregistré ✓" else "Le fichier n'a pas pu être écrit.")
        }
    }
    return { data, name, mime ->
        pending[0] = data to mime
        model.leavingOnPurpose()
        try {
            launcher.launch(name)
        } catch (x: ActivityNotFoundException) {
            pending[0] = null
            model.show("Le fichier n'a pas pu être écrit.")
        }
    }
}

/** Le type d'une adresse `data:` (`image/jpeg`…). */
private fun mimeOf(m: Message): String =
    m.mime.ifEmpty { if (m.data.startsWith("data:")) m.data.substring(5).takeWhile { it != ';' && it != ',' } else "" }

// ----- L'en-tête -----

/** Les mêmes mots que le navigateur (`subtitle`, client/src/conv.rs). */
private fun subtitle(e: Engine, chat: Chat): String {
    val typing = e.typing[chat.id] ?: emptySet()
    if (typing.isNotEmpty()) return if (chat.group) "${typing.joinToString(", ") { e.displayName(it) }} écrit…" else "écrit…"
    if (chat.group) {
        if (chat.left) return "Vous ne faites plus partie de ce groupe"
        return chat.members.joinToString(", ") { if (it == e.me.code) "Vous" else e.displayName(it) }
    }
    if (e.blocked(chat.id)) return "Bloqué"
    // Sans relais, sa présence est inconnue (comme WhatsApp).
    if (e.netState != Relay.State.online) return "Connexion…"
    e.presence[chat.id]?.let { p ->
        if (p.online) return "en ligne"
        p.seen?.let { return Dates.lastSeen(it) }
    }
    return TalkaCode.pretty(chat.id)
}

/**
 * Le fond : une photo de la galerie (couvre la zone, avec un léger voile
 * pour garder les bulles lisibles), sinon une couleur.
 */
@Composable
private fun Wallpaper(w: String) {
    val image = if (w.startsWith("data:")) remember(w) { Images.fromDataURL(w) } else null
    Box(Modifier.fillMaxSize().background(if (image != null) TalkaColors.chatBackground() else wallpaperColor(w))) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)))
        }
    }
}

@Composable
private fun wallpaperColor(w: String): Color {
    val dark = LocalDarkTheme.current
    return when (w) {
        "plain" -> if (dark) Color.Black else Color(0xFFF2F2F7)
        "mint" -> if (dark) Color(0.05f, 0.16f, 0.13f) else Color(0.85f, 0.95f, 0.9f)
        "sky" -> if (dark) Color(0.06f, 0.11f, 0.18f) else Color(0.86f, 0.92f, 0.98f)
        "rose" -> if (dark) Color(0.18f, 0.07f, 0.1f) else Color(0.98f, 0.89f, 0.91f)
        "night" -> Color(0.07f, 0.09f, 0.14f)
        "sand" -> if (dark) Color(0.16f, 0.13f, 0.09f) else Color(0.96f, 0.92f, 0.84f)
        else -> TalkaColors.chatBackground()
    }
}

// ----- La recherche et l'épingle -----

@Composable
private fun SearchBar(s: ConversationState, found: Int, active: Boolean) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextField(
                value = s.search ?: "",
                onValueChange = { s.search = it },
                placeholder = { Text("Rechercher dans la discussion…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                colors = clearFieldColors(),
                modifier = Modifier.weight(1f).focusRequester(s.searchFocus).semantics { contentDescription = "Rechercher dans la discussion" },
            )
            if (active) {
                Text(
                    when (found) {
                        0 -> "Aucun résultat"
                        1 -> "1 message"
                        else -> "$found messages"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { s.search = null }) { Icon(Icons.Filled.Cancel, "Fermer la recherche", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Le message épinglé, sous l'en-tête : le toucher y ramène. */
@Composable
private fun PinBar(e: Engine, m: Message, onOpen: () -> Unit, onUnpin: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(onClickLabel = "Aller au message épinglé", onClick = onOpen).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.PushPin, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Column {
                    Text(e.displayName(m.from), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    // Une capsule encore scellée ne se lit pas non plus dans l'épingle.
                    Text(e.readable(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onUnpin) { Icon(Icons.Filled.Close, "Désépingler", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ----- Le menu ⋮ de la discussion -----

/**
 * Le menu ⋮ d'une discussion : les mêmes choix, dans le même ordre, que
 * celui du navigateur (`chat_menu`), comme WhatsApp. (`ChatMoreMenu` de
 * l'iPhone.)
 */
@Composable
private fun ConversationMenu(e: Engine, chat: Chat, s: ConversationState, onExport: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val chatId = chat.id
    var open by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    var muting by remember { mutableStateOf(false) }
    var wallpaper by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<Boolean?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    val red = MaterialTheme.colorScheme.error

    @Composable
    fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, action: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label, color = if (danger) red else Color.Unspecified) },
            leadingIcon = { Icon(icon, null, tint = if (danger) red else MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = {
                open = false
                action()
            },
        )
    }

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Plus d'options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            item(if (chat.group) "Infos du groupe" else "Infos du contact", Icons.Filled.Info) { s.showInfo = true }
            item(if (s.search == null) "Rechercher" else "Fermer la recherche", Icons.Filled.Search) {
                s.search = if (s.search == null) "" else null
            }
            item("Messages éphémères", Icons.Filled.Timer) { timer = true }
            item("Exporter la discussion", Icons.Filled.Share, action = onExport)
            item(
                if (chat.brocolis.isEmpty()) "Chiffrer avec Brocolis 🥦" else "Brocolis 🥦 : ${e.chatBrocolis(chat).joinToString(", ") { it.name }}",
                Icons.Filled.Shield,
            ) { s.choosingBrocolis = true }
            if (chat.muted) {
                item("Réactiver les notifications", Icons.Filled.Notifications) { e.mute(chatId, 0.0) }
            } else {
                item("Mettre en sourdine", Icons.Filled.NotificationsOff) { muting = true }
            }
            item("Fond d'écran", Icons.Filled.Palette) { wallpaper = true }
            // Quelqu'un qui nous a écrit sans être dans nos contacts.
            if (!chat.group && e.contact(chatId)?.saved != true) {
                item("Ajouter aux contacts", Icons.Filled.PersonAdd) {
                    contactName = e.contact(chatId)?.profileName ?: ""
                    adding = true
                }
            }
            if (!chat.group) {
                if (e.blocked(chatId)) {
                    item("Débloquer", Icons.Filled.Block) { e.setBlocked(chatId, false) }
                } else {
                    item("Bloquer", Icons.Filled.Block, danger = true) { e.setBlocked(chatId, true) }
                }
            }
            item("Vider la discussion", Icons.Filled.DeleteSweep, danger = true) { clearing = e.hasStarred(chatId) }
            item(if (chat.group) "Quitter et supprimer" else "Supprimer la discussion", Icons.Filled.Delete, danger = true) { deleting = true }
        }
    }

    if (timer) {
        AlertDialog(
            onDismissRequest = { timer = false },
            title = { Text("Messages éphémères") },
            text = {
                Column {
                    for ((label, secs) in listOf("Désactivés" to 0L, "24 heures" to 86_400L, "7 jours" to 7 * 86_400L, "90 jours" to 90 * 86_400L)) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                timer = false
                                if (chat.timer != secs) e.setTimer(chatId, secs)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chat.timer == secs, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { timer = false }) { Text("Annuler") } },
        )
    }
    if (muting) MuteChoicesDialog(e, chatId, muted = false, onDismiss = { muting = false })
    clearing?.let { keepable -> ClearChatDialog(e, chatId, keepable, onDismiss = { clearing = null }) }
    if (wallpaper) {
        Sheet("Fond d'écran", onDismiss = { wallpaper = false }, actions = { TextButton(onClick = { wallpaper = false }) { Text("OK") } }) { padding ->
            Column(Modifier.padding(padding)) {
                WallpaperRows(e.chat(chatId)?.wallpaper ?: "", onChange = { v -> e.updateChat(chatId) { it.copy(wallpaper = v) } }, allowDefault = true)
            }
        }
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Ajouter aux contacts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(TalkaCode.pretty(chatId), fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Nom (facultatif)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    adding = false
                    val name = contactName
                    model.launch {
                        try {
                            e.addContact(chatId, name)
                        } catch (x: CancellationException) {
                            throw x
                        } catch (x: Exception) {
                        }
                    }
                }) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Annuler") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Supprimer la discussion ?") },
            text = { Text("Elle disparaîtra de cet appareil avec tous ses messages.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    if (chat.group && !chat.left) e.leaveGroup(chatId)
                    e.deleteChat(chatId)
                    model.path = emptyList()
                }) { Text("Supprimer", color = red) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annuler") } },
        )
    }
}

// ----- Le menu d'un message -----

@Composable
private fun MessageMenu(e: Engine, chat: Chat, m: Message, s: ConversationState, save: (ByteArray, String, String) -> Unit, close: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val chatId = chat.id
    val red = MaterialTheme.colorScheme.error

    @Composable
    fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, action: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label, color = if (danger) red else Color.Unspecified) },
            leadingIcon = { Icon(icon, null, tint = if (danger) red else MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = {
                close()
                action()
            },
        )
    }

    if (m.kind != "system" && !m.deleted) {
        val mine = m.from == e.me.code
        // Une capsule scellée ne se copie ni ne se transfère.
        val sealed = m.sealed(e.me.code)
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (emoji in quickReactions) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (m.reactions[e.me.code] == emoji) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable {
                            close()
                            e.react(chatId, m.id, emoji)
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 22.sp) }
            }
        }
        HorizontalDivider()
        item("Répondre", Icons.AutoMirrored.Filled.Reply) {
            s.reply = m
            requestFocus(s.focus)
        }
        // Dans un groupe, sur le message d'un autre membre : lui répondre
        // dans votre discussion à deux, sa phrase citée (comme le navigateur).
        if (chat.group && !mine && m.from != "system" && !e.blocked(m.from)) {
            item("Répondre en privé", Icons.Filled.AccountCircle) {
                model.privateReply = m
                model.openChat(m.from)
            }
        }
        if (m.text.isNotEmpty() && !sealed) item("Copier", Icons.Filled.ContentCopy) { copyText(context, m.text) }
        // Le jeton tel qu'il a voyagé : le clavier Brocolis le déchiffre.
        e.brocolisOriginal(m)?.let { token ->
            item("Copier chiffré 🥦", Icons.Filled.Lock) {
                copyText(context, token)
                model.show("Copié chiffré : « Lire » sur le clavier Brocolis")
            }
        }
        if (!sealed) item("Transférer", Icons.AutoMirrored.Filled.Forward) { s.forwarding = m }
        item(if (m.isStarred) "Retirer des importants" else "Important", if (m.isStarred) Icons.Filled.StarBorder else Icons.Filled.Star) {
            e.toggleStar(chatId, m.id)
        }
        item(if (chat.pinMsg == m.id) "Désépingler" else "Épingler", if (chat.pinMsg == m.id) Icons.Outlined.PushPin else Icons.Filled.PushPin) {
            e.setPin(chatId, if (chat.pinMsg == m.id) "" else m.id)
        }
        if (mine && m.kind == "text" && !m.isCapsule && nowMs() - m.at < 15 * 60_000) {
            item("Modifier", Icons.Filled.Edit) {
                s.editing = m
                s.text = TextFieldValue(m.text, TextRange(m.text.length))
                s.reply = null
                requestFocus(s.focus)
            }
        }
        if (mine) item("Infos", Icons.Filled.Info) { s.info = m }
        // Une photo, un vocal, un document : l'enregistrer, sous le même nom que le navigateur.
        if (!sealed && m.kind in listOf("image", "file", "voice") && m.data.startsWith("data:")) {
            item("Enregistrer", Icons.Filled.Download) { dataOfURL(m.data)?.let { save(it, m.saveName, mimeOf(m)) } }
        }
    }
    item("Supprimer", Icons.Filled.Delete, danger = true) { s.deleting = m }
}

// ----- La saisie -----

/** Ce que font les boutons de la saisie. */
private class ComposerActions(
    val photos: () -> Unit,
    val camera: (() -> Unit)?,
    val file: () -> Unit,
    val contact: () -> Unit,
    val capsule: () -> Unit,
    val voice: () -> Unit,
    val submit: () -> Unit,
)

@Composable
private fun clearFieldColors() =
    TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )

@Composable
private fun Composer(e: Engine, chat: Chat, s: ConversationState, recorder: VoiceRecorder, a: ComposerActions) {
    LocalAppModel.current.revision
    val chatId = chat.id
    val me = e.me.code
    val bar: @Composable (@Composable () -> Unit) -> Unit = { content ->
        Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) { content() }
        }
    }
    val note = MaterialTheme.typography.bodySmall
    when {
        chat.group && (chat.left || me !in chat.members) ->
            bar { Text("Vous ne faites plus partie de ce groupe.", style = note, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        !chat.canWrite(me) ->
            bar { Text("Seuls les admins peuvent envoyer des messages.", style = note, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        !chat.group && e.blocked(chatId) ->
            bar {
                TextButton(onClick = { e.setBlocked(chatId, false) }) { Text("Vous avez bloqué ce contact. Toucher pour débloquer.", style = note, textAlign = TextAlign.Center) }
            }
        else -> Column {
            // « @… » dans un groupe : les membres dont le nom correspond, comme WhatsApp ; un toucher complète « @Léa ».
            mentionMatches(e, chat, s)?.let { (q, found) ->
                MentionBar(found) { label ->
                    val t = s.text.text.dropLast(q.length + 1) + "@" + label + " "
                    s.text = TextFieldValue(t, TextRange(t.length))
                }
            }
            val shownMessage = s.editing ?: s.reply
            if (shownMessage != null) {
                Surface(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 3.dp,
                ) {
                    Row(Modifier.height(IntrinsicSize.Min).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(vertical = 6.dp).width(4.dp).fillMaxHeight().background(TalkaColors.green, RoundedCornerShape(2.dp)))
                        Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                            Text(
                                if (s.editing != null) "Modifier le message" else e.displayName(shownMessage.from),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TalkaColors.green,
                            )
                            Text(e.readable(shownMessage), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            if (s.editing != null) s.text = TextFieldValue("")
                            s.editing = null
                            s.reply = null
                        }) { Icon(Icons.Filled.Cancel, "Annuler", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (recorder.recording) {
                    Surface(shape = CircleShape, tonalElevation = 3.dp) {
                        IconButton(onClick = { recorder.cancel() }) { Icon(Icons.Filled.Delete, "Annuler l'enregistrement", tint = Color.Red) }
                    }
                    val tick by produceState(System.currentTimeMillis(), recorder.recording) {
                        while (recorder.recording) {
                            value = System.currentTimeMillis()
                            delay(500)
                        }
                    }
                    Surface(Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).background(Color.Red, CircleShape))
                            Text(formatDuration(recorder.elapsed(tick)), fontFamily = FontFamily.Monospace)
                        }
                    }
                } else {
                    // Une seule pastille, comme le navigateur : le champ, puis le trombone « Joindre ».
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(22.dp), tonalElevation = 3.dp) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            TextField(
                                value = s.text,
                                onValueChange = { v ->
                                    // Comme le `maxlength` du navigateur : la saisie s'arrête là.
                                    var value = v
                                    if (v.text.length > maxMessageText) {
                                        val t = v.text.firstChars(maxMessageText)
                                        if (t.length < v.text.length) value = TextFieldValue(t, TextRange(t.length))
                                    }
                                    val changed = value.text != s.text.text
                                    s.text = value
                                    if (changed && s.editing == null) e.setTyping(chatId, value.text.isNotEmpty())
                                },
                                placeholder = { Text(if (chat.brocolis.isEmpty()) "Message" else "Message chiffré par Brocolis 🥦") },
                                maxLines = 6,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                colors = clearFieldColors(),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(s.focus)
                                    // Un clavier (tablette, Chromebook) : Entrée envoie, Maj + Entrée
                                    // va à la ligne (Réglages → Entrée pour envoyer, comme le
                                    // navigateur). Le clavier de l'écran, lui, va à la ligne.
                                    .onPreviewKeyEvent { k ->
                                        val physical = k.nativeKeyEvent.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD
                                        if (physical && e.settings.enterSends && k.key == Key.Enter && !k.isShiftPressed) {
                                            if (k.type == KeyEventType.KeyDown) a.submit()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            )
                            AttachMenu(a)
                        }
                    }
                }
                val empty = s.text.text.isBlank() && s.editing == null
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(TalkaColors.green)
                        .clickable(onClick = if (empty) a.voice else a.submit)
                        .semantics {
                            contentDescription = if (!empty) "Envoyer" else if (recorder.recording) "Envoyer le message vocal" else "Message vocal"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when {
                        !empty && s.editing != null -> Icons.Filled.Check
                        !empty || recorder.recording -> Icons.Filled.ArrowUpward
                        else -> Icons.Filled.Mic
                    }
                    Icon(icon, null, tint = Color.White)
                }
            }
        }
    }
}

/** Le trombone « Joindre » : galerie, appareil photo, document, contact, capsule. */
@Composable
private fun AttachMenu(a: ComposerActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.AttachFile, "Joindre", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val entries = buildList {
                add(Triple("Galerie photos", Icons.Filled.PhotoLibrary, a.photos))
                a.camera?.let { add(Triple("Appareil photo", Icons.Filled.PhotoCamera, it)) }
                add(Triple("Document", Icons.Filled.Description, a.file))
                add(Triple("Contact", Icons.Filled.AccountCircle, a.contact))
                add(Triple("Capsule temporelle", Icons.Filled.HourglassEmpty, a.capsule))
            }
            for ((label, icon, action) in entries) {
                DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = {
                    open = false
                    action()
                })
            }
        }
    }
}

/** Ce qu'on tape après « @ », et les membres dont le nom correspond (six au plus). */
private fun mentionMatches(e: Engine, chat: Chat, s: ConversationState): Pair<String, List<Pair<String, String>>>? {
    if (!chat.group || s.editing != null) return null
    val q = Mentions.query(s.text.text) ?: return null
    val fq = Mentions.fold(q)
    val found = e.mentionCandidates(chat).filter { Mentions.fold(it.second).contains(fq) }.take(6)
    return if (found.isEmpty()) null else q to found
}

/** Les membres proposés au-dessus de la saisie ; un toucher complète. */
@Composable
private fun MentionBar(items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 6.dp)
            .semantics { contentDescription = "Mentionner un membre" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((code, label) in items) {
            Surface(shape = CircleShape, tonalElevation = 3.dp, modifier = Modifier.clip(CircleShape).clickable { onPick(label) }) {
                Text(
                    "@$label",
                    color = Avatars.color(code),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ----- Le fil -----

/**
 * Le haut du fil : s'il y a des messages plus anciens que ceux affichés, de
 * quoi les faire venir, d'un toucher ; sinon, le rappel du chiffrement, au
 * tout début. Pas de chargement tout seul en arrivant ici : la lecture
 * perdrait sa place.
 */
@Composable
private fun FilTop(more: Boolean, onOlder: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (more) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOlder),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.ArrowCircleUp, null, Modifier.size(16.dp))
                    Text("Messages plus anciens", style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 30.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(14.dp))
                    Text(
                        "Les messages sont chiffrés de bout en bout. Personne d'autre, pas même Talka, ne peut les lire.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** « 3 messages non lus » : là où commencent les messages arrivés depuis la dernière visite. */
@Composable
private fun UnreadSeparator(count: Int) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            if (count > 1) "$count messages non lus" else "1 message non lu",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                .padding(vertical = 7.dp)
                .semantics { heading() },
        )
    }
}

/** Le jour, au-dessus de ses messages (« Aujourd'hui », « Hier »…). */
@Composable
private fun DayChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** « Derniers messages » ⌄, avec les messages arrivés depuis qu'on a quitté le bas, comme WhatsApp. */
@Composable
private fun LatestButton(below: Int, modifier: Modifier, action: () -> Unit) {
    Box(
        modifier
            .padding(12.dp)
            .semantics {
                contentDescription = when (below) {
                    0 -> "Derniers messages"
                    1 -> "Derniers messages, 1 nouveau"
                    else -> "Derniers messages, $below nouveaux"
                }
            },
    ) {
        Surface(shape = CircleShape, tonalElevation = 3.dp, shadowElevation = 2.dp, modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = action)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (below > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-6).dp).defaultMinSize(20.dp, 20.dp).background(TalkaColors.green, CircleShape).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$below", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        }
    }
}

// ----- Une bulle -----

/**
 * La forme d'une bulle, comme le navigateur et WhatsApp : coins de 8 points,
 * et pour la première d'une suite une pointe en haut, du côté de qui écrit.
 * La place de la pointe (8 points) est gardée sur toutes les bulles : elles
 * restent alignées.
 */
private class BubbleShape(val mine: Boolean, val first: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = with(density) { tail.toPx() }
        val r = with(density) { 8.dp.toPx() }
        val body = Rect(if (mine) 0f else t, 0f, if (mine) size.width - t else size.width, size.height)
        val path = Path()
        path.addRoundRect(
            RoundRect(
                body,
                topLeft = CornerRadius(if (first && !mine) 0f else r),
                topRight = CornerRadius(if (first && mine) 0f else r),
                bottomRight = CornerRadius(r),
                bottomLeft = CornerRadius(r),
            ),
        )
        if (first) {
            val edge = if (mine) body.right else body.left
            path.moveTo(edge, 0f)
            path.lineTo(if (mine) size.width else 0f, 0f)
            path.lineTo(edge, min(with(density) { 13.dp.toPx() }, body.height))
            path.close()
        }
        return Outline.Generic(path)
    }

    companion object {
        val tail = 8.dp
    }
}

/** Les libellés des personnes que `m` mentionne. */
private fun mentionLabels(e: Engine, m: Message): List<String> = m.mentions.map { e.mentionLabel(it) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    e: Engine,
    chat: Chat,
    m: Message,
    first: Boolean,
    onReply: () -> Unit,
    onOpen: () -> Unit,
    onScrollTo: (String) -> Unit,
    onReactions: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    if (m.kind == "system") {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                m.text,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        return
    }
    val mine = m.from == e.me.code
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val drag = remember { Animatable(0f) }
    var menuOpen by remember { mutableStateOf(false) }
    var heart by remember { mutableStateOf(false) }

    // Double-tap : ❤️ (une seconde fois, retiré), comme le double-clic du navigateur.
    fun like() {
        if (m.deleted) return
        val adding = m.reactions[e.me.code] != "❤️"
        e.react(chat.id, m.id, "❤️")
        if (!adding) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        heart = true
        scope.launch {
            delay(650)
            heart = false
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 6.dp else 0.dp)
            .offset { IntOffset(drag.value.roundToInt(), 0) }
            // Glisser vers la droite : répondre.
            .pointerInput(m.id) {
                val limit = with(density) { 70.dp.toPx() }
                val trigger = with(density) { 55.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag.value > trigger && !m.deleted) onReply()
                        scope.launch { drag.animateTo(0f, spring()) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
                ) { change, amount ->
                    val v = (drag.value + amount).coerceIn(0f, limit)
                    if (v != drag.value) change.consume()
                    scope.launch { drag.snapTo(v) }
                }
            },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (chat.group && !mine) {
            if (first) EngineAvatar(e, m.from, 28.dp) else Spacer(Modifier.width(28.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(
            // Une bulle ne s'étire pas sur toute la largeur ; 50 points restent de l'autre côté.
            Modifier
                .weight(1f, fill = false)
                .padding(start = if (mine) 50.dp else 0.dp, end = if (mine) 0.dp else 50.dp)
                .widthIn(max = 560.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Box {
                Bubble(
                    e, chat, m, mine, first, onOpen, onScrollTo, onMenu = { menuOpen = true },
                    gestures = Modifier
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onDoubleClick = { like() },
                            onLongClick = { menuOpen = true },
                            onLongClickLabel = "Actions",
                        )
                        .semantics { customActions = listOf(CustomAccessibilityAction("Aimer ❤️") { like(); true }) },
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = heart,
                    enter = scaleIn(initialScale = 0.3f) + fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) { Text("❤️", fontSize = 44.sp, modifier = Modifier.clearAndSetSemantics {}) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
            }
            if (m.reactions.isNotEmpty()) {
                // Les plus nombreuses d'abord.
                val counts = m.reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                Row(
                    Modifier
                        .offset(y = (-6).dp)
                        .padding(horizontal = 8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(onClickLabel = "Voir qui a réagi", onClick = onReactions)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .clearAndSetSemantics { contentDescription = "Réactions : ${m.reactions.values.joinToString(" ")}" },
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((emoji, n) in counts) Text(if (n > 1) "$emoji$n" else emoji, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    e: Engine,
    chat: Chat,
    m: Message,
    mine: Boolean,
    first: Boolean,
    onOpen: () -> Unit,
    onScrollTo: (String) -> Unit,
    onMenu: () -> Unit,
    gestures: Modifier,
) {
    val size = e.settings.fontSize
    val big = m.kind == "text" && !m.deleted && m.reply == null && !m.isCapsule && onlyEmoji(m.text)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val h = if (m.kind == "image") 4.dp else 9.dp
    val v = if (m.kind == "image") 4.dp else 6.dp
    val tail = BubbleShape.tail
    Box(
        Modifier
            // Au plus 520 points (280 pour une photo), du côté de qui l'a écrit.
            .widthIn(max = if (m.kind == "image") 280.dp else 520.dp)
            .background(if (mine) TalkaColors.bubbleOut() else TalkaColors.bubbleIn(), BubbleShape(mine, first))
            .then(gestures)
            .padding(start = h + if (mine) 0.dp else tail, end = h + if (mine) tail else 0.dp, top = v, bottom = v),
    ) {
        // La bulle épouse son contenu (sans s'étirer sur toute la largeur),
        // l'heure en bas à droite, comme dans le navigateur.
        Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (chat.group && !mine && first) {
                Text(e.displayName(m.from), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Avatars.color(m.from))
            }
            if (m.fwd) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Forward, null, Modifier.size(14.dp), tint = secondary)
                    Text("Transféré", style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic, color = secondary)
                }
            }
            val r = m.reply
            if (r != null && !m.deleted) {
                val color = Avatars.color(r.from)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        // Un toucher long ici aussi ouvre le menu du message.
                        .combinedClickable(onClickLabel = "Aller au message cité", onLongClick = onMenu) { onScrollTo(r.id) }
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
                    Column {
                        Text(if (r.from.isEmpty()) "Message cité" else e.displayName(r.from), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
                        Text(Brocolis.masked(r.text), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = secondary)
                    }
                }
            }
            when {
                m.deleted -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Block, null, Modifier.size(size.dp), tint = secondary)
                    Text(if (mine) "Vous avez supprimé ce message" else "Ce message a été supprimé", fontSize = size.sp, fontStyle = FontStyle.Italic, color = secondary)
                }
                m.sealed(e.me.code) -> {
                    // La capsule scellée : l'enveloppe et le compte à rebours,
                    // puis le message, à l'heure dite.
                    val openAt = m.openAt ?: 0.0
                    val now by produceState(nowMs(), m.id) {
                        while (value < openAt) {
                            delay(1000)
                            value = nowMs()
                        }
                    }
                    if (m.sealed(e.me.code, now)) {
                        SealedCapsule(openAt, now)
                    } else {
                        CapsuleBand(m, now)
                        BubbleContent(e, m, size, big = false, onOpen, onMenu)
                    }
                }
                else -> {
                    if (m.isCapsule) CapsuleBand(m, nowMs())
                    BubbleContent(e, m, size, big, onOpen, onMenu)
                }
            }
            Row(
                Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val small = MaterialTheme.typography.labelSmall
                if (m.isStarred) Icon(Icons.Filled.Star, "Important", Modifier.size(12.dp), tint = secondary)
                if (m.bro && !m.deleted) Text("🥦", style = small, modifier = Modifier.semantics { contentDescription = "Chiffré aussi par Brocolis" })
                if (m.exp != null) Icon(Icons.Filled.Timer, "Message éphémère", Modifier.size(12.dp), tint = secondary)
                if (m.edited && !m.deleted) Text("modifié", style = small, color = secondary)
                Text(Dates.clock(m.at), style = small, color = secondary)
                if (mine && !m.deleted) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides secondary) { TickView(m.tick) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleContent(e: Engine, m: Message, size: Int, big: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    when (m.kind) {
        "image" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val image = remember(m.data) { Images.bubble(m.data) }
            if (image != null) {
                Image(
                    image,
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .sizeIn(maxWidth = 260.dp, maxHeight = 340.dp)
                        .aspectRatio(image.width.toFloat() / max(1, image.height))
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(onLongClick = onMenu, onClick = onOpen),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Photo, null)
                    Text("Photo")
                }
            }
            if (m.text.isNotEmpty()) Text(richText(m.text, mentionLabels(e, m)), fontSize = size.sp)
        }
        "voice" -> VoiceBubble(m)
        "file" -> FileBubble(m)
        "brokey" -> BrokeyCard(e, m)
        "contact" -> ContactBubble(e, m)
        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(richText(m.text, mentionLabels(e, m)), fontSize = if (big) 46.sp else size.sp, lineHeight = if (big) 54.sp else (size * 1.3f).sp)
            if (Brocolis.hasToken(m.text)) {
                LockedBrocolisHelp(e.displayName(m.from), Brocolis.hasRatchetToken(m.text), m.chat, m.id)
            }
        }
    }
}

// ----- Vocal, fichier, contact -----

/** Un message vocal : lecture, forme, durée, vitesse ; enchaîne avec le suivant. */
@Composable
fun VoiceBubble(message: Message) {
    val player = remember(message.id) { VoicePlayer() }
    val chain = LocalVoiceChain.current
    val data = remember(message.data) { dataOfURL(message.data) }
    DisposableEffect(player) { onDispose { player.release() } }
    // Son tour est venu (au besoin hors de l'écran : il démarre en apparaissant).
    LaunchedEffect(chain?.autoplay) {
        if (chain != null && chain.autoplay == message.id) {
            chain.autoplay = null
            if (!player.playing) player.toggle(data, message.mime)
        }
    }
    LaunchedEffect(player.finished) { if (player.finished > 0) chain?.ended(message.id) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconButton(onClick = { player.toggle(data, message.mime) }, modifier = Modifier.size(36.dp)) {
            Icon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (player.playing) "Pause" else "Écouter", Modifier.size(28.dp))
        }
        Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until 24) {
                Box(Modifier.width(3.dp).height(voiceBar(message.id, i).dp).background(secondary.copy(alpha = 0.6f), CircleShape))
            }
        }
        Text(formatDuration(message.dur), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = secondary)
        Text(
            player.speedLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .clickable { player.nextSpeed() }
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .semantics { contentDescription = "Vitesse de lecture : ${player.speedLabel}" },
        )
    }
}

/** La hauteur d'une barre de la forme d'un vocal : le même calcul que l'iPhone (tirée de son identifiant). */
internal fun voiceBar(id: String, i: Int): Int {
    var h = i.toUInt()
    var k = 0
    while (k < id.length) {
        val cp = id.codePointAt(k)
        h = h * 31u + cp.toUInt()
        k += Character.charCount(cp)
    }
    return 6 + (h % 18u).toInt()
}

/** Un document : son nom, sa taille ; le toucher l'enregistre. */
@Composable
fun FileBubble(message: Message) {
    val save = rememberSaver()
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable(onClickLabel = "Enregistrer") {
                dataOfURL(message.data)?.let { save(it, message.name.ifEmpty { message.saveName }, mimeOf(message)) }
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Description, null, Modifier.size(32.dp), tint = TalkaColors.green)
        Column(Modifier.weight(1f, fill = false)) {
            Text(message.name.ifEmpty { "Fichier" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(message.fileDetail(formatBytes(message.size)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.Download, null)
    }
}

/** Une carte de contact : « Écrire », ou « Ajouter aux contacts ». */
@Composable
private fun ContactBubble(e: Engine, m: Message) {
    val model = LocalAppModel.current
    model.revision
    val code = m.code
    val saved = e.contact(code)?.saved == true
    Column(Modifier.widthIn(min = 200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(code, m.name.ifEmpty { code }, size = 40.dp)
            Column {
                Text(m.name.ifEmpty { TalkaCode.pretty(code) }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(TalkaCode.pretty(code), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (code != e.me.code) {
            HorizontalDivider()
            TextButton(
                onClick = {
                    if (saved) {
                        model.openChat(code)
                    } else {
                        model.launch {
                            try {
                                e.addContact(code, m.name)
                            } catch (x: CancellationException) {
                                throw x
                            } catch (x: Exception) {
                            }
                            model.openChat(code)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saved) "Écrire" else "Ajouter aux contacts", fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ----- Photo en grand, infos d'un message, réactions -----

/** Une photo en grand, sur fond noir : pincer pour zoomer ; « Enregistrer ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewer(engine: Engine, message: Message, onDismiss: () -> Unit) {
    val image = remember(message.data) { Images.fromDataURL(message.data) }
    val save = rememberSaver()
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val zoom = rememberTransformableState { change, _, _ -> scope.launch { scale.snapTo(max(1f, scale.value * change)) } }
    LaunchedEffect(zoom.isTransformInProgress) { if (!zoom.isTransformInProgress) scale.animateTo(1f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    title = { Text(engine.displayName(message.from), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Fermer") } },
                    actions = {
                        if (image != null) {
                            IconButton(onClick = { dataOfURL(message.data)?.let { save(it, message.saveName, mimeOf(message)) } }) {
                                Icon(Icons.Filled.Download, "Enregistrer")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (image != null) {
                    Image(
                        image,
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .transformable(zoom)
                            .graphicsLayer {
                                scaleX = scale.value
                                scaleY = scale.value
                            },
                    )
                }
            }
        }
    }
}

/** À qui le message est parti, et où il en est chez chacun (✓, ✓✓, lu). */
@Composable
private fun MessageInfoSheet(e: Engine, message: Message, onDismiss: () -> Unit) {
    LocalAppModel.current.revision
    val m = e.store.message(message.chat, message.id) ?: message
    Sheet("Infos du message", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("OK") } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Envoyé")
            Text("${Dates.separator(m.at)} à ${Dates.clock(m.at)}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider()
            for (code in m.to) {
                val tick = when {
                    code in m.readBy -> Message.Tick.read
                    code in m.deliveredTo -> Message.Tick.delivered
                    code in m.sentTo -> Message.Tick.sent
                    else -> Message.Tick.pending
                }
                ListItem(
                    leadingContent = { EngineAvatar(e, code, 36.dp) },
                    headlineContent = { Text(e.displayName(code)) },
                    trailingContent = { TickView(tick) },
                )
            }
        }
    }
}

/** Qui a réagi à un message, avec quoi ; sa propre réaction se retire d'un toucher (comme dans le navigateur). */
@Composable
private fun ReactionsSheet(e: Engine, message: Message, onDismiss: () -> Unit) {
    LocalAppModel.current.revision
    val me = e.me.code
    val m = e.store.message(message.chat, message.id) ?: message
    val list = m.reactions.entries.sortedWith { a, b ->
        when {
            a.key == me -> -1
            b.key == me -> 1
            else -> e.displayName(a.key).compareTo(e.displayName(b.key))
        }
    }
    Sheet("Réactions", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("OK") } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            for ((code, emoji) in list) {
                val mine = code == me
                ListItem(
                    leadingContent = { EngineAvatar(e, code, 40.dp) },
                    headlineContent = { Text(if (mine) "Vous" else e.displayName(code)) },
                    supportingContent = if (mine) {
                        { Text("Touchez pour retirer") }
                    } else {
                        null
                    },
                    trailingContent = { Text(emoji, fontSize = 24.sp) },
                    modifier = Modifier.clickable(enabled = mine) {
                        e.react(m.chat, m.id, emoji)
                        onDismiss()
                    },
                )
            }
        }
    }
}

// ----- Brocolis 🥦 -----

/**
 * La bulle d'une clé partagée par Talka : son nom, son empreinte, et
 * « Ajouter la clé » (qui l'active aussi dans la discussion). Le
 * `BrocolisKeyCard` de l'iPhone (`BrocolisViews.swift`), dont la discussion
 * a besoin.
 */
@Composable
private fun BrokeyCard(e: Engine, m: Message) {
    val model = LocalAppModel.current
    model.revision
    val have = e.sharedKey(m)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🥦", fontSize = 22.sp)
            Column {
                Text("Clé Brocolis « ${m.name} »", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                KeyFingerprint(if (m.data.isEmpty()) have?.key ?: "" else m.data)
            }
        }
        when {
            m.from == e.me.code -> Text("Vous avez partagé cette clé. Comparez l'empreinte de vive voix.", style = MaterialTheme.typography.bodySmall, color = secondary)
            have != null -> Text("✓ Dans vos clés (« ${have.name} »)", style = MaterialTheme.typography.bodySmall, color = secondary)
            m.data.isEmpty() -> Text("Clé oubliée sur cet appareil.", style = MaterialTheme.typography.bodySmall, color = secondary)
            else -> Button(
                onClick = {
                    try {
                        val k = e.acceptBrocolisKey(m)
                        model.show("🥦 « ${k.name} » ajoutée et activée ici · empreinte ${Brocolis.fingerprint(k.key) ?: ""}")
                    } catch (x: Exception) {
                        model.show(x.message ?: "La clé n'a pas pu être ajoutée.")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TalkaColors.green),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ajouter la clé") }
        }
    }
}

@Composable
private fun KeyFingerprint(key: String) {
    val fp = Brocolis.fingerprint(key) ?: ""
    Text(
        fp,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = "Empreinte $fp" },
    )
}

private const val brocolisAbout =
    "Brocolis chiffre vos messages une seconde fois, avec des clés que vous partagez avec vos proches dans l'app Brocolis 🥦. Talka les chiffre déjà de bout en bout ; seuls ceux qui ont la clé ouvrent cette seconde couche, ici ou dans l'app Brocolis (clavier, Partager)."

/**
 * Les clés qui chiffrent ce qui part dans une discussion. Le
 * `BrocolisChatSheet` de l'iPhone (`BrocolisViews.swift`), dont le menu de
 * la discussion a besoin.
 */
@Composable
private fun ChatBrocolisSheet(chatId: String, onDismiss: () -> Unit, onManage: () -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var selected by remember { mutableStateOf(e.chat(chatId)?.brocolis?.toSet() ?: emptySet()) }
    val keys = e.brocolisKeys.filter { !it.retired }

    // Une clé neuve, au nom de la discussion : activée ici et envoyée par Talka.
    fun createAndSend() {
        val title = e.chat(chatId)?.let { e.title(it) } ?: ""
        val k = try {
            e.addBrocolisKey(title, Brocolis.generateKey())
        } catch (x: Exception) {
            return
        }
        e.setChatBrocolis(chatId, (e.chat(chatId)?.brocolis ?: emptyList()) + k.id)
        e.shareBrocolisKey(chatId, k.id)
        model.show("🥦 Clé créée, envoyée par Talka et activée ici")
        onDismiss()
    }

    Sheet(
        "Brocolis 🥦",
        onDismiss,
        actions = {
            TextButton(onClick = {
                val ids = e.brocolisKeys.map { it.id }.filter { it in selected }
                e.setChatBrocolis(chatId, ids)
                model.show(if (ids.isEmpty()) "Brocolis désactivé pour cette discussion" else "🥦 Brocolis activé pour cette discussion")
                onDismiss()
            }) { Text("Enregistrer") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (keys.isEmpty()) {
                Text("Ajoutez d'abord une clé, partagée avec vos correspondants.", Modifier.padding(16.dp))
                SectionFooter(brocolisAbout)
            } else {
                for (k in keys) {
                    ListItem(
                        headlineContent = { Text("🥦 ${k.name}") },
                        supportingContent = { KeyFingerprint(k.key) },
                        trailingContent = {
                            Switch(checked = k.id in selected, onCheckedChange = { on -> selected = if (on) selected + k.id else selected - k.id })
                        },
                    )
                }
                SectionFooter(
                    "Ce qui part dans cette discussion sera chiffré avec les clés choisies, en plus de Talka. Écrivez ((comme ceci)) pour ne chiffrer qu'un passage. Plusieurs clés : un seul message, lisible par chacune.",
                )
            }
            ListItem(
                headlineContent = { Text("Créer une clé et l'envoyer ici") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Send, null) },
                modifier = Modifier.clickable { createAndSend() },
            )
            if (selected.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text("Envoyer les clés cochées ici") },
                    leadingContent = { Icon(Icons.Filled.Key, null) },
                    modifier = Modifier.clickable {
                        for (id in keys.map { it.id }) if (id in selected) e.shareBrocolisKey(chatId, id)
                        model.show("🥦 Clé envoyée par Talka")
                        onDismiss()
                    },
                )
            }
            SectionFooter("Une clé envoyée par Talka voyage chiffrée de bout en bout ; vos correspondants l'ajoutent d'un geste. Comparez son empreinte de vive voix.")
            ListItem(headlineContent = { Text("Gérer les clés") }, modifier = Modifier.clickable(onClick = onManage))
        }
    }
}

/**
 * Un message Brocolis qu'aucune de vos clés n'ouvre : pourquoi, et quoi
 * faire. Le même texte que le navigateur (`locked_help`).
 */
@Composable
private fun LockedBrocolisHelp(who: String, ratchet: Boolean, chatId: String, messageId: String) {
    val model = LocalAppModel.current
    var shown by remember { mutableStateOf(false) }
    var missingKey by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Toutes vos clés s'essaient ; sinon, on colle celle du clavier.
        Button(
            onClick = {
                if (model.engine?.decryptNow(chatId, messageId) == true) model.show("🔓 Déchiffré") else missingKey = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = TalkaColors.green),
            shape = CircleShape,
        ) {
            Icon(Icons.Filled.LockOpen, null, Modifier.size(16.dp))
            Text("Déchiffrer", Modifier.padding(start = 6.dp), fontWeight = FontWeight.Bold)
        }
        Text(
            "Pourquoi ?",
            style = MaterialTheme.typography.bodySmall,
            textDecoration = TextDecoration.Underline,
            color = TalkaColors.green,
            modifier = Modifier.clickable { shown = true },
        )
    }
    if (missingKey) KeyFromBrocolis(who, chatId, messageId, onDismiss = { missingKey = false })
    if (shown) {
        val explanation = if (ratchet) {
            """
            $who a chiffré ce message avec le clavier Brocolis en mode « double cliquet, clé partagée », avec une clé que cet appareil n'a pas.

            • Ajoutez la clé Brocolis partagée avec $who (touchez « Ajouter la clé » sous sa carte 🥦, ou collez-la dans Réglages → Clés Brocolis) : le message s'ouvrira tout seul, et les suivants aussi.

            • Si rien ne s'ouvre avec la bonne clé, le début de la conversation Brocolis n'est pas arrivé ici : $who peut repartir d'une session neuve dans le clavier Brocolis.
            """.trimIndent()
        } else {
            """
            $who a chiffré ce message avec une clé Brocolis que cet appareil n'a pas.

            • Si vous lui avez partagé une clé : $who doit toucher « Ajouter la clé » sous la carte 🥦 de cette discussion. Ses prochains messages seront lisibles.

            • Pour lire celui-ci : demandez-lui de vous partager sa clé (menu ⋮ → Brocolis 🥦), puis touchez « Ajouter la clé ». Le message s'ouvrira tout seul.
            """.trimIndent()
        }
        AlertDialog(
            onDismissRequest = { shown = false },
            title = { Text("Message chiffré par Brocolis 🥦") },
            text = { Text(explanation) },
            confirmButton = { TextButton(onClick = { shown = false }) { Text("OK") } },
        )
    }
}

/**
 * La clé du clavier Brocolis, en deux gestes : « Ouvrir Brocolis » (y
 * copier la clé), puis « Coller ». Elle s'ajoute au nom du contact et le
 * message s'ouvre aussitôt ; une clé protégée demande seulement sa phrase.
 */
@Composable
private fun KeyFromBrocolis(who: String, chatId: String, messageId: String, onDismiss: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var protected by remember { mutableStateOf<String?>(null) }
    var phrase by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun add(key: String) {
        val e = model.engine ?: return
        try {
            val name = e.chat(chatId)?.let { e.title(it) } ?: who
            e.addBrocolisKey(name.trim('~'), key)
        } catch (x: Exception) {
            error = x.message ?: "La clé n'a pas pu être ajoutée."
            return
        }
        // L'ajout rouvre déjà ce que la clé ouvre ; sinon, on essaie ici.
        val still = Brocolis.hasToken(e.store.message(chatId, messageId)?.text ?: "")
        if (still && !e.decryptNow(chatId, messageId)) {
            error = "Cette clé n'ouvre pas ce message : ce n'est pas celle du clavier."
            return
        }
        model.show("🔓 Déchiffré · la clé de $who est dans Talka")
        onDismiss()
    }

    fun pasted(text: String) {
        error = null
        val key = Brocolis.findKey(text)
        when {
            Brocolis.hasProtectedKey(text) -> protected = text
            key != null -> add(key)
            else -> error = "Ce n'est pas une clé Brocolis. Dans Brocolis : la clé → Copier la clé."
        }
    }

    Sheet("Récupérer la clé du clavier", onDismiss) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Ce message vient du clavier Brocolis, avec une clé que Talka n'a pas encore. Il suffit de la copier dans Brocolis une fois.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val p = protected
            if (p != null) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("Phrase secrète de la clé") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        working = true
                        val secret = phrase
                        scope.launch {
                            val key = withContext(Dispatchers.Default) { Brocolis.unprotectKey(p, secret) }
                            working = false
                            if (key == null) error = "Phrase incorrecte, ou clé abîmée." else add(key)
                        }
                    },
                    enabled = phrase.isNotEmpty() && !working,
                    colors = ButtonDefaults.buttonColors(containerColor = TalkaColors.green),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.LockOpen, null)
                    Text("Ouvrir avec la phrase", Modifier.padding(start = 8.dp))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        try {
                            model.leavingOnPurpose()
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("brocolis://")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (x: ActivityNotFoundException) {
                            error = "L'app Brocolis n'est pas installée sur cet appareil."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("1. Ouvrir Brocolis et copier la clé") }
                Button(
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        pasted(cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: "")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TalkaColors.green),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.ContentCopy, null)
                    Text("Coller", Modifier.padding(start = 8.dp))
                }
                Text("2. Revenez ici et touchez « Coller » : le message s'ouvre.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
