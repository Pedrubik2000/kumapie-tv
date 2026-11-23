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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.i18n.tr

private val NEXT_PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

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
            ListRow(m.label(), null, m == mode, { onMode(m) }, sides.then(place).then(toLine))
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
            Button(onClick = { onPick(m) }, modifier = sides, padding = NEXT_PADDING) {
                Cover(m.cover, "", Modifier.width(32.dp).height(48.dp).clip(RoundedCornerShape(6.dp)), showTitle = false, description = null)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(m.shown(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    levels?.of(m.kitsuId)?.let { DifficultyTag(it.percent, Modifier) }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.BrowseFilterRows(state: BrowseState, loggedIn: Boolean, sides: Modifier) {
    val catalogs = listOf("") + state.rows.orEmpty().map { it.first }
    val f = state.filters
    Section(tr("Filters").uppercase())
    StepRow(tr("Catalog"), f.catalog.ifEmpty { tr("All") }, sides,
        { by -> state.pick(f.copy(catalog = catalogs[Math.floorMod(catalogs.indexOf(f.catalog) + by, catalogs.size)], query = "")) }, {})
    TvTextField(state.text, { state.text = it }, onDone = { state.pick(f.copy(query = state.text.trim())) }, sides, placeholder = tr("Search"))
    if (loggedIn) StepRow(tr("My list"), if (f.notOnList) tr("Not on it") else tr("Any"), sides, { state.pick(f.copy(notOnList = !f.notOnList)) }, {})
    Section(tr("Difficulty").uppercase())
    StepRow(tr("Up to"), limitLabel(f.limit), sides, { by -> state.pick(f.copy(limit = (f.limit + by).coerceIn(0, LIMIT_MAX))) }, {})
}
