package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker

/** Subtitle sizes to try, largest first; English is set at ENGLISH_SCALE of the German. */
private val SIZES = listOf(34, 31, 28, 25, 22, 20, 18)
private const val ENGLISH_SCALE = 0.72f

/**
 * While playing: the line being said. Paused: every line of the scene (the current one bright), then the
 * English as one block. The largest size that fits is used: a third of the screen while playing, two thirds
 * while paused (the video is stopped then), the smallest size if nothing fits.
 * Unknown words red, learning words orange.
 *
 * With the word picker open: every line of the scene, German readable (whatever the subtitle mode), the
 * picker's line bright and its word highlighted; [onAnchor] gets that word's place on screen.
 *
 * Touch (phone/tablet): [onTap] hears a tapped word as [SubtitleTap.Word] (only while the German is readable)
 * and any other tap on the subtitle box as [SubtitleTap.Box]. Without it (the TV) taps do nothing.
 */
@Composable
fun Subtitles(ctl: SceneController, picker: WordPicker, onAnchor: (Rect?) -> Unit, onTap: ((SubtitleTap) -> Unit)? = null) {
    val picking = picker.isOpen
    if (!picking && !ctl.showTarget && !ctl.showEnglish) {
        SideEffect { onAnchor(null) }
        return
    }
    val scene = ctl.scene
    val current = if (picking) scene.cues.getOrNull(picker.line) else ctl.currentCue()
    val pos = ctl.position
    // A novel's scene starts with the game's transition into the line (fades, wipes, waits): no text box yet.
    if (ctl.episode.novel && !picking &&scene.cues.firstOrNull()?.let { pos < it.start - 0.05 } == true) {
        SideEffect { onAnchor(null) }
        return
    }
    // A novel's scene is one line: shown whole from its text on (no flicker while the voice catches up).
    val paused = !ctl.playing || picking || ctl.episode.novel
    val german: List<Cue> = when {
        picking -> scene.cues
        !ctl.showTarget -> emptyList()
        paused -> scene.cues
        current != null && pos <= current.end + 0.6 -> listOf(current)
        else -> emptyList()
    }
    val english = when {
        !ctl.showEnglish -> emptyList()
        paused -> scene.english
        else -> scene.english.filter { it.start - 0.1 <= pos && pos <= it.end + 0.6 }.takeLast(1)
    }
    if (german.isEmpty() && english.isEmpty()) {
        SideEffect { onAnchor(null) }
        return
    }

    // The German as one text; remember where the picker's word starts and ends in it.
    var selectedRange: IntRange? = null
    // Where each word is in the German text, for taps: character range -> (line, segment).
    val wordRanges = mutableListOf<Pair<IntRange, SubtitleTap.Word>>()
    val germanText = buildAnnotatedString {
        german.forEachIndexed { i, cue ->
            if (i > 0) append('\n')
            val lineIndex = scene.cues.indexOf(cue)
            val selectedSeg = if (picking && lineIndex == picker.line) picker.seg else -1
            val start = length
            var at = start
            cue.segments.forEachIndexed { s, seg ->
                if (seg.word != null) wordRanges += (at until at + seg.text.length) to SubtitleTap.Word(lineIndex, s)
                at += seg.text.length
            }
            append(colored(cue, ctl.words, dim = paused && cue != current, selected = selectedSeg))
            if (selectedSeg >= 0) {
                val before = cue.segments.take(selectedSeg).sumOf { it.text.length }
                val len = cue.segments[selectedSeg].text.length
                selectedRange = (start + before) until (start + before + len)
            }
        }
    }
    val englishText = english.joinToString(" ") { it.text }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val range by rememberUpdatedState(selectedRange)
    val ranges by rememberUpdatedState(wordRanges.toList())
    val tapHandler by rememberUpdatedState(onTap)
    val blurred by rememberUpdatedState(ctl.blurTarget && !picking)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padH = 28.dp
        val padV = 14.dp
        val bottom = 40.dp
        val boxWidth = minOf(maxWidth - 64.dp, 900.dp)
        // While picking, leave room above for the meaning box.
        val share = if (picking) 0.55f else if (paused) 2f / 3f else 1f / 3f
        val budget = maxHeight * share - bottom - padV * 2
        val (textWidth, budgetPx) = with(density) { (boxWidth - padH * 2).roundToPx() to budget.toPx() }
        fun germanStyle(size: Int) = TextStyle(fontSize = size.sp, lineHeight = (size * 1.22f).sp, textAlign = TextAlign.Center)
        fun englishStyle(size: Int) = TextStyle(fontSize = (size * ENGLISH_SCALE).sp,
            lineHeight = (size * ENGLISH_SCALE * 1.22f).sp, textAlign = TextAlign.Center)
        val size = SIZES.firstOrNull { s ->
            val limits = Constraints(maxWidth = textWidth)
            val g = if (germanText.isEmpty()) 0 else measurer.measure(germanText, germanStyle(s), constraints = limits).size.height
            val e = if (englishText.isEmpty()) 0 else measurer.measure(englishText, englishStyle(s), constraints = limits).size.height
            g + e + with(density) { 8.dp.toPx() } <= budgetPx
        } ?: SIZES.last()
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottom).widthIn(max = boxWidth)
                .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                .then(if (onTap == null) Modifier else Modifier.pointerInput(Unit) {
                    detectTapGestures { tapHandler?.invoke(SubtitleTap.Box) }
                })
                .padding(horizontal = padH, vertical = padV),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (germanText.isNotEmpty()) {
                // Blurred = each word drawn only as its soft coloured shadow. (Modifier.blur blurred the video
                // under it on phones, where the video is a SurfaceView behind the window.)
                Text(if (blurred) shadowOnly(germanText, with(density) { 9.dp.toPx() }) else germanText,
                    style = germanStyle(size), color = Colors.text,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        // The box moves when its size changes (opening the picker shrinks the text): report the
                        // word's place again whenever the text is placed, not only when the cursor moves.
                        .onGloballyPositioned { coords = it; onAnchor(anchorOf(it, layout, range)) }
                        .then(if (onTap == null) Modifier else Modifier.pointerInput(Unit) {
                            detectTapGestures { p ->
                                val word = if (blurred) null else layout?.let { wordAt(it, p, ranges) }
                                tapHandler?.invoke(word ?: SubtitleTap.Box)
                            }
                        }))
            }
            if (englishText.isNotEmpty()) {
                Text(englishText, style = englishStyle(size), color = Colors.dim,
                    modifier = Modifier.padding(top = if (germanText.isEmpty()) 0.dp else 8.dp))
            }
        }
    }

    // Where the selected word is on screen, for the meaning box.
    SideEffect { onAnchor(coords?.let { anchorOf(it, layout, range) }) }
}

/** The text with every span drawn as a blurred shadow of its colour and no letters. */
private fun shadowOnly(text: AnnotatedString, radius: Float): AnnotatedString = AnnotatedString(
    text.text,
    spanStyles = text.spanStyles.map { r ->
        val color = if (r.item.color.isSpecified) r.item.color else Colors.text
        r.copy(item = SpanStyle(color = Color.Transparent, shadow = Shadow(color, blurRadius = radius)))
    },
)

/** A tap on the subtitles: on a word (line and segment of the scene), or anywhere else on the box. */
sealed interface SubtitleTap {
    data class Word(val line: Int, val seg: Int) : SubtitleTap
    data object Box : SubtitleTap
}

/** The word under [p] (local to the text), only when the finger is really on it, not just nearest to it. */
private fun wordAt(l: TextLayoutResult, p: Offset, ranges: List<Pair<IntRange, SubtitleTap.Word>>): SubtitleTap.Word? {
    val slop = 6f // px around a word that still counts as on it
    return ranges.firstOrNull { (r, _) ->
        r.any { i -> l.getBoundingBox(i).inflate(slop).contains(p) }
    }?.second
}

/** The screen rectangle of characters [range] of the laid-out text, or null. */
private fun anchorOf(c: LayoutCoordinates, l: TextLayoutResult?, range: IntRange?): Rect? {
    if (range == null || l == null || !c.isAttached || range.last >= l.layoutInput.text.length) return null
    val first = l.getBoundingBox(range.first)
    val box = Rect(first.left, first.top, l.getBoundingBox(range.last).right, first.bottom)
    return Rect(c.localToRoot(Offset(box.left, box.top)), box.size)
}

private fun colored(cue: Cue, words: Map<String, Word>, dim: Boolean, selected: Int = -1): AnnotatedString =
    buildAnnotatedString {
        val base = if (dim) Colors.dim else Colors.text
        cue.segments.forEachIndexed { i, seg ->
            val color = when (seg.word?.let { words[it]?.status ?: "u" }) {
                "u" -> if (dim) Colors.unknown.copy(alpha = 0.7f) else Colors.unknown
                "l" -> if (dim) Colors.learning.copy(alpha = 0.7f) else Colors.learning
                else -> base
            }
            val style = if (i == selected) SpanStyle(color = Color.Black, background = color) else SpanStyle(color = color)
            withStyle(style) { append(seg.text) }
        }
    }
