// L'onglet Appels et l'écran d'un appel : le portage de `Calls.swift`, À
// FAIRE (sauf `ContactPicker`, dont « Nouvelle discussion » a besoin).

package talka.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import talka.app.LocalAppModel
import talka.app.call.CallEngine
import talka.kit.TalkaCode
import java.text.Collator
import java.util.Locale

/** L'onglet Appels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsTab() {
    Scaffold(topBar = { TopAppBar(title = { Text("Appels") }) }) { padding -> ComingSoon("Le journal des appels", Modifier.padding(padding)) }
}

/** L'appel en cours, en plein écran (par-dessus tout le reste). */
@Composable
fun CallScreen(call: CallEngine) {
    val e = call.engine
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.inverseSurface) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            EngineAvatar(e, call.first, 96.dp)
            Text(e.displayName(call.first), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.inverseOnSurface)
            Text(call.status.ifEmpty { "Appel" }, color = MaterialTheme.colorScheme.inverseOnSurface)
            Text("Les appels arrivent bientôt sur Android.", color = MaterialTheme.colorScheme.inverseOnSurface)
            Button(onClick = { call.hangup() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.CallEnd, null)
                Text(if (call.incoming) "Refuser" else "Raccrocher", Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Choisir un contact (une discussion, un appel, un transfert, un partage de contact). */
@Composable
fun ContactPicker(title: String, onDismiss: () -> Unit, header: @Composable () -> Unit = {}, pick: (String) -> Unit) {
    val model = LocalAppModel.current
    model.revision
    val e = model.engine ?: return
    var query by remember { mutableStateOf("") }
    val collator = remember { Collator.getInstance(Locale.FRANCE).apply { strength = Collator.PRIMARY } }
    val list = e.contacts.values
        .filter { it.saved && !it.blocked }
        .filter { query.isEmpty() || e.displayName(it.code).contains(query, ignoreCase = true) || it.code.contains(query.uppercase()) }
        .sortedWith { a, b -> collator.compare(e.displayName(a.code), e.displayName(b.code)) }
    Sheet(title, onDismiss) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { header() }
            item { SectionHeader("Contacts") }
            if (list.isEmpty()) {
                item { Text("Aucun contact", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(list, key = { it.code }) { c ->
                ListItem(
                    leadingContent = { EngineAvatar(e, c.code, 40.dp) },
                    headlineContent = { Text(e.displayName(c.code)) },
                    supportingContent = { Text(TalkaCode.pretty(c.code), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.clickable { pick(c.code) },
                )
            }
        }
    }
}
