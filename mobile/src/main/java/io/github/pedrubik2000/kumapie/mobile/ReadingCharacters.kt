package io.github.pedrubik2000.kumapie.mobile

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Faces and names for the books and manga read in Hoshi and chimahon (user, 2026-11-14): the work titles they ask
 * faces for ([WordColours]) are kept here and sent to the PC, which builds Bee's characters for just those works
 * (kumapie-server characters.py + tools/kumapie/reading_characters.py); the newest `bee_reading_*.zip` is fetched and
 * imported ("Bee's Characters (books & manga)", replaced in place by title). Once per app start.
 */
object ReadingCharacters {
    private const val WORKS = "reading_works"
    private const val FETCHED = "reading_characters_fetched"
    private val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** A title Hoshi or chimahon sent with a face request. */
    fun note(context: Context, work: String?) {
        val w = work?.trim().orEmpty()
        if (w.isEmpty() || !seen.add(w)) return
        val prefs = Settings(context).prefs
        val all = prefs.getStringSet(WORKS, emptySet()).orEmpty()
        if (w !in all) prefs.edit().putStringSet(WORKS, all + w).apply()
    }

    /** Sends the titles, then imports a newer characters' dictionary if the PC has one. Blocking: call off the UI thread. */
    fun sync(context: Context, settings: Settings) = runCatching {
        val works = settings.prefs.getStringSet(WORKS, emptySet()).orEmpty()
        if (works.isNotEmpty()) post("${settings.server}/api/characters/works", JSONObject().put("works", JSONArray(works.toList())).toString())
        val names = JSONArray(URL("${settings.server}/api/models/ja-dicts").readText()).let { a -> (0 until a.length()).map(a::getString) }
        val newest = names.filter { it.startsWith("bee_reading_") }.maxOrNull() ?: return@runCatching
        if (newest == settings.prefs.getString(FETCHED, null)) return@runCatching
        val zip = File(context.cacheDir, newest)
        URL("${settings.server}/api/models/ja-dicts/${Uri.encode(newest)}").openStream().use { i -> zip.outputStream().use { i.copyTo(it) } }
        kotlinx.coroutines.runBlocking {
            YomitanDictionaries.get(context).import(listOf(Uri.fromFile(zip)), Lang.JAPANESE) {}.forEach { Log.i("kumapie", "reading characters: $it") }
        }
        zip.delete()
        WordColours.forgetFaces() // names that had no face before may have one now
        settings.prefs.edit().putString(FETCHED, newest).apply()
    }.onFailure { Log.w("kumapie", "reading characters: $it") }

    private fun post(url: String, body: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 60_000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try { c.outputStream.use { it.write(body.toByteArray()) }; c.responseCode } finally { c.disconnect() }
    }
}
