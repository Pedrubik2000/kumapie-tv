package io.github.pedrubik2000.kumapie.torrent

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class TorrentRef(val hash: String, val index: Int, val name: String, val trackers: List<String>) {
    fun uri(): String = "$SCHEME://$hash/${enc(name.ifEmpty { "video" })}?i=$index" + trackers.joinToString("") { "&tr=${enc(it)}" }

    fun magnet(): String = "magnet:?xt=urn:btih:$hash" + trackers.joinToString("") { "&tr=${enc(it)}" }

    companion object {
        const val SCHEME = "torrent"
        private val HASH = Regex("^[0-9a-fA-F]{40}$")
        private val SEEDERS = Regex("""👤\s*(\d+)""")
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce", "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce", "udp://exodus.desync.com:6969/announce",
        )

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

        fun parse(uri: String): TorrentRef? {
            if (!uri.startsWith("$SCHEME://")) return null
            val rest = uri.removePrefix("$SCHEME://")
            val hash = rest.substringBefore('/').lowercase().takeIf { HASH.matches(it) } ?: return null
            val after = rest.substringAfter('/', "")
            val query = after.substringAfter('?', "").split('&').filter { it.contains('=') }.map { it.substringBefore('=') to dec(it.substringAfter('=')) }
            return TorrentRef(hash, query.firstOrNull { it.first == "i" }?.second?.toIntOrNull() ?: -1, dec(after.substringBefore('?')),
                query.filter { it.first == "tr" }.map { it.second })
        }

        fun of(stream: JSONObject): TorrentRef? {
            val hash = stream.optString("infoHash").lowercase().takeIf { HASH.matches(it) } ?: return null
            val sources = stream.optJSONArray("sources")
            val trackers = (0 until (sources?.length() ?: 0)).map { sources?.optString(it).orEmpty() }
                .filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
            val name = stream.optJSONObject("behaviorHints")?.optString("filename").orEmpty()
            return TorrentRef(hash, if (stream.has("fileIdx")) stream.optInt("fileIdx", -1) else -1, name, trackers.ifEmpty { DEFAULT_TRACKERS })
        }

        fun seeders(text: String): Int = SEEDERS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
