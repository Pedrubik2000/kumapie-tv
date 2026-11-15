package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.objects
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Discover (kumapie-server discover.py): Kitsu's anime like Stremio shows them, an episode's Torrentio files, and
 * [onWatch] with the picked one: [WatchScreen] streams it from Real-Debrid, the PC makes only its words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(library: Library, onWatch: (Map<String, String>) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Found>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Found?>(null) }
    LaunchedEffect(searched) {
        found = null
        error = null
        runCatching { library.api.discover(searched) }.onSuccess { found = it }.onFailure { error = it.message ?: it.toString() }
    }
    open?.let { title ->
        BackHandler { open = null }
        return TitleScreen(library, title, onWatch, onBack = { open = null })
    }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = Colors.background, topBar = {
        TopAppBar(title = { Text(tr("Discover")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(tr("Search anime")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searched = query.trim() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            Text(if (searched.isEmpty()) tr("Trending") else searched, color = Colors.dim, modifier = Modifier.padding(horizontal = 16.dp))
            val list = found
            when {
                error != null -> Message(error!!)
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Colors.accent) }
                list.isEmpty() -> Message(tr("Nothing found"))
                else -> LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.id }) { f ->
                        Column(Modifier.clickable { open = f }) {
                            AsyncImage(f.poster, f.name, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Colors.surface))
                            Text(f.name, color = Colors.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(f.year, color = Colors.dim, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A title: its picture, description and episodes; an episode (or the movie) opens its files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleScreen(library: Library, title: Found, onWatch: (Map<String, String>) -> Unit, onBack: () -> Unit) {
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) } // (Stremio id, its name)
    LaunchedEffect(title.id) {
        runCatching { library.api.discoverMeta(title.type, title.id) }.onSuccess { meta = it }.onFailure { error = it.message ?: it.toString() }
    }
    Scaffold(containerColor = Colors.background, topBar = {
        TopAppBar(title = { Text(title.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }) { padding ->
        val m = meta
        when {
            error != null -> Box(Modifier.padding(padding)) { Message(error!!) }
            m == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Colors.accent) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    AsyncImage(m.optString("background").ifEmpty { title.poster }, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(180.dp).background(Colors.surface))
                    Text(listOf(m.optString("year")).filter { it.isNotEmpty() }.joinToString(), color = Colors.dim, modifier = Modifier.padding(16.dp, 8.dp))
                    Text(m.optString("description"), color = Colors.text, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
                val eps = m.optJSONArray("episodes")?.objects().orEmpty()
                if (eps.isEmpty()) item {
                    Button(onClick = { picking = title.id to title.name }, modifier = Modifier.padding(16.dp)) { Text(tr("Watch")) }
                }
                items(eps, key = { it.getString("id") }) { e ->
                    val name = "${title.name} ${e.optInt("number")}"
                    Row(Modifier.fillMaxWidth().clickable { picking = e.getString("id") to name }.padding(16.dp, 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(e.optString("thumb"), null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Colors.surface))
                        Column(Modifier.weight(1f)) {
                            Text(tr("Episode %1\$d", e.optInt("number")), color = Colors.text)
                            Text(e.optString("title"), color = Colors.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    picking?.let { (id, name) ->
        ModalBottomSheet(onDismissRequest = { picking = null }, containerColor = Colors.surface) {
            Files(library, title.type, id) { f ->
                picking = null
                onWatch(mapOf("lang" to "ja", "type" to title.type, "id" to id, "hash" to f.getString("hash"),
                    "file" to f.getString("file"), "idx" to f.optInt("idx", -1).toString(), "title" to name, "show" to title.name))
            }
        }
    }
}

/** Torrentio's files for [id], best first; asking the indexers takes up to a minute. */
@Composable
private fun Files(library: Library, type: String, id: String, onPick: (JSONObject) -> Unit) {
    var files by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        runCatching { library.api.discoverStreams(type, id).objects() }.onSuccess { files = it }.onFailure { error = it.message ?: it.toString() }
    }
    val list = files
    when {
        error != null -> Message(error!!)
        list == null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Colors.accent)
            Text(tr("Asking Torrentio…"), color = Colors.dim, modifier = Modifier.padding(top = 12.dp))
        }
        list.isEmpty() -> Message(tr("No files found for this"))
        else -> LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
            items(list) { f ->
                Column(Modifier.fillMaxWidth().clickable { onPick(f) }.padding(16.dp, 10.dp)) {
                    Text(f.optString("label"), color = Colors.text)
                    Text(f.optString("about"), color = Colors.dim, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * A picked Discover file: the PC gets Real-Debrid's link and makes the episode's words (asked every 3 s, its step shown),
 * then [onPlay] streams it.
 */
@Composable
fun WatchScreen(library: Library, query: Map<String, String>, onPlay: (Show, Episode, String) -> Unit, onBack: () -> Unit) {
    var step by remember { mutableStateOf(tr("Asking the PC…")) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        runCatching {
            while (true) {
                val r = library.api.watch(query)
                when (r.optString("state")) {
                    "ready" -> {
                        val title = query["title"].orEmpty()
                        val show = Show(query["id"].orEmpty(), query["show"].orEmpty(), "stream", "", emptyList(), lang = query["lang"] ?: "ja")
                        onPlay(show, Episode(r.getString("id"), title, null, null, 0.0, "", 0, 0, 0, null), r.getString("url"))
                        return@runCatching
                    }
                    "failed" -> error(r.optString("error"))
                    else -> step = r.optString("step")
                }
                delay(3_000)
            }
        }.onFailure { error = it.message ?: it.toString() }
    }
    Column(Modifier.fillMaxSize().background(Colors.background).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        Text(query["title"].orEmpty(), color = Colors.text, fontSize = 18.sp, textAlign = TextAlign.Center)
        if (error == null) CircularProgressIndicator(color = Colors.accent)
        Text(error ?: step, color = if (error == null) Colors.dim else Colors.unknown, fontSize = 16.sp, textAlign = TextAlign.Center)
        Button(onClick = onBack) { Text(if (error == null) tr("Not now") else tr("Back")) }
    }
}

@Composable
private fun Message(text: String) = Text(text, color = Colors.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
