// Les actions de balayage d'une ligne (le `swipeActions` de l'iPhone) :
// vers la droite, celles du début ; vers la gauche, celles de la fin.
// Comme sur l'iPhone :
// - un balayage court entrouvre la ligne sur ses tuiles, un toucher en
//   choisit une ;
// - un balayage long (au-delà des tuiles) fait aussitôt la première action,
//   celle du bord (`allowsFullSwipe`) ;
// - une seule ligne ouverte à la fois ; toucher la ligne, ou faire défiler
//   la liste, la referme.
// Les mêmes actions sont proposées à TalkBack (actions personnalisées) : un
// balayage ne lui est pas accessible.

package talka.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Une action de balayage : son nom, son icône, la couleur de sa tuile (le `.tint` de l'iPhone). */
class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val run: () -> Unit)

/** Les couleurs des tuiles, celles d'iOS (`.blue`, `.orange`, `.gray`, `.indigo`, `.green`, rôle destructif). */
object SwipeColors {
    val blue = Color(0xFF007AFF)
    val orange = Color(0xFFFF9500)
    val gray = Color(0xFF8E8E93)
    val indigo = Color(0xFF5856D6)
    val green = Color(0xFF34C759)
    val red = Color(0xFFFF3B30)
}

/** Les lignes d'une même liste : une seule ouverte à la fois, toutes refermées quand la liste défile. */
class SwipeGroup {
    /** La ligne ouverte (son identité), s'il y en a une. */
    var open: Any? by mutableStateOf(null)

    /** Placé sur la liste : le moindre défilement referme la ligne ouverte. */
    internal val closer = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (open != null && available.y != 0f) open = null
            return Offset.Zero
        }
    }
}

private val LocalSwipeGroup = staticCompositionLocalOf<SwipeGroup?> { null }

/**
 * Une liste dont les lignes se balaient : `content` reçoit le `Modifier` à
 * mettre sur la liste (le défilement referme la ligne ouverte).
 */
@Composable
fun SwipeGroupHost(content: @Composable (Modifier) -> Unit) {
    val group = remember { SwipeGroup() }
    CompositionLocalProvider(LocalSwipeGroup provides group) { content(Modifier.nestedScroll(group.closer)) }
}

/** La largeur d'une tuile (celle d'une action de l'iPhone). */
private val tileWidth = 74.dp

/** Au-delà des tuiles, la distance qui fait un balayage long. */
private val fullSwipeExtra = 48.dp

/**
 * Une ligne qu'on balaie. `leading` : vers la droite, la première action
 * tout à gauche ; `trailing` : vers la gauche, la première tout à droite
 * (comme sur l'iPhone, où la première est la plus au bord, et celle d'un
 * balayage long). `content` reçoit le `Modifier` à mettre sur l'élément
 * qu'on touche : il porte les actions pour TalkBack.
 */
@Composable
fun SwipeActionsBox(
    leading: List<SwipeAction>,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val group = LocalSwipeGroup.current
    val me = remember { Any() }
    var width by remember { mutableIntStateOf(0) }
    var x by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }

    // Les tuiles tiennent toujours dans la ligne (une ligne étroite les resserre).
    fun tile(n: Int): Float = if (n == 0) 0f else min(with(density) { tileWidth.toPx() }, width * 0.8f / n)
    val leadWidth = leading.size * tile(leading.size)
    val trailWidth = trailing.size * tile(trailing.size)
    fun full(open: Float): Float = max(width * 0.6f, open + with(density) { fullSwipeExtra.toPx() })

    fun settle(to: Float) {
        settling?.cancel()
        settling = scope.launch { animate(x, to) { v, _ -> x = v } }
        if (to == 0f) {
            if (group?.open === me) group.open = null
        } else {
            group?.open = me
        }
    }
    fun run(a: SwipeAction) {
        settle(0f)
        a.run()
    }

    // Une autre ligne s'ouvre, ou la liste défile : celle-ci se referme.
    LaunchedEffect(group?.open) {
        if (group != null && group.open !== me && x != 0f) {
            settling?.cancel()
            animate(x, 0f) { v, _ -> x = v }
        }
    }

    val drag = rememberDraggableState { delta ->
        val lo = if (trailing.isEmpty()) 0f else -width.toFloat()
        val hi = if (leading.isEmpty()) 0f else width.toFloat()
        x = (x + delta).coerceIn(lo, hi)
    }

    val actions = Modifier.semantics {
        customActions = (leading + trailing).map { a ->
            CustomAccessibilityAction(a.label) {
                a.run()
                true
            }
        }
    }

    Box(modifier.onSizeChanged { width = it.width }) {
        // Les tuiles, sous la ligne, du côté où elle s'ouvre ; rien tant
        // qu'elle n'est pas balayée (TalkBack a déjà ses actions).
        if (x != 0f) {
            val shown = abs(x)
            val actionsShown = if (x > 0) leading else trailing
            val open = if (x > 0) leadWidth else trailWidth
            // La tuile du bord s'étire au-delà des autres, comme sur l'iPhone.
            val each = if (actionsShown.isEmpty()) 0f else min(shown, open) / actionsShown.size
            val extra = max(0f, shown - open)
            Row(
                Modifier.matchParentSize(),
                horizontalArrangement = if (x > 0) Arrangement.Start else Arrangement.End,
            ) {
                val ordered = if (x > 0) actionsShown else actionsShown.reversed()
                for ((i, a) in ordered.withIndex()) {
                    val edge = if (x > 0) i == 0 else i == ordered.lastIndex
                    val w = each + if (edge) extra else 0f
                    Column(
                        Modifier
                            .width(with(density) { w.toDp() })
                            .fillMaxHeight()
                            .background(a.color)
                            .clickable { run(a) }
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Icon(a.icon, null, Modifier.size(22.dp), tint = Color.White)
                        Text(
                            a.label,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                // Opaque : les tuiles ne se voient pas à travers la ligne qui glisse.
                .background(MaterialTheme.colorScheme.background)
                .draggable(
                    drag,
                    Orientation.Horizontal,
                    enabled = leading.isNotEmpty() || trailing.isNotEmpty(),
                    onDragStarted = { settling?.cancel() },
                    onDragStopped = { velocity ->
                        when {
                            // Un balayage long : la première action, aussitôt.
                            x > 0 && x > full(leadWidth) -> run(leading.first())
                            x < 0 && -x > full(trailWidth) -> run(trailing.first())
                            // Court (ou lancé vite) : la ligne reste ouverte sur ses tuiles.
                            x > 0 && (x > leadWidth / 2 || velocity > 1500f) -> settle(leadWidth)
                            x < 0 && (-x > trailWidth / 2 || velocity < -1500f) -> settle(-trailWidth)
                            else -> settle(0f)
                        }
                    },
                ),
        ) {
            content(actions)
            // Ouverte : la toucher la referme (sans l'ouvrir).
            if (x != 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settle(0f) },
                )
            }
        }
    }
}
