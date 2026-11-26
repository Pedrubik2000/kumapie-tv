package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.pedrubik2000.kumapie.lang.GlossaryNodes
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.CardDefinition
import io.github.pedrubik2000.kumapie.lang.Headword
import io.github.pedrubik2000.kumapie.lang.JapaneseLookup
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal object TvReading {
    var at by mutableStateOf<Pair<Int, Int>?>(null)
    var word by mutableStateOf("")
    var readings by mutableStateOf(emptyList<String>())
    var shown by mutableStateOf("")
    var version by mutableIntStateOf(0)
    fun several(picker: WordPicker) = picker.cardOpen && at == (picker.line to picker.seg) && readings.size > 1
    suspend fun show(found: Headword?, spot: Pair<Int, Int>) {
        readings = found?.let { withContext(Dispatchers.IO) { TvMining.languages.japanese.readings(it.expression) } }.orEmpty()
        word = found?.expression.orEmpty()
        shown = found?.reading.orEmpty()
        at = spot
    }
}

fun nextTvReading() {
    TvMining.languages.japanese.nextReading(TvReading.word, TvReading.shown)
    TvReading.version++
}

@Composable
fun TvJapaneseCard(settings: Settings, ctl: SceneController, picker: WordPicker, height: Int) {
    val context = LocalContext.current
    val segment = picker.selected ?: return
    val key = segment.word ?: return
    val word = ctl.words[key] ?: io.github.pedrubik2000.kumapie.data.Word("u", "")
    var hw by remember(picker.line, picker.seg) { mutableStateOf<Headword?>(null) }
    var sections by remember(picker.line, picker.seg) { mutableStateOf<List<DictSection>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    Row(Modifier.width(860.dp).height(height.dp)) {
        Column(Modifier.width(230.dp).fillMaxHeight().padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val kana = hw?.reading?.takeIf { it.isNotBlank() } ?: word.reading
            val expression = hw?.expression ?: word.lemma.ifBlank { segment.text }
            if (kana.isNotBlank() && kana != expression) PitchReading(kana, hw?.pitches?.flatMap { it.second }?.firstOrNull())
            if (TvReading.several(picker)) Text(tr("· %d readings", TvReading.readings.size), color = Colors.dim, fontSize = 14.sp)
            Text(expression, color = Colors.text, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            if (segment.text != expression) Text(segment.text, color = Colors.dim, fontSize = 16.sp)
            (ctl.scene.meanings[key] ?: word.meaning).substringAfter(" = ").takeIf { it.isNotBlank() }?.let {
                Text(it, color = Colors.text, fontSize = 19.sp, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            val (state, color) = formStatus(word, segment.text)
            val shown = word.formCard(segment.text) ?: word.card.takeUnless { word.marked }
            if (shown == "d") Text(state, color = Arcade.background, fontSize = 12.sp, style = Arcade.label,
                modifier = Modifier.padding(top = 4.dp).background(Colors.due, Slab).padding(horizontal = 12.dp, vertical = 1.dp))
            val looked = lookedUp(word)
            Text(if (shown == "d") looked.trimStart(' ', '·') else state + looked, color = color, fontSize = 15.sp)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Colors.dim.copy(alpha = 0.2f)))
        val message = status ?: when { sections == null -> tr("Looking it up…"); sections?.isEmpty() == true -> tr("Not in your %s dictionaries.", ctl.lang.displayName); else -> null }
        if (message != null) Text(message, color = Colors.dim, fontSize = 17.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        else sections?.let { DictionaryNative(it, Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp)) }
    }
    LaunchedEffect(picker.line, picker.seg, TvReading.version) {
        TvMining.languages(context, settings)
        val y = TvMining.yomitan
        if (y.of(Lang.JAPANESE).isEmpty()) TvMining.fetchJapanese(context, settings)
        while (TvMining.fetching != null) { status = TvMining.fetching; delay(2_000) }
        status = null
        val cue = ctl.scene.cues.getOrNull(picker.line) ?: return@LaunchedEffect
        val offset = cue.segments.take(picker.seg).sumOf { it.text.length }
        val word = ctl.words[key]
        val lemma = ctl.scene.def(picker.line, key)?.lemma?.takeIf { it.isNotBlank() } ?: word?.lemma?.takeIf { it.isNotBlank() }
        val found = withContext(Dispatchers.IO) { io.github.pedrubik2000.kumapie.lang.WordCard.japaneseHeadwords(TvMining.languages.japanese, cue.text, offset, segment.text, lemma ?: key, word?.reading.orEmpty()).firstOrNull() }
        hw = found
        TvReading.show(found, picker.line to picker.seg)
        sections = if (found == null) emptyList() else withContext(Dispatchers.IO) {
            val byDict = LinkedHashMap<String, MutableList<String>>()
            for (t in found.terms) for (g in t.glossaries) byDict.getOrPut(g.dict) { ArrayList() }.let { if (g.raw !in it) it += g.raw }
            val budget = GlossaryNodes.Budget(GlossaryNodes.MAX_CARD_CHARS, GlossaryNodes.MAX_CARD_BLOCKS)
            byDict.keys.sortedBy { CardDefinition.rank(it) ?: Int.MAX_VALUE }
                .map { d -> DictSection(dictLabel(d), byDict.getValue(d).map { raw -> GlossaryNodes.build(raw, budget).map { Para(annotated(it), it.indent) } }.filter { it.isNotEmpty() }) }
                .filter { it.entries.isNotEmpty() }
        }
    }
}

private fun dictLabel(title: String): String {
    val name = Regex("[A-Za-z]+").find(title)?.value?.let { if (it.any(Char::isUpperCase)) it else it.replaceFirstChar(Char::uppercase) } ?: title.take(16)
    val lang = when {
        title.contains("es-jp", true) || title.contains("spanish", true) -> " ES"
        title.contains("en-jp", true) -> " EN"
        else -> ""
    }
    return name + lang
}

@Composable
private fun PitchReading(kana: String, downstep: Int?) {
    val morae = JapaneseLookup.morae(kana)
    fun high(i: Int, n: Int) = JapaneseLookup.high(i, n)
    Row {
        morae.forEachIndexed { i, m ->
            val up = downstep != null && high(i, downstep)
            val drop = downstep != null && downstep > 0 && i == downstep - 1
            Text(m, color = Colors.dim, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp).drawBehind {
                if (up) drawLine(Colors.accent, Offset(0f, 0f), Offset(size.width, 0f), 3f)
                if (drop) drawLine(Colors.accent, Offset(size.width, 0f), Offset(size.width, size.height * 0.45f), 3f)
            })
        }
    }
}

internal class Para(val text: AnnotatedString, val indent: Int)

internal class DictSection(val title: String, val entries: List<List<Para>>)

private var dictionaryStep: ((Int) -> Boolean)? = null

fun scrollTvDictionaries(dir: Int): Boolean = dictionaryStep?.invoke(dir) ?: false

private val CardBg = Color(0xFF14171D)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryNative(sections: List<DictSection>, modifier: Modifier) {
    val scroll = remember(sections) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    val tops = remember(sections) { IntArray(sections.size) }
    val active by remember(sections) { derivedStateOf {
        if (scroll.value >= scroll.maxValue - 2) sections.lastIndex
        else tops.indexOfLast { it <= scroll.value + 4 }.coerceAtLeast(0)
    } }
    DisposableEffect(sections) {
        val step: (Int) -> Boolean = step@{ d ->
            if (!(if (d > 0) scroll.canScrollForward else scroll.canScrollBackward)) return@step false
            val page = scroll.viewportSize * 0.85f
            val y = scroll.value
            val t = if (d > 0) tops.firstOrNull { it > y + 4 } else tops.lastOrNull { it < y - 4 }
            val to = if (t != null && kotlin.math.abs(t - y) <= page) t else (y + d * page).toInt()
            scope.launch { scroll.animateScrollTo(to.coerceAtLeast(0)) }
            true
        }
        dictionaryStep = step
        onDispose { if (dictionaryStep === step) dictionaryStep = null }
    }
    Box(modifier) {
        Column {
            FlowRow(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sections.forEachIndexed { i, s ->
                    val on = i == active
                    Text(s.title, color = if (on) Color.Black else Color(0xFFA8A8A8), fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else null,
                        modifier = Modifier.background(if (on) Color.White else Color.White.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp, vertical = 2.dp))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                sections.forEachIndexed { i, s ->
                    Column(Modifier.fillMaxWidth().onGloballyPositioned { tops[i] = it.positionInParent().y.toInt() }
                        .padding(top = 2.dp, bottom = if (i == sections.lastIndex) 40.dp else 14.dp)) {
                        Text(s.title, color = Color(0xFF9CC9FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                        s.entries.forEachIndexed { k, blocks ->
                            if (k > 0) Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Color(0x33A9A4B8)))
                            blocks.forEach { Text(it.text, color = Colors.text, fontSize = 19.sp, lineHeight = 30.sp, modifier = Modifier.padding(start = (it.indent * 22).dp)) }
                        }
                    }
                    if (i < sections.lastIndex) Box(Modifier.padding(bottom = 12.dp).fillMaxWidth().height(1.dp).background(Color(0x26A9A4B8)))
                }
            }
        }
        if (scroll.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
            .background(Brush.verticalGradient(listOf(CardBg.copy(alpha = 0f), CardBg))))
    }
}

private fun annotated(b: GlossaryNodes.Block): AnnotatedString = buildAnnotatedString {
    for (r in b.runs) {
        val style = SpanStyle(
            fontWeight = if (r.bold) FontWeight.Bold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            fontSize = (19f * (if (r.pill) minOf(r.size, 0.75f) else r.size)).sp,
            color = when { r.link -> Color(0xFF8AB4F8); r.dim -> Color(0xFFA8A8A8); else -> Color.Unspecified },
            background = if (r.pill) Color.White.copy(alpha = 0.12f) else Color.Unspecified,
        )
        withStyle(style) { append(if (r.pill) "\u00A0${r.text.trim()}\u00A0" else r.text) }
        if (r.pill) append(' ')
    }
}
