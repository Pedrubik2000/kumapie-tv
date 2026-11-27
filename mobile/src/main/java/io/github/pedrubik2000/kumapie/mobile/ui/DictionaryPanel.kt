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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.lang.DictEntry
import io.github.pedrubik2000.kumapie.lang.Headword
import io.github.pedrubik2000.kumapie.lang.Tap
import io.github.pedrubik2000.kumapie.lang.SensePick
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.WordStatus
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.compose.ui.text.withStyle

/**
 * The picked word in the dictionary, under the card's own meaning: every entry and sense (the first entries and
 * senses at first, "All meanings" for the rest), with examples. Offline from the Wiktionary file; Wiktionary online
 * (then cached) for words it doesn't have. The sense that fits the line's English ([SensePick]) is marked and shown
 * first as "In this line".
 */
@Composable
fun DictionaryPanel(library: Library, ctl: SceneController, picker: WordPicker, modifier: Modifier = Modifier,
                    /** Landscape: the ratings and buttons, beside the dictionaries' cards ([YomitanPopup]'s side). */
                    side: (@Composable () -> Unit)? = null) {
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
    var picked by remember(tap) { mutableIntStateOf(0) }
    LaunchedEffect(tap, missing, picked) {
        if (missing != null) return@LaunchedEffect
        val t0 = android.os.SystemClock.uptimeMillis()
        entries = language.entries(tap)
        val t1 = android.os.SystemClock.uptimeMillis()
        headwords = runCatching {
            io.github.pedrubik2000.kumapie.lang.WordCard.popup(language, tap, picker.selectedInDef == null, tap.key?.let { ctl.words[it]?.reading }.orEmpty())
        }.getOrDefault(emptyList())
        android.util.Log.i("kumapie", "lookup ${tap.word}: entries ${t1 - t0} ms, dictionaries ${android.os.SystemClock.uptimeMillis() - t1} ms")
    }

    if (headwords.isNullOrEmpty()) HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp), color = Colors.dim.copy(alpha = 0.3f))
    val found = entries.orEmpty()
    // The entries' sense that fits the line's English (not for a word picked inside a definition).
    val fits = remember(found) { if (picker.selectedInDef != null) null else SensePick.best(found, SensePick.english(ctl.scene, picker.line)) }
    // Dictionary entries scroll here; the popup's cards scroll inside it (a second scroller fought with it).
    val scroll = if (found.isEmpty() || !headwords.isNullOrEmpty()) Modifier else Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())
    Column(modifier.then(scroll), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // The tapped line, quoted with the word in bold: on a phone the card covers the subtitles (user, 2026-10-14).
        if (picker.selectedInDef == null) ctl.scene.cues.getOrNull(picker.line)?.let { cue ->
            Text(androidx.compose.ui.text.buildAnnotatedString {
                cue.segments.forEachIndexed { i, s ->
                    if (i == picker.seg) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Colors.text)) { append(s.text) }
                    else append(s.text)
                }
            }, color = Colors.dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 36.dp, bottom = 4.dp)) // clear of the card's ×
        }
        val hws = headwords
        val first = hws?.firstOrNull()?.takeIf { language is io.github.pedrubik2000.kumapie.lang.Japanese && picker.selectedInDef == null }
        val readings by produceState(emptyList<String>(), first) {
            value = first?.let { h -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (language as io.github.pedrubik2000.kumapie.lang.Japanese).readings(h.expression) } }.orEmpty()
        }
        if (first != null && readings.size > 1) TextButton(onClick = {
            (language as io.github.pedrubik2000.kumapie.lang.Japanese).nextReading(first.expression, first.reading)
            picked++
        }) { Text(tr("%1\$s · %2\$d readings", first.reading, readings.size), fontSize = 14.sp) }
        when {
            missing != null -> Text(missing, color = Colors.dim, fontSize = 14.sp)
            hws == null -> Text(tr("Looking it up…"), color = Colors.dim, fontSize = 14.sp)
            hws.isNotEmpty() -> YomitanPopup(library, ctl.lang, hws, onSpeak = language::say, compact = true, fit = modifier != Modifier, side = side,
                status = picker.selected?.takeIf { picker.selectedInDef == null }?.let { s ->
                    { WordStatus(s.word?.let { ctl.words[it] } ?: Word("u", ""), s.text) }
                },
                sentence = if (language is io.github.pedrubik2000.kumapie.lang.Japanese && picker.selectedInDef == null)
                    ctl.scene.cues.getOrNull(picker.line)?.text else null,
                // Japanese: the sense that fits the line's English, as the mine sheet picks it (user, 2026-10-14).
                fitFor = if (language is io.github.pedrubik2000.kumapie.lang.Japanese && picker.selectedInDef == null)
                    ({ h -> fitsThisLine(library, h, SensePick.english(ctl.scene, picker.line)) }) else null)
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
            }
        }
    }
    // No Yomitan cards to sit beside: the ratings and buttons go under.
    if (headwords.isNullOrEmpty()) side?.invoke()
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
