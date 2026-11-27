// Une discussion : les bulles, la saisie, les réponses, les réactions…
// Le portage de `Conversation.swift` (avec `Capsule.swift`, `Voice.swift`,
// `Brocoli.swift`), À FAIRE.
//
// L'onglet Discussions l'ouvre pour `model.path.last` ; le retour (flèche
// ou geste) enlève la discussion de `model.path`.

package talka.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import talka.app.LocalAppModel
import talka.app.LocalWide

@Composable
fun ConversationView(chatId: String) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    val title = e.chat(chatId)?.let { e.title(it) } ?: e.displayName(chatId)
    DisposableEffect(chatId) {
        e.activeChat = chatId
        e.markRead(chatId)
        Notifier.cancel(chatId)
        onDispose { if (e.activeChat == chatId) e.activeChat = null }
    }
    // Sur une fenêtre large, la discussion est à droite de la liste : pas de retour.
    val back = { model.path = model.path.dropLast(1) }
    if (LocalWide.current) {
        ComingSoon(title)
    } else {
        Page(title, onBack = back) { padding -> ComingSoon("La discussion", Modifier.padding(padding)) }
    }
}
