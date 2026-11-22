package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.DeviceFile.objects
import org.json.JSONArray
import org.json.JSONObject

object WordSync {
    class Elsewhere(val state: String, val device: String)

    @Volatile var words: Map<String, Map<String, Elsewhere>> = emptyMap()
        set(v) { field = v; loaded = true }
    @Volatile private var loaded = false
    @Volatile var insist = false
    private val warned = HashSet<String>()

    fun state(lang: String, form: String): String? = words[lang]?.get(form)?.state

    fun ensure(load: () -> Unit) { if (!loaded) synchronized(this) { if (!loaded) { runCatching(load); loaded = true } } }

    fun carded(mine: JSONObject, theirs: JSONObject?, device: String): JSONObject {
        val out = JSONObject(mine.toString())
        theirs?.keys()?.forEach { d ->
            val t = theirs.getJSONObject(d)
            if (d != device && t.optLong("made") > (out.optJSONObject(d)?.optLong("made") ?: -1)) out.put(d, t)
        }
        return out
    }

    fun own(device: String, made: Long, keys: Collection<String>): JSONObject =
        JSONObject().put(device, JSONObject().put("made", made).put("keys", JSONArray(keys.distinct().sorted())))

    fun count(before: JSONObject, after: JSONObject, device: String): DeviceFile.Count {
        fun keys(o: JSONObject) = o.keys().asSequence().filter { it != device }
            .flatMap { d -> o.getJSONObject(d).optJSONArray("keys").let { a -> List(a?.length() ?: 0) { "$d|" + a!!.getString(it) } } }.toSet()
        val b = keys(before)
        val a = keys(after)
        return DeviceFile.Count("Words with a card on another device", (a - b).size, (b - a).size, (a intersect b).size)
    }

    fun toAdd(events: JSONArray, cards: Map<Long, String?>, have: Set<Long>): List<Pair<JSONObject, Long>> {
        val byKey = HashMap<String, Long>()
        for ((id, key) in cards.entries.sortedBy { it.key }) if (key != null) byKey.putIfAbsent(key, id)
        return events.objects().filter { it.getLong("id") !in have }.mapNotNull { e -> byKey[e.getString("key")]?.let { e to it } }
    }

    fun elsewhere(events: JSONArray, carded: JSONObject, device: String, here: Set<String>, fsrs: (String) -> Fsrs6,
                  now: Long = System.currentTimeMillis()): Map<String, Map<String, Elsewhere>> {
        val where = HashMap<String, String>()
        for (d in carded.keys()) if (d != device) carded.getJSONObject(d).optJSONArray("keys")?.let { a ->
            for (i in 0 until a.length()) a.getString(i).takeIf { it !in here }?.let { where.putIfAbsent(it, d) }
        }
        val rows = events.objects().filter { it.getString("key") in where }
            .groupBy({ it.getString("key") }, { it.getLong("time") to it.getInt("ease") })
        val out = HashMap<String, HashMap<String, Elsewhere>>()
        for ((key, d) in where) {
            val lang = key.substringBefore('|')
            val f = fsrs(lang)
            val r = rows[key]?.let { Srs.replay(it.sortedBy { p -> p.first }, f) }
            val e = Elsewhere(Srs.state(r?.let { it.lastReview + f.interval(it.state.stability) * Srs.DAY }, now), d)
            val form = key.substringAfter('|').lowercase()
            val m = out.getOrPut(lang) { HashMap() }
            m[form] = e
            m.putIfAbsent(form.substringBefore('\t'), e)
        }
        return out
    }

    fun refusal(lang: String, key: String?): String? {
        val form = key?.substringAfter('|')?.lowercase() ?: return null
        val e = words[lang]?.get(form) ?: return null
        if (insist) return null
        return synchronized(warned) { if (warned.add("$lang|$form")) tr("Already a card on %s. Mine it again to make one here too.", e.device) else null }
    }
}
