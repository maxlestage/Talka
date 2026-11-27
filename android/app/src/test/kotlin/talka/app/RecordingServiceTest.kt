// Un message vocal s'enregistre même si l'on quitte Talka un instant (comme
// sur l'iPhone) : un service au premier plan tient le micro, le temps de
// l'enregistrement seulement.

package talka.app

import android.Manifest
import android.app.Notification
import android.content.pm.ServiceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import talka.app.sys.RecordingService
import talka.app.ui.VoiceRecorder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun requested(): String? = shadowOf(app).nextStartedService?.component?.className

    @Test
    fun theServiceHoldsTheMicrophoneWhileRecordingOnly() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(RecordingService.wanted)
        val rec = VoiceRecorder(app)
        assertTrue(rec.start())
        assertTrue(RecordingService.wanted)
        assertEquals(RecordingService::class.java.name, requested())

        // Le service démarre : au premier plan, de type micro, avec sa notification discrète.
        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)
        val service = shadowOf(controller.get())
        assertFalse(service.isStoppedBySelf)
        assertTrue(service.isLastForegroundNotificationAttached)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, controller.get().foregroundServiceType)
        val n = assertNotNull(service.lastForegroundNotification)
        assertEquals("Enregistrement d'un message vocal…", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)

        // L'enregistrement fini (envoyé ou annulé) : le service s'arrête.
        rec.cancel()
        assertFalse(RecordingService.wanted)
        assertTrue(service.isStoppedBySelf)
        controller.destroy()
        assertNull(requested())
    }

    /** Un enregistrement fini avant que le service n'ait démarré : il démarre, puis s'arrête de lui-même. */
    @Test
    fun aServiceStartedTooLateStopsAtOnce() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val rec = VoiceRecorder(app)
        assertTrue(rec.start())
        assertNull(rec.stop(), "moins d'une demi-seconde : rien ne part")
        assertFalse(RecordingService.wanted)
        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    /** Sans la permission du micro, pas de type micro (Android le refuserait). */
    @Test
    fun withoutTheMicrophonePermissionTheServiceClaimsNoType() {
        RecordingService.start(app)
        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)
        assertEquals(0, controller.get().foregroundServiceType)
        RecordingService.stop()
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }
}
