// Les messages vocaux enregistrés sur la montre : le même format que le
// téléphone (AAC mono, 32 kbit/s), envoyés au téléphone en fichier ; c'est
// lui qui les chiffre et les envoie. (`Voice.swift` de l'Apple Watch.)

package talka.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import talka.kit.WatchLink
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToLong

/** L'enregistreur : AAC mono, 24 kHz, 32 kbit/s ; deux minutes au plus. */
class WatchRecorder(private val context: Context) {
    var recording by mutableStateOf(false)
        private set
    var started = 0L
        private set
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /** Arrivé au bout des deux minutes : l'enregistreur s'est arrêté de lui-même. */
    private var finished = false

    /** Commence (la permission du micro est déjà donnée). Faux sans micro. */
    fun start(): Boolean {
        val f = File(context.cacheDir, "vocal-${UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(24_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration((WatchLink.maxVoiceSeconds * 1000).toInt())
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finished = true
            }
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            recorder = r
            file = f
            finished = false
            started = System.currentTimeMillis()
            recording = true
            true
        } catch (e: IOException) {
            r.release()
            f.delete()
            false
        } catch (e: RuntimeException) {
            r.release()
            f.delete()
            false
        }
    }

    /**
     * Arrête, et rend le fichier et sa durée (rien en dessous d'une
     * demi-seconde). Le fichier est à effacer une fois parti.
     */
    fun stop(): Pair<File, Double>? {
        val seconds = minOf((System.currentTimeMillis() - started) / 1000.0, WatchLink.maxVoiceSeconds)
        val ok = halt()
        val f = file ?: return null
        file = null
        if (!ok || seconds < 0.5) {
            f.delete()
            return null
        }
        return f to seconds
    }

    fun cancel() {
        halt()
        file?.delete()
        file = null
    }

    /** Faux : rien d'utilisable n'a été enregistré. */
    private fun halt(): Boolean {
        val r = recorder ?: return false
        recorder = null
        recording = false
        val ok = try {
            if (!finished) r.stop()
            true
        } catch (e: RuntimeException) {
            false
        }
        r.release()
        return ok
    }
}

/** « 0:07 » */
fun voiceDuration(seconds: Double): String {
    val t = maxOf(0L, seconds.roundToLong())
    return "${t / 60}:" + "%02d".format(t % 60)
}

/** L'enregistrement : le temps qui passe, Envoyer, Annuler. */
@Composable
fun RecordScreen(model: WatchModel, chatId: String, close: () -> Unit) {
    val context = LocalContext.current
    val recorder = remember { WatchRecorder(context) }
    var failed by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted || !recorder.start()) failed = true
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!recorder.start()) failed = true
        } else {
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(Unit) { onDispose { if (recorder.recording) recorder.cancel() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failed) {
            Icon(painterResource(R.drawable.ic_mic_off), null, Modifier.size(28.dp), tint = WatchColors.failed)
            Text("Micro indisponible", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
            Text(
                "Autorisez le micro pour Talka dans les réglages de la montre.",
                style = MaterialTheme.typography.caption2,
                color = WatchColors.secondary,
                textAlign = TextAlign.Center,
            )
        } else {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(recorder.recording) {
                while (recorder.recording) {
                    now = System.currentTimeMillis()
                    delay(1000)
                }
            }
            val elapsed = if (recorder.recording) (now - recorder.started) / 1000.0 else 0.0
            val pulse by rememberInfiniteTransition(label = "pouls").animateFloat(
                1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pouls",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = "Enregistrement : ${voiceDuration(elapsed)}" },
            ) {
                Box(Modifier.size(10.dp).alpha(pulse).background(WatchColors.failed, CircleShape))
                Text(voiceDuration(elapsed), fontSize = 30.sp, fontFamily = FontFamily.Monospace)
            }
            Chip(
                onClick = {
                    recorder.stop()?.let { (file, seconds) -> model.sendVoice(chatId, file, seconds) }
                    close()
                },
                label = { Text("Envoyer") },
                icon = { Icon(painterResource(R.drawable.ic_send), null) },
                enabled = recorder.recording,
                colors = ChipDefaults.primaryChipColors(backgroundColor = WatchColors.talkaGreen),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Chip(
            onClick = {
                recorder.cancel()
                close()
            },
            label = { Text("Annuler") },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
