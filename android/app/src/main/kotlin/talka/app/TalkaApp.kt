// Talka pour Android : la messagerie où un code remplace le numéro de
// téléphone. Le même protocole et le même chiffrement que le navigateur et
// l'iPhone : un téléphone Android, un iPhone et un ordinateur discutent
// ensemble, dans les mêmes groupes.

package talka.app

import android.app.Application
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import talka.app.sys.KeystoreGuard
import talka.app.ui.CallScreen
import talka.app.ui.CallsTab
import talka.app.ui.ChatsTab
import talka.app.ui.ConversationView
import talka.app.ui.LockView
import talka.app.ui.Notifier
import talka.app.ui.SettingsTab
import talka.app.ui.Sounds
import talka.app.ui.StatusTab
import talka.app.ui.VaultLockView
import talka.app.ui.WelcomeView
import talka.kit.BrocolisVault
import talka.kit.StoreVault
import talka.kit.TalkaLog
import talka.kit.V3Vault

/**
 * L'application : la garde des clés (le Keystore) et le modèle, un seul
 * pour tout le processus (le `@State` de l'`App` de l'iPhone), créé à la
 * première ouverture de l'activité.
 */
class TalkaApp : Application() {
    private var guard: KeystoreGuard? = null

    /** Un modèle tout prêt, à la place du Keystore (absent des tests sous Robolectric). */
    var preset: AppModel? = null

    val model: AppModel by lazy {
        preset?.let { return@lazy it }
        val g = KeystoreGuard(this)
        guard = g
        StoreVault.keyGuard = g
        V3Vault.guard = g
        BrocolisVault.guard = g
        Notifier.prepare(this)
        Sounds.prepare(this)
        if (BuildConfig.DEBUG) TalkaLog.sink = { Log.d("Talka", it) }
        AppModel(AppEnv(this, unlockCipher = { account -> g.unlockCipher(account) }))
    }
}

/** Les couleurs de Talka, celles de l'iPhone et du navigateur. */
object TalkaColors {
    val green = Color(0xFF00A884)
    val dark = Color(0xFF008069)
    val accent = Color(0xFF25D366)
    val tickBlue = Color(0xFF53BDEB)

    /** Le fond de l'écran de chargement (`LaunchBackground`). */
    val launch = Color(0xFF0E6B63)

    @Composable
    fun bubbleOut() = if (LocalDarkTheme.current) Color(0xFF005C4B) else Color(0xFFD9FDD3)

    /** L'en-tête d'une discussion : vert (sombre la nuit), comme le navigateur. */
    @Composable
    fun head() = if (LocalDarkTheme.current) Color(0xFF202C33) else Color(0xFF008069)

    @Composable
    fun bubbleIn() = if (LocalDarkTheme.current) Color(0xFF202C33) else Color.White

    @Composable
    fun chatBackground() = if (LocalDarkTheme.current) Color(0xFF0B141A) else Color(0xFFEFEAE2)
}

/** Le thème sombre est-il celui de l'écran ? */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** Le thème de Talka : Material 3, teinté du vert de Talka, clair ou sombre selon le réglage. */
@Composable
fun TalkaTheme(model: AppModel, content: @Composable () -> Unit) {
    model.revision
    val dark = model.darkTheme ?: isSystemInDarkTheme()
    val scheme = remember(dark) { if (dark) {
        darkColorScheme(
            primary = TalkaColors.green,
            onPrimary = Color(0xFF0B141A),
            primaryContainer = Color(0xFF005C4B),
            onPrimaryContainer = Color(0xFFD9FDD3),
            secondary = TalkaColors.accent,
            background = Color(0xFF0B141A),
            surface = Color(0xFF111B21),
            surfaceContainer = Color(0xFF1F2C34),
        )
    } else {
        lightColorScheme(
            primary = TalkaColors.dark,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD9FDD3),
            onPrimaryContainer = Color(0xFF002A22),
            secondary = TalkaColors.green,
        )
    } }
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalAppModel provides model) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Ce que montre Talka quand il n'est pas au premier plan (le sélecteur d'apps). */
@Composable
fun PrivacyCover(note: String? = null) {
    Box(
        Modifier.fillMaxSize().background(TalkaColors.chatBackground()).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Lock, null, Modifier.size(40.dp), tint = TalkaColors.green)
            Text("Talka", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp),
                )
            }
        }
    }
}

/** L'accueil, le verrou, ou l'application ; l'appel et les bandeaux par-dessus. */
@Composable
fun RootView() {
    val model = LocalAppModel.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                model.vaultLocked -> VaultLockView()
                // Le verrou (au-dessus) ouvre la base avec le code.
                model.codeLocked -> Box(Modifier.fillMaxSize())
                model.engine == null -> WelcomeView()
                else -> MainView()
            }
            val call = model.call
            if (call != null) {
                Box(Modifier.fillMaxSize().zIndex(1f)) { CallScreen(call) }
            }
            if (model.locked || model.codeLocked) {
                Box(Modifier.fillMaxSize().zIndex(3f)) { LockView() }
            }
            val step = model.loading
            if (step != null) {
                Box(Modifier.fillMaxSize().zIndex(4f)) { LoadingView(step) }
            }
            // Hors du premier plan (le sélecteur d'apps) : ni les discussions ni les clés.
            if (!model.active && model.engine != null) {
                Box(Modifier.fillMaxSize().zIndex(5f)) { PrivacyCover() }
            }
            val toast = model.toast
            AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(6f),
            ) {
                // Gardé pendant la disparition.
                val shown = toast ?: return@AnimatedVisibility
                ToastView(
                    shown.text,
                    Modifier
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
                        .clickable { model.toast = null },
                )
            }
            if (toast != null) {
                LaunchedEffect(toast.id) {
                    delay(3000)
                    if (model.toast == toast) model.toast = null
                }
            }
        }
    }
}

@Composable
fun ToastView(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 520.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Une fenêtre large (tablette, pliable ouvert) : la liste et la discussion côte à côte. */
val LocalWide = staticCompositionLocalOf { false }

/** Les quatre onglets, comme WhatsApp. */
@Composable
fun MainView() {
    val model = LocalAppModel.current
    model.revision
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        CompositionLocalProvider(LocalWide provides wide) {
            // Une discussion ouverte (sur un téléphone) : tout l'écran, sans les onglets.
            val open = model.path.lastOrNull()
            if (!wide && model.tab == AppModel.Tab.chats && open != null) {
                ConversationView(open)
                return@CompositionLocalProvider
            }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        val unread = model.unreadTotal
                        NavigationBarItem(
                            selected = model.tab == AppModel.Tab.chats,
                            onClick = { model.tab = AppModel.Tab.chats },
                            icon = {
                                BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, null)
                                }
                            },
                            label = { Text("Discussions") },
                        )
                        NavigationBarItem(
                            selected = model.tab == AppModel.Tab.status,
                            onClick = { model.tab = AppModel.Tab.status },
                            icon = { Icon(Icons.Outlined.DonutLarge, null) },
                            label = { Text("Actus") },
                        )
                        NavigationBarItem(
                            selected = model.tab == AppModel.Tab.calls,
                            onClick = { model.tab = AppModel.Tab.calls },
                            icon = { Icon(Icons.Filled.Call, null) },
                            label = { Text("Appels") },
                        )
                        NavigationBarItem(
                            selected = model.tab == AppModel.Tab.settings,
                            onClick = { model.tab = AppModel.Tab.settings },
                            icon = { Icon(Icons.Filled.Settings, null) },
                            label = { Text("Réglages") },
                        )
                    }
                },
            ) { padding ->
                // Les marges des barres du système, prises ici une fois pour toutes.
                Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    when (model.tab) {
                        AppModel.Tab.chats -> ChatsTab()
                        AppModel.Tab.status -> StatusTab()
                        AppModel.Tab.calls -> CallsTab()
                        AppModel.Tab.settings -> SettingsTab()
                    }
                }
            }
        }
    }
}

/** Le logo de Talka. */
@Composable
fun Logo(size: Dp = 80.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.23f)
    Image(
        painterResource(R.mipmap.ic_launcher),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .shadow(12.dp, shape, ambientColor = TalkaColors.green.copy(alpha = 0.35f), spotColor = TalkaColors.green.copy(alpha = 0.35f))
            .clip(shape),
    )
}

/**
 * L'écran de chargement : sur le vert de Talka, le logo qui respire, le
 * nom, une barre qui avance, l'étape en cours (comme le navigateur).
 */
@Composable
fun LoadingView(step: String) {
    val motion = rememberInfiniteTransition(label = "chargement")
    val breathe by motion.animateFloat(1f, 0.94f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "souffle")
    val slide by motion.animateFloat(-72f, 180f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "barre")
    Box(
        Modifier
            .fillMaxSize()
            .background(TalkaColors.launch)
            .clickable(enabled = true, onClick = {})
            .semantics(mergeDescendants = true) { contentDescription = "Chargement de Talka. $step" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).scale(breathe).alpha(if (breathe < 0.97f) 0.85f else 1f)) {
                Logo(88.dp)
                Box(Modifier.size(88.dp).border(3.dp, Color.White.copy(alpha = 0.92f), RoundedCornerShape(88.dp * 0.23f)))
            }
            Column(
                Modifier.padding(top = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Talka", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Box(Modifier.width(180.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.offset(x = slide.dp).width(72.dp).height(4.dp).clip(CircleShape).background(Color.White))
                }
                Text(step, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
            }
        }
        Text(
            "🔒 Chiffré de bout en bout",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
        )
    }
}
