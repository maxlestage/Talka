// La fiche d'un contact et celle d'un groupe : le portage d'`Info.swift`,
// À FAIRE (sauf ce dont l'onglet Discussions et les réglages ont déjà
// besoin : la sourdine, « Vider la discussion », le fond d'écran).

package talka.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import talka.app.LocalAppModel
import talka.kit.Engine

/** La fiche d'un contact. */
@Composable
fun ContactInfoView(code: String, onBack: () -> Unit) {
    val model = LocalAppModel.current
    val e = model.engine ?: return
    Page(e.displayName(code), onBack) { padding -> ComingSoon("La fiche du contact", Modifier.padding(padding)) }
}

/** La fiche d'un groupe. */
@Composable
fun GroupInfoView(chatId: String, onBack: () -> Unit) {
    Page("Infos du groupe", onBack) { padding -> ComingSoon("La fiche du groupe", Modifier.padding(padding)) }
}

/** Les choix de la sourdine : « Mettre en sourdine » (8 heures, 1 semaine, toujours), ou réactiver le son. */
@Composable
fun MuteChoicesDialog(engine: Engine, chatId: String, muted: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (muted) "Sourdine" else "Mettre en sourdine") },
        text = {
            Column {
                Text("Plus de notification ni de son ; les messages arrivent quand même.")
                if (muted) {
                    TextButton(onClick = {
                        engine.mute(chatId, 0.0)
                        onDismiss()
                    }) { Text("Réactiver le son") }
                } else {
                    for ((label, seconds) in listOf("8 heures" to 8.0 * 3600, "1 semaine" to 7.0 * 86400, "Toujours" to null)) {
                        TextButton(onClick = {
                            engine.mute(chatId, seconds)
                            onDismiss()
                        }) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/**
 * « Vider la discussion ? » : avec des messages importants, on peut les
 * garder (comme WhatsApp). `keepable` : la discussion a des messages
 * importants (`Engine.hasStarred`, demandé au toucher).
 */
@Composable
fun ClearChatDialog(engine: Engine, chatId: String, keepable: Boolean, onDismiss: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vider la discussion ?") },
        text = {
            Column {
                Text("Les messages seront effacés de vos appareils.")
                if (keepable) {
                    TextButton(onClick = {
                        engine.clearChat(chatId, keepStarred = true)
                        onDismiss()
                    }) { Text("Vider, garder les importants", color = red) }
                    TextButton(onClick = {
                        engine.clearChat(chatId)
                        onDismiss()
                    }) { Text("Tout vider", color = red) }
                } else {
                    TextButton(onClick = {
                        engine.clearChat(chatId)
                        onDismiss()
                    }) { Text("Vider", color = red) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Les fonds d'écran de Talka : (valeur, nom), les mêmes que le navigateur. */
val wallpapers = listOf("doodle" to "Talka", "plain" to "Uni", "mint" to "Menthe", "sky" to "Ciel", "rose" to "Rose", "night" to "Nuit", "sand" to "Sable")

/**
 * Le choix d'un fond d'écran : un des fonds de Talka, ou une photo de la
 * galerie (réduite, gardée sur l'appareil en adresse `data:`, jamais
 * envoyée) — la même valeur que dans le navigateur.
 */
@Composable
fun WallpaperRows(value: String, onChange: (String) -> Unit, allowDefault: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val isPhoto = value.startsWith("data:")
    val current = when {
        isPhoto -> "Ma photo"
        value.isEmpty() -> if (allowDefault) "Par défaut" else "Talka"
        else -> wallpapers.firstOrNull { it.first == value }?.second ?: "Talka"
    }
    val pick = rememberPhotoPicker { bitmap: Bitmap -> Images.compressed(bitmap, 1280, 0.78f)?.let { onChange(it.first) } }
    Box {
        ListItem(
            headlineContent = { Text("Fond d'écran") },
            leadingContent = { Icon(Icons.Filled.Palette, null) },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isPhoto) {
                        Images.fromDataURL(value)?.let {
                            Image(it, null, Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                        }
                    }
                    Text(current, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.clickable { open = true }.semantics { contentDescription = "Fond d'écran : $current" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val choices = (if (allowDefault) listOf("" to "Par défaut") else emptyList()) + wallpapers
            for ((v, label) in choices) {
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(selected = !isPhoto && v == value, onClick = null) },
                    onClick = {
                        open = false
                        onChange(v)
                    },
                )
            }
            if (isPhoto) {
                DropdownMenuItem(text = { Text("Ma photo") }, leadingIcon = { RadioButton(selected = true, onClick = null) }, onClick = { open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (isPhoto) "Changer de photo…" else "Une photo de la galerie…") },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                onClick = {
                    open = false
                    pick()
                },
            )
        }
    }
}
