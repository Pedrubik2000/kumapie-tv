package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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

internal enum class PickerKind { GENRES, TAGS }

internal class PickerLinks(val genres: FocusRequester, val tags: FocusRequester, val open: (PickerKind) -> Unit)

internal fun picksLabel(p: ChipPicks): String = if (!p.any) tr("Any") else listOfNotNull(
    p.include.size.takeIf { it > 0 }?.let { "$it ✓" }, p.exclude.size.takeIf { it > 0 }?.let { "$it ✕" }).joinToString(" ")

@Composable
internal fun LibraryColumn(all: List<Mine>, list: LibraryList, filters: LibraryFilters, entry: FocusRequester, toGrid: FocusRequester?,
                           toRail: () -> FocusRequester, onList: (LibraryList) -> Unit, onFilters: (LibraryFilters) -> Unit, picker: PickerLinks, modifier: Modifier) {
    val counts = remember(all) { LibraryList.entries.associateWith { it.of(all).size } }
    val sides = Modifier.fillMaxWidth().focusProperties { toGrid?.let { right = it }; left = toRail() }
    var text by remember { mutableStateOf(filters.query) }
    Column(modifier.width(PANEL_WIDTH).arcadePanel().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Title(tr("Library"), fontSize = 26.sp)
        TvTextField(text, { text = it }, onDone = { onFilters(filters.copy(query = text.trim())) }, sides, placeholder = tr("Search my %d titles", all.size))
        Section("ANILIST")
        LibraryList.entries.forEach { l ->
            ListRow(l.label(), counts.getValue(l), l == list, { onList(l) }, sides.then(if (l == list) Modifier.focusRequester(entry) else Modifier), toGrid != null)
        }
        Section(tr("Filters").uppercase())
        StepRow(tr("Sort"), filters.sort.label(), sides, { by -> onFilters(filters.copy(sort = filters.sort.step(by))) }, {}, flat = true)
        StepRow(tr("Difficulty"), limitLabel(filters.limit), sides,
            { by -> onFilters(filters.copy(limit = (filters.limit + by).coerceIn(0, LIMIT_MAX))) }, {}, flat = true)
        StepRow(tr("Genres"), picksLabel(filters.genres), sides.then(Modifier.focusRequester(picker.genres)), {}, {}, flat = true, onOpen = { picker.open(PickerKind.GENRES) })
        StepRow(tr("Tags"), picksLabel(filters.tags), sides.then(Modifier.focusRequester(picker.tags)), {}, {}, flat = true, onOpen = { picker.open(PickerKind.TAGS) })
        StepRow(tr("Format"), filters.format.label(), sides, { by -> onFilters(filters.copy(format = filters.format.step(by))) }, {}, flat = true)
        StepRow(tr("Episodes"), filters.episodes.label(), sides, { by -> onFilters(filters.copy(episodes = filters.episodes.step(by))) }, {}, flat = true)
        StepRow(tr("Year"), filters.year.label(), sides, { by -> onFilters(filters.copy(year = filters.year.step(by))) }, {}, flat = true)
        StepRow(tr("Airing"), filters.airing.label(), sides, { by -> onFilters(filters.copy(airing = filters.airing.step(by))) }, {}, flat = true)
        StepRow(tr("Show only"), if (filters.inProgress) tr("In progress") else tr("All"), sides, { onFilters(filters.copy(inProgress = !filters.inProgress)) }, {}, flat = true)
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
internal fun ListRow(label: String, count: Int?, selected: Boolean, onSelect: () -> Unit, modifier: Modifier, canEnter: Boolean = true) =
    FlatButton(onSelect, modifier.hint(if (canEnter) HintKind.ROW else HintKind.SELECT), selected) {
        Text(label, modifier = Modifier.weight(1f))
        count?.let { Text("$it", color = LocalContentColor.current.copy(alpha = 0.6f)) }
    }
