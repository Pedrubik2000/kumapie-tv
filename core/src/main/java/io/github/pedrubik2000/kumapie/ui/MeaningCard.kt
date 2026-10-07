package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.pedrubik2000.kumapie.data.LineDef
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlin.math.roundToInt

/**
 * The picked word's card, right above the word. With a per-line definition (definitions v2): dictionary form,
 * the word (if different), English, the German definition with its words coloured (first when all are known),
 * grammar, status. Without one: the short meaning, as before.
 *
 * Touch: [onTapDef] gets the segment of a word tapped in the German definition; [footer] goes at the bottom
 * (the phone's buttons). [maxWidth] keeps the card narrower on small screens.
 */
@Composable
fun MeaningCard(
    ctl: SceneController,
    picker: WordPicker,
    anchor: Rect,
    maxWidth: androidx.compose.ui.unit.Dp = 720.dp,
    onTapDef: ((Int) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    /** The episode's own meaning of the word (and its German definition); off when the footer has the dictionaries. */
    ownMeaning: Boolean = true,
) {
    val segment = picker.selected ?: return
    val key = segment.word ?: return
    val word = ctl.words[key] ?: Word("u", "")
    val def = picker.definition
    AboveAnchor(anchor) {
        Column(
            Modifier.widthIn(max = maxWidth).background(Colors.surface, RoundedCornerShape(12.dp))
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .pointerInput(Unit) { detectTapGestures { } } // taps on the card stay on the card
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                !ownMeaning -> Text(segment.text, color = Colors.text, fontSize = 26.sp)
                def != null -> DefinitionParts(ctl, picker, segment.text, def, onTapDef)
                else -> ShortMeaning(ctl, key, segment.text, word)
            }
            val (status, color) = statusLine(word)
            Text(status + if (word.lookups > 0) "  ·  looked up ${word.lookups}×" else "", color = color, fontSize = 15.sp)
            // The word picked inside the German definition: its own short meaning and status.
            picker.selectedInDef?.word?.let { inner ->
                val w = ctl.words[inner] ?: Word("u", "")
                val (s, c) = statusLine(w)
                Text("${picker.selectedInDef?.text}: " + (w.meaning.substringAfter(" = ").ifBlank { "no meaning yet" }) + "  ·  $s",
                    color = c, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun DefinitionParts(ctl: SceneController, picker: WordPicker, written: String, def: LineDef, onTapDef: ((Int) -> Unit)?) {
    val heading = def.lemma.ifBlank { written }
    Text(heading, color = Colors.text, fontSize = 26.sp)
    if (!heading.equals(written, ignoreCase = true)) Text(written, color = Colors.dim, fontSize = 18.sp)
    val germanKnown = def.target.all { s -> s.word == null || (ctl.words[s.word]?.status ?: "u") == "k" }
    val german = coloredDefinition(def, ctl.words, if (picker.inDef) picker.defSeg else -1)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tappable = if (onTapDef == null) Modifier else Modifier.pointerInput(def) {
        detectTapGestures { p -> layout?.let { segmentAt(it, p, def) }?.let(onTapDef) }
    }
    if (germanKnown) {
        Text(german, fontSize = 20.sp, onTextLayout = { layout = it }, modifier = Modifier.padding(top = 4.dp).then(tappable))
        Text(def.english, color = Colors.dim, fontSize = 17.sp)
    } else {
        Text(def.english, color = Colors.text, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
        Text(german, fontSize = 17.sp, onTextLayout = { layout = it }, modifier = tappable)
    }
    if (def.grammar.isNotBlank()) Text(def.grammar, color = Colors.dim, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun ShortMeaning(ctl: SceneController, key: String, written: String, word: Word) {
    val meaning = withoutSameWord(ctl.scene.meanings[key] ?: word.meaning, written)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (meaning.isNotBlank()) {
            Text(meaning, color = Colors.text, fontSize = 22.sp)
        } else {
            Text(written, color = Colors.text, fontSize = 22.sp)
            Text("no meaning yet", color = Colors.dim, fontSize = 15.sp)
        }
        if (word.lemma.isNotBlank() && word.lemma != key && !word.lemma.equals(written, ignoreCase = true)) {
            Text("dictionary form: ${word.lemma}", color = Colors.dim, fontSize = 15.sp)
        }
    }
}

/** The segment of the definition whose word is under [p], or null. */
private fun segmentAt(l: TextLayoutResult, p: Offset, def: LineDef): Int? {
    var at = 0
    def.target.forEachIndexed { i, seg ->
        val range = at until at + seg.text.length
        at += seg.text.length
        if (seg.word != null && range.any { c -> l.getBoundingBox(c).inflate(6f).contains(p) }) return i
    }
    return null
}

/** The German definition, its words coloured like the subtitles (red new, orange learning), [selected] highlighted. */
private fun coloredDefinition(def: LineDef, words: Map<String, Word>, selected: Int): AnnotatedString = buildAnnotatedString {
    def.target.forEachIndexed { i, seg ->
        val color = when (seg.word?.let { words[it]?.status ?: "u" }) {
            "u" -> Colors.unknown
            "l" -> Colors.learning
            else -> Colors.text
        }
        withStyle(if (i == selected) SpanStyle(color = Color.Black, background = color) else SpanStyle(color = color)) {
            append(seg.text)
        }
    }
}

/**
 * "echt = really" under a highlighted "echt" says the word twice: show "really". The "word =" part stays when
 * it differs from the selected word (a split verb: "sehen aus = to look" while "sehen" is selected).
 */
private fun withoutSameWord(meaning: String, selected: String): String {
    val head = meaning.substringBefore(" = ", missingDelimiterValue = "")
    return if (head.isNotEmpty() && head.equals(selected, ignoreCase = true)) meaning.substringAfter(" = ") else meaning
}

private fun statusLine(w: Word) = when {
    w.marked -> "marked known" to Colors.dim
    w.status == "k" -> (w.stability?.let { "known · ${days(it)}" } ?: "known") to Colors.dim
    w.status == "l" -> "learning · ${w.stability?.let(::days) ?: "in Anki"}" to Colors.learning
    else -> "never studied" to Colors.unknown
}

private fun days(d: Double) = if (d < 1) "under a day" else "${d.roundToInt()} day${if (d.roundToInt() == 1) "" else "s"}"

/** Places [content] centred above [anchor] (screen coordinates), 10 dp up, kept inside the screen. */
@Composable
private fun AboveAnchor(anchor: Rect, content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val margin = with(LocalDensity.current) { 24.dp.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        // At most the screen's height (a phone in landscape): the card scrolls, so its buttons stay reachable.
        val card = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth - 2 * margin,
            maxHeight = (constraints.maxHeight - 2 * margin).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (anchor.center.x - card.width / 2f).roundToInt()
                .coerceIn(margin, (constraints.maxWidth - margin - card.width).coerceAtLeast(margin))
            val y = (anchor.top - gap - card.height).roundToInt().coerceAtLeast(margin)
            card.place(x, y)
        }
    }
}
