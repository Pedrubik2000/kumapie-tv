package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import io.github.pedrubik2000.kumapie.data.AniListBrowse
import io.github.pedrubik2000.kumapie.data.BestForYou
import io.github.pedrubik2000.kumapie.data.Discovery
import io.github.pedrubik2000.kumapie.data.Found
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal const val NEXT_PICKS = 4
internal const val DUEL_SIZE = 8
private const val RESTORE_LIMIT_MS = 2_000L

@Stable
internal class Link {
    val to = FocusRequester()
    var ready by mutableStateOf(false)
}

internal class Ranked(val list: List<Mine>, val items: List<BestForYou.Item>)

internal class Picks(val current: Mine?, val next: List<Mine>)


internal const val UNDO_MS = 6_000L

internal class Undo(val skip: Boolean, val id: Int, val until: Long)

internal fun <T> Result<T>.rethrowCancel(): Result<T> = onFailure { if (it is CancellationException) throw it }

@Stable
internal class PickState(private val store: PickStore, val clock: () -> Long = System::currentTimeMillis) {
    var hidden by mutableStateOf(store.hidden())
    var chosen by mutableStateOf<Int?>(null)
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

internal typealias BrowseFetch = suspend (AniListBrowse.Query, Int) -> AniListBrowse.Page

private const val BROWSE_DEBOUNCE_MS = 400L
private const val EMPTY_PAGES = 3

@Stable
internal class BrowseState(initial: BrowseFilters, private val settings: Settings) {
    var filters by mutableStateOf(initial)
    var text by mutableStateOf(initial.query)
    var items by mutableStateOf<List<Mine>>(emptyList())
    var total by mutableIntStateOf(0)
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var moreError by mutableStateOf<String?>(null)
    var expired by mutableStateOf(false)
    var emptyNote by mutableStateOf<String?>(null)
    var attempt by mutableIntStateOf(0)
    var moreTick by mutableIntStateOf(0)
    var picker by mutableStateOf<PickerKind?>(null)
    var last: PickerKind? = null
    val links = PickerLinks(FocusRequester(), FocusRequester()) { k -> picker = k; last = k }
    var hasNext = false
    var page = 1
        private set
    private var moreBusy = false
    private var generation = 0
    private var begun = false
    private var seenAttempt = 0
    private var fetchedFor: Any? = null
    private var query = AniListBrowse.Query()

    fun pick(f: BrowseFilters) {
        if (f.query.isEmpty() && filters.query.isNotEmpty()) text = ""
        filters = f
        settings.browseLimit = f.limit
    }

    fun retry() {
        fetchedFor = null
        attempt++
    }

    fun requestMore() {
        if (hasNext && !moreBusy && loaded && moreError == null) moreTick++
    }

    fun retryMore() {
        moreError = null
        requestMore()
    }

    suspend fun first(key: Any, q: AniListBrowse.Query?, fetch: BrowseFetch) {
        if (fetchedFor == key) return
        val g = ++generation
        val immediate = !begun || attempt != seenAttempt
        begun = true
        seenAttempt = attempt
        moreBusy = false
        moreError = null
        if (!immediate) delay(BROWSE_DEBOUNCE_MS)
        error = null
        emptyNote = null
        loaded = false
        try {
            if (q == null) return finish(g, key, AniListBrowse.Page(emptyList(), 0, false), 1, query, tr("Your Planning list is empty"))
            var pages = 1
            var p = fetch(q, 1)
            var all = p.items
            while (all.isEmpty() && p.hasNext && pages < EMPTY_PAGES) {
                pages++
                p = fetch(q, pages)
                all = all + p.items
            }
            finish(g, key, AniListBrowse.Page(all, p.total, p.hasNext, p.expired), pages, q, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (g == generation) error = e.message ?: e.toString()
        }
    }

    private fun finish(g: Int, key: Any, p: AniListBrowse.Page, pages: Int, q: AniListBrowse.Query, note: String?) {
        if (g != generation) return
        items = p.items; total = p.total; hasNext = p.hasNext; expired = p.expired; page = pages; query = q; emptyNote = note; fetchedFor = key; loaded = true
    }

    suspend fun more(fetch: BrowseFetch) {
        if (!hasNext || moreBusy || !loaded) return
        moreBusy = true
        val g = generation
        val next = page + 1
        moreError = null
        try {
            val p = fetch(query, next)
            if (g != generation) return
            val have = items.map { it.anilistId }.toSet()
            items = items + p.items.filter { it.anilistId !in have }; hasNext = p.hasNext; page = next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (g == generation) moreError = e.message ?: e.toString()
        } finally {
            if (g == generation) moreBusy = false
        }
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
internal fun LoadPicks(pick: PickState) {
    LaunchedEffect(Unit) { pick.ready = true }
}

@Composable
internal fun LoadBrowse(state: BrowseState, fetch: BrowseFetch, planning: List<Int>?, active: Boolean) {
    val needs = state.filters.needsPlanning
    val key = state.filters.copy(limit = 0) to planning.takeIf { needs }
    LaunchedEffect(active, key, state.attempt) {
        if (!active || (needs && planning == null)) return@LaunchedEffect
        val q = if (needs && planning.orEmpty().isEmpty()) null else state.filters.toQuery(planning.orEmpty())
        state.first(key, q, fetch)
    }
    LaunchedEffect(state.moreTick) { if (state.moreTick > 0 && active) state.more(fetch) }
    LaunchedEffect(state.picker == null) {
        state.last?.takeIf { state.picker == null }?.let { (if (it == PickerKind.GENRES) state.links.genres else state.links.tags).focusSoon(); state.last = null }
    }
}
