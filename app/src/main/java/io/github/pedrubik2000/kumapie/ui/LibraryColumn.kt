package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr

internal val PANEL_WIDTH = 232.dp
internal val CONTENT_START = RAIL + PANEL_WIDTH + 16.dp
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
            { by -> onFilters(filters.copy(limit = (filters.limit + by).coerceIn(0, LIMIT_MAX))) }, {}, flat = true)
    }
}

@Composable
internal fun Section(text: String) =
    Text(text, color = Colors.dim, fontSize = 13.sp, letterSpacing = 2.sp, style = Arcade.label, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

@Composable
internal fun FlatButton(onClick: () -> Unit, modifier: Modifier, selected: Boolean = false, content: @Composable RowScope.() -> Unit) =
    androidx.tv.material3.Button(onClick = onClick, modifier = modifier, scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = if (selected) Arcade.accent.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = if (selected) Arcade.accent else Colors.text, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)), contentPadding = ROW_PADDING,
        border = ButtonDefaults.border(border = Border.None, focusedBorder = Border.None)) { ProvideTextStyle(Arcade.label) { content() } }

@Composable
internal fun ListRow(label: String, count: Int?, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) =
    FlatButton(onSelect, modifier, selected) {
        Text(label, modifier = Modifier.weight(1f))
        count?.let { Text("$it", color = LocalContentColor.current.copy(alpha = 0.6f)) }
    }

@Composable
internal fun CheckRow(label: String, checked: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    FlatButton(onToggle, modifier.onFocusChanged { focused = it.isFocused }) {
        Box(Modifier.size(18.dp).background(if (!checked) Color.Transparent else if (focused) Color.Black else Arcade.accent, RoundedCornerShape(4.dp))
            .border(2.dp, if (focused) Color.Black else if (checked) Arcade.accent else WHITE_DIM, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = if (focused) Color.White else Color.Black, fontSize = 13.sp)
        }
        Text(label, modifier = Modifier.padding(start = 10.dp))
    }
}
