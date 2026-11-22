package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * Japanese subtitles from jimaku.cc for a stream with no Japanese track of its own, asked from the device
 * (kumapie_plan.md "for other people"). The phone's `mobile/src/main/python/jimaku.py` in Kotlin: the same picking
 * ([rank]) and reading ([cues]) rules; keep the two alike. The user's own free API key.
 */
object Jimaku {
    private const val API = "https://jimaku.cc/api"
    private const val AGENT = "kumapie (github.com/Pedrubik2000/kumapie-tv)"
    private val SUB_EXT = listOf(".srt", ".ass", ".ssa", ".vtt")

    /** One line: seconds and text. */
    data class Line(val start: Double, val end: Double, val text: String)

    /**
     * The Japanese lines for [episode] of [show] (its name as the addon gives it; AniList finds the entry), from the
     * file that fits [videoName] best ([skip]: the next ones, best first; only the best is kept on the device, unless
     * [keep] says another). Empty when Jimaku has none.
     */
    suspend fun lines(key: String, show: String, season: Int?, episode: Int?, videoName: String = "", skip: Int = 0, duration: suspend () -> Double? = { null }): List<Line> {
        if (key.isBlank()) error("Jimaku needs your API key (free, jimaku.cc): add it in Settings")
        if (skip > 0) return fetch(key, show, season, episode, videoName, skip)
        // An episode opened before: its lines from the device (four requests in a row took ~2 s of a stream's words, tablet 2026-11-18).
        val saved = savedFile(show, season, episode, videoName)
        if (saved != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saved.takeIf { it.exists() }?.let { f ->
            runCatching { org.json.JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { l -> Line(l.getDouble(0), l.getDouble(1), l.getString(2)) } } } }
                .getOrNull() } }?.takeIf { fitsLength(it, duration()) }?.let(::japanese)?.takeIf { it.isNotEmpty() }?.let { return it }
        return fetch(key, show, season, episode, videoName, 0, duration).also { keep(show, season, episode, videoName, it) }
    }

    /**
     * [lines] become the episode's own on this device: [lines] answers with them from now on. [fitted]: they are the
     * file the stream switched to because it fits (the first ranked didn't): its release is remembered for [show], and
     * its next episodes rank that release's files first ([rank]'s `release`; Eden of the East: Coalgirls' files are
     * 80 s off this video, [gg]'s fit, 2026-11-19).
     */
    fun keep(show: String, season: Int?, episode: Int?, videoName: String, lines: List<Line>, fitted: Boolean = false) {
        if (fitted && lastName.isNotEmpty()) releaseFile(show)?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(lastName) } }
        val saved = savedFile(show, season, episode, videoName)
        if (saved != null && lines.isNotEmpty()) runCatching {
            saved.parentFile?.mkdirs()
            saved.writeText(org.json.JSONArray(lines.map { org.json.JSONArray().put(it.start).put(it.end).put(it.text) }).toString())
        }
    }

    private fun savedFile(show: String, season: Int?, episode: Int?, videoName: String) =
        cache?.let { java.io.File(it, "jimaku/" + "$show|$season|$episode|$videoName".hashCode().toUInt() + ".json") }

    /** The show's release that fitted ([keep] `fitted`): a Jimaku file name. */
    private fun releaseFile(show: String) = cache?.let { java.io.File(it, "jimaku/release-" + show.hashCode().toUInt() + ".txt") }
    private fun release(show: String) = releaseFile(show)?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()

    /** The file [fetch] read last ([keep] remembers its release when it fitted). */
    @Volatile private var lastName = ""

    /** Where [lines] keeps each episode's lines (the app's cache folder; null: not kept). */
    @Volatile var cache: java.io.File? = null

    private suspend fun fetch(key: String, show: String, season: Int?, episode: Int?, videoName: String, skip: Int = 0, duration: suspend () -> Double? = { null }): List<Line> {
        val media = runCatching { anilist(show, season) }.getOrNull()
        fun search(q: String) = "$API/entries/search?anime=true&$q"
        var entries = JSONArray(String(get(search(media?.let { "anilist_id=${it.first}" } ?: "query=${enc(show)}"), key)))
        if (entries.length() == 0 && media != null) entries = JSONArray(String(get(search("query=${enc(media.second)}"), key)))
        if (entries.length() == 0) return emptyList()
        val files = JSONArray(String(get("$API/entries/${entries.getJSONObject(0).getInt("id")}/files", key)))
        val ranked = rank((0 until files.length()).map { files.getJSONObject(it).let { f -> f.getString("name") to f.getString("url") } }, episode, videoName, release(show))
        if (skip > 0) return ranked.getOrNull(skip)?.let { f -> japanese(read(key, f, episode)).orEmpty().also { lastName = f.first } }.orEmpty()
        var best: Triple<Int, String, List<Line>>? = null
        for (f in ranked.take(CUTS + 1)) {
            val all = read(key, f, episode)
            val ja = japanese(all)?.takeIf { it.isNotEmpty() }
            if (ja == null) continue
            val score = (if (fitsLength(ja, duration())) 2 else 0) + if (ja.size == all.size) 1 else 0
            if (score == 3) { lastName = f.first; return ja }
            if (best == null || score > best.first) best = Triple(score, f.first, ja)
        }
        if (best == null && ranked.isNotEmpty()) error(tr("Jimaku has subtitles for this episode, none in Japanese"))
        lastName = best?.second.orEmpty()
        return best?.third.orEmpty()
    }

    fun japanese(lines: List<Line>): List<Line>? {
        val kana = lines.count { KANA.containsMatchIn(it.text) }
        return when {
            lines.isEmpty() || kana * 20 >= lines.size * 17 -> lines
            kana * 5 < lines.size -> null
            else -> lines.filter { KANA.containsMatchIn(it.text) }
        }
    }

    private suspend fun read(key: String, best: Pair<String, String>, episode: Int?): List<Line> {
        var name = best.first
        var data = get(best.second, key)
        if (name.lowercase().endsWith(".zip")) {
            val inside = unzip(data).filter { (n, _) -> SUB_EXT.any(n.lowercase()::endsWith) && (episode == null || numbers(n).second == episode) }
                .minByOrNull { (n, _) -> !n.lowercase().endsWith(".srt") } ?: return emptyList()
            name = inside.first
            data = inside.second
        }
        return cues(name, data)
    }

    private const val CUTS = 3

    fun fitsLength(lines: List<Line>, duration: Double?): Boolean = duration == null || duration <= 0 || lines.isEmpty() ||
        lines.last().start <= duration + 120 && lines.maxOf { it.end } >= duration * 0.6

    /**
     * Jimaku files for [episode], best first: its own files (else packs), subtitles over archives, a name tagged Chinese
     * last (bilingual "[CHS, JPN]" before it), then the most words
     * shared with [release] (a file of the show that fitted before, [keep]), then with the video's name, .srt first.
     * The phone's jimaku.py has no [release] (its episodes are built once, not streamed).
     */
    fun rank(files: List<Pair<String, String>>, episode: Int?, videoName: String = "", release: String = ""): List<Pair<String, String>> {
        fun wordsOf(s: String) = Regex("[a-z0-9]+").findAll(s.lowercase()).map { it.value }.toSet()
        val words = wordsOf(videoName)
        val released = wordsOf(release.substringBeforeLast('.'))
        fun score(name: String): List<Int> {
            val n = name.lowercase()
            val zh = ZH_TAG.containsMatchIn(n)
            return listOf(if (episode == null || numbers(n).second == episode) 1 else 0, if (SUB_EXT.any(n::endsWith)) 1 else 0,
                if (!zh) 2 else if (JA_TAG.containsMatchIn(n)) 1 else 0,
                wordsOf(n.substringBeforeLast('.')).intersect(released).size,
                wordsOf(n).intersect(words).size, if (n.endsWith(".srt")) 1 else 0)
        }
        return files.filter { (n, _) -> n.lowercase().let { l -> l.endsWith(".zip") || (SUB_EXT.any(l::endsWith) && (episode == null || numbers(n).second == episode)) } }
            .sortedWith { a, b -> score(b.first).zip(score(a.first)).firstOrNull { it.first != it.second }?.let { it.first - it.second } ?: 0 }
    }

    /** (season, episode) from a file name: S01E05, E05, " - 05", a lone 05; null where not found (realdebrid.py numbers). */
    fun numbers(name: String): Pair<Int?, Int?> {
        val stem = name.substringAfterLast('/').substringBeforeLast('.')
        Regex("""[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})""").find(stem)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        val season = Regex("""(?:[Ss]taffel|[Ss]eason)[ ._-]?(\d{1,2})""").find(stem)?.groupValues?.get(1)?.toInt()
        for (p in listOf("""\b(?:[Ee][Pp]?|[Ff]olge)[ ._-]?(\d{1,3})\b""", """ - (\d{1,3})\b""", """(?<![\dxp])(\d{1,2})(?![\dp])"""))
            Regex(p).find(stem)?.let { return season to it.groupValues[1].toInt() }
        return season to null
    }

    // Compiled on first use, not when the app opens (`Jimaku.cache = cacheDir` in onCreate): ~10 % of the Chromecast's
    // main thread in the first 3 s (2026-11-19).
    private val CHINESE_STYLE by lazy { Regex("""CN|CHS|CHT|ZH|[中简繁]|(?i:(?:^|[\W_])(?:cn|chs|cht|zh|chi)(?:$|[\W_]))""") }
    private val SONG_OR_SIGN by lazy { Regex("""sign|\bop\d*\b|\bed\d*\b|opening|ending|song|karaoke|kfx|lyric|\brom|romaji|kanji|title|insert|typeset|\bts\b|credit|staff""", RegexOption.IGNORE_CASE) }
    private val ZH_TAG by lazy { Regex("""(?:^|[^a-z])(?:zh|chs|cht|chi|sc|tc|big5)(?:[^a-z]|$)|[简繁]|中文""") }
    private val JA_TAG by lazy { Regex("""(?:^|[^a-z])(?:ja|jp|jpn|japanese)(?:[^a-z]|$)|日本語""") }
    private val KANA by lazy { Regex("[ぁ-ヿ]") }
    private val JAPANESE by lazy { Regex("[぀-ヿ一-鿿]") }
    private const val STAMP = """(?:(\d+):)?(\d+):(\d+)[,.](\d+)""" // VTT may leave out the hours (mm:ss.ttt)

    /** An .srt / .vtt arrow line's two times (groups 1-4, 5-8; an hours group may be empty). */
    val ARROW by lazy { Regex("$STAMP\\s*-->\\s*$STAMP") }

    /**
     * A subtitle file (.srt / .vtt / .ass / .ssa) -> the lines that are said, in time order: styling dropped, ASS signs,
     * songs and karaoke left out, a bilingual file's Chinese half too, readings and sounds in brackets dropped.
     * ponytail: sung lines are left out (the phone keeps them as "♪ …" for Skip intro); add them when the TV builds episodes.
     * [said]: what of a line is kept ("" drops it); Japanese by default, [plain] for another language.
     */
    fun cues(name: String, data: ByteArray, said: (String) -> String = ::spoken): List<Line> {
        val text = decode(data).replace("\r", "")
        val out = ArrayList<Line>()
        if (name.lowercase().let { it.endsWith(".ass") || it.endsWith(".ssa") }) {
            var fields: List<String>? = null
            var events = false
            for (line in text.lines()) when {
                line.startsWith("[") -> events = line.trim().lowercase() == "[events]"
                line.startsWith("Format:") && events -> fields = line.substring(7).split(",").map { it.trim().lowercase() }
                line.startsWith("Dialogue:") -> {
                    val f = fields ?: continue
                    val row = f.zip(line.substring(9).split(",", limit = f.size)).toMap()
                    val style = row["style"].orEmpty()
                    val body = row["text"].orEmpty()
                    if (CHINESE_STYLE.containsMatchIn(style) || !dialogue(style, body)) continue
                    val said = body.replace(Regex("""\{[^}]*\}"""), "").replace(Regex("""\\[Nn]"""), "\n").replace("\\h", " ").trim()
                    val a = time(row["start"].orEmpty())
                    val b = time(row["end"].orEmpty())
                    if (said.isNotEmpty() && a != null && b != null) out += Line(a, b, said)
                }
            }
        } else for (block in text.split(Regex("""\n\s*\n"""))) {
            val m = ARROW.find(block) ?: continue
            // The rest of the arrow line is VTT cue settings (align:start position:10%), not text.
            val body = block.substring(m.range.last + 1).substringAfter('\n', "").trim().lines().joinToString("\n") { it.trim() }
                .replace(Regex("""<[^>]+>|\{[^}]*\}"""), "").trim()
            val g = m.groupValues
            if (body.isNotEmpty()) out += Line(secs(g[1], g[2], g[3], g[4]), secs(g[5], g[6], g[7], g[8]), body)
        }
        val seen = HashSet<Line>()
        return out.sortedBy { it.start }.mapNotNull { l ->
            if ("♪" in l.text) return@mapNotNull null
            val t = said(l.text)
            Line(Math.round(l.start * 1000) / 1000.0, Math.round(l.end * 1000) / 1000.0, t).takeIf { t.isNotEmpty() && seen.add(it) }
        }
    }

    /** An ASS line that is said: not a sign / song / karaoke style, no karaoke timing, no drawing; a placed line only in a dialogue-looking style. */
    private fun dialogue(style: String, body: String): Boolean {
        if (SONG_OR_SIGN.containsMatchIn(style) || Regex("""\\(k[fo]?\d|p[1-9])""").containsMatchIn(body)) return false
        return !Regex("""\\(pos|move)""").containsMatchIn(body) ||
            Regex("default|main|dialog|alt|italic|top|flash|overlap", RegexOption.IGNORE_CASE).containsMatchIn(style)
    }

    /**
     * Only what is said: readings 後藤(ごとう), speaker names and sounds （園児）（拍手）, music marks dropped, and in a line of
     * two languages (日本語\n中文) its row of 5 kanji or more and no kana; "" when nothing Japanese is left.
     */
    fun spoken(text: String): String {
        val rows = text.lines().filter { it.isNotBlank() }
        val t = (if (rows.any { KANA.containsMatchIn(it) }) rows.filter { KANA.containsMatchIn(it) || JAPANESE.findAll(it).count() < 5 } else rows)
            .joinToString(" ").replace(Regex("""\([^)]*\)"""), "").replace(Regex("""（[^）]*）|［[^］]*］|\[[^\]]*\]|[♪♬～〜]"""), "").trim()
        return if (JAPANESE.containsMatchIn(t) && (KANA.containsMatchIn(t) || t.length <= 12)) t.replace(Regex("""\s+"""), " ") else ""
    }

    /** A line in another language as it is, spaces made single. */
    fun plain(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    private fun time(s: String): Double? = Regex("""\s*(\d+):(\d+):(\d+)\.(\d+)""").find(s)?.groupValues?.let { secs(it[1], it[2], it[3], it[4]) }
    private fun secs(h: String, m: String, s: String, frac: String) = (h.toIntOrNull() ?: 0) * 3600 + m.toInt() * 60 + s.toInt() + frac.toInt() / Math.pow(10.0, frac.length.toDouble())

    private fun decode(data: ByteArray): String {
        if (data.size >= 2 && ((data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) || (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()))) return String(data, Charsets.UTF_16)
        val utf8 = String(data, Charsets.UTF_8).removePrefix("\uFEFF")
        return if ('\uFFFD' in utf8) String(data, charset("windows-31j")) else unmangle(utf8) // Shift-JIS files exist
    }

    /**
     * Lines saved twice over (UTF-8 read as Windows-1252, then saved as UTF-8 again): "\u00E2\u2122\u00AA" is \u266A, "\u00E2\u20AC\u00A6" is \u2026, "\u00C3\u00A9" is \u00E9
     * (an English file for Dandadan 9 on the Chromecast, 2026-11-19). Each such line is turned back; others stay.
     */
    fun unmangle(text: String): String {
        if (!MANGLED.containsMatchIn(text)) return text
        val cp1252 = charset("windows-1252")
        return text.lines().joinToString("\n") { line ->
            if (!MANGLED.containsMatchIn(line)) return@joinToString line
            // Back to the bytes: Windows-1252, and the five bytes it leaves undefined (0x81 0x8D 0x8F 0x90 0x9D, kept as
            // U+0081\u2026 by the first wrong reading) as themselves. Anything else (Japanese): the line wasn't mangled.
            val bytes = java.io.ByteArrayOutputStream()
            for (c in line) when {
                c.code < 0x80 || c.code in 0xA0..0xFF || c.code in listOf(0x81, 0x8D, 0x8F, 0x90, 0x9D) -> bytes.write(c.code)
                cp1252.newEncoder().canEncode(c) -> bytes.write(c.toString().toByteArray(cp1252))
                else -> return@joinToString line
            }
            String(bytes.toByteArray(), Charsets.UTF_8).takeIf { '\uFFFD' !in it } ?: line
        }
    }
    private val MANGLED by lazy { Regex("[\u00C2\u00C3\u00E2][\u0080-\u00BF\u0152\u0153\u0160\u0161\u0178\u017D\u017E\u0192\u02C6\u02DC\u2013\u2014\u2018-\u201E\u2020-\u2022\u2026\u2030\u2039\u203A\u20AC\u2122]") }

    /** Names in UTF-8, else Shift-JIS (Japanese Windows zips: UTF-8 throws on their names). */
    private fun unzip(data: ByteArray): List<Pair<String, ByteArray>> =
        try { unzip(data, Charsets.UTF_8) } catch (_: IllegalArgumentException) { unzip(data, charset("Shift_JIS")) }

    private fun unzip(data: ByteArray, names: java.nio.charset.Charset): List<Pair<String, ByteArray>> = ZipInputStream(ByteArrayInputStream(data), names).use { z ->
        generateSequence { z.nextEntry }.filter { !it.isDirectory }.map { it.name.substringAfterLast('/') to z.readBytes() }.toList()
    }

    /** (AniList id, romaji name) of [show]; for season 2+ the entry named for that season. */
    private suspend fun anilist(show: String, season: Int?): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", "query(\$s:String){Page(perPage:8){media(search:\$s,type:ANIME){id title{romaji english native}}}}")
            .put("variables", JSONObject().put("s", show)).toString().toByteArray()
        val c = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("User-Agent", AGENT)
        }
        try {
            c.outputStream.use { it.write(body) }
            val found = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("data").getJSONObject("Page").getJSONArray("media")
            val all = (0 until found.length()).map { found.getJSONObject(it) }
            val word = season?.takeIf { it > 1 }?.let { Regex("""\b(?:$it(?:st|nd|rd|th) Season|Season $it)\b|第${it}期""", RegexOption.IGNORE_CASE) }
            val pick = all.firstOrNull { m -> word != null && m.getJSONObject("title").let { t -> t.keys().asSequence().any { k -> word.containsMatchIn(t.optString(k)) } } }
                ?: all.firstOrNull() ?: return@withContext null
            pick.getInt("id") to pick.getJSONObject("title").optString("romaji")
        } finally { c.disconnect() }
    }

    private suspend fun get(url: String, key: String): ByteArray = withContext(Dispatchers.IO) {
        repeat(4) { attempt ->
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000
                setRequestProperty("User-Agent", AGENT); setRequestProperty("Authorization", key)
            }
            try {
                when (val code = c.responseCode) {
                    in 200..299 -> return@withContext c.inputStream.use { it.readBytes() }
                    429 -> if (attempt < 3) { delay(((c.getHeaderField("x-ratelimit-reset-after")?.toDoubleOrNull() ?: 5.0) * 1000).toLong() + 500); return@repeat }
                    401 -> error("Jimaku refused the API key: check it in Settings")
                    else -> error("jimaku.cc: HTTP $code")
                }
            } finally { c.disconnect() }
        }
        error("jimaku.cc: too many requests, try again in a minute")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
