package io.github.pedrubik2000.kumapie.data

import android.content.Context

/**
 * A title's big picture, the one its title page shows (its meta's "background"), for Home's and Discover's heroes too
 * (Pedro, 2026-11-18: Home showed the episode's frame, Discover the catalog's banner cropped). Kept on the device, so
 * Home's first frame has it; a title whose meta has none (or fails) is asked again next time the app runs.
 */
object Backdrops {
    private const val HERO = " hero" // not a title id
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun store(context: Context) = context.getSharedPreferences("backdrops", Context.MODE_PRIVATE)

    /** The saved picture of [id], or null (not asked yet / none). */
    fun saved(context: Context, id: String): String? = store(context).getString(id, null)

    fun put(context: Context, id: String, image: String) {
        if (image.isNotEmpty() && saved(context, id) != image) store(context).edit().putString(id, image).apply()
    }

    /** The picture Home's top showed last: decoded again as the TV app opens, before Home asks for it. */
    fun lastHero(context: Context): String? = store(context).getString(HERO, null)

    fun putHero(context: Context, image: String) {
        if (image.isNotEmpty() && lastHero(context) != image) store(context).edit().putString(HERO, image).apply()
    }

    /** The picture of [id], asked from [api] once; null when it has none. */
    suspend fun get(context: Context, api: Discovery, type: String, id: String): String? {
        saved(context, id)?.let { return it }
        if (id in missing) return null
        val image = runCatching { api.discoverMeta(type, id).optString("background") }.getOrDefault("")
        if (image.isEmpty()) { missing += id; return null }
        put(context, id, image)
        return image
    }
}
