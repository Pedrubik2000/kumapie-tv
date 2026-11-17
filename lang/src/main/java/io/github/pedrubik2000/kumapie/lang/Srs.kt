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
import kotlin.random.Random

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

    data class Card(
        val id: Long, val note: Long, val ord: Int, val word: String, val sentence: String, val meaning: String,
        val fields: String, val tags: String, val show: String?, val episode: String?, val scene: Int?,
        val created: Long, val modified: Long, val stability: Double?, val difficulty: Double?, val lastReview: Long?, val due: Long?,
    )

    /** Where a rating happened: subs = shown|blurred|hidden, heard = times heard since the card's last rating. */
    data class Watch(val episode: String? = null, val scene: Int? = null, val subs: String? = null,
                     val lookedUp: Boolean? = null, val speed: Double? = null, val heard: Int? = null)

    /** FSRS-6 with the parameters and this language's retention fitted on the PC (`meta`), else the defaults. */
    val fsrs: Fsrs6 by lazy {
        val w = meta("params")?.let { JSONArray(it) }?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } } ?: Fsrs6.DEFAULT
        val r = meta("retention")?.let { JSONObject(it).optDouble(lang.code, 0.9) } ?: 0.9
        Fsrs6(w, r)
    }

    fun meta(key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun cards(): List<Card> = db.rawQuery("SELECT $CARD_COLS FROM cards", null).use { c -> buildList { while (c.moveToNext()) add(card(c)) } }

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
            try { db.insertOrThrow("cards", null, v); return card(id)!! } catch (_: SQLiteConstraintException) { id++ }
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
        db.beginTransaction()
        try {
            db.insertOrThrow("revlog", null, ContentValues().apply {
                put("id", newId(now)); put("card", cardId); put("time", now); put("rating", rating); put("answer_ms", answerMs)
                put("device", device); put("s_before", before?.stability); put("d_before", before?.difficulty)
                put("s_after", after.stability); put("d_after", after.difficulty); put("r", r)
                put("episode", watch.episode); put("scene", watch.scene); put("subs", watch.subs)
                put("looked_up", watch.lookedUp?.let { if (it) 1 else 0 }); put("speed", watch.speed); put("heard", watch.heard)
            })
            cache(cardId, after, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
            db.insertOrThrow("revlog", null, ContentValues().apply {
                put("id", newId(now)); put("card", cardId); put("time", now); put("rating", 0); put("device", device)
                put("s_before", c.stability); put("d_before", c.difficulty)
                put("s_after", back?.state?.stability); put("d_after", back?.state?.difficulty)
            })
            if (back == null) cache(cardId, null, null) else cache(cardId, back.state, back.lastReview)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return back?.state
    }

    /** A carded word seen in a scene that played to its end without being rated. */
    fun expose(cardId: Long, episode: String?, scene: Int?, subs: String?, now: Long = System.currentTimeMillis()) {
        db.insertOrThrow("exposures", null, ContentValues().apply {
            put("id", newId(now)); put("card", cardId); put("time", now); put("episode", episode); put("scene", scene); put("subs", subs)
        })
    }

    /** The cached state of every card again from its revlog (after a sync brought other devices' rows). */
    fun replayAll() {
        val rows = HashMap<Long, MutableList<Pair<Long, Int>>>()
        db.rawQuery("SELECT card, time, rating FROM revlog ORDER BY time, id", null).use { c ->
            while (c.moveToNext()) rows.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getLong(1) to c.getInt(2))
        }
        db.beginTransaction()
        try {
            for ((id, r) in rows) replay(r, fsrs).let { cache(id, it?.state, it?.lastReview) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

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
        ) + listOf("revlog", "exposures").flatMap { t ->
            listOf("UPDATE", "DELETE").map { op ->
                "CREATE TRIGGER ${t}_no_${op.lowercase()} BEFORE $op ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END"
            }
        }
    }
}
