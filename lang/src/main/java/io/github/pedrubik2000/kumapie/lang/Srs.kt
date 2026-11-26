package io.github.pedrubik2000.kumapie.lang

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import io.github.pedrubik2000.kumapie.data.Lang
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import android.util.Log
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * kumapie's own cards and reviews (kumapie_plan.md "kumapie's own Anki"), one `srs/<person>/<lang>.db` per person
 * and language, the same tables as the PC's `state\feed\srs\<person>.sqlite` (tools\srs\import_kuma3.py reads
 * [SCHEMA] from here).
 *
 * `revlog` and `exposures` are append-only (triggers refuse UPDATE and DELETE): a card's FSRS state on its row is a
 * cache of [replay]ing its revlog in time order with [Fsrs6], so phone, tablet and TV sync by appending only.
 * Undo appends a rating-0 row that cancels the card's last standing rating.
 */
class Srs(context: Context, person: String, val lang: Lang, private val device: String = android.os.Build.MODEL) {
    val db: SQLiteDatabase = File(context.filesDir, "srs/$person").apply { mkdirs() }
        .let { SQLiteDatabase.openOrCreateDatabase(File(it, "${lang.code}.db"), null) }
        .apply { if (version < 1) { beginTransaction(); try { SCHEMA.forEach(::execSQL); version = 1; setTransactionSuccessful() } finally { endTransaction() } } }

    private val app: Context = context.applicationContext

    init { if (lang == Lang.JAPANESE) moveWords() }

    data class Card(
        val id: Long, val note: Long, val ord: Int, val word: String, val sentence: String, val meaning: String,
        val fields: String, val tags: String, val show: String?, val episode: String?, val scene: Int?,
        val created: Long, val modified: Long, val stability: Double?, val difficulty: Double?, val lastReview: Long?, val due: Long?,
    )

    /**
     * Where a rating happened: subs = shown|blurred|hidden, heard = times heard since the card's last rating.
     * [scene] (the `scene` column of cards, revlog and exposures): where the line starts in the episode, in ms, so a
     * subtitle fix can't lose it. Rows from before 2026-11-17 hold a scene number there (they are never rewritten);
     * nothing reads the column back.
     */
    data class Watch(val episode: String? = null, val scene: Int? = null, val subs: String? = null,
                     val lookedUp: Boolean? = null, val speed: Double? = null, val heard: Int? = null)

    /** FSRS-6 with the parameters and this language's retention fitted on the PC (`meta`), else the defaults. */
    val fsrs: Fsrs6 get() = scheduler ?: run {
        val w = meta("params")?.let { JSONArray(it) }?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } } ?: Fsrs6.DEFAULT
        val r = meta("retention")?.let { JSONObject(it).optDouble(lang.code, 0.9) } ?: 0.9
        Fsrs6(w, r).also { scheduler = it }
    }
    @Volatile private var scheduler: Fsrs6? = null

    fun meta(key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Every card but the deleted ones (tag `kumapie::deleted`: the sync has no deletes, a newer row carries the tag; duplicates, user 2026-11-17). */
    fun cards(): List<Card> = db.rawQuery("SELECT $CARD_COLS FROM cards WHERE (' ' || tags || ' ') NOT LIKE '% $DELETED %'", null).use { c -> buildList { while (c.moveToNext()) add(card(c)) } }

    /** How many of [cards] are due by [time] (never-rated ones too), counted by SQLite: loading every card to count took
     *  ~14 % of the Chromecast's main thread at cold start (2026-11-19). Only cards with the line's audio and picture, as
     *  Review shows ([ownReview]; their files are checked there). */
    fun dueCount(time: Long): Int = db.rawQuery("SELECT COUNT(*) FROM cards WHERE (' ' || tags || ' ') NOT LIKE '% $DELETED %' AND IFNULL(due, 0) <= ?" +
        " AND fields LIKE '%[audio:%' AND fields LIKE '%<img%'",
        arrayOf("$time")).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun card(id: Long): Card? = db.rawQuery("SELECT $CARD_COLS FROM cards WHERE id = ?", arrayOf("$id")).use { if (it.moveToFirst()) card(it) else null }

    /** A word just mined: a new card (blue until its first rating). */
    fun addCard(word: String, sentence: String, meaning: String, show: String?, episode: String?, scene: Int?,
                fields: String = "{}", tags: String = "", now: Long = System.currentTimeMillis()): Card {
        var id = now
        while (true) {
            val v = ContentValues().apply {
                put("id", id); put("note", id); put("ord", 0); put("lang", lang.code); put("word", word); put("sentence", sentence)
                put("meaning", meaning); put("fields", fields); put("tags", tags); put("show", show); put("episode", episode)
                put("scene", scene); put("created", now); put("modified", now)
            }
            try { append("cards", v); return card(id)!! } catch (_: SQLiteConstraintException) { id++ }
        }
    }

    /** One rating (1-4) from the player: appended to the revlog with its context; answers the card's new state. */
    fun rate(cardId: Long, rating: Int, watch: Watch = Watch(), answerMs: Long? = null, now: Long = System.currentTimeMillis()): Fsrs6.State {
        require(rating in 1..4)
        val c = requireNotNull(card(cardId)) { "no card $cardId" }
        val before = c.stability?.let { Fsrs6.State(it, c.difficulty!!) }
        val days = c.lastReview?.let { elapsedDays(it, now) } ?: 0
        val r = before?.let { fsrs.retrievability(days.toDouble(), it.stability) }
        val after = fsrs.next(before, days, rating)
        // Scenes it was heard in (played to their end, on any device) since its last rating, or since it was mined.
        val heard = watch.heard ?: db.rawQuery("SELECT COUNT(*) FROM exposures WHERE card = ? AND time > ?",
            arrayOf("$cardId", "${c.lastReview ?: c.created}")).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        db.beginTransaction()
        try {
            append("revlog", ContentValues().apply {
                put("id", newId(now)); put("card", cardId); put("time", now); put("rating", rating); put("answer_ms", answerMs)
                put("device", device); put("s_before", before?.stability); put("d_before", before?.difficulty)
                put("s_after", after.stability); put("d_after", after.difficulty); put("r", r)
                put("episode", watch.episode); put("scene", watch.scene); put("subs", watch.subs)
                put("looked_up", watch.lookedUp?.let { if (it) 1 else 0 }); put("speed", watch.speed); put("heard", heard)
            })
            cache(cardId, after, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        runCatching { DeviceSync.rated(app) } // every rating, from any screen or the provider: the LAN sync follows
        return after
    }

    /** Cancels the card's last standing rating (a rating-0 row); answers its state now (null = new again). */
    fun undo(cardId: Long, now: Long = System.currentTimeMillis()): Fsrs6.State? {
        val c = requireNotNull(card(cardId)) { "no card $cardId" }
        val rows = history(cardId)
        if (replay(rows, fsrs) == null) return null // nothing standing to undo
        val back = replay(rows + (now to 0), fsrs)
        db.beginTransaction()
        try {
            append("revlog", ContentValues().apply {
                put("id", newId(now)); put("card", cardId); put("time", now); put("rating", 0); put("device", device)
                put("s_before", c.stability); put("d_before", c.difficulty)
                put("s_after", back?.state?.stability); put("d_after", back?.state?.difficulty)
            })
            if (back == null) cache(cardId, null, null) else cache(cardId, back.state, back.lastReview)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        runCatching { DeviceSync.rated(app) }
        return back?.state
    }

    /** A carded word seen in a scene that played to its end without being rated. */
    fun expose(cardId: Long, episode: String?, scene: Int?, subs: String?, now: Long = System.currentTimeMillis()) {
        append("exposures", ContentValues().apply {
            put("id", newId(now)); put("card", cardId); put("time", now); put("episode", episode); put("scene", scene); put("subs", subs)
        })
    }

    /** A card's text changed here (Improve, a fixed sentence): newest edit wins on sync. */
    fun updateCard(id: Long, word: String, sentence: String, meaning: String, fields: String, tags: String, now: Long = System.currentTimeMillis()) {
        db.beginTransaction()
        try {
            db.update("cards", ContentValues().apply {
                put("word", word); put("sentence", sentence); put("meaning", meaning); put("fields", fields); put("tags", tags); put("modified", now)
            }, "id = ?", arrayOf("$id"))
            db.insertWithOnConflict("outbox", null, ContentValues().apply { put("kind", "cards"); put("id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun moveWords() {
        for (from in CardIndex.MOVED.keys) db.rawQuery("SELECT $CARD_COLS FROM cards WHERE word LIKE ?", arrayOf("$from%")).use { c -> buildList { while (c.moveToNext()) add(card(c)) } }
            .forEach { c -> CardIndex.moved(c.word)?.let { updateCard(c.id, it, c.sentence, c.meaning, c.fields, c.tags) } }
    }

    /** Card id -> (ratings standing, Agains), as kuma3's reps and lapses. */
    fun counts(): Map<Long, Pair<Int, Int>> = HashMap<Long, Pair<Int, Int>>().apply {
        db.rawQuery("SELECT card, SUM(CASE WHEN rating > 0 THEN 1 ELSE -1 END), SUM(rating = 1) FROM revlog GROUP BY card", null)
            .use { c -> while (c.moveToNext()) put(c.getLong(0), c.getInt(1) to c.getInt(2)) }
    }

    /** The cached state of these cards (null = all) again from their revlog, after a sync brought other devices' rows. */
    fun replayAll(only: Set<Long>? = null) {
        val rows = HashMap<Long, MutableList<Pair<Long, Int>>>()
        db.rawQuery("SELECT card, time, rating FROM revlog ORDER BY time, id", null).use { c ->
            while (c.moveToNext()) if (only == null || c.getLong(0) in only) rows.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getLong(1) to c.getInt(2))
        }
        db.beginTransaction()
        try {
            for ((id, r) in rows) replay(r, fsrs).let { cache(id, it?.state, it?.lastReview) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun revlogIds(): Set<Long> = db.rawQuery("SELECT id FROM revlog", null).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }

    fun take(events: List<Pair<JSONObject, Long>>) {
        db.beginTransaction()
        try {
            for ((e, card) in events) db.insertWithOnConflict("revlog", null, ContentValues().apply {
                put("id", e.getLong("id")); put("card", card); put("time", e.getLong("time")); put("rating", e.getInt("ease")); put("device", e.optString("device"))
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        replayAll(events.map { it.second }.toSet())
    }

    fun useFsrs(params: String?, retention: String?) {
        if (meta("params") == params && meta("retention") == retention) return
        for ((k, v) in listOf("params" to params, "retention" to retention))
            if (v == null) db.delete("meta", "key = ?", arrayOf(k)) else db.insertWithOnConflict("meta", null, values(JSONObject().put("key", k).put("value", v)), SQLiteDatabase.CONFLICT_REPLACE)
        scheduler = null
        replayAll()
    }

    /**
     * Sends this device's new rows (the outbox: kept while offline) and takes everyone else's from the PC
     * (`POST /api/tv/srs/sync`, [post] adds person and language). The first one downloads the whole store.
     * Answers how many rows came in.
     */
    suspend fun sync(post: suspend (JSONObject) -> JSONObject): Int = withContext(Dispatchers.IO) {
        val out = db.rawQuery("SELECT kind, id FROM outbox", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
        val body = JSONObject().put("since", meta("sync_seq")?.toLong() ?: 0L)
        for (t in SYNCED) body.put(t, JSONArray(out.filter { it.first == t }.mapNotNull { row(t, it.second) }))
        val got = post(body)
        // The PC's store was made again (a new kuma3 import, before the switch): start over from it.
        val imported = got.optJSONObject("meta")?.optString("imported_from")?.takeIf { it.isNotEmpty() }
        val mine = meta("imported_from")
        if (imported != null && mine != null && imported != mine) {
            Log.w("kumapie", "srs ${lang.code}: the PC's store is new ($mine -> $imported): downloading it again")
            db.beginTransaction()
            try {
                for (t in listOf("meta", "cards", "revlog", "exposures", "outbox")) db.execSQL("DROP TABLE $t")
                SCHEMA.forEach(db::execSQL)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            scheduler = null
            return@withContext sync(post)
        }
        val rated = HashSet<Long>()
        var n = 0
        db.beginTransaction()
        try {
            got.optJSONObject("meta")?.let { m -> m.keys().forEach { k -> db.insertWithOnConflict("meta", null, values(JSONObject().put("key", k).put("value", m.getString(k))), SQLiteDatabase.CONFLICT_REPLACE) } }
            for (o in got.optJSONArray("cards").objects()) {
                val v = values(o)
                val old = db.rawQuery("SELECT modified FROM cards WHERE id = ?", arrayOf("${o.getLong("id")}")).use { if (it.moveToFirst()) it.getLong(0) else null }
                when {
                    old == null -> db.insertOrThrow("cards", null, v)
                    o.getLong("modified") > old -> db.update("cards", v, "id = ?", arrayOf("${o.getLong("id")}"))
                    else -> continue
                }
                n++
            }
            for (t in listOf("revlog", "exposures")) for (o in got.optJSONArray(t).objects()) {
                if (db.insertWithOnConflict(t, null, values(o), SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    n++
                    if (t == "revlog") rated += o.getLong("card")
                }
            }
            for ((kind, id) in out) db.delete("outbox", "kind = ? AND id = ?", arrayOf(kind, "$id"))
            db.insertWithOnConflict("meta", null, values(JSONObject().put("key", "sync_seq").put("value", "${got.getLong("seq")}")), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        scheduler = null // the PC may have sent new parameters
        if (got.optJSONObject("meta")?.has("params") == true && meta("replayed_params") != meta("params")) {
            replayAll() // new parameters: every card again
            db.insertWithOnConflict("meta", null, values(JSONObject().put("key", "replayed_params").put("value", meta("params"))), SQLiteDatabase.CONFLICT_REPLACE)
        } else if (rated.isNotEmpty()) replayAll(rated)
        n
    }

    /** A row this device made: stored, and queued for the PC. */
    private fun append(table: String, v: ContentValues) {
        db.beginTransaction()
        try {
            db.insertOrThrow(table, null, v)
            db.insertWithOnConflict("outbox", null, ContentValues().apply { put("kind", table); put("id", v.getAsLong("id")) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** One row as the PC takes it (cards without the FSRS cache columns: every device replays its own). */
    private fun row(table: String, id: Long): JSONObject? = db.rawQuery("SELECT * FROM $table WHERE id = ?", arrayOf("$id")).use { c ->
        if (!c.moveToFirst()) return null
        json(c).apply { if (table == "cards") CACHE.forEach(::remove) }
    }

    fun dump(): JSONObject = JSONObject().apply {
        for (t in listOf("meta", "cards", "revlog", "exposures")) put(t, JSONArray(db.rawQuery("SELECT * FROM $t", null).use { c -> buildList { while (c.moveToNext()) add(json(c)) } }))
    }

    private fun json(c: Cursor) = JSONObject().apply {
        for (i in 0 until c.columnCount) put(c.getColumnName(i), when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
            else -> c.getString(i)
        })
    }

    private fun values(o: JSONObject) = ContentValues().apply {
        for (k in o.keys()) when (val v = o.get(k)) {
            JSONObject.NULL -> putNull(k)
            is Int -> put(k, v.toLong())
            is Long -> put(k, v)
            is Double -> put(k, v)
            is Number -> put(k, v.toDouble())
            else -> put(k, v.toString())
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun history(cardId: Long) = db.rawQuery("SELECT time, rating FROM revlog WHERE card = ? ORDER BY time, id", arrayOf("$cardId"))
        .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) } }

    private fun cache(cardId: Long, s: Fsrs6.State?, lastReview: Long?) {
        db.update("cards", ContentValues().apply {
            put("stability", s?.stability); put("difficulty", s?.difficulty); put("last_review", lastReview)
            put("due", if (s == null || lastReview == null) null else lastReview + fsrs.interval(s.stability) * DAY)
        }, "id = ?", arrayOf("$cardId"))
    }

    data class Replayed(val state: Fsrs6.State, val lastReview: Long)

    companion object {
        const val DAY = 86_400_000L
        private val SYNCED = listOf("cards", "revlog", "exposures")
        private val CACHE = setOf("stability", "difficulty", "last_review", "due")

        /** Row id for revlog and exposures: ms * 1000 + a random 0..999, so two devices never collide. */
        fun newId(timeMs: Long = System.currentTimeMillis()) = timeMs * 1000 + Random.nextInt(1000)

        /** Whole days between two ratings, as py-fsrs counts them (24 h periods). */
        fun elapsedDays(from: Long, to: Long) = Math.floorDiv(to - from, DAY).toInt().coerceAtLeast(0)

        /**
         * A card's state from its revlog (time, rating) in time order: a rating 0 cancels the last standing rating;
         * the standing ones go through [Fsrs6] one by one. Null = never rated (or every rating undone).
         */
        fun replay(rows: List<Pair<Long, Int>>, fsrs: Fsrs6): Replayed? {
            val standing = ArrayList<Pair<Long, Int>>()
            for (r in rows) if (r.second == 0) standing.removeLastOrNull() else standing.add(r)
            var out: Replayed? = null
            for ((t, rating) in standing) {
                val days = out?.let { elapsedDays(it.lastReview, t) } ?: 0
                out = Replayed(fsrs.next(out?.state, days, rating), t)
            }
            return out
        }

        /** Start of the next study day (4 am local, as Anki): a card is due today when its due is before this. */
        fun nextDay(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
            val t = Instant.ofEpochMilli(now).atZone(zone)
            val four = t.toLocalDate().atTime(LocalTime.of(4, 0)).atZone(zone)
            return (if (t.isBefore(four)) four else four.plusDays(1)).toInstant().toEpochMilli()
        }

        /** [CardIndex] state of one card: "d" due today, "b" never rated (blue), "k" studied and not due. */
        fun state(due: Long?, now: Long = System.currentTimeMillis()): String = when {
            due == null -> "b"
            due < nextDay(now) -> "d"
            else -> "k"
        }

        /** The tag of a deleted card ([cards] skips it). */
        const val DELETED = "kumapie::deleted"
        private const val CARD_COLS = "id, note, ord, word, sentence, meaning, fields, tags, show, episode, scene, created, modified, stability, difficulty, last_review, due"

        private fun card(c: Cursor) = Card(
            c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7),
            c.getStringOrNull(8), c.getStringOrNull(9), if (c.isNull(10)) null else c.getInt(10), c.getLong(11), c.getLong(12),
            if (c.isNull(13)) null else c.getDouble(13), if (c.isNull(14)) null else c.getDouble(14),
            if (c.isNull(15)) null else c.getLong(15), if (c.isNull(16)) null else c.getLong(16),
        )

        private fun Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)

        val SCHEMA = listOf(
            // FSRS parameters ("params", JSON array of 21), "retention" ({lang: r}) and where they came from.
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            // word = the Mined note's Word field as is (German keeps its forms: "verstehe[→ verstehen]");
            // ord: kuma3's card ordinal; fields = the note's other text fields (JSON); tags space-separated as in
            // Anki (known-manually = known). Text only: no images, audio or video. modified = newest edit wins on sync.
            """CREATE TABLE cards (
                id INTEGER PRIMARY KEY, note INTEGER NOT NULL, ord INTEGER NOT NULL DEFAULT 0, lang TEXT NOT NULL,
                word TEXT NOT NULL, sentence TEXT NOT NULL DEFAULT '', meaning TEXT NOT NULL DEFAULT '',
                fields TEXT NOT NULL DEFAULT '{}', tags TEXT NOT NULL DEFAULT '', show TEXT, episode TEXT, scene INTEGER,
                created INTEGER NOT NULL, modified INTEGER NOT NULL,
                stability REAL, difficulty REAL, last_review INTEGER, due INTEGER)""",
            "CREATE UNIQUE INDEX cards_note_ord ON cards(note, ord)",
            "CREATE INDEX cards_word ON cards(word)",
            // One rating (1-4; 0 = undo of the card's last standing rating). time/answer_ms in ms; s/d before (null =
            // new) and after (null = new again after an undo); r = predicted recall then. Context: subs =
            // shown|blurred|hidden; looked_up 0/1 before rating; heard = times heard since last rating.
            """CREATE TABLE revlog (
                id INTEGER PRIMARY KEY, card INTEGER NOT NULL, time INTEGER NOT NULL, rating INTEGER NOT NULL,
                answer_ms INTEGER, device TEXT NOT NULL,
                s_before REAL, d_before REAL, s_after REAL, d_after REAL, r REAL,
                episode TEXT, scene INTEGER, subs TEXT, looked_up INTEGER, speed REAL, heard INTEGER)""",
            "CREATE INDEX revlog_card ON revlog(card, time)",
            // A carded word seen in a scene that played to its end without being rated.
            """CREATE TABLE exposures (
                id INTEGER PRIMARY KEY, card INTEGER NOT NULL, time INTEGER NOT NULL,
                episode TEXT, scene INTEGER, subs TEXT)""",
            "CREATE INDEX exposures_card ON exposures(card, time)",
            // Device only: rows made here and not yet taken by the PC (the offline queue). Empty on the PC.
            "CREATE TABLE outbox (kind TEXT NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (kind, id))",
        ) + listOf("revlog", "exposures").flatMap { t ->
            listOf("UPDATE", "DELETE").map { op ->
                "CREATE TRIGGER ${t}_no_${op.lowercase()} BEFORE $op ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END"
            }
        }
    }
}
