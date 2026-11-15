package io.github.pedrubik2000.kumapie.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.StreamActivity
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.objects
import io.github.pedrubik2000.kumapie.i18n.tr
import org.json.JSONObject

/**
 * Discover (kumapie-server discover.py), as on the phone: Kitsu's anime, an episode's Torrentio files, then
 * [StreamActivity] with kumapie://watch?…: the PC makes the words, the TV streams Real-Debrid's link.
 */
@Composable
fun DiscoverScreen(api: Api) {
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Found>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Found?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(searched) {
        found = null
        error = null
        runCatching { api.discover(searched) }.onSuccess { found = it }.onFailure { error = it.message ?: it.toString() }
    }
    open?.let { title ->
        BackHandler { open = null }
        return TitleScreen(api, title)
    }
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title(tr("Discover").uppercase())
            Spacer(Modifier.width(32.dp))
            TvTextField(query, { query = it }, onDone = { searched = query.trim() }, Modifier.width(420.dp))
        }
        Heading(if (searched.isEmpty()) tr("Trending") else searched, Modifier.padding(top = 16.dp, start = 16.dp))
        val list = found
        when {
            error != null -> Text(error!!, color = Colors.dim)
            list == null -> Text(tr("Loading…"), color = Colors.dim)
            list.isEmpty() -> Text(tr("Nothing found"), color = Colors.dim)
            else -> LazyVerticalGrid(GridCells.Adaptive(130.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                itemsIndexed(list, key = { _, f -> f.id }) { i, f ->
                    Poster(f.poster, f.name, f.year, null, if (i == 0) Modifier.focusRequester(first) else Modifier) { open = f }
                }
            }
        }
    }
    LaunchedEffect(found) { if (!found.isNullOrEmpty()) first.focusSoon() }
}

/** A title's episodes (or the movie), then the picked one's files; OK on a file starts it. */
@Composable
private fun TitleScreen(api: Api, title: Found) {
    val context = LocalContext.current
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) } // (Stremio id, its name)
    var files by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(title.id) {
        runCatching { api.discoverMeta(title.type, title.id) }.onSuccess { meta = it }.onFailure { error = it.message ?: it.toString() }
    }
    LaunchedEffect(picking) {
        files = null
        val (id, _) = picking ?: return@LaunchedEffect
        runCatching { api.discoverStreams(title.type, id).objects() }.onSuccess { files = it }.onFailure { error = it.message ?: it.toString() }
    }
    if (picking != null) BackHandler { picking = null; error = null }
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(320.dp)) {
            AsyncImage(title.poster, title.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Text(title.name.uppercase(), color = Colors.text, fontSize = 22.sp, style = Arcade.label, modifier = Modifier.padding(top = 12.dp))
            Text(meta?.optString("description").orEmpty(), color = Colors.dim, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        val m = meta
        val pick = picking
        when {
            error != null -> Text(error!!, color = Colors.dim)
            m == null -> Text(tr("Loading…"), color = Colors.dim)
            pick != null -> {
                val list = files
                Column {
                    Heading(pick.second)
                    when {
                        list == null -> Text(tr("Asking Torrentio…"), color = Colors.dim)
                        list.isEmpty() -> Text(tr("No files found for this"), color = Colors.dim)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(12.dp)) {
                            itemsIndexed(list) { i, f ->
                                Line(i, f.optString("label"), f.optString("about"), null) {
                                    val link = Uri.parse("kumapie://watch").buildUpon().apply {
                                        listOf("lang" to "ja", "type" to title.type, "id" to pick.first, "hash" to f.getString("hash"),
                                            "file" to f.getString("file"), "idx" to f.optInt("idx", -1).toString(), "title" to pick.second, "show" to title.name)
                                            .forEach { (k, v) -> appendQueryParameter(k, v) }
                                    }.build()
                                    context.startActivity(Intent(context, StreamActivity::class.java).setData(link))
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                val eps = m.optJSONArray("episodes")?.objects().orEmpty()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(12.dp)) {
                    if (eps.isEmpty()) item { Line(0, tr("Watch"), m.optString("year"), null) { picking = title.id to title.name } }
                    itemsIndexed(eps, key = { _, e -> e.getString("id") }) { i, e ->
                        val ready = e.optBoolean("ready") // the PC got a file ready for it: no file list, it starts at once
                        Line(i, (if (ready) "▶ " else "") + tr("Episode %1\$d", e.optInt("number")), e.optString("title"), e.optString("thumb")) {
                            val name = "${title.name} ${e.optInt("number")}"
                            if (ready) context.startActivity(Intent(context, StreamActivity::class.java).setData(Uri.parse("kumapie://watch").buildUpon()
                                .appendQueryParameter("lang", "ja").appendQueryParameter("type", title.type).appendQueryParameter("id", e.getString("id"))
                                .appendQueryParameter("title", name).appendQueryParameter("show", title.name).build()))
                            else picking = e.getString("id") to name
                        }
                    }
                }
            }
        }
    }
}

/** One focusable line of the episode / file list ([i] 0 takes the focus first). */
@Composable
private fun Line(i: Int, text: String, line: String, thumb: String?, onClick: () -> Unit) {
    val first = remember { FocusRequester() }
    Card(onClick = onClick, shape = arcadeCardShape(RowSlab), border = arcadeCardBorder(RowSlab),
        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!thumb.isNullOrEmpty()) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.width(160.dp).aspectRatio(16f / 9f))
            Column {
                Text(text, color = Colors.text, fontSize = 18.sp)
                Text(line, color = Colors.dim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (i == 0) LaunchedEffect(Unit) { first.focusSoon() }
}
