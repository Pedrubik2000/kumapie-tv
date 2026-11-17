package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.pedrubik2000.kumapie.data.Lang
import java.io.File
import kotlin.random.Random

/**
 * kumapie's own cards and reviews (kumapie_plan.md "kumapie's own Anki"), one `srs/<person>/<lang>.db` per person
 * and language. Step 1: the schema only; the callers of AnkiCards switch here in step 4.
 *
 * `revlog` and `exposures` are append-only (triggers refuse UPDATE and DELETE): a card's FSRS state on its row is a
 * cache of replaying its revlog in time order with [Fsrs6], so phone, tablet and TV sync by appending only.
 * An Undo is therefore a new revlog row, not a deleted one.
 */
class Srs(context: Context, person: String, lang: Lang) {
    val db: SQLiteDatabase = File(context.filesDir, "srs/$person").apply { mkdirs() }
        .let { SQLiteDatabase.openOrCreateDatabase(File(it, "${lang.code}.db"), null) }
        .apply { if (version < 1) { beginTransaction(); try { SCHEMA.forEach(::execSQL); version = 1; setTransactionSuccessful() } finally { endTransaction() } } }

    companion object {
        /** Row id for revlog and exposures: ms * 1000 + a random 0..999, so two devices never collide. */
        fun newId(timeMs: Long = System.currentTimeMillis()) = timeMs * 1000 + Random.nextInt(1000)

        val SCHEMA = listOf(
            // word/forms as the Mined note has them; ord: kuma3's card ordinal (Japanese notes have several cards).
            // Text only: no images, audio or video. modified = newest edit wins on sync.
            """CREATE TABLE cards (
                id INTEGER PRIMARY KEY, note INTEGER NOT NULL, ord INTEGER NOT NULL DEFAULT 0,
                word TEXT NOT NULL, forms TEXT NOT NULL DEFAULT '', sentence TEXT NOT NULL DEFAULT '',
                meaning TEXT NOT NULL DEFAULT '', show TEXT, episode TEXT, scene INTEGER,
                created INTEGER NOT NULL, modified INTEGER NOT NULL,
                stability REAL, difficulty REAL, last_review INTEGER, due INTEGER)""",
            "CREATE UNIQUE INDEX cards_note_ord ON cards(note, ord)",
            "CREATE INDEX cards_word ON cards(word)",
            // One rating. time/answer_ms in ms; s/d before (null = first rating) and after; r = predicted recall then.
            // Context: subs = shown|blurred|hidden; looked_up 0/1 before rating; heard = times heard since last rating.
            """CREATE TABLE revlog (
                id INTEGER PRIMARY KEY, card INTEGER NOT NULL, time INTEGER NOT NULL, rating INTEGER NOT NULL,
                answer_ms INTEGER, device TEXT NOT NULL,
                s_before REAL, d_before REAL, s_after REAL NOT NULL, d_after REAL NOT NULL, r REAL,
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
