package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.SrsCard
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.ReviewSource
import io.github.pedrubik2000.kumapie.lang.wordRanges
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

/**
 * Review without a PC (kumapie for other people): the device's own cards, due now then new ([ReviewSource]). The line
 * with the word marked; Show gives the reading and meaning; four buttons rate it (FSRS on the device). As the TV's.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(source: ReviewSource) {
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<SrsCard>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var i by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    fun load() = scope.launch { runCatching { source.due() }.onSuccess { cards = it; i = 0; shown = false }.onFailure { error = it.message ?: it.toString() } }
    LaunchedEffect(Unit) { load() }
    val card = cards?.getOrNull(i)
    // The card's line audio: plays when the card comes, and on Listen (as the TV's).
    val context = androidx.compose.ui.platform.LocalContext.current
    val audio = remember { androidx.media3.exoplayer.ExoPlayer.Builder(context).build() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { audio.release() } }
    fun play(uri: String) {
        audio.setMediaItem(androidx.media3.common.MediaItem.fromUri(source.audioUrl(uri)))
        audio.prepare()
        audio.play()
    }
    LaunchedEffect(card?.id) { card?.audio?.takeIf { it.isNotEmpty() }?.let(::play) }
    fun rate(r: Int) {
        val c = card ?: return
        if (busy) return
        busy = true
        scope.launch {
            runCatching { source.rate(c, r) }
                .onSuccess { done++; if (i + 1 < cards!!.size) { i++; shown = false } else load() }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Review"), fontSize = 34.sp, fontWeight = FontWeight.Bold) },
            actions = { cards?.let { Text(tr("%1\$d left · %2\$d done", it.size - i, done), color = Colors.dim, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 16.dp)) } })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error!!, color = Colors.unknown, textAlign = TextAlign.Center)
                cards == null -> CircularProgressIndicator()
                card == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("All done for now"), fontSize = 22.sp)
                    Text(tr("Add words while watching: tap one, then Mine."), color = Colors.dim, textAlign = TextAlign.Center)
                }
                else -> Column(Modifier.widthIn(max = 700.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    // The line's picture (own cards: ownReview) in only the height the line and the buttons leave: full width on a tablet pushed the ratings off the screen.
                    io.github.pedrubik2000.kumapie.ui.CardPicture(card.image, Modifier.weight(1f, fill = false).fillMaxWidth())
                    Text(buildAnnotatedString {
                        var at = 0
                        for (r in wordRanges(card.sentence, card.word)) {
                            append(card.sentence.substring(at, r.first))
                            withStyle(SpanStyle(color = Color.Black, background = Colors.accent)) { append(card.sentence.substring(r)) }
                            at = r.last + 1
                        }
                        append(card.sentence.substring(at))
                    }, fontSize = 34.sp, lineHeight = 46.sp, textAlign = TextAlign.Center)
                    if (shown) Column(Modifier.background(Glass.page.copy(alpha = 0.55f)).padding(horizontal = 32.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(card.word, fontSize = 30.sp, color = Colors.accent, fontWeight = FontWeight.SemiBold)
                        if (card.meaning.isNotBlank()) Text(card.meaning, fontSize = 24.sp, textAlign = TextAlign.Center)
                    }
                    // The TV's row of pills (Good white), wrapped on a phone's width; Listen last, as the TV's.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val labels = if (shown) listOf("Again", "Hard", "Good", "Easy") else listOf("Show")
                        labels.forEachIndexed { n, label ->
                            Pill(tr(label), { if (shown) rate(n + 1) else { shown = true } }, primary = label == "Good" || label == "Show")
                        }
                        if (card.audio.isNotEmpty()) Pill(tr("Listen"), { play(card.audio) })
                    }
                }
            }
        }
    }
}
