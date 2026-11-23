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
    private var blockedUntil = 0L
    private val failed = HashMap<String, Long>()
    private val flow = MutableStateFlow<Map<String, TitleDifficulty?>>(emptyMap())

    val results: StateFlow<Map<String, TitleDifficulty?>> get() = flow

    fun cached(kitsu: String): TitleDifficulty? = store.anilistOf(kitsu)?.let { store.entry(it)?.value }

    fun levelOf(kitsu: String): TitleDifficulty? = cached(kitsu).also { if (!isFresh(kitsu)) ask(listOf(kitsu), false) }

    private fun isFresh(kitsu: String) = if (!store.knowsKitsu(kitsu)) false else store.anilistOf(kitsu)?.let { store.fresh(it) } ?: true

    @Synchronized
    fun ask(kitsuIds: List<String>, priority: Boolean = true) {
        val wanted = kitsuIds.distinct().filter { !isFresh(it) && clock() - (failed[it] ?: -RETRY_MS) >= RETRY_MS }
        if (priority) {
            pending.removeAll(wanted.toSet())
            wanted.asReversed().forEach { pending.addFirst(it) }
        } else wanted.filter { it !in pending }.forEach { pending.addLast(it) }
    }

    @Synchronized
    private fun next(): String? = if (idle() && clock() >= blockedUntil) pending.removeFirstOrNull() else null

    @Synchronized
    private fun back(kitsu: String) = pending.addFirst(kitsu)

    suspend fun step(): Boolean {
        val kitsu = next() ?: return false
        val id = if (store.knowsKitsu(kitsu)) store.anilistOf(kitsu) else {
            val found = runCatching { resolve(kitsu) }.getOrElse { failed[kitsu] = clock(); return true }
            found.also { store.linkKitsu(kitsu, it) }
        }
        if (id == null) {
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
            JitenResult.Failed -> failed[kitsu] = clock()
        }
        return true
    }

    suspend fun run() {
        while (true) if (!step()) delay(1000)
    }

    companion object {
        const val REFUSED_MS = 6L * 3600 * 1000
        const val RETRY_MS = 60_000L
    }
}
