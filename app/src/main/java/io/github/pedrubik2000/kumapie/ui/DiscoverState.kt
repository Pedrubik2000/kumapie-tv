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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal const val NEXT_PICKS = 4
internal const val DUEL_SIZE = 8
private const val SAVED_WAIT_MS = 2_000L
private const val RESTORE_LIMIT_MS = 2_000L

@Stable
internal class Link {
    val to = FocusRequester()
    var ready by mutableStateOf(false)
}

internal class Ranked(val list: List<Mine>, val items: List<BestForYou.Item>)

internal class Picks(val current: Mine?, val next: List<Mine>)

internal class Kept(val ids: Set<String>)

internal const val UNDO_MS = 6_000L

internal class Undo(val skip: Boolean, val id: Int, val until: Long)

internal fun <T> Result<T>.rethrowCancel(): Result<T> = onFailure { if (it is CancellationException) throw it }

@Stable
internal class PickState(private val store: PickStore, val clock: () -> Long = System::currentTimeMillis) {
    var hidden by mutableStateOf(store.hidden())
    var chosen by mutableStateOf<Int?>(null)
    var loaded by mutableStateOf(Kept(emptySet()))
    var ready by mutableStateOf(false)
    var duel: Knockout? = null
    var undo by mutableStateOf<Undo?>(null)


    fun notForMe(id: Int, name: String) {
        store.notForMe(id, name)
        hidden = store.hidden()
        chosen = null
        duel = null
        undo = Undo(false, id, clock() + UNDO_MS)
    }

    fun skip(id: Int) {
        store.skip(id)
        hidden = store.hidden()
        chosen = null
        duel = null
        undo = Undo(true, id, clock() + UNDO_MS)
    }

    fun modeChanged(from: DiscoverMode, to: DiscoverMode) {
        if (from != to) undo = null
    }

    var toHome = false

    fun liveUndo(): Undo? = undo?.takeIf { clock() < it.until }

    fun undoLast(): Boolean {
        val u = undo ?: return false
        if (u.skip) store.undoSkip(u.id) else store.undoNotForMe(u.id)
        hidden = store.hidden()
        chosen = u.id
        duel = null
        undo = null
        return true
    }

}

@Stable
internal class BrowseState(initial: BrowseFilters, private val settings: Settings) {
    var filters by mutableStateOf(initial)
    var text by mutableStateOf(initial.query)
    var rows by mutableStateOf<List<Pair<String, List<Found>>>?>(null)
    var results by mutableStateOf<List<Found>?>(null)
    var error by mutableStateOf<String?>(null)
    var searchError by mutableStateOf<String?>(null)
    var attempt by mutableIntStateOf(0)
    var fresh = false
    var resultsFor = ""

    fun pick(f: BrowseFilters) {
        if (f.query.isEmpty() && filters.query.isNotEmpty()) text = ""
        filters = f
        settings.browseLimit = f.limit
    }

    fun retry() {
        fresh = false
        resultsFor = ""
        attempt++
    }
}

@Stable
internal class DiscoverHold(startMode: DiscoverMode, val pick: PickState, val browse: BrowseState) {
    var mode by mutableStateOf(startMode)
    var inContent by mutableStateOf(false)
    var restoring by mutableStateOf(false)
    private var leftMode: DiscoverMode? = null
    private var leftFrom: Any? = null

    val returning: Boolean get() = leftFrom != null

    fun leave(from: Any?) {
        pick.undo = null
        leftMode = mode
        leftFrom = from
        restoring = mode != DiscoverMode.BROWSE
    }

    fun take(m: DiscoverMode): Any? {
        if (leftMode != m) return null
        val from = leftFrom
        leftMode = null
        leftFrom = null
        return from
    }

    fun dropRestore() {
        leftMode = null
        leftFrom = null
        restoring = false
    }

    suspend fun giveUpRestoring() {
        if (!restoring) return
        delay(RESTORE_LIMIT_MS)
        dropRestore()
    }
}

internal class DiscoverEnv(val api: Discovery, val settings: Settings, val levels: Levels?, val link: Link, val entry: FocusRequester,
                           val onSettings: () -> Unit, val hold: DiscoverHold, private val opener: (Found) -> Unit) {
    fun open(f: Found, from: Any? = null) {
        hold.leave(from)
        opener(f)
    }
}

@Composable
internal fun LoadPicks(api: Discovery, pick: PickState, active: Boolean) {
    LaunchedEffect(Unit) {
        if (!active) return@LaunchedEffect
        runCatching { api.saved().map { it.id }.toSet() }.rethrowCancel().onSuccess { pick.loaded = Kept(it) }
        pick.ready = true
    }
    LaunchedEffect(Unit) {
        delay(SAVED_WAIT_MS)
        pick.ready = true
    }
}

@Composable
internal fun LoadBrowse(api: Discovery, state: BrowseState, active: Boolean) {
    LaunchedEffect(active, state.attempt) {
        if (!active || state.fresh) return@LaunchedEffect
        state.error = null
        if (state.rows == null) state.rows = (api as? Addons)?.lastBoard()
        runCatching { api.board() }.rethrowCancel().onSuccess { state.rows = it; state.fresh = true }.onFailure { if (state.rows == null) state.error = it.message ?: it.toString() }
    }
    val query = state.filters.query
    LaunchedEffect(active, query, state.attempt) {
        state.searchError = null
        if (query.isEmpty()) {
            state.results = null
            state.resultsFor = ""
            return@LaunchedEffect
        }
        if (!active || (state.resultsFor == query && state.results != null)) return@LaunchedEffect
        state.results = null
        runCatching { api.discover(query) }.rethrowCancel().onSuccess { state.results = it; state.resultsFor = query }.onFailure { state.searchError = it.message ?: it.toString() }
    }
}
