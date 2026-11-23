package io.github.pedrubik2000.kumapie.lang

import org.json.JSONObject
import java.io.File

class JitenStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    class Entry(val at: Long, val value: TitleDifficulty?)

    private val entries = HashMap<Int, Entry>()
    private val anilist = HashMap<String, Int>()

    init {
        runCatching {
            val j = JSONObject(file.readText())
            j.optJSONObject("ids")?.let { o -> o.keys().forEach { anilist[it] = o.getInt(it) } }
            j.optJSONObject("t")?.let { o ->
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    val v = e.optJSONObject("v")?.let { TitleDifficulty(it.getDouble("raw"), it.getInt("deck"), it.optInt("words", -1).takeIf { n -> n >= 0 }, it.optInt("min", -1).takeIf { n -> n >= 0 }) }
                    entries[k.toInt()] = Entry(e.getLong("at"), v)
                }
            }
        }
    }

    @Synchronized
    fun entry(anilistId: Int): Entry? = entries[anilistId]

    @Synchronized
    fun fresh(anilistId: Int): Boolean = entries[anilistId]?.let { clock() - it.at < WEEK_MS } == true

    @Synchronized
    fun put(anilistId: Int, value: TitleDifficulty?) {
        entries[anilistId] = Entry(clock(), value)
        save()
    }

    @Synchronized
    fun anilistOf(kitsu: String): Int? = anilist[kitsu]?.takeIf { it > 0 }

    @Synchronized
    fun knowsKitsu(kitsu: String): Boolean = kitsu in anilist

    @Synchronized
    fun linkKitsu(kitsu: String, anilistId: Int?) {
        anilist[kitsu] = anilistId ?: 0
        save()
    }

    @Synchronized
    fun linkAll(ids: Map<String, Int>) {
        var changed = false
        ids.forEach { (k, v) -> if ((anilist[k] ?: 0) <= 0 && v > 0) { anilist[k] = v; changed = true } }
        if (changed) save()
    }

    private fun save() {
        val t = JSONObject()
        entries.forEach { (k, e) ->
            val o = JSONObject().put("at", e.at)
            e.value?.let { v ->
                o.put("v", JSONObject().put("raw", v.raw).put("deck", v.deckId).apply {
                    v.words?.let { put("words", it) }
                    v.speechMinutes?.let { put("min", it) }
                })
            }
            t.put(k.toString(), o)
        }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("ids", JSONObject(anilist as Map<*, *>)).put("t", t).toString())
    }

    companion object {
        const val WEEK_MS = 7L * 24 * 3600 * 1000
    }
}
