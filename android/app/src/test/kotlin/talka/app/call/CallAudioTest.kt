// Par où passe le son d'un appel (`AVAudioSession` sur l'iPhone :
// `.allowBluetooth`, `.defaultToSpeaker` en vidéo, `overrideOutputAudioPort`
// pour le bouton) : le casque ou l'oreillette s'il y en a, sinon l'écouteur
// (le haut-parleur en vidéo) ; le bouton force le haut-parleur.

package talka.app.call

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallAudioTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val am get() = app.getSystemService(AudioManager::class.java)

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    private val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    private val wired = device(AudioDeviceInfo.TYPE_WIRED_HEADSET)

    private fun route(): Int? = am.communicationDevice?.type

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun aBluetoothHeadsetCarriesTheCallLikeOnTheIPhone() {
        shadowOf(am).setAvailableCommunicationDevices(listOf(earpiece, speaker, bluetooth))
        val system = CallSystem(app)
        system.audioStart(speaker = false)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, route())
        // Le bouton : le haut-parleur, puis de nouveau l'oreillette.
        system.setSpeaker(true)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, route())
        system.setSpeaker(false)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, route())
        system.audioStop()
        assertNull(route())
        assertEquals(AudioManager.MODE_NORMAL, am.mode)
    }

    @Test
    fun aVideoCallIsOnTheSpeakerUnlessAHeadsetIsThere() {
        // `.defaultToSpeaker` : le haut-parleur par défaut, le casque d'abord s'il y en a un.
        shadowOf(am).setAvailableCommunicationDevices(listOf(earpiece, speaker))
        val alone = CallSystem(app)
        assertTrue(alone.audioStart(speaker = true))
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, route())
        // Le bouton : l'écouteur.
        alone.setSpeaker(false)
        assertNull(route())
        alone.audioStop()

        shadowOf(am).setAvailableCommunicationDevices(listOf(earpiece, speaker, wired))
        val plugged = CallSystem(app)
        assertFalse(plugged.audioStart(speaker = true))
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, route())
        plugged.audioStop()

        // Un appel audio sans casque : l'écouteur (rien de forcé).
        shadowOf(am).setAvailableCommunicationDevices(listOf(earpiece, speaker))
        val voice = CallSystem(app)
        assertFalse(voice.audioStart(speaker = false))
        assertNull(route())
        voice.audioStop()
    }

    @Test
    fun aHeadsetConnectedDuringTheCallTakesIt() {
        shadowOf(am).setAvailableCommunicationDevices(listOf(earpiece, speaker))
        val system = CallSystem(app)
        val changes = ArrayList<Boolean>()
        system.routeChanged = { changes.add(it) }
        system.audioStart(speaker = true)
        idle()
        assertEquals(emptyList(), changes)
        // Le haut-parleur forcé, puis une oreillette s'allume : elle prend l'appel (l'iPhone aussi).
        system.setSpeaker(true)
        shadowOf(am).addAvailableCommunicationDevice(bluetooth, true)
        idle()
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, route())
        assertEquals(listOf(false), changes)
        // Autre chose se branche (un écran) : rien ne change.
        shadowOf(am).addAvailableCommunicationDevice(device(AudioDeviceInfo.TYPE_HDMI), true)
        idle()
        assertEquals(listOf(false), changes)
        // Elle s'éteint : retour au haut-parleur de l'appel vidéo.
        shadowOf(am).removeAvailableCommunicationDevice(bluetooth, true)
        idle()
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, route())
        assertEquals(listOf(false, true), changes)
        // L'appel fini, plus rien ne suit.
        system.audioStop()
        shadowOf(am).addAvailableCommunicationDevice(bluetooth, true)
        idle()
        assertNull(route())
    }

    @Test
    fun whichOutputCarriesTheCall() {
        val bt = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val wire = AudioDeviceInfo.TYPE_WIRED_HEADSET
        val loud = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val ear = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        // L'oreillette Bluetooth avant le casque à fil ; ni l'écouteur, ni le haut-parleur, ni une enceinte (A2DP).
        assertEquals(bt, CallSystem.headset(listOf(ear, loud, wire, bt)))
        assertEquals(wire, CallSystem.headset(listOf(ear, loud, wire)))
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, CallSystem.headset(listOf(ear, AudioDeviceInfo.TYPE_BLE_HEADSET)))
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, CallSystem.headset(listOf(loud, AudioDeviceInfo.TYPE_USB_HEADSET)))
        assertNull(CallSystem.headset(listOf(ear, loud, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertNull(CallSystem.headset(emptyList()))
        // Sans le bouton : le casque, sinon le haut-parleur en vidéo, sinon l'écouteur.
        assertEquals(bt, CallSystem.route(speaker = true, headset = bt))
        assertEquals(bt, CallSystem.route(speaker = false, headset = bt))
        assertEquals(loud, CallSystem.route(speaker = true, headset = null))
        assertNull(CallSystem.route(speaker = false, headset = null))
    }
}
