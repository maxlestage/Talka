// Un message vocal s'enregistre même si l'on quitte Talka un instant, comme
// sur l'iPhone (son mode d'arrière-plan « audio »). Android coupe le micro
// d'une application en arrière-plan, sauf si un service au premier plan, de
// type micro, le tient : celui-ci, le temps de l'enregistrement seulement
// (2 minutes au plus), avec sa notification discrète.

package talka.app.sys

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import talka.app.MainActivity

class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Talka")
            .setContentText("Enregistrement d'un message vocal…")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setColor(0xFF00A884.toInt())
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, notificationId, n, type)
        } catch (e: RuntimeException) {
            stopSelf()
        }
        // L'enregistrement a fini avant que le service ne démarre : rien à tenir.
        if (!wanted) stopSelf()
        return START_NOT_STICKY
    }

    /** Talka est fermé depuis le sélecteur d'apps : plus rien à tenir. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        wanted = false
        stopSelf()
    }

    companion object {
        private const val channel = "enregistrement"
        private const val notificationId = 0x7A1CA

        /** Un enregistrement est-il en cours ? */
        @Volatile
        var wanted = false
            private set

        @Volatile
        private var running: RecordingService? = null

        /** L'enregistrement commence (Talka est à l'écran). */
        fun start(context: Context) {
            val c = context.applicationContext
            wanted = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                    NotificationChannel(channel, "Enregistrement d'un message vocal", NotificationManager.IMPORTANCE_LOW),
                )
            }
            try {
                ContextCompat.startForegroundService(c, Intent(c, RecordingService::class.java))
            } catch (e: RuntimeException) {
                // Refusé (Talka n'est plus à l'écran) : l'enregistrement se fait sans lui.
                wanted = false
            }
        }

        /**
         * L'enregistrement est fini. Le service déjà démarré s'arrête ; s'il
         * ne l'est pas encore, il s'arrêtera de lui-même en démarrant (on
         * n'arrête pas de l'extérieur un service qui n'a pas encore pris le
         * premier plan : Android le reprocherait).
         */
        fun stop() {
            wanted = false
            running?.stopSelf()
        }
    }
}
