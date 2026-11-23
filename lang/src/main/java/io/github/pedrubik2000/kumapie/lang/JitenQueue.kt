package io.github.pedrubik2000.kumapie.lang

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class JitenQueue(
    private val store: JitenStore,
    private val api: JitenApi,
    private val resolve: suspend (String) -> Int?,
    private val idle: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val pending = ArrayDeque<String>()
    private val queued = HashSet<String>()
    private val shared = HashSet<String>()
    private val lent = HashMap<Any, MutableSet<String>>()
    private val released = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    private var blockedUntil = 0L
    private val failed = HashMap<String, Long>()
    private val flow = MutableStateFlow<Map<String, TitleDifficulty?>>(emptyMap())

    val results: StateFlow<Map<String, TitleDifficulty?>> get() = flow

    fun cached(kitsu: String): TitleDifficulty? = store.anilistOf(kitsu)?.let { store.entry(it)?.value }

    fun levelOf(kitsu: String): TitleDifficulty? = cached(kitsu).also { if (!isFresh(kitsu)) ask(listOf(kitsu), false) }

    private fun isFresh(kitsu: String) = if (!store.knowsKitsu(kitsu)) false else store.anilistOf(kitsu)?.let { store.fresh(it) } ?: true

    fun know(ids: Map<String, Int>) = store.linkAll(ids)

    @Synchronized
    fun ask(kitsuIds: List<String>, priority: Boolean = true, owner: Any? = null) {
        if (owner != null && owner in released) return
        val wanted = kitsuIds.distinct().filter { !isFresh(it) && clock() - (failedAt(it) ?: -RETRY_MS) >= RETRY_MS }
        if (priority) {
            val moving = wanted.filter { it in queued }.toSet()
            if (moving.isNotEmpty()) pending.removeAll(moving)
            wanted.asReversed().forEach { pending.addFirst(it) }
            queued += wanted
        } else wanted.filter { queued.add(it) }.forEach { pending.addLast(it) }
        if (priority || owner == null) shared += wanted else lent.getOrPut(owner) { HashSet() } += wanted
    }

    @Synchronized
    fun release(owner: Any) {
        released += owner
        drop(owner)
    }

    @Synchronized
    fun drop(owner: Any) {
        val mine = lent.remove(owner) ?: return
        val gone = mine.filter { it in queued && it !in shared && lent.values.none { s -> it in s } }.toSet()
        if (gone.isEmpty()) return
        pending.removeAll(gone)
        queued -= gone
    }

    @Synchronized
    private fun next(): String? = if (idle() && clock() >= blockedUntil) pending.removeFirstOrNull()?.also { k ->
        queued.remove(k)
        shared.remove(k)
        lent.values.forEach { it.remove(k) }
    } else null

    @Synchronized
    private fun back(kitsu: String) {
        pending.addFirst(kitsu)
        queued += kitsu
        shared += kitsu
    }

    @Synchronized
    private fun fail(kitsu: String) {
        failed[kitsu] = clock()
    }

    @Synchronized
    private fun failedAt(kitsu: String): Long? = failed[kitsu]

    suspend fun step(): Boolean {
        val kitsu = next() ?: return false
        var asked = false
        val id = if (store.knowsKitsu(kitsu)) store.anilistOf(kitsu) else {
            asked = true
            val found = runCatching { resolve(kitsu) }.getOrElse { fail(kitsu); delay(RESOLVE_GAP_MS); return true }
            store.linkResolved(kitsu, found)
        }
        if (id == null) {
            if (asked) delay(RESOLVE_GAP_MS)
            flow.value = flow.value + (kitsu to null)
            return true
        }
        if (store.fresh(id)) {
            flow.value = flow.value + (kitsu to store.entry(id)?.value)
            return true
        }
        when (val r = api.lookup(id)) {
            is JitenResult.Got -> {
                store.put(id, r.value)
                flow.value = flow.value + (kitsu to r.value)
            }
            is JitenResult.Limited -> { blockedUntil = clock() + r.seconds * 1000L; back(kitsu) }
            JitenResult.Refused -> {
                if (blockedUntil <= clock()) log("api.jiten.moe refused the request (418/403): no more asks for 6 hours")
                blockedUntil = clock() + REFUSED_MS
                back(kitsu)
            }
            JitenResult.Failed -> fail(kitsu)
        }
        return true
    }

    suspend fun run() {
        while (true) if (!step()) delay(1000)
    }

    companion object {
        const val REFUSED_MS = 6L * 3600 * 1000
        const val RETRY_MS = 60_000L
        const val RESOLVE_GAP_MS = 500L
    }
}
