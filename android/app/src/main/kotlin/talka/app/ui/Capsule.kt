// La capsule temporelle : un message scellé jusqu'à une heure choisie. Il
// part tout de suite, mais le destinataire ne voit qu'une enveloppe et un
// compte à rebours ; à l'heure dite, il s'ouvre de lui-même. Le même champ
// (`open_at`) que dans le navigateur. Le portage de `Capsule.swift`.

package talka.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import talka.app.TalkaColors
import talka.kit.Message
import talka.kit.capsuleMaxMs
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** « dans 2 j 4 h », « dans 3 h 05 », « dans 12 min », « dans 40 s ». */
fun capsuleCountdown(openAt: Double, now: Double): String {
    val s = max(0, ceil((openAt - now) / 1000).toInt())
    val d = s / 86_400
    val h = s % 86_400 / 3600
    val m = s % 3600 / 60
    if (d > 0) return "dans $d j $h h"
    if (h > 0) return "dans $h h ${"%02d".format(m)}"
    if (m > 0) return "dans $m min"
    return "dans $s s"
}

/** « Demain à 09:00 », « Le 25 décembre 2026 à 09:00 ». */
fun capsuleOpenLine(openAt: Double): String = Dates.`when`(openAt).replaceFirstChar { it.uppercase() } + " à " + Dates.clock(openAt)

/** L'enveloppe d'une capsule encore scellée. */
@Composable
fun SealedCapsule(openAt: Double, now: Double) {
    val breathe = rememberInfiniteTransition(label = "capsule")
    val t by breathe.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "souffle")
    val countdown = capsuleCountdown(openAt, now)
    Column(
        Modifier
            .widthIn(min = 200.dp)
            .padding(start = 14.dp, end = 14.dp, top = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("✉️", fontSize = 40.sp, modifier = Modifier.scale(1f + 0.08f * t).rotate(-4f * t).clearAndSetSemantics {})
        Text("Capsule temporelle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("S'ouvre $countdown", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TalkaColors.green)
        Text(capsuleOpenLine(openAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Le bandeau d'une capsule : pour l'auteur, quand elle s'ouvrira ; une fois
 * ouverte, quand elle a été écrite.
 */
@Composable
fun CapsuleBand(m: Message, now: Double) {
    val openAt = m.openAt ?: 0.0
    Text(
        if (openAt > now) {
            "⏳ Scellée · s'ouvre ${Dates.`when`(openAt)} à ${Dates.clock(openAt)}"
        } else {
            "⏳ Capsule écrite ${Dates.`when`(m.at)} · ouverte ${Dates.`when`(openAt)}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

object CapsuleTimes {
    private fun ms(t: LocalDateTime): Double = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble()

    /** Dans `days` jours, à `hour` heures pile. */
    fun at(days: Int, hour: Int): Double = ms(LocalDate.now().plusDays(days.toLong()).atTime(hour, 0))

    /** Le 1er janvier prochain, à minuit. */
    val newYear: Double get() = ms(LocalDate.of(LocalDate.now().year + 1, 1, 1).atStartOfDay())

    fun presets(now: Double = System.currentTimeMillis().toDouble()): List<Pair<String, Double>> =
        listOf(
            "Dans 1 heure" to now + 3_600_000,
            "Demain 9 h" to at(1, 9),
            "Dans une semaine" to at(7, 9),
            "Au Nouvel An" to newYear,
            "Dans un an" to at(365, 9),
        )
}

/** Écrire une capsule : le message, et quand elle s'ouvre. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsuleComposer(onDismiss: () -> Unit, send: (String, Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    var openAt by remember { mutableDoubleStateOf(CapsuleTimes.at(1, 9)) }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val now = System.currentTimeMillis().toDouble()
    val valid = text.trim().isNotEmpty() && openAt - now >= 60_000
    val day = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRANCE)
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochMilli(openAt.toLong()).atZone(zone)
    Sheet(
        "Capsule temporelle",
        onDismiss,
        actions = {
            TextButton(onClick = { send(text.trim(), openAt) }, enabled = valid) { Text("Sceller") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ce que vous voulez qu'on lise plus tard…") },
                minLines = 4,
                maxLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 120.dp).focusRequester(focus),
            )
            SectionFooter(
                "Un message scellé : il part maintenant, mais ne s'ouvre qu'à l'heure choisie. Jusque-là, on ne voit qu'une enveloppe et un compte à rebours.",
            )
            SectionHeader("S'ouvre")
            ListItem(
                headlineContent = { Text("Le") },
                leadingContent = { Icon(Icons.Filled.Event, null) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { pickingDay = true }) { Text(day.format(local)) }
                        TextButton(onClick = { pickingTime = true }) { Text(Dates.clock(openAt)) }
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((label, at) in CapsuleTimes.presets(now)) {
                    OutlinedButton(onClick = { openAt = at }) { Text(label, color = TalkaColors.green) }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (x: IllegalStateException) {
        }
    }

    // Le jour : entre maintenant et dans dix ans (la limite des capsules).
    if (pickingDay) {
        val first = LocalDate.now(zone)
        val last = Instant.ofEpochMilli((now + capsuleMaxMs).toLong()).atZone(zone).toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = first.year..last.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isBefore(first) && !d.isAfter(last)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDay = false
                    state.selectedDateMillis?.let { utc ->
                        val d = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        openAt = d.atTime(local.toLocalTime()).atZone(zone).toInstant().toEpochMilli().toDouble()
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDay = false }) { Text("Annuler") } },
        ) { DatePicker(state) }
    }
    if (pickingTime) {
        val state = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingTime = false
                    openAt = local.toLocalDate().atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant().toEpochMilli().toDouble()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Annuler") } },
            text = { TimePicker(state) },
        )
    }
}
