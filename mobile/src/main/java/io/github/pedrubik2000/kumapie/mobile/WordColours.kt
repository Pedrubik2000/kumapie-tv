package io.github.pedrubik2000.kumapie.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File
import java.security.MessageDigest
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
 * Long texts (book chapters) keep their split on disk ([words]), so only the colours are redone when asked again.
 */
class WordColours : ContentProvider() {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val japanese by lazy {
        val ctx = context!!.applicationContext
        Japanese(ctx, Settings(ctx), YomitanDictionaries.get(ctx), background).also { ja ->
            // Card states (green, blue) come from kuma3: read once here, then kept fresh by [fresh].
            // ponytail: second KnownWords next to the app's own; one shared instance if they ever disagree.
            background.launch { ja.known.refresh() }
        }
    }
    @Volatile private var cardsAt = System.currentTimeMillis()

    /**
     * What the app changed since: its marked and imported words (files), and the Mined deck's cards at most once a
     * minute (ratings and cards made in kumapie or kuma3), so the colours asked for next are today's.
     */
    private fun fresh(known: KnownWords) {
        known.reloadLists()
        if (System.currentTimeMillis() - cardsAt < 60_000) return
        cardsAt = System.currentTimeMillis()
        runBlocking { known.reloadCards() }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val out = MatrixCursor(arrayOf("start", "length", "state"))
        val text = args?.firstOrNull() ?: return out
        if (uri.lastPathSegment != "colours" || !japanese.model.isReady) return out
        val known = japanese.known
        fresh(known)
        background.launch { known.refreshDue() }
        for (t in words(text)) {
            val w = known.paint(t.base, Word(status = known.status(t.base) ?: "u", meaning = "", forms = mapOf(t.surface to "")))
            out.addRow(arrayOf<Any>(t.begin, t.surface.length, w.shownFor(t.surface)))
        }
        return out
    }

    private class Split(val begin: Int, val surface: String, val base: String)

    /**
     * [text]'s words. A text of [CACHED]+ chars (a book chapter) is split once: its words are kept in the cache dir under
     * a hash of the text and the dictionary files' dates, so a new dictionary splits it again.
     */
    private fun words(text: String): List<Split> = synchronized(this) {
        // ponytail: one lock for every split; a chapter's pre-split can delay the open chapter's colours by ~1 s.
        val split = { japanese.model.words(listOf(text)).first().filter { it.isWord }.map { Split(it.begin, it.surface, it.base) } }
        if (text.length < CACHED) return split()
        val model = japanese.model
        val stamp = (listOf(model.dic) + model.splitterFiles).joinToString(",") { it.lastModified().toString() }
        val key = MessageDigest.getInstance("SHA-1").digest("$stamp\n$text".toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(File(context!!.cacheDir, "word-splits").apply { mkdirs() }, key)
        val started = System.currentTimeMillis()
        val cached = file.exists()
        val words = if (cached) file.readLines().map { it.split('\t').let { (b, s, w) -> Split(b.toInt(), s, w) } }
        else split().also { words -> file.writeText(words.joinToString("") { "${it.begin}\t${it.surface}\t${it.base}\n" }) }
        android.util.Log.i("kumapie", "word colours: ${text.length} chars ${if (cached) "from cache" else "split"} in ${System.currentTimeMillis() - started} ms")
        return words
    }

    /**
     * The word card's rating row for other apps (Hoshi's popup), as [io.github.pedrubik2000.kumapie.mobile.ui.RatingRows]:
     * extras `word` + `reading` name a Japanese word (its card key). Methods: "split" (+ `text`) splits a chapter ahead
     * for [query]; "state" -> `state` (d/k/b/n/u, absent without kuma3); "rate" (+ `ease` 1..4, `ms`) -> `notes`, `cards`, `undo` (empty: kuma3 took none); "undo" (+ the
     * rate's answer) -> `undone`; "reload" (after the caller mined a card) re-reads the Mined deck. Callers need
     * READ_WORDS, and kuma3's own permission to rate or undo (only apps you let into kuma3 change its cards).
     */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
        val ctx = context!!
        ctx.enforceCallingPermission("${ctx.packageName}.permission.READ_WORDS", "kumapie word colours")
        if (method == "split") { // a chapter the reader will open next: split it now, for [query]
            extras?.getString("text")?.takeIf { japanese.model.isReady }?.let(::words)
            return android.os.Bundle()
        }
        val known = japanese.known
        val out = android.os.Bundle()
        runBlocking {
            if (known.lastReading == null) known.refresh()
            if (method == "state") fresh(known)
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

    private companion object { const val CACHED = 2000 }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
