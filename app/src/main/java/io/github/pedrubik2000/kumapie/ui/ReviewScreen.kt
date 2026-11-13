package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import io.github.pedrubik2000.kumapie.data.SrsCard
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.launch

/**
 * "Review" (Home, only for a person whose cards are on the PC: Jackson, user 2026-11-13): the PC's due cards, then the
 * new ones. Front: the line with the word marked, its audio plays; OK shows the meaning and the translation; four
 * buttons rate it (FSRS on the PC). When the list ends it asks the PC again (learning steps come back in minutes).
 */
@Composable
fun ReviewScreen(api: Api, lang: String) {
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
        runCatching { api.srsDue(lang) }.onSuccess { cards = it.cards; i = 0; shown = false }.onFailure { error = it.message ?: it.toString() }
    }
    LaunchedEffect(Unit) { load() }
    val card = cards?.getOrNull(i)
    fun play() {
        val a = card?.audio?.takeIf { it.isNotEmpty() } ?: return
        audio.setMediaItem(MediaItem.fromUri(api.url(a)))
        audio.prepare()
        audio.play()
    }
    LaunchedEffect(card) { play() }
    fun rate(r: Int) {
        val c = card ?: return
        if (busy) return
        busy = true
        scope.launch {
            runCatching { api.srsRate(c.word, r, lang = lang) }
                .onSuccess { done++; if (i + 1 < cards!!.size) { i++; shown = false } else load() }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title(tr("Review"))
            Box(Modifier.weight(1f))
            cards?.let { Text(tr("%1\$d left · %2\$d done", it.size - i, done).uppercase(), color = Colors.dim, style = Arcade.label) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error!!, color = Colors.unknown)
                cards == null -> Text(tr("Loading…"), color = Colors.dim)
                card == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Title(tr("All done for now"), fontSize = 28.sp)
                    Text(tr("Hold ↑ on a word while watching to add it here."), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
                }
                else -> Front(card, shown, onShow = { shown = true }, onListen = ::play, onRate = ::rate)
            }
        }
    }
}

@Composable
private fun Front(card: SrsCard, shown: Boolean, onShow: () -> Unit, onListen: () -> Unit, onRate: (Int) -> Unit) {
    val first = remember(card.word, shown) { FocusRequester() }
    Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(marked(card.sentence, card.word), fontSize = 34.sp, color = Colors.text, textAlign = TextAlign.Center)
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
                Button(onClick = onShow, modifier = Modifier.focusRequester(first)) { Text(tr("Show").uppercase()) }
                Button(onClick = onListen) { Text(tr("Listen").uppercase()) }
            } else {
                RATINGS.forEachIndexed { n, label ->
                    Button(onClick = { onRate(n + 1) }, modifier = if (n == 2) Modifier.focusRequester(first) else Modifier) {
                        Text(tr(label).uppercase())
                    }
                }
                Button(onClick = onListen) { Text(tr("Listen").uppercase()) }
            }
        }
    }
    LaunchedEffect(card.word, shown) { first.focusSoon() }
}

/** The line with every whole-word use of [word] marked as the player marks the picked word. */
private fun marked(sentence: String, word: String) = buildAnnotatedString {
    val hits = Regex("(?i)(?<![\\p{L}'])" + Regex.escape(word) + "(?![\\p{L}])").findAll(sentence).toList()
    var at = 0
    for (h in hits) {
        append(sentence.substring(at, h.range.first))
        withStyle(SpanStyle(color = Color.Black, background = Arcade.accent)) { append(h.value) }
        at = h.range.last + 1
    }
    append(sentence.substring(at))
}
