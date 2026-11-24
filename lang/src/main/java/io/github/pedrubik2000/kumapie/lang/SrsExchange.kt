package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Lang
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

internal const val EXCHANGE_CAP = 100_000

internal fun Srs.merge(got: JSONObject, mine: Boolean, touched: MutableSet<Long>): Int {
    var n = 0
    for (o in got.optJSONArray("cards").objects()) {
        val v = values(o)
        val old = db.rawQuery("SELECT modified FROM cards WHERE id = ?", arrayOf("${o.getLong("id")}")).use { if (it.moveToFirst()) it.getLong(0) else null }
        when {
            old == null -> db.insertOrThrow("cards", null, v)
            o.getLong("modified") > old -> db.update("cards", v, "id = ?", arrayOf("${o.getLong("id")}"))
            else -> continue
        }
        n++
        if (mine) { queue("cards", o.getLong("id")); touched += o.getLong("id") }
    }
    for (t in listOf("revlog", "exposures")) for (o in got.optJSONArray(t).objects()) {
        if (db.insertWithOnConflict(t, null, values(o), android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
            n++
            if (t == "revlog") touched += o.getLong("card")
            if (mine) queue(t, o.getLong("id"))
        }
    }
    return n
}

/** The items taken in order until their running [size] has passed [cap] (the item being added is always finished); true when more were left. */
internal fun <T> takeCapped(items: Sequence<T>, size: (T) -> Int, cap: Int = EXCHANGE_CAP): Pair<List<T>, Boolean> {
    val out = ArrayList<T>()
    var total = 0
    for (item in items) {
        if (total > cap) return out to true
        out += item
        total += size(item)
    }
    return out to false
}

internal class Mine(val count: Int, val modified: Long, val maxId: Long)

internal class Theirs(val count: Int, val modified: Long, val maxId: Long?)

internal fun callerShouldSend(mine: Mine?, theirs: Theirs): Boolean = mine == null || mine.modified < theirs.modified || mine.count < theirs.count ||
    (mine.count == theirs.count && theirs.maxId != null && theirs.maxId != mine.maxId)

internal fun sendCard(mine: Mine, theirs: Theirs?): Boolean = theirs == null || mine.modified > theirs.modified

internal fun sendRevlog(mine: Mine, theirs: Theirs?): Boolean =
    theirs == null || mine.count != theirs.count || (theirs.maxId != null && theirs.maxId != mine.maxId)

internal fun cardsOfWord(keys: List<Pair<Long, List<String>>>, word: String, reading: String): List<Long> {
    val hit = keys.filter { (if (reading.isBlank()) word else CardIndex.japaneseKey(word, reading)) in it.second }.map { it.first }
    return if (reading.isBlank() && hit.size != 1) emptyList() else hit
}

private class Out(val card: JSONObject?, val revlog: List<JSONObject>) {
    val size = (card?.toString()?.length ?: 0) + revlog.sumOf { it.toString().length }
}

fun Srs.exchange(body: JSONObject): JSONObject {
    val skipped = HashSet<Long>()
    val cards = body.optJSONArray("cards").objects().filter { (it.optString("lang") == lang.code).also { ok -> if (!ok) skipped += it.getLong("id") } }
    val touched = HashSet<Long>()
    var n = 0
    db.beginTransaction()
    try {
        n = merge(JSONObject().put("cards", JSONArray(cards)), true, touched)
        val rest = JSONObject()
        for (t in listOf("revlog", "exposures")) rest.put(t, JSONArray(body.optJSONArray(t).objects().filter { o ->
            o.getLong("card") !in skipped && modifiedOf(o.getLong("card")) != null
        }))
        n += merge(rest, true, touched)
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
    touched += cards.map { it.getLong("id") }
    touched += body.optJSONArray("revlog").objects().map { it.getLong("card") }.filter { it !in skipped }
    runCatching { replayAll(touched) }.onFailure { android.util.Log.w("kumapie", "srs exchange replay: $it") }

    val have = body.optJSONObject("have")?.let { h ->
        h.keys().asSequence().associate { k -> k.toLong() to h.getJSONArray(k).let { Theirs(it.getInt(0), it.getLong(1), if (it.length() > 2) it.getLong(2) else null) } }
    }.orEmpty()
    val mine = mineNow()
    val missing = have.keys.sorted().filter { it !in skipped && callerShouldSend(mine[it], have.getValue(it)) }
    val asked = if (lang.code == Lang.JAPANESE.code) body.optJSONArray("words").objects() else emptyList()
    val keys = if (asked.isEmpty()) emptyList() else keysByCard()
    val adopted = asked.map {
        JSONObject().put("word", it.getString("word")).put("reading", it.optString("reading"))
            .put("cards", JSONArray(cardsOfWord(keys, it.getString("word"), it.optString("reading"))))
    }
    val first = adopted.flatMap { a -> a.getJSONArray("cards").let { c -> List(c.length()) { c.getLong(it) } } }
    val items = (first.asSequence() + have.keys.sorted().asSequence()).distinct().mapNotNull { id ->
        val m = mine[id] ?: return@mapNotNull null
        val theirs = have[id]
        val card = if (sendCard(m, theirs)) row("cards", id) else null
        val rows = if (sendRevlog(m, theirs)) revlogOf(id) else emptyList()
        if (card == null && rows.isEmpty()) null else Out(card, rows)
    }
    val (taken, more) = takeCapped(items, { it.size })
    val meta = JSONObject().apply { for (k in listOf("params", "retention")) meta(k)?.let { put(k, it) } }
    return JSONObject().put("seq", System.currentTimeMillis()).put("meta", meta)
        .put("cards", JSONArray(taken.mapNotNull { it.card })).put("revlog", JSONArray(taken.flatMap { it.revlog }))
        .put("exposures", JSONArray()).put("in", n).put("missing", JSONArray(missing)).put("refused", JSONArray(skipped.sorted()))
        .put("adopted", JSONArray(adopted)).put("read", true).put("more", more)
}

private fun Srs.mineNow(): Map<Long, Mine> {
    val stats = HashMap<Long, Pair<Int, Long>>()
    db.rawQuery("SELECT card, COUNT(*), MAX(id) FROM revlog GROUP BY card", null).use { while (it.moveToNext()) stats[it.getLong(0)] = it.getInt(1) to it.getLong(2) }
    return db.rawQuery("SELECT id, modified FROM cards", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getLong(0), Mine(stats[c.getLong(0)]?.first ?: 0, c.getLong(1), stats[c.getLong(0)]?.second ?: 0)) }
    }
}

private fun Srs.keysByCard(): List<Pair<Long, List<String>>> = cards().map { c ->
    val f = runCatching { JSONObject(c.fields) }.getOrNull()
    val all = buildMap { f?.keys()?.forEach { put(it, f.optString(it)) }; put("Word", c.word) }
    c.id to CardIndex.japaneseKeys(AnkiCards.Note(c.note, all, emptySet()))
}

private fun Srs.modifiedOf(id: Long): Long? =
    db.rawQuery("SELECT modified FROM cards WHERE id = ?", arrayOf("$id")).use { if (it.moveToFirst()) it.getLong(0) else null }

private fun Srs.revlogOf(id: Long): List<JSONObject> =
    db.rawQuery("SELECT * FROM revlog WHERE card = ? ORDER BY time, id", arrayOf("$id")).use { c -> buildList { while (c.moveToNext()) add(json(c)) } }
