package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Style
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.SrsCard
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.ReviewSource
import io.github.pedrubik2000.kumapie.lang.wordRanges
import kotlinx.coroutines.launch

/** A person whose cards are on the PC (Jackson, user 2026-11-13): FSRS on the PC. Without a PC: [ownReview]. */
fun pcReview(api: Api, lang: String) = ReviewSource({ api.srsDue(lang).cards }, { c, r -> api.srsRate(c.word, r, lang = lang) }, api::url)

/**
 * "Review" (Home): the due cards, then the new ones. Front: the line with the word marked, its audio plays (cards from
 * the PC); OK shows the meaning and the translation; four buttons rate it. When the list ends it asks again
 * (learning steps come back in minutes).
 */
@Composable
internal fun ReviewScreen(source: ReviewSource, api: Discovery, nav: RailNav, lang: String, server: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<SrsCard>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var i by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val audio = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { audio.release() } }
    fun load() = scope.launch {
        runCatching { source.due() }.onSuccess { cards = it; i = 0; shown = false }.onFailure { error = it.message ?: it.toString() }
    }
    LaunchedEffect(Unit) { load() }
    val card = cards?.getOrNull(i)
    fun play() {
        val a = card?.audio?.takeIf { it.isNotEmpty() } ?: return
        audio.setMediaItem(MediaItem.fromUri(source.audioUrl(a)))
        audio.prepare()
        audio.play()
    }
    LaunchedEffect(card) { play() }
    fun rate(r: Int) {
        val c = card ?: return
        if (busy) return
        busy = true
        scope.launch {
            runCatching { source.rate(c, r) }
                .onSuccess { done++; DueTick.n++; if (i + 1 < cards!!.size) { i++; shown = false } else load() }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    TvPage(api, RailAt.REVIEW, nav, null, cards?.let { tr("%1\$d left · %2\$d done", it.size - i, done) }, lang) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                error != null -> ErrorBox(error!!, onRetry = { error = null; load() }, onSettings = null, server = server)
                cards == null -> Text(tr("Loading…"), color = Colors.dim)
                card == null -> { val ok = remember { FocusRequester() }
                    EmptyState(androidx.compose.material.icons.Icons.Default.Style, tr("All done for now"), tr("Hold ↑ on a word while watching to add it here."), tr("Open Continue watching"), nav.onHome, Modifier.focusRequester(ok))
                    LaunchedEffect(Unit) { ok.focusSoon() } }
                else -> Front(card, shown, onShow = { shown = true }, onListen = ::play, onRate = ::rate)
            }
        }
    }
}

@Composable
private fun Front(card: SrsCard, shown: Boolean, onShow: () -> Unit, onListen: () -> Unit, onRate: (Int) -> Unit) {
    val first = remember(card.word, shown) { FocusRequester() }
    Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CardPicture(card.image, Modifier.weight(1f, fill = false).padding(bottom = 20.dp).heightIn(max = 300.dp)) // own cards: the line's frame
        Text(marked(card.sentence, card.word), fontSize = 34.sp, lineHeight = 46.sp, color = Colors.text, textAlign = TextAlign.Center)
        if (shown) {
            Column(Modifier.padding(top = 28.dp).background(Arcade.slab).padding(horizontal = 32.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(card.word, fontSize = 30.sp, color = Arcade.accent, style = Arcade.label)
                if (card.meaning.isNotBlank()) Text(card.meaning, fontSize = 24.sp, color = Colors.text, textAlign = TextAlign.Center)
                if (card.translation.isNotBlank()) Text(card.translation, fontSize = 20.sp, color = Colors.dim, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
        Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!shown) {
                Button(onClick = onShow, modifier = Modifier.focusRequester(first)) { Text(tr("Show")) }
                if (card.audio.isNotEmpty()) Button(onClick = onListen) { Text(tr("Listen")) }
            } else {
                RATINGS.forEachIndexed { n, label ->
                    Button(onClick = { onRate(n + 1) }, modifier = if (n == 2) Modifier.focusRequester(first) else Modifier) {
                        Text(tr(label))
                    }
                }
                if (card.audio.isNotEmpty()) Button(onClick = onListen) { Text(tr("Listen")) }
            }
        }
    }
    LaunchedEffect(card.word, shown) { first.focusSoon() }
}

/** The line with every use of [word] marked as the player marks the picked word ([wordRanges]). */
private fun marked(sentence: String, word: String) = buildAnnotatedString {
    var at = 0
    for (r in wordRanges(sentence, word)) {
        append(sentence.substring(at, r.first))
        withStyle(SpanStyle(color = Color.Black, background = Arcade.accent)) { append(sentence.substring(r)) }
        at = r.last + 1
    }
    append(sentence.substring(at))
}
