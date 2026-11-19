package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.KnownWords
import io.github.pedrubik2000.kumapie.lang.Miner
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.lang.Progress
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One form to rate: its [key] in the language's card index and a [label] (Japanese rows: "気にしない [きにしない]"; none
 * for the one German row). Only a form with a card of its own shows (the TV alike: Pedro, 2026-11-19).
 */
class RateForm(val key: String, val label: String?)

/**
 * A rating, its "Answered: …" line, and where it was made ([at]: the player's line, null elsewhere). The rated word's
 * own card takes that scene when you leave it ([recontext], kumapie_plan.md 37b).
 */
class Rating(val rated: KnownWords.Rated, val label: String, val at: Miner.Request?)

/** The ratings per form key. */
typealias Ratings = MutableMap<String, Rating>

/** Leaving a scene (or the player) you rated words in: each rated word's own card takes the scene it was rated in. */
suspend fun recontext(library: Library, ratings: Collection<Rating>) {
    for (r in ratings) {
        val at = r.at ?: continue
        runCatching { library.miner.recontext(r.rated.notes, at) }.onFailure { android.util.Log.w("kumapie", "recontext: $it") }
    }
}

/**
 * Again / Hard / Good / Easy for each form's own cards (kumapie's store): one rating answers every card of the form due
 * today, else its first (an early review); time = rows shown -> button. A form without a card of its own has no row
 * (Pedro, 2026-11-19: as on the TV). Then Undo and today's total of cards reviewed from episodes. While a rating or the
 * player's read runs ([busy], in [scope], which outlives the rows) no button works.
 */
@Composable
fun RatingRows(known: KnownWords, prefs: android.content.SharedPreferences, forms: List<RateForm>, ratings: Ratings,
               scope: CoroutineScope, busy: MutableState<Boolean>,
               /** The player's line ([Rating.at]); null in the dictionary screen. */
               at: Miner.Request? = null,
               /** Where it was rated (kumapie's own cards keep it); null in the dictionary screen. */
               watch: io.github.pedrubik2000.kumapie.lang.Srs.Watch? = null,
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
        // Read each time (a map lookup): a card mined meanwhile (the Mine sheet reloads the index and recolours the
        // words, which redraws this) shows at once instead of "No card yet". [tick] redraws after a rating.
        @Suppress("UNUSED_EXPRESSION") tick
        val state = known.cardIndex.state(f.key)
        if (state != null && state != "u") Column(Modifier.padding(top = 6.dp)) {
            f.label?.let {
                Text(it, fontSize = 15.sp, color = when (state) { "d" -> Colors.due; "k" -> Colors.text; "b" -> Colors.mined; else -> Colors.unknown })
            }
            val r = ratings[f.key]?.rated
            val answered = ratings[f.key]?.label.orEmpty()
            // Filled and the card's whole width (user, 2026-10-13): red, gray, green, blue.
            if (r == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Again" to Colors.unknown, "Hard" to Color(0xFF8A8696), "Good" to Colors.due, "Easy" to Colors.mined).forEachIndexed { i, (label, color) ->
                    Button(enabled = !busy.value, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF1E1B26)), onClick = {
                        rating {
                            val done = known.rate(f.key, i + 1, System.currentTimeMillis() - opened, watch)
                            if (done != null) { ratings[f.key] = Rating(done, tr("Answered: %s", tr(label)), at); count(done.cards); said.remove(f.key) }
                            else said[f.key] = tr("The rating didn't go in.")
                        }
                    }) { Text(tr(label), fontSize = 14.sp, maxLines = 1) }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(answered, color = Colors.dim, fontSize = 14.sp)
                TextButton(enabled = !busy.value, onClick = {
                    rating {
                        if (known.undo(r)) { count(-r.cards); ratings.remove(f.key); said.remove(f.key) }
                        else said[f.key] = tr("Couldn't undo it (something else was done since?).")
                    }
                }) { Text(tr("Undo")) }
            }
            said[f.key]?.let { Text(it, color = Colors.dim, fontSize = 13.sp) }
        }
    }
    if (today > 0) Text(tr("Reviewed from episodes today: %d", today), color = Colors.dim, fontSize = 13.sp)
}

/** Every word of the player's episode coloured by the index again (cheap: a few hundred words; shared with the TV). */
fun repaintWords(ctl: io.github.pedrubik2000.kumapie.player.SceneController, known: KnownWords) = known.repaint(ctl, pcColours = false)
