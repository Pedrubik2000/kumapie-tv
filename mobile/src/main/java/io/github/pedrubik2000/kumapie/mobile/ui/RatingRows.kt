package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.KnownWords
import io.github.pedrubik2000.kumapie.mobile.offline.Progress
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One form to rate: its [key] in the language's card index, a [label] (Japanese rows: "気にしない [きにしない]"; none for
 * the one German row), and [make]: makes its card when it has none (answers the line to show), null when it can't.
 */
class RateForm(val key: String, val label: String?, val make: (suspend (progress: (String) -> Unit) -> String)?)

/** A rating and its "Answered: …" line, per form key. */
typealias Ratings = MutableMap<String, Pair<KnownWords.Rated, String>>

/**
 * Again / Hard / Good / Easy for each form's own kuma3 cards (kumapie_anki_review_plan.md): one rating answers every
 * card of the form kuma3 shows today, else its first (an early review); time = rows shown -> button. A form without a
 * card of its own gets its card made first ([RateForm.make]), then the rating. Then Undo (kuma3's; a card it made
 * stays, new) and today's total of cards reviewed from episodes. kuma3's queue is not read again here: reading it
 * selects decks, which replaces kuma3's Undo ("Select Deck"); the player reads it when you leave a scene you rated in.
 * While a rating or the player's read runs ([busy], in [scope], which outlives the rows) no button works.
 */
@Composable
fun RatingRows(known: KnownWords, prefs: android.content.SharedPreferences, forms: List<RateForm>, ratings: Ratings,
               scope: CoroutineScope, busy: MutableState<Boolean>,
               /** Called after the index changed (the player recolours its words). */
               repaint: () -> Unit = {}) {
    if (known.cardIndex.due == null) return // an Anki without kuma3/due: no rows
    val day = "rated_" + Progress.studyDay()
    val opened = remember(forms.map { it.key }) { System.currentTimeMillis() }
    // The index isn't observable: read again after each change.
    var tick by remember { mutableIntStateOf(0) }
    val said = remember { mutableStateMapOf<String, String>() }
    var today by remember { mutableStateOf(prefs.getInt(day, 0)) }
    fun count(n: Int) { today = prefs.getInt(day, 0) + n; prefs.edit().putInt(day, today).apply() }
    fun rating(step: suspend () -> Unit) {
        busy.value = true
        scope.launch {
            try {
                step()
                repaint(); tick++
            } finally {
                busy.value = false
            }
        }
    }

    for (f in forms) key(f.key) {
        val state = remember(tick, f.key) { known.cardIndex.state(f.key) }
        if (state != null && (state != "u" || f.make != null)) Column(Modifier.padding(top = 6.dp)) {
            f.label?.let {
                Text(it, fontSize = 15.sp, color = when (state) { "d" -> Colors.due; "k" -> Colors.text; else -> Colors.unknown })
            }
            val (r, answered) = ratings[f.key] ?: (null to "")
            if (r == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Again" to Colors.unknown, "Hard" to Colors.learning, "Good" to Colors.due, "Easy" to Colors.accent).forEachIndexed { i, (label, color) ->
                    OutlinedButton(enabled = !busy.value, contentPadding = PaddingValues(horizontal = 10.dp), onClick = {
                        rating {
                            if (known.cardIndex.state(f.key) == "u") {
                                val made = runCatching { f.make!! { said[f.key] = it } }.onFailure { said[f.key] = it.message ?: it.toString() }.isSuccess
                                if (!made) return@rating
                                known.reloadCards()
                            }
                            val done = known.rate(f.key, i + 1, System.currentTimeMillis() - opened)
                            if (done != null) { ratings[f.key] = done to tr("Answered: %s", tr(label)); count(done.cards); said.remove(f.key) }
                            else said[f.key] = tr("kuma3 didn't take the rating.")
                        }
                    }) { Text(tr(label), color = color, fontSize = 14.sp) }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(answered, color = Colors.dim, fontSize = 14.sp)
                TextButton(enabled = !busy.value, onClick = {
                    rating {
                        if (known.undo(r)) { count(-r.cards); ratings.remove(f.key); said.remove(f.key) }
                        else said[f.key] = tr("Can't undo: something else was done in kuma3 since.")
                    }
                }) { Text(tr("Undo")) }
            }
            said[f.key]?.let { Text(it, color = Colors.dim, fontSize = 13.sp) }
        }
    }
    if (busy.value) Text(tr("Updating kuma3's queue…"), color = Colors.dim, fontSize = 13.sp)
    if (today > 0) Text(tr("Reviewed from episodes today: %d", today), color = Colors.dim, fontSize = 13.sp)
}

/** Every word of the player's episode coloured by the index again (cheap: a few hundred words). */
fun repaintWords(ctl: io.github.pedrubik2000.kumapie.player.SceneController, known: KnownWords) {
    for (k in ctl.words.keys.toList()) ctl.words[k]?.let { w -> known.paint(k, w).let { n -> if (n != w) ctl.words[k] = n } }
}
