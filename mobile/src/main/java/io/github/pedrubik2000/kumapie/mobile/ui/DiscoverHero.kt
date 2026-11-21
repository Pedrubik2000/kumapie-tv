package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pedrubik2000.kumapie.data.Backdrops
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

/**
 * Discover's top as the TV's (look plan step 2): the hero title (the TV's before the D-pad moves: the first row's first)
 * with its row's name over it, its rating line and text, ▶ Watch (its page, as the TV's) and + Home.
 */
@Composable
internal fun DiscoverHero(library: Library, f: Found, row: String, onOpen: () -> Unit) {
    var kept by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) { kept = runCatching { library.discovery.saved().map { it.id }.toSet() }.getOrNull() }
    val scope = rememberCoroutineScope()
    Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp).fillMaxWidth(if (wide()) 0.55f else 1f)) {
        Text(tr(row).uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
        if (f.logo.isNotEmpty()) TitleLogo(f)
        else Text(f.shownName, color = Color.White, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(f.line, color = Colors.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(f.description.replace(Regex("""\s+"""), " "), color = Colors.dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("▶  " + tr("Watch"), onOpen, primary = true)
            kept?.let { k ->
                val on = f.id in k
                Pill(if (on) "✓ " + tr("On Home") else "+ " + tr("Home"), {
                    scope.launch { runCatching { library.discovery.save(f, !on) }.onSuccess { l -> kept = l.map { it.id }.toSet() } }
                })
            }
        }
    }
}

/** The hero's picture, the title page's (Backdrops) else the catalog's, over the page (a phone: its top), dark where the text and rows are. */
@Composable
internal fun BoxScope.DiscoverBackdrop(library: Library, f: Found) {
    val context = LocalContext.current
    var big by remember(f.id) { mutableStateOf(Backdrops.saved(context, f.id)) }
    LaunchedEffect(f.id) { if (big == null) big = Backdrops.get(context, library.discovery, f.type, f.id) }
    Box(Modifier.fillMaxWidth().then(if (wide()) Modifier.fillMaxHeight() else Modifier.height(380.dp))) {
        AsyncImage(big ?: f.background.ifEmpty { f.poster }, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Colors.background, 0.6f to Colors.background.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to Colors.background)))
    }
}

/** Discover's search, small as the TV's (top right; a phone: in its top bar). */
@Composable
internal fun DiscoverSearch(query: String, onChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier) =
    OutlinedTextField(query, onChange, singleLine = true, placeholder = { Text(tr("Search")) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(28.dp), leadingIcon = { Icon(Icons.Default.Search, null) },
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Colors.background.copy(alpha = 0.7f), focusedContainerColor = Colors.background.copy(alpha = 0.85f),
            unfocusedBorderColor = Glass.line, focusedBorderColor = Color.White.copy(alpha = 0.5f)),
        modifier = modifier)
