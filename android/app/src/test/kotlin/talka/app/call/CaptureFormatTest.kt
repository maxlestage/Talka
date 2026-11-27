// Le format de la caméra d'un appel vidéo : le plus proche de 1280 × 720,
// comme `CallEngine.startCapture` de l'iPhone, et 30 images par seconde au plus.

package talka.app.call

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureFormatTest {
    private fun f(w: Int, h: Int, fps: Int = 30) = CaptureFormat(w, h, fps)

    @Test
    fun theExactSizeWins() {
        val formats = listOf(f(1920, 1080), f(640, 480), f(1280, 720, 60), f(320, 240))
        assertEquals(f(1280, 720, 60), CaptureFormat.closest(formats))
    }

    @Test
    fun otherwiseTheSmallestGapInWidthPlusHeight() {
        // 1280 × 960 : 0 + 240 ; 960 × 720 : 320 + 0 ; 1920 × 1080 : 640 + 360.
        assertEquals(f(1280, 960), CaptureFormat.closest(listOf(f(1920, 1080), f(960, 720), f(1280, 960))))
        // 1024 × 768 : 256 + 48 = 304, mieux que 800 × 600 (480 + 120 = 600).
        assertEquals(f(1024, 768), CaptureFormat.closest(listOf(f(800, 600), f(1024, 768), f(3264, 2448))))
        // Seulement plus grand : le moins grand.
        assertEquals(f(1920, 1080), CaptureFormat.closest(listOf(f(4032, 3024), f(1920, 1080), f(3840, 2160))))
    }

    @Test
    fun aTieKeepsTheFirstLikeTheIPhone() {
        // 1280 × 760 et 1280 × 680 : 40 d'écart chacun.
        assertEquals(f(1280, 760), CaptureFormat.closest(listOf(f(1280, 760), f(1280, 680))))
        assertEquals(f(1280, 680), CaptureFormat.closest(listOf(f(1280, 680), f(1280, 760))))
    }

    @Test
    fun noFormatNoChoice() {
        assertNull(CaptureFormat.closest(emptyList()))
        assertEquals(CaptureFormat(1280, 720, 30), CaptureFormat.preferred)
    }

    @Test
    fun thirtyFramesPerSecondAtMost() {
        assertEquals(30, f(1280, 720, 60).fps)
        assertEquals(24, f(1280, 720, 24).fps)
        assertEquals(30, f(1280, 720, 30).fps)
        // La caméra ne dit rien : 30.
        assertEquals(30, f(1280, 720, 0).fps)
    }
}
