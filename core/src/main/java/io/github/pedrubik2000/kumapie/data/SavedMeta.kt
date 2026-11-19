package io.github.pedrubik2000.kumapie.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A title page's last answer ([Discovery.discoverMeta]), one small file per title: the page shows it at once and the
 * fresh answer replaces it (the addon was asked every time: Home → title page ~1.2 s on the Chromecast, 2026-11-18).
 */
object SavedMeta {
    private fun file(context: Context, id: String) = File(context.cacheDir, "meta/" + id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun get(context: Context, id: String): JSONObject? = runCatching { JSONObject(file(context, id).readText()) }.getOrNull()

    fun put(context: Context, id: String, meta: JSONObject) {
        runCatching { file(context, id).apply { parentFile?.mkdirs() }.writeText(meta.toString()) }
    }
}
