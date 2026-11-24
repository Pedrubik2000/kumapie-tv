package io.github.pedrubik2000.kumapie.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TitlePage(
    private val d: Discovery,
    val title: Found,
    private val has: () -> Set<String> = { Playable.device },
    private val fresh: suspend (JSONObject) -> Unit = {},
) {
    var meta by mutableStateOf<JSONObject?>(null)
    var error by mutableStateOf<String?>(null)
    var picking by mutableStateOf<Pair<String, String>?>(null)
    var files by mutableStateOf<List<JSONObject>?>(null)
    var kept by mutableStateOf<Boolean?>(null)

    val resuming = d.goingNow()?.firstOrNull { it.first.id == title.id }?.second
    private val going = resuming?.id

    val shown get() = meta?.let(d::found)?.let { f -> if (f.nameJa.isEmpty()) f.copy(nameJa = title.nameJa) else f } ?: title
    val episodes get() = meta?.optJSONArray("episodes")?.objects().orEmpty()
    var aniProgress by mutableStateOf<Int?>(null)
    val next get() = aniNext() ?: nextUp(episodes, going)

    private fun aniNext(): JSONObject? {
        val p = aniProgress?.takeIf { it > 0 } ?: return null
        if (resuming?.resume != null) return null
        val numbers = episodes.map { it.optInt("number") }
        if (numbers.distinct().size != numbers.size) return null
        val local = maxOf(resuming?.number ?: 0, episodes.filter { it.optBoolean("watched") }.maxOfOrNull { it.optInt("number") } ?: 0)
        return if (p > local) episodes.firstOrNull { it.optInt("number") == p + 1 && !it.optBoolean("watched") } else null
    }

    suspend fun load(cached: JSONObject? = null) {
        if (meta == null && cached != null) meta = cached
        runCatching { d.discoverMeta(title.type, title.id) }.onSuccess {
            val old = meta
            if (withContext(Dispatchers.Default) { it.toString() != old?.toString() }) meta = it
            fresh(it)
        }.onFailure { if (meta == null) error = it.message ?: it.toString() }
    }

    suspend fun retry() { error = null; load() }

    suspend fun loadKept() { kept = runCatching { d.saved().any { it.id == title.id } }.getOrNull() }

    suspend fun mark(e: JSONObject) {
        val id = e.getString("id")
        val on = !e.optBoolean("watched")
        meta = meta?.let { m -> JSONObject(m.toString()).also { c -> c.optJSONArray("episodes").objects().firstOrNull { it.optString("id") == id }?.put("watched", on) } }
        runCatching { d.seen(id, on) }
        load()
    }

    fun play(e: JSONObject?): Map<String, String>? {
        if (e == null) { picking = title.id to title.name; return null }
        val name = "${title.name} ${e.optInt("number")}"
        if (e.optBoolean("ready")) return StreamStart.ready(title, e.getString("id"), name)
        picking = e.getString("id") to name
        return null
    }

    fun others(e: JSONObject) { picking = e.getString("id") to "${title.name} ${e.optInt("number")}" }

    fun closeFiles() { picking = null; error = null }

    fun missing(f: JSONObject) = Playable.missing(f.optString("label") + " " + f.optString("about") + " " + f.optString("file"), has())

    suspend fun loadFiles() {
        files = null
        val (id, _) = picking ?: return
        runCatching { d.discoverStreams(title.type, id).objects() }
            .onSuccess { l -> files = l.sortedBy { missing(it).isNotEmpty() } }
            .onFailure { error = it.message ?: it.toString() }
    }

    suspend fun pick(at: Pair<String, String>, f: JSONObject): Map<String, String> {
        val (query, list) = StreamStart.picked(d, title, at.first, at.second, f, kept)
        list?.let { l -> kept = l.any { it.id == title.id } }
        return query
    }
}
