package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.delay

internal val PANEL_WIDTH = 232.dp
internal val CONTENT_START = RAIL + PANEL_WIDTH + 16.dp
private const val SELECT_AFTER_MS = 300L
private val ROW_PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

@Composable
internal fun LibraryColumn(all: List<Mine>, list: LibraryList, filters: LibraryFilters, entry: FocusRequester, toGrid: FocusRequester?,
                           toRail: () -> FocusRequester, onList: (LibraryList) -> Unit, onFilters: (LibraryFilters) -> Unit, modifier: Modifier) {
    val counts = remember(all) { LibraryList.entries.associateWith { it.of(all).size } }
    val sides = Modifier.fillMaxWidth().focusProperties { toGrid?.let { right = it }; left = toRail() }
    Column(modifier.width(PANEL_WIDTH).arcadePanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Title(tr("Library"), fontSize = 26.sp)
        Section("ANILIST")
        LibraryList.entries.forEach { l ->
            ListRow(l.label(), counts.getValue(l), l == list, { onList(l) }, sides.then(if (l == list) Modifier.focusRequester(entry) else Modifier))
        }
        Section(tr("Show only").uppercase())
        CheckRow(tr("In progress"), filters.inProgress, sides) { onFilters(filters.copy(inProgress = !filters.inProgress)) }
        CheckRow(tr("On Home"), filters.onHome, sides) { onFilters(filters.copy(onHome = !filters.onHome)) }
        Section(tr("Difficulty").uppercase())
        StepRow(tr("Up to"), limitLabel(filters.limit), sides,
            { by -> onFilters(filters.copy(limit = (filters.limit + by).coerceIn(0, LIMIT_MAX))) }, {})
    }
}

@Composable
internal fun Section(text: String) =
    Text(text, color = Colors.dim, fontSize = 13.sp, letterSpacing = 2.sp, style = Arcade.label, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

@Composable
internal fun ListRow(label: String, count: Int?, selected: Boolean, onRest: () -> Unit, modifier: Modifier) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(on) { if (on) { delay(SELECT_AFTER_MS); onRest() } }
    Button(onClick = {}, modifier = modifier.onFocusChanged { on = it.isFocused }, colors = arcadeButtonColors(selected), padding = ROW_PADDING) {
        Text(label, modifier = Modifier.weight(1f))
        count?.let { Text("$it") }
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, modifier: Modifier, onToggle: () -> Unit) =
    Button(onClick = onToggle, modifier = modifier, padding = ROW_PADDING) {
        Box(Modifier.size(18.dp).background(if (checked) Arcade.accent else Color.Transparent, RoundedCornerShape(4.dp))
            .border(2.dp, if (checked) Arcade.accent else WHITE_DIM, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = Color.Black, fontSize = 13.sp)
        }
        Text(label, modifier = Modifier.padding(start = 10.dp))
    }
