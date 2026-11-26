package io.github.pedrubik2000.kumapie.lang

import android.content.SharedPreferences
import io.github.pedrubik2000.kumapie.player.SceneController
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** One subtitle line as shown: start and end in seconds, as the player has them now (after every shift). */
data class ReportCue(val start: Double, val end: Double, val text: String)

/** "Report subtitle timing": what Pedro needs to see why the lines are off, nothing about the account. */
data class SubReport(
    val title: String, val titleId: String, val anilist: Int?, val episode: Int?, val source: String, val subFile: String,
    val fit: Double, val nudge: Double, val savedKey: String, val saved: Double, val position: Double,
    val now: ReportCue?, val next: List<ReportCue>, val media: String,
)

object SubReports {
    private const val TEXT_MAX = 120

    private fun clean(s: String) = CrashReports.scrub(s).take(TEXT_MAX)

    /** The file's name only: a URL (it may carry a token) or a path (a user dir) is cut to its last piece. */
    fun fileName(s: String): String = clean(s.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').substringAfterLast('\\'))

    /** The line on screen at [at] (the last one that started) and the [n] after it, by start. */
    fun cues(ctl: SceneController, at: Double, n: Int = 2): Pair<ReportCue?, List<ReportCue>> {
        val all = ctl.scenes.flatMap { it.cues }.sortedBy { it.start }.map { ReportCue(it.start, it.end, clean(it.text)) }
        val i = all.indexOfLast { it.start <= at }
        return all.getOrNull(i) to all.drop(i + 1).take(n)
    }

    fun of(ctl: SceneController, prefs: SharedPreferences): SubReport {
        val id = ctl.episode.id
        val file = prefs.getString(AloneStream.fileKey(id), "").orEmpty()
        val key = AloneStream.offKey(id, file)
        val titleId = io.github.pedrubik2000.kumapie.data.Addons.titleOf(id.removePrefix("alone:"))
        val (now, next) = cues(ctl, ctl.position)
        return SubReport(clean(ctl.episode.show), clean(titleId),
            prefs.getString("anilist_of_kitsu_" + titleId.removePrefix("kitsu:"), null)?.toIntOrNull(),
            AloneStream.episodeNumber(id), if (id.startsWith("alone:")) "jimaku" else "pc", fileName(file),
            prefs.getFloat(key, 0f).toDouble(), AloneStream.nudge(prefs, id), clean(key), prefs.getFloat(key, 0f).toDouble(),
            ctl.position, now, next, fileName(ctl.episode.video))
    }

    private fun cue(c: ReportCue) = JSONObject().put("start", c.start).put("end", c.end).put("text", c.text)

    fun json(r: SubReport, e: CrashEnv): String = JSONObject().put("title", r.title).put("title_id", r.titleId).put("anilist", r.anilist ?: JSONObject.NULL)
        .put("episode", r.episode ?: JSONObject.NULL).put("source", r.source).put("sub_file", r.subFile).put("fit_shift", r.fit)
        .put("nudge", r.nudge).put("saved_key", r.savedKey).put("saved_shift", r.saved).put("position", r.position)
        .put("cue_now", r.now?.let(::cue) ?: JSONObject.NULL).put("cues_next", JSONArray(r.next.map(::cue)))
        .put("media", r.media).put("device", e.device).put("model", e.model).put("app", e.app).put("version", e.version).toString()

    fun name(r: SubReport) = r.title + " ep " + (r.episode ?: "?")

    private fun f(x: Double) = "%.2f".format(java.util.Locale.ROOT, x)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun issueUrl(r: SubReport, e: CrashEnv, cap: Int = CrashReports.ISSUE_CAP): String {
        fun c(x: ReportCue) = "${f(x.start)}-${f(x.end)} ${x.text.take(40)}"
        fun url(cues: Boolean): String {
            val body = "kumapie ${e.version} (${e.app}), ${e.model}\n\n- Source: ${r.source} ${r.subFile}\n- Video: ${r.media}\n" +
                "- Fit shift: %.2f s, nudge: %.2f s, saved: %.2f s (${r.savedKey})\n- Position: %.2f s\n".format(java.util.Locale.ROOT, r.fit, r.nudge, r.saved, r.position) +
                if (cues) "- Now: ${r.now?.let(::c)}\n" + r.next.joinToString("") { "- Next: ${c(it)}\n" } else ""
            return "${CrashReports.ISSUES}?title=${enc("Subtitle timing: " + name(r))}&body=${enc(body)}"
        }
        return url(true).takeIf { it.length <= cap } ?: url(false)
    }

    suspend fun send(context: android.content.Context, r: SubReport, e: CrashEnv, qrCap: Int = 0): Delivery =
        ReportSender.deliver(context, "/api/report", json(r, e), qrCap) { cap -> issueUrl(r, e, cap) }
}
