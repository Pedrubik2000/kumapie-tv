package io.github.pedrubik2000.kumapie.lang

import android.content.ContentValues
import io.github.pedrubik2000.kumapie.i18n.tr
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Reads the German notes and their cards from an AnkiDroid on the same device (kuma3 Anki, or any AnkiDroid)
 * through AnkiDroid's content provider, and adds the cards mined in kumapie ([Miner]). Each AnkiDroid build has its own provider and permission,
 * named after its app id: kuma3 Anki is `io.github.pedrubik2000.kuma3` (its old test build `com.ichi2.anki.debug`).
 */
class AnkiCards(private val context: Context) {

    /** The AnkiDroid builds installed here, in order of preference. */
    fun installed(): List<String> = PACKAGES.filter {
        context.packageManager.resolveContentProvider("$it.flashcards", 0) != null
    }

    fun permission(pkg: String) = "$pkg.permission.READ_WRITE_DATABASE"

    fun hasPermission(pkg: String) = context.checkSelfPermission(permission(pkg)) == PackageManager.PERMISSION_GRANTED

    data class Note(val id: Long, val fields: Map<String, String>, val tags: Set<String>)

    /** [due]: a new card's position (what morphs' recalc orders). [type]: 0 new, 1 learning, 2 review, 3 relearning. */
    data class Card(val id: Long, val noteId: Long, val reviewed: Boolean, val stability: Double?, val due: Long,
                    val reps: Int = 0, val lapses: Int = 0, val ord: Int = 0, val type: Int = 0)

    /** Notes found by an Anki search (`"note:🐻 German"`), with their fields by name. */
    fun notes(pkg: String, search: String): List<Note> {
        val names = HashMap<Long, List<String>>()
        val out = ArrayList<Note>()
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/notes"), arrayOf("_id", "mid", "flds", "tags"),
            search, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mid = c.getLong(1)
                val fieldNames = names.getOrPut(mid) { fieldNames(pkg, mid) }
                val values = c.getString(2).split(SEPARATOR)
                out += Note(c.getLong(0), fieldNames.zip(values).toMap(), c.getString(3).trim().split(' ').filter { it.isNotEmpty() }.toSet())
            }
        }
        return out
    }

    /** Cards found by an Anki search: reviewed or new, and FSRS stability (days) when it has one. */
    fun cards(pkg: String, search: String): List<Card> {
        val out = ArrayList<Card>()
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/cards"), arrayOf("_id", "note_id", "type", "fsrs_stability", "due", "reps", "lapses", "ord"),
            search, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Card(c.getLong(0), c.getLong(1), c.getInt(2) != 0, if (c.isNull(3)) null else c.getDouble(3), c.getLong(4),
                    c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(2))
            }
        }
        return out
    }

    /**
     * The cards kuma3 would show today (its provider's `kuma3/due`: the deck list's queue, RWKV-Instant aware): card id ->
     * "new" | "learn" | "review". Null when this Anki doesn't have it (older kuma3, AnkiDroid).
     */
    fun due(pkg: String): Map<Long, String>? = runCatching {
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/kuma3/due"), null, null, null, null)?.use { c ->
            HashMap<Long, String>().apply { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }
    }.getOrNull()

    /** Answers a card as kuma3's reviewer does (FSRS-7, RWKV and the review log see it); [ease] 1 Again .. 4 Easy. */
    fun answer(pkg: String, noteId: Long, ord: Int, ease: Int, ms: Long) {
        context.contentResolver.update(Uri.parse("content://$pkg.flashcards/schedule"), ContentValues().apply {
            put("note_id", noteId)
            put("ord", ord)
            put("answer_ease", ease)
            put("time_taken", ms)
        }, null, null)
    }

    // ------------------------------------------------------------------ writing (mining)

    /** The id of the first note type found by name (e.g. "🐻 German", then "🇩🇪 MvJ"), and its field names. */
    fun noteType(pkg: String, names: List<String>): Pair<Long, List<String>>? {
        val found = HashMap<String, Long>()
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/models"), arrayOf("_id", "name"), null, null, null)?.use { c ->
            while (c.moveToNext()) found[c.getString(1)] = c.getLong(0)
        }
        val mid = names.firstNotNullOfOrNull { found[it] } ?: return null
        return mid to fieldNames(pkg, mid)
    }

    /** The deck's id, made when it doesn't exist ("Deutsch::Mined"). */
    fun deck(pkg: String, name: String): Long {
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/decks"), arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getLong(0)
        }
        val uri = context.contentResolver.insert(Uri.parse("content://$pkg.flashcards/decks"),
            ContentValues().apply { put("deck_name", name) }) ?: error(tr("Anki couldn't make the deck %s", name))
        return uri.lastPathSegment!!.toLong()
    }

    /** Puts a file into Anki's media folder; answers the name Anki gave it (for "[audio:name]"). */
    fun addMedia(pkg: String, file: File, preferredName: String): String {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val added = context.contentResolver.insert(Uri.parse("content://$pkg.flashcards/media"), ContentValues().apply {
                put("file_uri", uri.toString())
                put("preferred_name", preferredName)
            }) ?: error(tr("Anki didn't take the file %s", file.name))
            return added.lastPathSegment!!
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Adds a note (fields in the note type's order) and puts its cards in [deck]; answers the note id. */
    fun addNote(pkg: String, mid: Long, fields: List<String>, tags: List<String>, deck: Long): Long {
        val uri = context.contentResolver.insert(Uri.parse("content://$pkg.flashcards/notes"), ContentValues().apply {
            put("mid", mid)
            put("flds", fields.joinToString(SEPARATOR))
            put("tags", tags.joinToString(" "))
        }) ?: error(tr("Anki didn't add the note"))
        val nid = uri.lastPathSegment!!.toLong()
        // Cards go to the note type's last deck; move them, as AnkiDroid's own API does.
        val ords = ArrayList<Int>()
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/notes/$nid/cards"), arrayOf("ord"), null, null, null)?.use { c ->
            while (c.moveToNext()) ords += c.getInt(0)
        }
        for (ord in ords) {
            context.contentResolver.update(Uri.parse("content://$pkg.flashcards/notes/$nid/cards/$ord"),
                ContentValues().apply { put("deck_id", deck) }, null, null)
        }
        return nid
    }

    // ------------------------------------------------------------------ writing (morphs recalc)

    /** New cards' order in one bulk update (kuma3 Anki only: its provider's `kuma3_new_due`); answers how many changed. */
    fun setNewDues(pkg: String, dues: Map<Long, Long>): Int = context.contentResolver.update(
        Uri.parse("content://$pkg.flashcards/cards"),
        ContentValues().apply { put("kuma3_new_due", org.json.JSONObject(dues.mapKeys { it.key.toString() }).toString()) }, null, null)

    /** A note's tags, and its fields (in order) when [fields] is given. */
    fun updateNote(pkg: String, nid: Long, tags: Set<String>, fields: List<String>?) {
        context.contentResolver.update(Uri.parse("content://$pkg.flashcards/notes/$nid"), ContentValues().apply {
            put("tags", tags.joinToString(" "))
            if (fields != null) put("flds", fields.joinToString(SEPARATOR))
        }, null, null)
    }

    /** A new note type with [fields] and one card showing the first field; answers its id. */
    fun addNoteType(pkg: String, name: String, fields: List<String>): Long =
        context.contentResolver.insert(Uri.parse("content://$pkg.flashcards/models"), ContentValues().apply {
            put("name", name)
            put("field_names", fields.joinToString(SEPARATOR))
            put("num_cards", 1)
        })?.lastPathSegment?.toLong() ?: error(tr("Anki didn't add the note type %s", name))

    /**
     * A fresh collection (the parents' kuma3) gets [lang]'s note type from kumapie's copy of Pedro's
     * (`assets/notetypes/<code>.json`: fields, one template, CSS). Answers (id, fields), or null without a copy.
     */
    fun addBundledNoteType(pkg: String, lang: io.github.pedrubik2000.kumapie.data.Lang): Pair<Long, List<String>>? {
        val json = runCatching { context.assets.open("notetypes/${lang.code}.json").use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrNull() ?: return null
        val nt = org.json.JSONObject(json)
        val fields = nt.getJSONArray("fields").let { a -> (0 until a.length()).map { a.getString(it) } }
        val t = nt.getJSONArray("templates").getJSONObject(0)
        val mid = context.contentResolver.insert(Uri.parse("content://$pkg.flashcards/models"), ContentValues().apply {
            put("name", lang.noteTypes.first())
            put("field_names", fields.joinToString(SEPARATOR))
            put("num_cards", 1)
            put("css", nt.getString("css"))
            put("sort_field_index", nt.optInt("sortf"))
        })?.lastPathSegment?.toLong() ?: return null
        context.contentResolver.update(Uri.parse("content://$pkg.flashcards/models/$mid/templates/0"), ContentValues().apply {
            put("name", t.getString("name"))
            put("question_format", t.getString("qfmt"))
            put("answer_format", t.getString("afmt"))
        }, null, null)
        return mid to fields
    }

    /** Sets a card's flag (2 = orange: changed by kumapie, as the PC's improve-card does). */
    fun flag(pkg: String, nid: Long, ord: Int, flag: Int) {
        context.contentResolver.update(Uri.parse("content://$pkg.flashcards/notes/$nid/cards/$ord"),
            ContentValues().apply { put("flags", flag) }, null, null)
    }

    fun deleteNote(pkg: String, nid: Long) {
        context.contentResolver.delete(Uri.parse("content://$pkg.flashcards/notes/$nid"), null, null)
    }

    /** Suspends the note's cards (kuma3 Anki only: its provider's `kuma3_suspend`). */
    fun suspendCards(pkg: String, nid: Long) {
        val ords = ArrayList<Int>()
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/notes/$nid/cards"), arrayOf("ord"), null, null, null)?.use { c ->
            while (c.moveToNext()) ords += c.getInt(0)
        }
        for (ord in ords) context.contentResolver.update(Uri.parse("content://$pkg.flashcards/notes/$nid/cards/$ord"),
            ContentValues().apply { put("kuma3_suspend", true) }, null, null)
    }

    private fun fieldNames(pkg: String, mid: Long): List<String> =
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/models/$mid"), arrayOf("field_names"), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0).split(SEPARATOR) else null } ?: emptyList()

    companion object {
        /**
         * kuma3 Anki first, then its old test build and the Play Store AnkiDroid. Keep in step with the manifest
         * (<uses-permission> and <queries>). Only apps that are set up should be read: reading wakes an AnkiDroid,
         * and one never opened then makes itself a new empty collection.
         */
        val PACKAGES = listOf("io.github.pedrubik2000.kuma3", "com.ichi2.anki.debug", "com.ichi2.anki")
        private const val SEPARATOR = "\u001f"
    }
}
