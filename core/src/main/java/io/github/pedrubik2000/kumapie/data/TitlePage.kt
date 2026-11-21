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

    private val going = d.goingNow()?.firstOrNull { it.first.id == title.id }?.second?.id

    val shown get() = meta?.let(d::found) ?: title
    val episodes get() = meta?.optJSONArray("episodes")?.objects().orEmpty()
    val next get() = nextUp(episodes, going)

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

    suspend fun keep() {
        val on = kept ?: return
        runCatching { d.save(title, !on) }.onSuccess { l -> kept = l.any { it.id == title.id } }
    }

    suspend fun mark(e: JSONObject) {
        runCatching { d.seen(e.getString("id"), !e.optBoolean("watched")) }
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
