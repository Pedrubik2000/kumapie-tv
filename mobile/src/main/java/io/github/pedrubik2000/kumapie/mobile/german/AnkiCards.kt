package io.github.pedrubik2000.kumapie.mobile.german

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Reads the German notes and their cards from an AnkiDroid on the same device (kuma3 Anki, or any AnkiDroid)
 * through AnkiDroid's content provider, read-only. Each AnkiDroid build has its own provider and permission,
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

    data class Card(val noteId: Long, val reviewed: Boolean, val stability: Double?)

    /** Notes found by an Anki search (`note:"🇩🇪 MvJ"`), with their fields by name. */
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
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/cards"), arrayOf("note_id", "type", "fsrs_stability"),
            search, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Card(c.getLong(0), c.getInt(1) != 0, if (c.isNull(2)) null else c.getDouble(2))
            }
        }
        return out
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
