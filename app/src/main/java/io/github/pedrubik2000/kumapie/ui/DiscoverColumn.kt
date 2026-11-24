package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr

@Composable
internal fun DiscoverColumn(mode: DiscoverMode, entry: FocusRequester, link: Link, toRail: () -> FocusRequester, onMode: (DiscoverMode) -> Unit,
                            modifier: Modifier, extras: @Composable ColumnScope.(Modifier) -> Unit) {
    val firstRow = remember { FocusRequester() }
    val sides = Modifier.fillMaxWidth().focusProperties { if (link.ready) right = link.to; left = toRail() }
    Column(modifier.width(PANEL_WIDTH).arcadePanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Title(tr("Discover"), fontSize = 26.sp)
        DiscoverMode.entries.forEachIndexed { i, m ->
            val place = if (m == mode) Modifier.focusRequester(entry) else if (i == 0) Modifier.focusRequester(firstRow) else Modifier
            val toLine = if (i == 0) Modifier.focusProperties { up = MissingKeyLine.up(if (m == mode) entry else firstRow) } else Modifier
            ListRow(m.label(), null, m == mode, { onMode(m) }, sides.then(place).then(toLine), link.ready)
        }
        extras(sides)
    }
}

@Composable
internal fun ColumnScope.NextPicks(next: List<Mine>, levels: Levels?, sides: Modifier, onPick: (Mine) -> Unit) {
    if (next.isEmpty()) return
    Section(tr("Next picks").uppercase())
    next.forEachIndexed { i, m ->
        key(i) {
            FlatButton({ onPick(m) }, sides.hint(HintKind.NEXT)) {
                Cover(m.cover, "", Modifier.width(32.dp).height(48.dp).clip(RoundedCornerShape(6.dp)), showTitle = false, description = null)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(m.shown(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    levels?.of(m.kitsuId)?.let { DifficultyTag(it, Modifier) }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.BrowseFilterRows(state: BrowseState, loggedIn: Boolean, sides: Modifier) {
    val f = state.filters
    TvTextField(state.text, { state.text = it }, onDone = { state.pick(f.copy(query = state.text.trim())) },
        sides.onFocusChanged { if (!it.isFocused && state.text.trim() != f.query) state.pick(f.copy(query = state.text.trim())) }, placeholder = tr("Search all anime"))
    Section(tr("Filters").uppercase())
    StepRow(tr("Sort"), f.sort.label(), sides, { by -> state.pick(f.copy(sort = f.sort.step(by))) }, {}, flat = true)
    StepRow(tr("Genres"), picksLabel(f.genres), sides.focusRequester(state.links.genres), {}, {}, flat = true, onOpen = { state.links.open(PickerKind.GENRES) })
    StepRow(tr("Tags"), picksLabel(f.tags), sides.focusRequester(state.links.tags), {}, {}, flat = true, onOpen = { state.links.open(PickerKind.TAGS) })
    StepRow(tr("Format"), f.format.label(), sides, { by -> state.pick(f.copy(format = f.format.step(by))) }, {}, flat = true)
    StepRow(tr("Year"), f.year.label(), sides, { by -> state.pick(f.copy(year = f.year.step(by))) }, {}, flat = true)
    StepRow(tr("Airing"), f.airing.label(), sides, { by -> state.pick(f.copy(airing = f.airing.step(by))) }, {}, flat = true)
    StepRow(tr("Episodes"), f.episodes.label(), sides, { by -> state.pick(f.copy(episodes = f.episodes.step(by))) }, {}, flat = true)
    if (loggedIn) StepRow(tr("My list"), f.mine.label(), sides, { by -> state.pick(f.copy(mine = f.mine.step(by, true))) }, {}, flat = true)
    StepRow(tr("Difficulty"), limitLabel(f.limit), sides, { by -> state.pick(f.copy(limit = (f.limit + by).coerceIn(0, LIMIT_MAX))) }, {}, flat = true)
}
