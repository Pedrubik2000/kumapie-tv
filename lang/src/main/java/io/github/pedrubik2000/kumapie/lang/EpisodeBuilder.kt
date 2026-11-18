package io.github.pedrubik2000.kumapie.lang

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A Japanese episode made on the device without Python (the TV has none; kumapie_plan.md "for other people"):
 * `newepisode.build`'s Japanese path in Kotlin, the same JSON. Every subtitle line is a "scene" of its own (a line
 * is kumapie's only unit), its words as segments, the translation lines that overlap it, every word.
 * Words come split already ([JapaneseModel.words]), one token list per cue.
 */
object EpisodeBuilder {
    data class Cue(val start: Double, val end: Double, val text: String)

    /** Cues starting within this of each other are shown together: one line. */
    private const val SAME = 0.05

    fun build(id: String, show: String, title: String, duration: Double, video: String,
              cues: List<Cue>, english: List<Cue>, words: List<List<JapaneseModel.Token>>): JSONObject {
        val (kept, once) = unsplit(cues)
        val toks = kept.map { words.getOrElse(it) { emptyList() } }.iterator()
        val lines = ArrayList<MutableList<Cue>>()
        for (c in once) if (lines.isNotEmpty() && kotlin.math.abs(c.start - lines.last()[0].start) < SAME) lines.last() += c else lines += mutableListOf(c)
        val owner = english.map { e -> nearest(lines, e) }
        val all = JSONObject()
        val scenes = JSONArray()
        lines.forEachIndexed { i, p ->
            val start = round3(p[0].start)
            val end = round3(p.maxOf { it.end })
            val en = JSONArray()
            english.forEachIndexed { k, e -> if (owner[k] == i) en.put(JSONArray().put(e.start).put(e.end).put(e.text.replace(Regex("""\s*\([^)]*\)"""), "").trim())) }
            val sceneCues = JSONArray()
            for (c in p) {
                val t = toks.next()
                for (w in t) if (w.isWord && !all.has(w.base)) all.put(w.base, JSONObject().put("s", "u").put("g", ""))
                sceneCues.put(JSONObject().put("start", c.start).put("end", c.end).put("seg", segments(c.text, t)))
            }
            scenes.put(JSONObject().put("id", sha1("$id|${"%.3f".format(java.util.Locale.ROOT, start)}|${"%.3f".format(java.util.Locale.ROOT, end)}").take(12))
                .put("i", i).put("start", start).put("end", end).put("german", true).put("level", JSONObject.NULL).put("seen", false)
                .put("cues", sceneCues).put("english", en).put("g", JSONObject()).put("defs", JSONObject()))
        }
        return JSONObject().put("id", id).put("show", show).put("title", title).put("lang", "ja").put("duration", duration)
            .put("video", video).put("resume", JSONObject.NULL).put("scenes", scenes).put("words", all)
    }

    /**
     * Styled subtitles cut a line that overlaps another into slices with the same text, each starting the ms the last
     * ends: one line, its end the last slice's. -> the kept cues' indices, the cues with each line once.
     */
    fun unsplit(cues: List<Cue>): Pair<List<Int>, List<Cue>> {
        val kept = ArrayList<Int>()
        val out = ArrayList<Cue>()
        val last = HashMap<String, Int>()
        cues.forEachIndexed { i, c ->
            val j = last[c.text]
            if (j != null && kotlin.math.abs(c.start - out[j].end) <= 0.001) { out[j] = out[j].copy(end = c.end); return@forEachIndexed }
            last[c.text] = out.size
            kept += i
            out += c
        }
        return kept to out
    }

    /** [[surface, dictionary form or null]] covering [text]: the words, the text between them. */
    fun segments(text: String, tokens: List<JapaneseModel.Token>): JSONArray {
        val out = JSONArray()
        var pos = 0
        for (t in tokens.filter { it.isWord }.sortedBy { it.begin }) {
            if (t.begin < pos) continue
            if (t.begin > pos) out.put(JSONArray().put(text.substring(pos, t.begin)).put(JSONObject.NULL))
            val end = (t.begin + t.surface.length).coerceAtMost(text.length)
            out.put(JSONArray().put(text.substring(t.begin, end)).put(t.base))
            pos = end
        }
        if (pos < text.length) out.put(JSONArray().put(text.substring(pos)).put(JSONObject.NULL))
        return out
    }

    /** The line a translation belongs to: the one it overlaps most, else the closest. */
    private fun nearest(lines: List<List<Cue>>, e: Cue): Int = lines.indices.maxByOrNull { i ->
        val a = lines[i][0].start
        val b = lines[i].last().end
        val overlap = minOf(b, e.end) - maxOf(a, e.start)
        if (overlap > 0) overlap else -minOf(kotlin.math.abs(e.start - b), kotlin.math.abs(a - e.end))
    } ?: -1

    /**
     * A Jimaku stream's episode made on the device without a PC (TV and phone): its lines moved by [offset] (the fit
     * or the hand shift), its words split by Sudachi, the [english] lines (already on the video's clock) under them.
     * Null while Sudachi's dictionary isn't on the device yet: its download starts here (about 80 MB, once).
     */
    suspend fun fromLines(context: android.content.Context, id: String, show: String, title: String, video: String, duration: Double,
                          lines: List<Triple<Double, Double, String>>, offset: Double, resume: Double,
                          english: List<io.github.pedrubik2000.kumapie.data.Jimaku.Line> = emptyList()): io.github.pedrubik2000.kumapie.data.EpisodeDetail? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val model = JapaneseModel(context)
            if (!model.isReady) { model.download(); return@withContext null }
            val cues = lines.map { Cue(it.first + offset, it.second + offset, it.third) }
            val json = build(id, show, title, duration, video, cues, english.map { Cue(it.start, it.end, it.text) }, model.words(cues.map { it.text }))
            io.github.pedrubik2000.kumapie.data.Api("").parseEpisode(json.put("resume", resume).toString())
        }

    private fun round3(x: Double) = Math.round(x * 1000) / 1000.0
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
