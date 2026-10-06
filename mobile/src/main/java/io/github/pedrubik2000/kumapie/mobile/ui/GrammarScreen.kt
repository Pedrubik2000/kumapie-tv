package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.mobile.lang.Grammar
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The next grammar point: its ten sentences, a picture to choose for each (Openverse; the best match is picked to
 * start with, tap another or "none"), then "Add to Anki". One point a day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarScreen(library: Library, onBack: () -> Unit) {
    val context = LocalContext.current
    val grammar = remember { Grammar(context, library.settings) }
    val scope = rememberCoroutineScope()
    var point by remember { mutableStateOf<Grammar.Point?>(null) }
    var said by remember { mutableStateOf("Reading the grammar points…") }
    var waiting by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf("") } // what the last "Add" did
    var reload by remember { mutableIntStateOf(0) }
    val choices = remember { mutableStateListOf<List<Grammar.Picture>?>() }
    val chosen = remember { mutableStateListOf<Int>() } // per note: index into its pictures, -1 none

    LaunchedEffect(reload) {
        point = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val pkg = grammar.ankiApp() ?: error("No kuma3 Anki on this device.")
                val all = grammar.points()
                val added = grammar.added(pkg, all)
                all.filter { it.key !in added }
            }
        }
        result.onSuccess { left ->
            waiting = left.size
            point = left.firstOrNull()
            said = if (left.isEmpty()) "No new grammar points here. Ask Claude on the PC for the next batch." else ""
        }.onFailure { said = it.message ?: it.toString() }
        val p = point ?: return@LaunchedEffect
        choices.clear(); choices.addAll(List(p.notes.size) { null })
        chosen.clear(); chosen.addAll(List(p.notes.size) { 0 })
        p.notes.forEachIndexed { i, n ->
            choices[i] = withContext(Dispatchers.IO) { runCatching { grammar.search(n.query) }.getOrDefault(emptyList()) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Grammar") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        val p = point
        if (p == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done.isNotEmpty()) Text(done)
                Text(said, color = Colors.dim)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${p.number}. ${p.title}", color = Colors.accent, fontSize = 20.sp)
                    Text(p.explanation, fontSize = 15.sp)
                    Text("$waiting point(s) waiting on this device. Choose a picture for each sentence, then add the point.",
                        fontSize = 13.sp, color = Colors.dim)
                }
            }
            itemsIndexed(p.notes) { i, note ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(note.sentence, fontSize = 17.sp)
                    Text(note.english, fontSize = 13.sp, color = Colors.dim)
                    val pics = choices.getOrNull(i)
                    when {
                        pics == null -> Text("Finding pictures for \"${note.query}\"…", fontSize = 13.sp, color = Colors.dim)
                        pics.isEmpty() -> Text("No pictures for \"${note.query}\" (offline?). The card gets none.", fontSize = 13.sp, color = Colors.dim)
                        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(pics.take(8)) { k, pic ->
                                val on = chosen.getOrNull(i) == k
                                AsyncImage(pic.thumb, pic.credit, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(120.dp, 90.dp).clip(RoundedCornerShape(8.dp))
                                        .border(if (on) 3.dp else 0.dp, if (on) Colors.accent else Colors.background, RoundedCornerShape(8.dp))
                                        .clickable { chosen[i] = k })
                            }
                            item {
                                val on = chosen.getOrNull(i) == -1
                                Box(Modifier.size(90.dp).clip(RoundedCornerShape(8.dp))
                                    .border(if (on) 3.dp else 1.dp, if (on) Colors.accent else Colors.dim, RoundedCornerShape(8.dp))
                                    .clickable { chosen[i] = -1 }, contentAlignment = Alignment.Center) { Text("none", color = Colors.dim) }
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (grammar.addedToday) Text("A point was added today: the deck shows 20 new cards a day, so the next one is " +
                        "for tomorrow.", fontSize = 14.sp, color = Colors.dim)
                    val add = {
                        busy = true
                        scope.launch {
                            val pictures = p.notes.indices.map { i -> chosen.getOrNull(i)?.takeIf { it >= 0 }?.let { choices.getOrNull(i)?.getOrNull(it) } }
                            done = withContext(Dispatchers.IO) {
                                runCatching { grammar.add(p, pictures) { done = it } }.getOrElse { it.message ?: it.toString() }
                            }
                            busy = false
                            reload++
                        }
                    }
                    if (!grammar.addedToday) Button(enabled = !busy && choices.none { it == null }, onClick = { add() }) { Text("Add to Anki") }
                    else TextButton(enabled = !busy && choices.none { it == null }, onClick = { add() }) { Text("Add it anyway") }
                    if (done.isNotEmpty()) Text(done, fontSize = 14.sp)
                }
            }
            item { Box(Modifier.height(8.dp)) }
        }
    }
}
