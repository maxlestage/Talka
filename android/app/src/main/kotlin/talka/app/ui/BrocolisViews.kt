// Les clés Brocolis 🥦 : le portage de `BrocolisViews.swift`, À FAIRE.

package talka.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Réglages → Clés Brocolis 🥦. */
@Composable
fun BrocolisKeysView(onBack: () -> Unit) {
    Page("Clés Brocolis 🥦", onBack) { padding -> ComingSoon("Les clés Brocolis", Modifier.padding(padding)) }
}
