package talka.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import talka.app.call.CallEngine
import talka.app.call.CallSystem
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * L'unique activité : tout Talka est dessiné en Compose. Elle branche sur
 * le modèle ce qui demande une activité : l'empreinte ou le visage
 * (`BiometricPrompt`), la permission des notifications, le cycle de vie
 * (premier plan, arrière-plan), le retour du réseau, l'écran sécurisé, les
 * liens qui ouvrent Talka.
 */
class MainActivity : FragmentActivity() {
    private val model: AppModel get() = (application as TalkaApp).model
    private var network: ConnectivityManager.NetworkCallback? = null
    private var permission: CompletableDeferred<Boolean>? = null
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permission?.complete(ok)
        permission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Un téléphone reste debout, une tablette (ou un pliable ouvert) tourne
        // librement : les `UISupportedInterfaceOrientations` de l'iPhone et de l'iPad.
        if (phoneSized(resources.configuration.smallestScreenWidthDp)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // Le sélecteur d'apps ne montre jamais les discussions (Android 13 et plus).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        val m = model
        m.biometrics = PromptBiometrics(this)
        m.askNotifications = { requestNotifications() }
        m.askPermission = { requestPermission(it) }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> m.scene(AppModel.Phase.active)
                    Lifecycle.Event.ON_PAUSE -> m.scene(AppModel.Phase.inactive)
                    Lifecycle.Event.ON_STOP -> m.scene(AppModel.Phase.background)
                    else -> {}
                }
            },
        )
        watchNetwork()
        if (savedInstanceState == null) {
            intent?.let {
                openLink(it)
                openCall(it)
            }
        }
        setContent {
            TalkaTheme(m) {
                // L'écran sécurisé : noir dans les captures, les enregistrements
                // et les recopies (Réglages → Verrouillage et sécurité).
                LaunchedEffect(Unit) {
                    snapshotFlow { m.hideWhenCaptured && m.engine != null }.collect { secure ->
                        if (secure) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
                // Un appel (qui sonne ou en cours) s'affiche par-dessus l'écran
                // verrouillé, et l'allume ; plus dès qu'il est fini.
                LaunchedEffect(Unit) {
                    snapshotFlow { m.call?.let { it.phase != CallEngine.Phase.ended } == true }.collect { showOverLock(it) }
                }
                RootView()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
        openCall(intent)
    }

    /** Les notifications d'appel : Répondre, ou l'écran d'appel (plein écran, même verrouillé). */
    private fun openCall(intent: Intent) {
        if (intent.action != CallSystem.ACTION_ANSWER && intent.action != CallSystem.ACTION_SHOW) return
        val call = model.call ?: return
        // Tout de suite (sans attendre l'écran) : l'appel s'ouvre par-dessus l'écran verrouillé.
        if (call.phase != CallEngine.Phase.ended) showOverLock(true)
        if (intent.action == CallSystem.ACTION_ANSWER && call.phase == CallEngine.Phase.ringing) model.launch { call.accept() }
    }

    @Suppress("DEPRECATION")
    private fun showOverLock(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    override fun onDestroy() {
        network?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        network = null
        if (model.biometrics is PromptBiometrics && (model.biometrics as PromptBiometrics).activity === this) model.biometrics = null
        super.onDestroy()
    }

    /** `talka://add/…`, `https://…/c/…`, ou une notification (`talka://chat/…`). */
    private fun openLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        intent.dataString?.let { model.open(it) }
    }

    /** Le réseau revient : le relais se reconnecte aussitôt (sans attendre son prochain essai). */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                runOnUiThread { model.networkBack() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            network = callback
        } catch (e: RuntimeException) {
        }
    }

    /** Une permission du système (le micro, la caméra d'un appel). */
    private suspend fun requestPermission(name: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED) return true
        permission?.complete(false)
        val wait = CompletableDeferred<Boolean>()
        permission = wait
        model.leavingOnPurpose()
        try {
            askPermission.launch(name)
        } catch (e: IllegalStateException) {
            // L'activité n'est plus là.
            permission = null
            return false
        }
        return wait.await()
    }

    private suspend fun requestNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return talka.app.ui.Notifier.allowed()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        val wait = CompletableDeferred<Boolean>()
        permission = wait
        model.leavingOnPurpose()
        askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        return wait.await()
    }
}

/** Un écran de téléphone (moins de 600 dp au plus étroit), pas de tablette. */
fun phoneSized(smallestWidthDp: Int): Boolean = smallestWidthDp < 600

/** L'empreinte ou le visage par `BiometricPrompt` ; le code de l'appareil quand c'est permis. */
class PromptBiometrics(val activity: FragmentActivity) : Biometrics {
    private val manager = BiometricManager.from(activity)

    override val name: String?
        get() {
            if (manager.canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return null
            val pm = activity.packageManager
            val finger = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
            val face = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm.hasSystemFeature(PackageManager.FEATURE_FACE)
            return when {
                finger && !face -> "Empreinte digitale"
                face && !finger -> "Reconnaissance faciale"
                else -> "Empreinte ou visage"
            }
        }

    override val deviceSecure: Boolean
        get() = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: false

    override suspend fun authenticate(title: String, crypto: Any?, deviceCredential: Boolean): Boolean {
        val cipher = crypto as? Cipher
        // Avec une clé du Keystore : la biométrie forte (et le code de
        // l'appareil, à partir d'Android 11) ; sans clé : la biométrie ou le code.
        val credential = deviceCredential && (cipher == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val kinds = (if (cipher != null || !credential) BIOMETRIC_STRONG else BIOMETRIC_WEAK) or (if (credential) DEVICE_CREDENTIAL else 0)
        if (manager.canAuthenticate(kinds) != BiometricManager.BIOMETRIC_SUCCESS) return false
        // `BiometricPrompt` s'ouvre sur une activité au premier plan (le verrou
        // le demande dès son affichage, parfois avant).
        activity.withResumed {}
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(kinds)
            .apply { if (!credential) setNegativeButtonText("Annuler") }
            .build()
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
            if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}
