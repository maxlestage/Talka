// Les actions de balayage d'une ligne (le `swipeActions` de l'iPhone) :
// vers la droite, celles du début ; vers la gauche, celles de la fin. Le
// `SwipeToDismissBox` de Material 3 n'a pas de position « entrouverte » :
// le balayage ouvre la ligne sur ses actions, en tuiles de couleur sur toute
// sa largeur, et un toucher en choisit une (la ligne se referme ; la
// balayer dans l'autre sens la referme aussi). Les mêmes actions sont
// proposées à TalkBack (actions personnalisées) : un balayage ne lui est
// pas accessible.

package talka.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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

/**
 * Une ligne qu'on balaie. `leading` : vers la droite, la première action
 * tout à gauche ; `trailing` : vers la gauche, la première tout à droite
 * (comme sur l'iPhone, où la première est la plus au bord). `content`
 * reçoit le `Modifier` à mettre sur l'élément qu'on touche : il porte les
 * actions pour TalkBack.
 */
@Composable
fun SwipeActionsBox(
    leading: List<SwipeAction>,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState()
    fun run(a: SwipeAction) {
        scope.launch { state.reset() }
        a.run()
    }
    val actions = Modifier.semantics {
        customActions = (leading + trailing).map { a ->
            CustomAccessibilityAction(a.label) {
                a.run()
                true
            }
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = leading.isNotEmpty(),
        enableDismissFromEndToStart = trailing.isNotEmpty(),
        backgroundContent = {
            // Rien tant que la ligne n'est pas balayée : les tuiles ne
            // doublent pas, pour TalkBack, ce que disent ses actions.
            val tiles = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> leading
                SwipeToDismissBoxValue.EndToStart -> trailing.reversed()
                SwipeToDismissBoxValue.Settled -> emptyList()
            }
            for (a in tiles) {
                Column(
                    Modifier
                        .weight(1f)
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
        },
    ) {
        // Opaque : les tuiles ne se voient pas à travers la ligne qui glisse.
        Box(Modifier.background(MaterialTheme.colorScheme.background)) { content(actions) }
    }
}
