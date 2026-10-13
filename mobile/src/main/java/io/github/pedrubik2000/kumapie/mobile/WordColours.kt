package io.github.pedrubik2000.kumapie.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.lang.CardIndex
import io.github.pedrubik2000.kumapie.lang.Japanese
import io.github.pedrubik2000.kumapie.lang.KnownWords
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Word colours for other apps (chimahon's manga OCR boxes): `content://<app id>.words/colours`, selectionArgs = one
 * Japanese text. One row per word: start, length, state as the subtitles colour it ([Word.shownFor]: "k" known,
 * "u" unknown, "d" due today, "b" mined and never reviewed). Needs kumapie's READ_WORDS permission; [call] rates cards.
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

    /**
     * The word card's rating row for other apps (Hoshi's popup), as [io.github.pedrubik2000.kumapie.mobile.ui.RatingRows]:
     * extras `word` + `reading` name a Japanese word (its card key). Methods: "state" -> `state` (d/k/b/n/u, absent
     * without kuma3); "rate" (+ `ease` 1..4, `ms`) -> `notes`, `cards`, `undo` (empty: kuma3 took none); "undo" (+ the
     * rate's answer) -> `undone`; "reload" (after the caller mined a card) re-reads the Mined deck. Callers need
     * READ_WORDS, and kuma3's own permission to rate or undo (only apps you let into kuma3 change its cards).
     */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
        val ctx = context!!
        ctx.enforceCallingPermission("${ctx.packageName}.permission.READ_WORDS", "kumapie word colours")
        val known = japanese.known
        val out = android.os.Bundle()
        runBlocking {
            if (known.lastReading == null) known.refresh()
            if (method == "rate" || method == "undo") {
                val pkg = known.ankiApp() ?: return@runBlocking
                ctx.enforceCallingPermission(known.permission(pkg), "rating kuma3 cards")
            }
            val key = CardIndex.japaneseKey(extras?.getString("word").orEmpty(), extras?.getString("reading").orEmpty())
            when (method) {
                "state" -> known.cardIndex.state(key)?.let { out.putString("state", it) }
                "reload" -> known.reloadCards()
                "rate" -> known.rate(key, extras!!.getInt("ease"), extras.getLong("ms"))?.let {
                    out.putLongArray("notes", it.notes.toLongArray()); out.putInt("cards", it.cards); out.putString("undo", it.undoLabel)
                }
                "undo" -> out.putBoolean("undone", known.undo(KnownWords.Rated(
                    extras!!.getLongArray("notes")!!.toList(), extras.getInt("cards"), extras.getString("undo").orEmpty())))
            }
        }
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
