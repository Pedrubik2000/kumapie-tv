package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.mobile.lang.DictEntry
import io.github.pedrubik2000.kumapie.mobile.lang.SensePick
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors

/**
 * The picked word in the dictionary, under the card's own meaning: every entry and sense (the first entries and
 * senses at first, "All meanings" for the rest), with examples. Offline from the Wiktionary file; Wiktionary online
 * (then cached) for words it doesn't have. The sense that fits the line's English ([SensePick]) is marked and shown
 * first as "In this line".
 */
@Composable
fun DictionaryPanel(library: Library, ctl: SceneController, picker: WordPicker) {
    val segment = picker.selectedInDef ?: picker.selected ?: return
    val key = segment.word
    val lemma = key?.let { ctl.words[it]?.lemma }?.takeIf { it.isNotBlank() }
    var entries by remember(segment.text, key) { mutableStateOf<List<DictEntry>?>(null) }
    var all by remember(segment.text, key) { mutableStateOf(false) }
    LaunchedEffect(segment.text, key) { entries = library.dictionary.lookup(segment.text, key, lemma) }

    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp), color = Colors.dim.copy(alpha = 0.3f))
    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val found = entries
        when {
            found == null -> Text("Looking it up…", color = Colors.dim, fontSize = 14.sp)
            found.isEmpty() -> Text(
                if (library.dictionary.isReady) "Not in the dictionary." else "Download the dictionary in Settings to see meanings here.",
                color = Colors.dim, fontSize = 14.sp,
            )
            else -> {
                val fits = remember(found) { if (picker.selectedInDef != null) null else SensePick.best(found, SensePick.english(ctl.scene, picker.line)) }
                fits?.let { (i, j) ->
                    Text("In this line: " + found[i].senses[j].gloss, color = Colors.accent, fontSize = 15.sp)
                }
                val shown = if (all) found else found.take(2)
                shown.forEachIndexed { i, e -> Entry(e, all, fits?.takeIf { it.first == i }?.second) }
                val more = found.size > shown.size || (!all && shown.any { it.senses.size > 3 })
                if (more) TextButton(onClick = { all = true }) { Text("All meanings (${found.sumOf { it.senses.size }})", fontSize = 14.sp) }
                if (found.any { it.online }) Text("From Wiktionary online, saved on this device.", color = Colors.dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Entry(e: DictEntry, all: Boolean, fitting: Int?) {
    Text("${e.word}  ·  ${e.pos}" + if (e.ipa.isNotBlank()) "  ·  ${e.ipa}" else "", color = Colors.accent, fontSize = 15.sp,
        modifier = Modifier.padding(top = 4.dp))
    if (e.head.isNotBlank() && e.head != e.word) {
        Text(e.head, color = Colors.dim, fontSize = 13.sp, maxLines = if (all) 4 else 1, overflow = TextOverflow.Ellipsis)
    }
    val senses = if (all) e.senses else e.senses.take(3)
    senses.forEachIndexed { i, s ->
        Text("${i + 1}. ${s.gloss}" + (if (s.tags.isNotBlank()) "  (${s.tags})" else "") + if (i == fitting) "  ✓" else "",
            color = if (i == fitting) Colors.accent else Colors.text, fontSize = 15.sp)
        (if (all) s.examples else s.examples.take(1)).forEach { (de, en) ->
            Text("$de  — $en", color = Colors.dim, fontSize = 13.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 14.dp))
        }
    }
}
