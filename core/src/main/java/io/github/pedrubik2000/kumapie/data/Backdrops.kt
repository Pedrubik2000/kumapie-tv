package io.github.pedrubik2000.kumapie.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A title's big picture, the one its title page shows (its meta's "background"), for Home's and Discover's heroes too
 * (Pedro, 2026-11-18: Home showed the episode's frame, Discover the catalog's banner cropped). Kept on the device, so
 * Home's first frame has it; a title with none (or a failed ask) is asked again next time the app runs.
 */
object Backdrops {
    private const val HERO = " hero" // not a title id
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun store(context: Context) = context.getSharedPreferences("backdrops", Context.MODE_PRIVATE)

    /** The saved picture of [id], or null (not asked yet / none). */
    fun saved(context: Context, id: String): String? = store(context).getString(id, null)

    fun put(context: Context, id: String, image: String) {
        if (image.isNotEmpty() && saved(context, id) == null) store(context).edit().putString(id, image).apply()
    }

    /** The picture Home's top showed last: decoded again as the TV app opens, before Home asks for it. */
    fun lastHero(context: Context): String? = store(context).getString(HERO, null)

    fun putHero(context: Context, image: String) {
        if (image.isNotEmpty() && lastHero(context) != image) store(context).edit().putString(HERO, image).apply()
    }

    val noKitsuCover = setOf("10016", "49537", "50021", "46729")

    /** The picture of [id], asked once (Kitsu's cover for a `kitsu:` title, else [api]'s meta); null when it has none. */
    suspend fun get(context: Context, api: Discovery, type: String, id: String): String? =
        get(context, id, ::kitsuCover) { api.discoverMeta(type, id).optString("background") }

    suspend fun get(context: Context, id: String, kitsu: suspend (String) -> String, meta: suspend () -> String): String? {
        saved(context, id)?.let { return it }
        if (id in missing) return null
        var asked = true
        val n = id.removePrefix("kitsu:").takeIf { id.startsWith("kitsu:") && it.isNotEmpty() && it.all(Char::isDigit) && it !in noKitsuCover }
        if (n != null) {
            val cover = runCatching { kitsu(n) }.onFailure { asked = false }.getOrDefault("")
            if (cover.isNotEmpty()) { put(context, id, cover); return cover }
        }
        val image = runCatching { meta() }.onFailure { asked = false }.getOrDefault("")
        if (image.isEmpty()) { if (asked) missing += id; return null }
        if (asked) put(context, id, image)
        return image
    }

    private suspend fun kitsuCover(n: String): String = withContext(Dispatchers.IO) {
        val url = URL("https://kitsu.app/api/edge/anime/$n")
        val body = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.api+json")
            inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val cover = JSONObject(body).getJSONObject("data").getJSONObject("attributes").optJSONObject("coverImage")
        cover?.optString("original").orEmpty().ifEmpty { cover?.optString("large").orEmpty() }
    }
}
