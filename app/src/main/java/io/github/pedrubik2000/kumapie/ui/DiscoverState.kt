package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.BestForYou
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings

internal const val NEXT_PICKS = 4
internal const val DUEL_SIZE = 8

@Stable
internal class Link {
    val to = FocusRequester()
    var ready by mutableStateOf(false)
}

internal class Ranked(val list: List<Mine>, val items: List<BestForYou.Item>)

internal class Picks(val current: Mine?, val next: List<Mine>)

internal class DiscoverEnv(val api: Discovery, val settings: Settings, val levels: Levels?, val link: Link, val entry: FocusRequester,
                           val onSettings: () -> Unit, private val opener: (Found, String?) -> Unit) {
    fun open(f: Found, cover: String? = null) = opener(f, cover)
}

@Stable
internal class PickState(private val store: PickStore) {
    var hidden by mutableStateOf(store.hidden())
    var never by mutableStateOf(store.never())
    var chosen by mutableStateOf<Int?>(null)
    var kept by mutableStateOf<Set<String>?>(null)
    var failed by mutableStateOf(false)

    fun notForMe(id: Int) {
        store.notForMe(id)
        hidden = store.hidden()
        never = store.never()
        chosen = null
    }

    fun skip(id: Int) {
        store.skip(id)
        hidden = store.hidden()
        chosen = null
    }

    fun onHome(found: String) {
        kept = kept.orEmpty() + found
        chosen = null
    }
}

@Stable
internal class BrowseState(initial: BrowseFilters, private val settings: Settings) {
    var filters by mutableStateOf(initial)
    var text by mutableStateOf(initial.query)
    var rows by mutableStateOf<List<Pair<String, List<Found>>>?>(null)
    var results by mutableStateOf<List<Found>?>(null)
    var error by mutableStateOf<String?>(null)
    var attempt by mutableIntStateOf(0)
    var fresh = false

    fun pick(f: BrowseFilters) {
        if (f.query.isEmpty() && filters.query.isNotEmpty()) text = ""
        filters = f
        settings.browseLimit = f.limit
    }
}

@Composable
internal fun LoadBrowse(api: Discovery, state: BrowseState, active: Boolean) {
    LaunchedEffect(active, state.attempt) {
        if (!active || state.fresh) return@LaunchedEffect
        state.error = null
        if (state.rows == null) state.rows = (api as? Addons)?.lastBoard()
        runCatching { api.board() }.onSuccess { state.rows = it; state.fresh = true }.onFailure { if (state.rows == null) state.error = it.message ?: it.toString() }
    }
    LaunchedEffect(active, state.filters.query, state.attempt) {
        state.results = null
        if (!active || state.filters.query.isEmpty()) return@LaunchedEffect
        state.error = null
        runCatching { api.discover(state.filters.query) }.onSuccess { state.results = it }.onFailure { state.error = it.message ?: it.toString() }
    }
}
