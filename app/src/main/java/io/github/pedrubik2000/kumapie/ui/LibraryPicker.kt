package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr

internal val INCLUDE_GREEN = Color(0xFF4ADE80)
internal val EXCLUDE_RED = Color(0xFFF87171)
private const val TAGS_SHOWN = 40
private val PICKER_PANEL = Modifier.fillMaxSize().padding(start = RAIL, top = 28.dp, end = 28.dp, bottom = 28.dp)

internal fun styled(line: LibraryLine, plain: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = plain)) { append(line.head) }
    if (line.include.isNotEmpty()) { withStyle(SpanStyle(color = plain)) { append(" · ") }; withStyle(SpanStyle(color = INCLUDE_GREEN)) { append(line.include) } }
    if (line.exclude.isNotEmpty()) { withStyle(SpanStyle(color = plain)) { append(" · ") }; withStyle(SpanStyle(color = EXCLUDE_RED)) { append(line.exclude) } }
}

private fun picksOf(f: LibraryFilters, kind: PickerKind): ChipPicks = if (kind == PickerKind.GENRES) f.genres else f.tags

private fun withPicks(f: LibraryFilters, kind: PickerKind, p: ChipPicks): LibraryFilters = if (kind == PickerKind.GENRES) f.copy(genres = p) else f.copy(tags = p)

private fun names(inList: List<Mine>, kind: PickerKind, picks: ChipPicks): List<Pair<String, Int>> {
    val counts = inList.flatMap { if (kind == PickerKind.GENRES) it.genres else it.tags }.groupingBy { it }.eachCount()
    val base = if (kind == PickerKind.GENRES) counts.toList().sortedBy { it.first }
        else counts.toList().sortedWith(compareBy({ -it.second }, { it.first })).take(TAGS_SHOWN)
    val missing = (picks.include + picks.exclude).filter { n -> base.none { it.first == n } }.map { it to (counts[it] ?: 0) }
    return base + missing
}

@Composable
internal fun LibraryPicker(kind: PickerKind, onKind: (PickerKind) -> Unit, list: LibraryList, inList: List<Mine>, filters: LibraryFilters, line: LibraryLine?,
                           shown: Int, onFilters: (LibraryFilters) -> Unit, onClose: () -> Unit) {
    val picks = picksOf(filters, kind)
    val chips = remember(inList, kind, picks) { names(inList, kind, picks) }
    val first = remember(kind) { FocusRequester() }
    LaunchedEffect(kind) { first.focusSoon() }
    Column(PICKER_PANEL.onBackKey { onClose() }.arcadePanel().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Title(tr("Filter %s", list.label()), fontSize = 30.sp)
            PickerKind.entries.forEach { k ->
                FlatButton({ onKind(k) }, Modifier.hint(HintKind.CHOOSE), selected = k == kind) {
                    Text(if (k == PickerKind.GENRES) tr("Genres") else tr("Tags"))
                }
            }
            Text(tr("OK on a chip: off → ✓ include → ✕ exclude"), color = Colors.dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        Section((if (kind == PickerKind.GENRES) tr("Genres") else tr("Tags")).uppercase() + " · " + tr("counts from your %1\$d %2\$s titles", inList.size, list.label()))
        FlowRow(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            chips.forEachIndexed { i, (name, count) ->
                PickChip(name, count, picks, { onFilters(withPicks(filters, kind, picks.cycle(name))) }, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (line == null) AnnotatedString("") else styled(line, Colors.text), fontSize = 16.sp, modifier = Modifier.weight(1f))
            Button(onClick = { onFilters(withPicks(filters, kind, ChipPicks())) }, modifier = Modifier.hint(HintKind.CHOOSE)) { Text(tr("Clear")) }
            Button(onClick = onClose, modifier = Modifier.hint(HintKind.CHOOSE)) { Text(tr("Show %d titles", shown)) }
        }
    }
}

@Composable
private fun PickChip(name: String, count: Int, picks: ChipPicks, onClick: () -> Unit, modifier: Modifier) {
    val on = name in picks.include
    val off = name in picks.exclude
    val tint = if (on) INCLUDE_GREEN else if (off) EXCLUDE_RED else Colors.text
    androidx.tv.material3.Button(onClick = onClick, modifier = modifier.hint(HintKind.CHOOSE), scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = tint.copy(alpha = if (on || off) 0.14f else 0f), contentColor = tint,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        shape = ButtonDefaults.shape(CircleShape), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        border = ButtonDefaults.border(border = Border(BorderStroke(1.dp, if (on || off) tint else Arcade.line)), focusedBorder = Border.None)) {
        Text((if (on) "✓ " else if (off) "✕ " else "") + name, textDecoration = if (off) TextDecoration.LineThrough else null)
        Text("  $count", color = tint.copy(alpha = 0.6f))
    }
}
