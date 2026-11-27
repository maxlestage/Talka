// Les messages vocaux : les enregistrer et les lire. Le portage de
// `Voice.swift`.
//
// Le même format que l'iPhone : de l'AAC dans du MP4 (`audio/mp4`, mono,
// 24 kHz, 32 kbit/s), que l'iPhone, Android et les navigateurs lisent tous.
// Android lit aussi, sans rien de plus, les vocaux de Chrome et Firefox
// (WebM / Ogg Opus), que l'iPhone fait lire par WebKit.

package talka.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import talka.app.sys.RecordingService
import talka.kit.Content
import java.io.File
import java.util.UUID
import kotlin.math.min

/** L'enregistreur (2 minutes au plus). */
class VoiceRecorder(private val context: Context) {
    var recording by mutableStateOf(false)
        private set

    /** Le début de l'enregistrement (ms). */
    var started by mutableLongStateOf(System.currentTimeMillis())
        private set
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /**
     * Commence (la permission du micro est déjà donnée). Faux si le micro
     * ne s'ouvre pas.
     */
    fun start(): Boolean {
        val f = File(context.cacheDir, "talka-${UUID.randomUUID()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            configure(r)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
        } catch (x: Exception) {
            r.release()
            f.delete()
            return false
        }
        // 2 minutes : le micro s'arrête de lui-même, et le service qui le tient en arrière-plan aussi.
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) RecordingService.stop()
        }
        recorder = r
        file = f
        started = System.currentTimeMillis()
        recording = true
        // Quitter Talka un instant ne coupe pas l'enregistrement (comme sur l'iPhone).
        RecordingService.start(context)
        return true
    }

    /**
     * Arrête, et rend l'enregistrement (rien s'il dure moins d'une
     * demi-seconde). Le fichier est effacé aussitôt.
     */
    fun stop(): ByteArray? {
        val f = file
        halt()
        recording = false
        try {
            if (System.currentTimeMillis() - started <= 500 || f == null || !f.exists()) return null
            return f.readBytes().takeIf { it.isNotEmpty() }
        } catch (x: Exception) {
            return null
        } finally {
            f?.delete()
            file = null
        }
    }

    fun cancel() {
        halt()
        file?.delete()
        file = null
        recording = false
    }

    /** La durée de l'enregistrement en cours (s), bornée aux 2 minutes du micro. */
    fun elapsed(now: Long = System.currentTimeMillis()): Double = min(now - started, maxDurationMs.toLong()) / 1000.0

    private fun halt() {
        val r = recorder ?: return
        recorder = null
        RecordingService.stop()
        // Arrêté de lui-même au bout de 2 minutes : `stop` lève alors une exception.
        try {
            r.stop()
        } catch (x: Exception) {
        }
        r.release()
    }

    companion object {
        const val maxDurationMs = 120_000

        /** Ce qui part sur le fil : de l'AAC dans du MP4, comme l'iPhone. */
        const val mime = "audio/mp4"
        const val sampleRate = 24_000
        const val bitRate = 32_000

        /** Les réglages de l'iPhone (`kAudioFormatMPEG4AAC`, 24 kHz, mono, 32 kbit/s). */
        fun configure(r: MediaRecorder) {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(sampleRate)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(bitRate)
            r.setMaxDuration(maxDurationMs)
        }

        /** Le message d'un vocal enregistré : `dur` arrondie à la seconde, comme l'iPhone. */
        fun content(data: ByteArray, dur: Double): Content =
            Content(kind = "voice", data = data.dataURL(mime), mime = mime, size = data.size.toDouble(), dur = Math.round(dur).toDouble())
    }
}

/**
 * Les vocaux d'une discussion qui s'enchaînent : un vocal terminé lance le
 * suivant s'il vient juste après (comme WhatsApp et le navigateur).
 */
class VoiceChain {
    /** Le vocal à lancer maintenant (sa bulle le lit, puis remet à `null`). */
    var autoplay by mutableStateOf<String?>(null)

    /** Le suivant d'un vocal, d'après la discussion à l'écran. */
    var next: (String) -> String? = { null }

    fun ended(id: String) {
        autoplay = next(id)
    }
}

/** L'enchaînement des vocaux de la discussion (absent ailleurs : pas d'enchaînement). */
val LocalVoiceChain = staticCompositionLocalOf<VoiceChain?> { null }

/** Le lecteur d'un message vocal. */
class VoicePlayer {
    var playing by mutableStateOf(false)
        private set

    /** Augmente à chaque lecture allée jusqu'au bout (pas à l'arrêt manuel). */
    var finished by mutableIntStateOf(0)
        private set

    /** La vitesse de lecture : 1×, 1,5× ou 2×, comme dans le navigateur. */
    var speed by mutableFloatStateOf(1f)
        private set
    private var player: MediaPlayer? = null

    /** 1× → 1,5× → 2× → 1×, appliquée aussitôt, même pendant la lecture. */
    fun nextSpeed() {
        speed = if (speed < 1.4f) 1.5f else if (speed < 1.9f) 2f else 1f
        val p = player ?: return
        if (playing) {
            try {
                p.playbackParams = p.playbackParams.setSpeed(speed)
            } catch (x: Exception) {
            }
        }
    }

    val speedLabel: String get() = if (speed == 1.5f) "1,5×" else if (speed == 2f) "2×" else "1×"

    fun toggle(data: ByteArray?, mime: String) {
        if (playing) return stop()
        if (data == null) return
        release()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setDataSource(BytesSource(data))
            p.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                it.start()
                if (speed != 1f) {
                    try {
                        it.playbackParams = it.playbackParams.setSpeed(speed)
                    } catch (x: Exception) {
                    }
                }
            }
            p.setOnCompletionListener {
                if (player !== it) return@setOnCompletionListener
                playing = false
                finished += 1
            }
            p.setOnErrorListener { it, _, _ ->
                if (player === it) playing = false
                true
            }
            player = p
            playing = true
            p.prepareAsync()
        } catch (x: Exception) {
            p.release()
            player = null
            playing = false
        }
    }

    fun stop() {
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (x: Exception) {
        }
        playing = false
    }

    fun release() {
        player?.release()
        player = null
        playing = false
    }

    /** Le vocal lu depuis la mémoire : pas de fichier en clair sur le disque. */
    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = min(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() {}
    }
}
