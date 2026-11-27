// L'œuf de Pâques 🥦, comme dans le navigateur : un message qui parle de
// brocoli fait pleuvoir des brocolis sur la conversation. Rien ne voyage en
// plus : le téléphone regarde le texte qu'il a déjà déchiffré. Le portage de
// `Brocoli.swift`.

package talka.app.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/** Une goutte de la pluie : sa place, son retard, sa durée, sa taille, son tour. */
private class Drop(val x: Float, val delay: Float, val duration: Float, val size: Float, val turn: Float)

/** La pluie de brocolis : 36 qui tombent en tournant, pendant quelques secondes. */
@Composable
fun BrocoliRain(modifier: Modifier = Modifier) {
    // Tirés une fois : la pluie ne change pas à chaque dessin.
    val drops = remember {
        List(36) {
            Drop(
                x = Random.nextFloat(),
                delay = Random.nextFloat() * 1.2f,
                duration = 2.2f + Random.nextFloat() * 1.6f,
                size = 22f + Random.nextFloat() * 22f,
                turn = -360f + Random.nextFloat() * 720f,
            )
        }
    }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> t = (now - start) / 1e9f }
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().clearAndSetSemantics {}) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val margin = with(density) { 40.sp.toPx() }
        for (d in drops) {
            Box(
                Modifier.graphicsLayer {
                    // Lu ici, au dessin : la pluie avance sans redessiner l'écran.
                    val p = maxOf(0f, (t - d.delay) / d.duration)
                    translationX = d.x * w - margin / 2
                    translationY = -margin + p * (h + 2 * margin)
                    rotationZ = d.turn * p
                },
            ) { Text("🥦", fontSize = d.size.sp) }
        }
    }
}

/** Les animations sont-elles coupées (Accessibilité → Supprimer les animations) ? */
fun reduceMotion(context: android.content.Context): Boolean =
    try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (x: Exception) {
        false
    }
