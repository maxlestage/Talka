// Ce qu'un appel demande à Android, hors de WebRTC :
//
// - le son (`AudioManager`) : le mode communication, le haut-parleur, la
//   priorité sur la musique (ce que `AVAudioSession` fait sur l'iPhone) ;
// - la sonnerie : l'appel qui sonne chez l'autre (« tuut… »), et celui qui
//   sonne ici (la sonnerie du téléphone, et le vibreur) ;
// - un appel qui arrive quand Talka n'est pas à l'écran : une notification
//   d'appel (`CallStyle`), sur un canal qui sonne, qui ouvre l'écran d'appel
//   en plein écran même verrouillé (`fullScreenIntent`) ;
// - pendant l'appel : un service au premier plan (`CallService`), pour que
//   le micro et la caméra continuent hors de Talka, avec sa notification
//   « Appel en cours » et son bouton Raccrocher.
//
// Rien ne passe par un serveur de notifications : seulement quand Talka
// tourne (comme les notifications des messages).

package talka.app.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import talka.app.MainActivity

class CallSystem(context: Context) {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var tone: ToneGenerator? = null
    private var ringtone: Ringtone? = null
    private var vibrating = false

    // ----- Le son -----

    /** L'appel commence : le mode communication (l'annulation d'écho), la priorité sur le reste. */
    fun audioStart(speaker: Boolean) {
        val am = audio ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(voice)
                    .build()
                focus = request
                am.requestAudioFocus(request)
            }
        } catch (e: RuntimeException) {
        }
        setSpeaker(speaker)
    }

    /** Le haut-parleur, ou l'écouteur (ou le casque, s'il y en a un). */
    fun setSpeaker(on: Boolean) {
        val am = audio ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { am.setCommunicationDevice(it) }
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        } catch (e: RuntimeException) {
        }
    }

    /** L'appel est fini : le son redevient celui d'avant. */
    fun audioStop() {
        val am = audio ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { am.abandonAudioFocusRequest(it) }
            focus = null
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: RuntimeException) {
        }
    }

    // ----- La sonnerie -----

    /**
     * `outgoing` : l'appel sonne chez l'autre (la tonalité d'appel, à
     * l'oreille) ; sinon, il sonne ici : la sonnerie du téléphone et le
     * vibreur, selon son mode (silencieux, vibreur, sonnerie).
     */
    fun ring(outgoing: Boolean) {
        stopRing()
        ringing = outgoing
        try {
            if (outgoing) {
                tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
                return
            }
            val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.also {
                    it.audioAttributes = ringAttributes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLooping = true
                    it.play()
                }
            }
            if (mode != AudioManager.RINGER_MODE_SILENT) {
                vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0))
                vibrating = true
            }
        } catch (e: RuntimeException) {
        }
    }

    /** Ce qui sonne : `true` l'appel sortant, `false` l'appel entrant, null rien. */
    var ringing: Boolean? = null
        private set

    fun stopRing() {
        ringing = null
        try {
            tone?.stopTone()
            tone?.release()
        } catch (e: RuntimeException) {
        }
        tone = null
        try {
            ringtone?.stop()
        } catch (e: RuntimeException) {
        }
        ringtone = null
        if (vibrating) vibrator()?.cancel()
        vibrating = false
    }

    /** L'appel connecté se termine : un petit son (le 1112 de l'iPhone). */
    fun endTone() {
        try {
            val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
            t.startTone(ToneGenerator.TONE_PROP_PROMPT, 300)
            Handler(Looper.getMainLooper()).postDelayed({ t.release() }, 500)
        } catch (e: RuntimeException) {
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    // ----- Les notifications -----

    /**
     * Un appel arrive et Talka n'est pas à l'écran : la notification
     * d'appel (Répondre, Refuser), qui sonne jusqu'à ce qu'on réponde, et
     * ouvre l'écran d'appel même sur l'écran verrouillé. Vrai si elle est
     * partie (elle sonne : Talka n'a plus à sonner lui-même).
     */
    @SuppressLint("MissingPermission")
    fun incoming(who: String, text: String, video: Boolean): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        channels(context)
        val person = Person.Builder().setName(who.ifEmpty { "Talka" }).setImportant(true).build()
        val show = activity(context, ACTION_SHOW, 1)
        val answer = activity(context, ACTION_ANSWER, 2)
        val decline = broadcast(context, ACTION_DECLINE, 3)
        // L'écran verrouillé ne montre pas qui appelle (seulement l'écran d'appel).
        val public = NotificationCompat.Builder(context, incomingChannel)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Talka")
            .setContentText(if (video) "Appel vidéo entrant" else "Appel entrant")
            .build()
        fun base() =
            NotificationCompat.Builder(context, incomingChannel)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(who)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(show)
                .setFullScreenIntent(show, true)
                .setOngoing(true)
                .setAutoCancel(false)
                // Comme l'appelant : 45 secondes au plus.
                .setTimeoutAfter(45_000)
                .setColor(0xFF00A884.toInt())
        val n = try {
            base()
                .addPerson(person)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video))
                .build()
        } catch (e: RuntimeException) {
            base()
                .addAction(0, "Refuser", decline)
                .addAction(0, "Répondre", answer)
                .build()
        }
        // Elle sonne jusqu'à ce qu'on réponde, refuse, ou que l'appel cesse.
        n.flags = n.flags or Notification.FLAG_INSISTENT
        return try {
            NotificationManagerCompat.from(context).notify(incomingId, n)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /** La notification de l'appel qui sonnait s'en va. */
    fun cancelIncoming() {
        NotificationManagerCompat.from(context).cancel(incomingId)
    }

    /** L'appel commence (ou est pris) : le service au premier plan, et sa notification. */
    fun startService(title: String, video: Boolean) {
        val i = Intent(context, CallService::class.java).putExtra(extraTitle, title).putExtra(extraVideo, video)
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (e: RuntimeException) {
            // Android refuse un service au premier plan démarré de l'arrière-plan : l'appel continue sans.
        }
    }

    fun stopService() {
        try {
            context.stopService(Intent(context, CallService::class.java))
        } catch (e: RuntimeException) {
        }
        NotificationManagerCompat.from(context).cancel(ongoingId)
    }

    companion object {
        /** L'appel en cours : pour les boutons des notifications (Refuser, Raccrocher). */
        var current: CallEngine? = null

        const val ACTION_SHOW = "talka.app.call.SHOW"
        const val ACTION_ANSWER = "talka.app.call.ANSWER"
        const val ACTION_DECLINE = "talka.app.call.DECLINE"
        const val ACTION_HANGUP = "talka.app.call.HANGUP"
        const val incomingChannel = "appels"
        const val ongoingChannel = "appels-en-cours"
        const val incomingId = 4201
        const val ongoingId = 4202
        internal const val extraTitle = "titre"
        internal const val extraVideo = "video"

        private val voice: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        private val ringAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /**
         * Les canaux des appels : « Appels entrants » sonne (la sonnerie du
         * téléphone) et vibre ; « Appel en cours » est silencieux.
         */
        fun channels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(incomingChannel) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(incomingChannel, "Appels entrants", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), ringAttributes)
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 800, 1200)
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    },
                )
            }
            if (manager.getNotificationChannel(ongoingChannel) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(ongoingChannel, "Appel en cours", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
                )
            }
        }

        fun activity(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context, 4200 + code,
                Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun broadcast(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, 4200 + code, Intent(context, CallActions::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** La notification « Appel en cours » (celle du service), avec Raccrocher. */
        fun ongoing(context: Context, title: String, video: Boolean): Notification {
            channels(context)
            val person = Person.Builder().setName(title.ifEmpty { "Talka" }).setImportant(true).build()
            val hangup = broadcast(context, ACTION_HANGUP, 4)
            fun base() =
                NotificationCompat.Builder(context, ongoingChannel)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle(title)
                    .setContentText(if (video) "Appel vidéo en cours · chiffré de bout en bout" else "Appel en cours · chiffré de bout en bout")
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setContentIntent(activity(context, ACTION_SHOW, 1))
                    .setOngoing(true)
                    .setUsesChronometer(true)
                    .setColor(0xFF00A884.toInt())
            return try {
                base().addPerson(person).setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangup).setIsVideo(video)).build()
            } catch (e: RuntimeException) {
                base().addAction(0, "Raccrocher", hangup).build()
            }
        }
    }
}

/**
 * Le service au premier plan d'un appel : le micro (et la caméra) continuent
 * quand Talka n'est plus à l'écran. Le type « appel téléphonique » (Android 10
 * et plus), avec le micro et la caméra (Android 11 et plus), selon ce qui
 * est permis.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(CallSystem.extraTitle) ?: "Talka"
        val video = intent?.getBooleanExtra(CallSystem.extraVideo, false) ?: false
        val n = CallSystem.ongoing(this, title, video)
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(this, CallSystem.ongoingId, n, types)
        } catch (e: RuntimeException) {
            stopSelf()
        }
        // Plus d'appel (il a fini avant que le service ne démarre) : rien à garder.
        if (CallSystem.current == null) stopSelf()
        return START_NOT_STICKY
    }

    /** Talka est fermé depuis le sélecteur d'apps : l'appel s'arrête (comme sur l'iPhone). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        CallSystem.current?.hangup()
        stopSelf()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

/** Les boutons des notifications d'appel : Refuser, Raccrocher. */
class CallActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CallSystem.ACTION_DECLINE, CallSystem.ACTION_HANGUP -> {
                val call = CallSystem.current
                if (call != null) call.hangup() else NotificationManagerCompat.from(context).cancel(CallSystem.incomingId)
            }
        }
    }
}
