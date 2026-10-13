package io.github.pedrubik2000.kumapie.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.lang.Japanese
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Word colours for other apps (chimahon's manga OCR boxes): `content://<app id>.words/colours`, selectionArgs = one
 * Japanese text. One row per word: start, length, state as the subtitles colour it ([Word.shownFor]: "k" known,
 * "u" unknown, "d" due today, "b" mined and never reviewed). Read-only; needs kumapie's READ_WORDS permission.
 */
class WordColours : ContentProvider() {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val japanese by lazy {
        val ctx = context!!.applicationContext
        Japanese(ctx, Settings(ctx), YomitanDictionaries.get(ctx), background).also { ja ->
            // Card states (green, blue) come from kuma3: read it once in this process, the due queue each minute.
            // ponytail: second KnownWords next to the app's own; one shared instance if they ever disagree.
            background.launch { ja.known.refresh() }
        }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val out = MatrixCursor(arrayOf("start", "length", "state"))
        val text = args?.firstOrNull() ?: return out
        if (uri.lastPathSegment != "colours" || !japanese.model.isReady) return out
        val known = japanese.known
        background.launch { known.refreshDue() }
        for (t in japanese.model.words(listOf(text)).first()) {
            if (!t.isWord) continue
            val w = known.paint(t.base, Word(status = known.status(t.base) ?: "u", meaning = "", forms = mapOf(t.surface to "")))
            out.addRow(arrayOf<Any>(t.begin, t.surface.length, w.shownFor(t.surface)))
        }
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
