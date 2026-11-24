package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class AnkiLookups(private val context: Context) {
    class NoteType(val name: String, val fields: List<String>)

    suspend fun apps(): List<String> = withContext(Dispatchers.IO) { runCatching { Cards.get(context).installed() }.getOrDefault(emptyList()) }

    class Listing(val decks: List<String> = emptyList(), val types: List<NoteType> = emptyList(), val problem: String? = null)

    suspend fun list(pkg: String): Listing = withContext(Dispatchers.IO) {
        if (!Cards.get(context).hasPermission(pkg)) return@withContext Listing()
        try {
            val types = whileAnkiStarts {
                read(pkg, "models", arrayOf("name", "field_names")) { c -> c.getString(0)?.let { NoteType(it, fieldNames(c.getString(1).orEmpty())) } }
            }.filterNotNull().sortedBy { it.name }
            val decks = whileAnkiStarts { read(pkg, "decks", arrayOf("deck_name")) { it.getString(0) } }.filterNotNull().distinct().sorted()
            Listing(decks, types)
        } catch (e: AnkiNotSetUp) {
            Listing(problem = tr("Open %s once first.", if (pkg == "com.ichi2.anki") "AnkiDroid" else "kuma3 Anki"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("kumapie", "anki lists not read: $e")
            Listing(problem = e.message ?: e.toString())
        }
    }

    private fun <T> read(pkg: String, path: String, columns: Array<String>, row: (android.database.Cursor) -> T): List<T> {
        return context.contentResolver.query(Uri.parse("content://$pkg.flashcards/$path"), columns, null, null, null)
            ?.use { c -> buildList { while (c.moveToNext()) add(row(c)) } }.orEmpty()
    }

    companion object {
        fun fieldNames(raw: String): List<String> {
            val text = raw.trim()
            if (text.startsWith("[") && text.endsWith("]")) {
                val json = runCatching { JSONArray(text) }.getOrNull()
                if (json != null) return List(json.length()) { json.getString(it) }
            }
            return text.split('\u001f').filter { it.isNotEmpty() }
        }
    }
}
