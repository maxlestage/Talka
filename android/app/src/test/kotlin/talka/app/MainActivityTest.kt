// L'activité, pour de vrai (Robolectric) : elle dessine Talka, suit le
// premier plan et l'arrière-plan, rend l'écran sécurisé, ouvre les liens.

package talka.app

import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest : AppTestBase() {
    @Before
    fun setUp() = install()

    @After
    fun tearDown() = uninstall()

    @Test
    fun lifecycleSecureScreenAndLinks() {
        val m = model()
        m.createAccount("Léa", "")
        idle()
        (RuntimeEnvironment.getApplication() as TalkaApp).preset = m
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val activity = controller.get()
        assertIs<PromptBiometrics>(m.biometrics)
        assertTrue(m.active)
        // « Masquer pendant un enregistrement » (par défaut) : l'écran sécurisé.
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

        // Une notification touchée (`talka://chat/…`), Talka ouvert sur un autre onglet : la discussion.
        m.engine!!.ensureDirectChat("K7Q2MX9PRT")
        m.tab = AppModel.Tab.settings
        idle()
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("talka://chat/K7Q2MX9PRT")))
        idle()
        assertEquals(AppModel.Tab.chats, m.tab)
        assertEquals(listOf("K7Q2MX9PRT"), m.path)
        m.path = emptyList()
        idle()

        // Arrière-plan, puis retour.
        controller.pause().stop()
        idle()
        assertFalse(m.active)
        assertFalse(m.engine!!.foreground)
        controller.start().resume()
        idle()
        assertTrue(m.active)
        assertTrue(m.engine!!.foreground)

        // Le réglage retiré : l'écran ne l'est plus.
        m.hideWhenCaptured = false
        idle()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        controller.pause().stop().destroy()
    }
}
