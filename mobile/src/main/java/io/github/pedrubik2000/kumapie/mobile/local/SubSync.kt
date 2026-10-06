package io.github.pedrubik2000.kumapie.mobile.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Locale

/**
 * Subtitles made for another release (Jimaku: WEB vs BD, a longer OP, 25 fps) → timed to this episode's audio. Silero
 * VAD finds the speech (as for Parakeet), written as a reference SRT; alass (kaegi/alass 2.0.0, GPL-3.0, shipped as
 * `libalass.so`, built by tools/alass/build.sh) moves the lines to fit it: one offset, a few parts, or a frame-rate
 * ratio. Tested on a German episode with planted errors: +4 s, a 3.8 s longer OP and 25 fps all came back within
 * 0.25 s (370-377 of 377 lines); alass takes 0.5 s on the Tab S7+.
 */
class SubSync(private val context: Context) {
    private val parakeet = Parakeet(context)
    /** Parakeet's copy when it is downloaded, else a 0.6 MB one of its own. */
    private val vad: File get() = File(parakeet.dir, "silero_vad.onnx").takeIf { it.exists() }
        ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "models/silero_vad.onnx")

    /** [subs] (.srt / .ass / .ssa / .vtt) → [out] (same format) fitted to [audio]; answers "+1.84 s" / "2 parts: …". */
    suspend fun sync(audio: File, subs: File, out: File): String = withContext(Dispatchers.IO) {
        val model = vad
        if (!model.exists()) {
            model.parentFile!!.mkdirs()
            val part = File(model.path + ".part")
            URL(Parakeet.FILES.first { it.first == "silero_vad.onnx" }.second).openStream().use { i -> part.outputStream().use { i.copyTo(it) } }
            part.renameTo(model)
        }
        val spans = parakeet.speech(parakeet.decode16k(audio), model)
        val ref = File(context.cacheDir, "sync-reference.srt")
        ref.writeText(spans.mapIndexed { i, (start, pcm) -> "${i + 1}\n${ts(start / 16000.0)} --> ${ts((start + pcm.size) / 16000.0)}\n.\n" }
            .joinToString("\n"))
        try {
            // Singing counts as speech for the VAD, so alass may move whole parts into the opening or ending song
            // (Bofuri 01: +40 s and +74 s). A firmer split penalty than alass's 7, and a part over 20 s away from the
            // rest means one offset for everything. ponytail: 20 s guard; TV captures with whole ad breaks
            // differ by more and then need the manual fix.
            val log = alass(ref, subs, out, "--split-penalty", "11")
            if (!strayPart(log)) summary(log) else summary(alass(ref, subs, out, "--no-split")) + " (one offset: parts looked like songs)"
        } finally {
            ref.delete()
        }
    }

    private suspend fun alass(ref: File, subs: File, out: File, vararg options: String): String {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libalass.so")
        val proc = ProcessBuilder(listOf(bin.path, *options, ref.path, subs.path, out.path)).redirectErrorStream(true).start()
        try {
            val log = runInterruptible { proc.inputStream.bufferedReader().readText() }
            val code = runInterruptible { proc.waitFor() }
            if (code != 0 || !out.exists()) error("alass exited with $code: " + log.lines().lastOrNull { it.isNotBlank() })
            return log
        } finally {
            proc.destroy()
        }
    }

    companion object {
        private fun ts(t: Double): String {
            val ms = Math.round(t * 1000)
            return "%02d:%02d:%02d,%03d".format(Locale.ROOT, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }

        private fun shifts(log: String) = Regex("""shifted block of (\d+) subtitles .*? by (-?)(\d+):(\d+):(\d+\.\d+)""").findAll(log).map { m ->
            val (count, sign, h, min, s) = m.destructured
            count.toInt() to (if (sign == "-") -1 else 1) * (h.toInt() * 3600 + min.toInt() * 60 + s.toDouble())
        }.toList()

        /** A part of 5+ lines shifted over 20 s away from the biggest part's shift. */
        fun strayPart(log: String): Boolean {
            val parts = shifts(log)
            val main = parts.maxByOrNull { it.first }?.second ?: return false
            return parts.any { it.first >= 5 && kotlin.math.abs(it.second - main) > 20 }
        }

        /** alass's "shifted block of N subtitles … by -0:00:03.758" lines (and a frame-rate ratio) → "−3.76 s" / "2 parts: …". */
        fun summary(log: String): String {
            val shifts = shifts(log)
            val ratio = Regex("""ratio is ([\d.]+/[\d.]+)""").find(log)?.groupValues?.get(1)?.takeIf { it != "1" }
            fun s(x: Double) = "%+.2f s".format(Locale.ROOT, x)
            val main = shifts.maxByOrNull { it.first }?.second ?: 0.0
            val parts = shifts.count { it.first >= 5 } // a handful of lines alone is noise at a cut, not a part
            return (if (parts > 1) "$parts parts, mostly ${s(main)}" else s(main)) + (ratio?.let { ", frame rate $it" } ?: "")
        }
    }
}
