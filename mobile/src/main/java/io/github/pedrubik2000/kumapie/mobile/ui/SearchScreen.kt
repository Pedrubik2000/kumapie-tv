package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.mobile.lang.JapaneseLookup
import io.github.pedrubik2000.kumapie.mobile.lang.JapaneseModel
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Japanese dictionary search (home menu): type or paste Japanese, Sudachi splits it into words (coloured by what
 * Pedro knows), tap a word for the popup with every imported dictionary. Also how the Japanese dictionaries are
 * checked before Japanese episodes exist.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(library: Library, onBack: () -> Unit) {
    val lookup = remember { JapaneseLookup(library.knownJa.japanese, library.yomitan) }
    var text by remember { mutableStateOf(library.settings.prefs.getString("search_text", "") ?: "") }
    var tokens by remember { mutableStateOf<List<JapaneseModel.Token>>(emptyList()) }
    var picked by remember { mutableStateOf<JapaneseModel.Token?>(null) }
    var headwords by remember { mutableStateOf<List<JapaneseLookup.Headword>?>(null) }
    var said by remember { mutableStateOf("") }
    BackHandler { onBack() }

    LaunchedEffect(text) {
        library.settings.prefs.edit().putString("search_text", text).apply()
        if (!library.knownJa.modelReady) { said = "Download the Japanese dictionary (Sudachi) in Settings first."; return@LaunchedEffect }
        tokens = runCatching { withContext(Dispatchers.Default) { library.knownJa.japanese.parse(listOf(text.trim())).first() } }.getOrDefault(emptyList())
        if (tokens.size == 1 && tokens[0].isWord) picked = tokens[0]
    }
    LaunchedEffect(picked) {
        val t = picked ?: return@LaunchedEffect
        headwords = null
        val started = System.currentTimeMillis()
        headwords = withContext(Dispatchers.IO) { runCatching { lookup.lookup(text.trim(), t.begin) }.getOrElse { said = it.message ?: it.toString(); emptyList() } }
        said = "${headwords?.size ?: 0} words · ${System.currentTimeMillis() - started} ms"
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Japanese dictionary") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(text, { text = it; picked = null; headwords = null }, label = { Text("Japanese text") },
                modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tokens.forEach { t ->
                    val status = if (t.isWord) library.knownJa.status(t.base) else "k"
                    val color = when { !t.isWord -> Colors.dim; status == "k" -> Colors.text; status == "l" -> Colors.accent; else -> Colors.unknown }
                    Text(t.surface, fontSize = 24.sp, color = color, modifier = Modifier
                        .background(if (t == picked) Colors.surface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable(enabled = t.isWord) { picked = t }.padding(horizontal = 2.dp))
                }
            }
            if (said.isNotEmpty()) Text(said, fontSize = 12.sp, color = Colors.dim)
            headwords?.let { YomitanPopup(library, Lang.JAPANESE, it) { s -> library.voiceJa.speak(s) } }
        }
    }
}
