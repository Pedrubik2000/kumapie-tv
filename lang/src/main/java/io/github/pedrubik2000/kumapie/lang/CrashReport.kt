package io.github.pedrubik2000.kumapie.lang

import org.json.JSONObject
import java.net.URLEncoder

data class CrashEnv(val device: String, val model: String, val android: String, val abi: String, val app: String, val version: String)

data class CrashReport(val kind: String, val time: Long, val summary: String, val trace: String, val publicTrace: String = trace)

data class CrashExit(val time: Long, val kind: String)

object CrashReports {
    const val TRACE_MAX = 100_000
    const val MAX_AGE_MS = 7L * 24 * 3_600_000
    const val ISSUE_CAP = 6_000
    const val QR_CAP = 1_800
    private const val ISSUES = "https://github.com/Pedrubik2000/kumapie-tv/issues/new"
    private val url = Regex("""(?i)\b([a-z][\w+.-]{0,20})://(?:[^/\s@]*@)?([^/\s"'<>?#@]*)\S*""")
    private val opaque = Regex("""(?i)\b(magnet|mailto|data|intent|blob|javascript):\S+""")
    private val secret = Regex("""(?i)([\w-]{0,30}(?:key|token|secret|password|authorization|realdebrid|cookie|session|auth)[\w-]{0,8})\\?["']?\s*(=|:|%3D)\s*\\?["']?[^\s&"',;)}\\]+""")
    private val bearer = Regex("""(?i)\bbearer\s+[\w.\-~+/]+=*""")
    private val printable = Regex("""[\x20-\x7e]{6,}""")
    private val keep = Regex("""^(Abort message.*|.*\bsignal \d+.*|\s*#\d+ pc .*)$""")

    fun scrub(text: String): String = text
        .replace(url) { "${it.groupValues[1]}://${it.groupValues[2]}/…" }
        .replace(opaque) { "${it.groupValues[1]}:…" }
        .replace(bearer, "Bearer ***")
        .replace(secret) { "${it.groupValues[1]}${it.groupValues[2]}***" }

    fun nativeLines(text: String): String = text.lineSequence().map { it.trim().take(300) }.filter { keep.matches(it) }.joinToString("\n")

    fun readable(bytes: ByteArray): String {
        val text = String(bytes, Charsets.ISO_8859_1)
        val odd = text.count { it.code == 0 || (it.code < 9) }
        return if (odd * 50 < text.length) String(bytes, Charsets.UTF_8) else printable.findAll(text).joinToString("\n") { it.value }
    }

    fun summary(kind: String, trace: String, note: String): String {
        val lines = trace.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val pick = when (kind) {
            "native" -> lines.firstOrNull { it.startsWith("Abort message") } ?: lines.firstOrNull { "signal " in it || "SIG" in it }
            "java" -> lines.firstOrNull()
            else -> null
        }
        return (pick ?: note.ifBlank { lines.firstOrNull().orEmpty() }.ifBlank { kind }).take(300)
    }

    fun build(kind: String, time: Long, note: String, raw: String, publicRaw: String = raw, structured: Boolean = false): CrashReport {
        fun clean(text: String) = scrub(text.take(TRACE_MAX).let { if (kind == "native" && !structured) nativeLines(it) else it }).take(TRACE_MAX)
        val trace = clean(raw)
        return CrashReport(kind, time, summary(kind, trace, scrub(note)), trace, clean(publicRaw))
    }

    fun fromBytes(kind: String, time: Long, note: String, bytes: ByteArray): CrashReport =
        (if (kind == "native") Tombstone.parse(bytes) else null)?.let { build(kind, time, note, it.all, it.crashing, true) }
            ?: build(kind, time, note, readable(bytes))

    fun json(r: CrashReport, e: CrashEnv): String = JSONObject().put("device", e.device).put("model", e.model).put("android", e.android)
        .put("abi", e.abi).put("app", e.app).put("version", e.version).put("kind", r.kind).put("time", r.time)
        .put("summary", r.summary).put("trace", r.trace).toString()

    fun newest(exits: List<CrashExit>, last: Long, now: Long): CrashExit? =
        exits.filter { it.time > last && now - it.time < MAX_AGE_MS }.maxByOrNull { it.time }

    fun trimmed(trace: String, head: Int, tail: Int): String {
        val lines = trace.lines()
        return if (lines.size <= head + tail) trace else (lines.take(head) + "…" + lines.takeLast(tail)).joinToString("\n")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun issue(r: CrashReport, e: CrashEnv, trace: String): String {
        val body = "kumapie ${e.version} (${e.app}) crashed (${r.kind}).\n\n- Device: ${e.model}\n- Android: ${e.android} (${e.abi})\n" +
            "- Time: ${r.time}\n- Summary: ${r.summary}\n" + if (trace.isEmpty()) "" else "\n```\n$trace\n```\n"
        return "$ISSUES?title=${enc("Crash: " + r.summary.take(90))}&body=${enc(body)}"
    }

    fun issueUrl(r: CrashReport, e: CrashEnv, cap: Int = ISSUE_CAP): String {
        var head = 40
        var tail = 20
        while (head > 0) {
            val u = issue(r, e, trimmed(r.publicTrace, head, tail))
            if (u.length <= cap) return u
            head /= 2
            tail /= 2
        }
        var s = r.summary
        while (true) {
            val u = issue(r.copy(summary = s), e, "")
            if (u.length <= cap || s.isEmpty()) return u
            s = s.take(s.length / 2)
        }
    }
}
