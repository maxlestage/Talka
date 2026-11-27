// Talka sur la montre : les discussions récentes du téléphone, lues,
// répondues (vocal, dictée ou clavier, réponses rapides, emoji) et marquées
// comme lues depuis le poignet. Le téléphone reste l'appareil Talka : la
// montre passe par lui. (`TalkaWatchApp.swift` de l'Apple Watch.)

package talka.wear

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import talka.kit.WidgetShare
import talka.link.FileWidgetStorage

class WearApp : Application() {
    /** Le modèle, un seul pour tout le processus (le `@State` de l'`App` de la montre). */
    val model: WatchModel by lazy {
        WatchModel(
            WearDataLayer(this),
            getSharedPreferences("talka.montre", Context.MODE_PRIVATE),
            reloadComplications = { reloadComplications(this) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Ce que lisent les complications : branché dès le lancement, car le
        // cadran peut les demander sans que Talka ait été ouvert.
        if (WidgetShare.storage == null) WidgetShare.storage = FileWidgetStorage(this)
    }
}

/** Les complications de Talka sont redemandées par le cadran. */
fun reloadComplications(context: Context) {
    try {
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, UnreadComplicationService::class.java))
            .requestUpdateAll()
    } catch (e: RuntimeException) {
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = (application as WearApp).model
        setContent { TalkaWatchApp(model) }
    }
}

/** Les couleurs de Talka, celles de l'Apple Watch. */
object WatchColors {
    val talkaGreen = Color(0xFF00A884)
    val tickBlue = Color(0xFF53BDEB)
    val bubbleOut = Color(0xFF005C4B)
    val bubbleIn = Color(0xFF202C33)
    val secondary = Color(0xFFAEB4B8)
    val failed = Color(0xFFFF453A)
}
