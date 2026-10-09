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
import io.github.pedrubik2000.kumapie.lang.DictEntry
import io.github.pedrubik2000.kumapie.lang.Headword
import io.github.pedrubik2000.kumapie.lang.JapaneseLookup
import io.github.pedrubik2000.kumapie.lang.Tap
import io.github.pedrubik2000.kumapie.lang.SensePick
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.i18n.tr

/**
 * The picked word in the dictionary, under the card's own meaning: every entry and sense (the first entries and
 * senses at first, "All meanings" for the rest), with examples. Offline from the Wiktionary file; Wiktionary online
 * (then cached) for words it doesn't have. The sense that fits the line's English ([SensePick]) is marked and shown
 * first as "In this line".
 */
@Composable
fun DictionaryPanel(library: Library, ctl: SceneController, picker: WordPicker) {
    val language = library.languages.of(ctl.lang)
    fun lemma(key: String?) = key?.let { ctl.words[it]?.lemma }?.takeIf { it.isNotBlank() }
    // A word in the card's definition alone; a subtitle word with its place in the line (Japanese scans from there).
    val tap = picker.selectedInDef?.let { Tap(it.text, 0, it.text.length, it.word, lemma(it.word)) }
        ?: run {
            val cue = ctl.scene.cues.getOrNull(picker.line) ?: return
            val segment = cue.segments.getOrNull(picker.seg) ?: return
            Tap(cue.text, cue.segments.take(picker.seg).sumOf { it.text.length }, segment.text.length, segment.word, lemma(segment.word))
        }
    val missing = language.missing
    var entries by remember(tap) { mutableStateOf<List<DictEntry>?>(null) }
    var headwords by remember(tap) { mutableStateOf<List<Headword>?>(null) }
    var all by remember(tap) { mutableStateOf(false) }
    LaunchedEffect(tap, missing) {
        if (missing != null) return@LaunchedEffect
        entries = language.entries(tap)
        headwords = runCatching { language.lookup(tap) }.getOrDefault(emptyList())
    }

    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp), color = Colors.dim.copy(alpha = 0.3f))
    val found = entries.orEmpty()
    // The entries' sense that fits the line's English (not for a word picked inside a definition).
    val fits = remember(found) { if (picker.selectedInDef != null) null else SensePick.best(found, SensePick.english(ctl.scene, picker.line)) }
    // Dictionary entries scroll here; the popup's cards scroll inside it (a second scroller fought with it).
    val scroll = if (found.isEmpty()) Modifier else Modifier.heightIn(max = if (headwords.isNullOrEmpty()) 260.dp else 460.dp).verticalScroll(rememberScrollState())
    Column(scroll, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val hws = headwords
        when {
            missing != null -> Text(missing, color = Colors.dim, fontSize = 14.sp)
            hws == null -> Text(tr("Looking it up…"), color = Colors.dim, fontSize = 14.sp)
            hws.isNotEmpty() -> {
                // "In this line": the sense that fits the line's English, from the entries, else from the bilingual meanings.
                val fit = remember(hws) {
                    if (found.isNotEmpty()) fits?.let { (i, j) -> found[i].senses[j].gloss } else {
                        val meanings = hws.first().terms.flatMap { it.glossaries }
                            .filter { library.yomitan.groupOf(ctl.lang, it.dict).contains("bilingual", ignoreCase = true) }
                            .flatMap { g -> g.senses.flatMap { JapaneseLookup.bilingualMeanings(it) } }.distinct()
                        SensePick.bestMeaning(meanings, SensePick.english(ctl.scene, picker.line))?.let { meanings[it] }
                    }
                }
                fit?.let { Text(tr("In this line: %s", it), color = Colors.accent, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                YomitanPopup(library, ctl.lang, hws, onSpeak = language::say, compact = true)
            }
            found.isEmpty() -> Text(
                if (library.yomitan.of(ctl.lang).any { it.enabled && it.terms > 0 }) tr("Not in the dictionary.")
                else tr("Download the recommended %s dictionaries in Settings to see meanings here.", ctl.lang.displayName),
                color = Colors.dim, fontSize = 14.sp,
            )
            else -> {
                fits?.let { (i, j) ->
                    Text(tr("In this line: %s", found[i].senses[j].gloss), color = Colors.accent, fontSize = 15.sp)
                }
                val shown = if (all) found else found.take(2)
                shown.forEachIndexed { i, e -> Entry(e, all, fits?.takeIf { it.first == i }?.second) }
                val more = found.size > shown.size || (!all && shown.any { it.senses.size > 3 })
                if (more) TextButton(onClick = { all = true }) { Text(tr("All meanings (%d)", found.sumOf { it.senses.size }), fontSize = 14.sp) }
                if (found.any { it.online }) Text(tr("From Wiktionary online, saved on this device."), color = Colors.dim, fontSize = 12.sp)
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
