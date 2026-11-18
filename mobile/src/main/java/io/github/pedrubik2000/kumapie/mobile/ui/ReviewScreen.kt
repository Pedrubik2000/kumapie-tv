package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(source: ReviewSource, onBack: () -> Unit) {
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
        TopAppBar(title = { Text(tr("Review")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = { cards?.let { Text(tr("%1\$d left · %2\$d done", it.size - i, done), color = Colors.dim, modifier = Modifier.padding(end = 16.dp)) } })
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
                    Text(buildAnnotatedString {
                        var at = 0
                        for (r in wordRanges(card.sentence, card.word)) {
                            append(card.sentence.substring(at, r.first))
                            withStyle(SpanStyle(color = Color.Black, background = Colors.accent)) { append(card.sentence.substring(r)) }
                            at = r.last + 1
                        }
                        append(card.sentence.substring(at))
                    }, fontSize = 26.sp, lineHeight = 38.sp, textAlign = TextAlign.Center)
                    if (shown) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(card.word, fontSize = 24.sp, color = Colors.accent)
                        if (card.meaning.isNotBlank()) Text(card.meaning, fontSize = 18.sp, textAlign = TextAlign.Center)
                    }
                    if (!shown) Button(onClick = { shown = true }) { Text(tr("Show")) }
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Again", "Hard", "Good", "Easy").forEachIndexed { n, label ->
                            if (n == 2) Button(onClick = { rate(n + 1) }) { Text(tr(label)) }
                            else OutlinedButton(onClick = { rate(n + 1) }) { Text(tr(label)) }
                        }
                    }
                }
            }
        }
    }
}
