// Les appareils du compte (v4) et le QR code (le montrer, le scanner) : le
// portage de `Devices.swift` (et de `QR` / `QRScanner`), À FAIRE.

package talka.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Les appareils liés au compte (Réglages → Appareils). */
@Composable
fun LinkedDevicesView(onBack: () -> Unit) {
    Page("Appareils liés", onBack) { padding -> ComingSoon("Les appareils du compte", Modifier.padding(padding)) }
}

/**
 * « J'ai déjà Talka sur un autre appareil » : ce nouvel appareil montre son
 * QR code (`model.makeDeviceLink()`), et attend que l'autre le lie ; une
 * fois le compte reçu, `model.finishLinking()`.
 */
@Composable
fun LinkWaitView(onDismiss: () -> Unit) {
    Sheet("Lier cet appareil", onDismiss) { padding -> ComingSoon("Lier cet appareil par QR code", Modifier.padding(padding)) }
}

/** Le QR code d'un texte (un lien d'invitation), sur fond blanc. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(16.dp))
            .semantics { contentDescription = "QR code de votre code Talka" },
        contentAlignment = Alignment.Center,
    ) { Text("QR code : bientôt", color = Color.Gray) }
}

/** Le lecteur de QR code (la caméra) : `onFound` reçoit le texte lu. */
@Composable
fun QRScanner(onFound: (String) -> Unit, onDismiss: () -> Unit) {
    Sheet("Scanner un QR code", onDismiss) { padding -> ComingSoon("Le lecteur de QR code", Modifier.padding(padding)) }
}
