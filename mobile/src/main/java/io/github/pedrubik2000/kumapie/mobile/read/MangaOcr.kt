package io.github.pedrubik2000.kumapie.mobile.read

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Manga OCR on the device, no Mokuro and no network once the model is here (kumapie_plan.md, "Readers"):
 * PP-OCRv6_manga (huggingface.co/Kellenok/PP-OCRv6_manga, Apache-2.0: PP-OCRv6 tiny detector + small recognizer
 * fine-tuned on manga, 23 MB in FP32), the model card's pipeline (examples/onnx_openvino.py), then Manatan's line →
 * speech-bubble merge ([merge]). Checked on the PC first (C:\dojo\work\ppocr-manga): 0.3-0.7 s a page on the CPU.
 */
class MangaOcr(context: Context) {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/ppocr-manga")
    val isReady: Boolean get() = FILES.all { File(dir, it.name).length() == it.size }

    /** Downloads the missing files (pinned revision, size and SHA-256 checked). Blocking: call it off the main thread. */
    fun download(progress: (String) -> Unit) {
        dir.mkdirs()
        dir.listFiles().orEmpty().filter { f -> FILES.none { it.name == f.name } }.forEach { it.delete() } // an older model
        FILES.forEachIndexed { i, f ->
            val file = File(dir, f.name)
            if (file.length() == f.size) return@forEachIndexed
            progress(io.github.pedrubik2000.kumapie.i18n.tr("Downloading the manga OCR model (%1\$d of %2\$d)…", i + 1, FILES.size))
            val part = File(dir, f.name + ".part")
            URL("$HF${f.path}").openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val sha = MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
            if (part.length() != f.size || sha != f.sha256) { part.delete(); error("${f.name}: download damaged, try again") }
            part.renameTo(file)
        }
    }

    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val det by lazy { env.createSession(File(dir, FILES[0].name).path, OrtSession.SessionOptions()) }
    private val rec by lazy { env.createSession(File(dir, FILES[1].name).path, OrtSession.SessionOptions()) }
    private val vocab by lazy { listOf("blank") + File(dir, FILES[2].name).readLines(Charsets.UTF_8) + " " }

    /** A page image → (width, height, its speech bubbles); bubble i gets the id [base] + i. One page at a time. */
    @Synchronized
    fun read(image: File, base: Int): Triple<Int, Int, List<Manga.Block>> {
        val page = BitmapFactory.decodeFile(image.path) ?: error("Can't read ${image.name}")
        try {
            val t0 = System.currentTimeMillis()
            val lines = lines(page)
            val bubbles = merge(lines, page.width, page.height)
            android.util.Log.i("kumabook", "ocr ${image.name} ${page.width}x${page.height}: ${lines.size} lines, ${bubbles.size} bubbles, ${System.currentTimeMillis() - t0} ms")
            return Triple(page.width, page.height, bubbles.mapIndexed { i, b ->
                Manga.Block(base + i, listOf(b.x1, b.y1, b.x2, b.y2), b.vertical, b.font, b.text)
            })
        } finally {
            page.recycle()
        }
    }

    /** One detected line in page pixels, and what the recognizer read. */
    class Line(val x: Double, val y: Double, val w: Double, val h: Double, val vertical: Boolean, val text: String)

    private fun lines(page: Bitmap): List<Line> {
        val W = page.width; val H = page.height
        val ratio = if (maxOf(W, H).toDouble() / minOf(W, H) > 2) minOf(1.0, Math.sqrt(0.75 * 960 * 960 / (W.toDouble() * H))) else 960.0 / maxOf(W, H)
        val rw = maxOf(Math.round(W * ratio / 32).toInt() * 32, 32)
        val rh = maxOf(Math.round(H * ratio / 32).toInt() * 32, 32)
        val small = Bitmap.createScaledBitmap(page, rw, rh, true)
        val input = chw(small, floatArrayOf(0.485f, 0.456f, 0.406f), floatArrayOf(0.229f, 0.224f, 0.225f))
        if (small !== page) small.recycle()
        val pred = run(det, input, longArrayOf(1, 3, rh.toLong(), rw.toLong())) { it as Array<*> }.let { out ->
            @Suppress("UNCHECKED_CAST") val map = (out[0] as Array<Array<FloatArray>>)[0]
            FloatArray(rw * rh).also { f -> for (y in 0 until rh) System.arraycopy(map[y], 0, f, y * rw, rw) }
        }
        return regions(pred, rw, rh).mapNotNull { (bx, by, bw, bh) ->
            // The unclip offset of the model card's DB post-processing, for the region's box.
            val d = bw.toDouble() * bh * UNCLIP / (2.0 * (bw + bh))
            val x1 = maxOf(0.0, (bx - d) / rw * W); val y1 = maxOf(0.0, (by - d) / rh * H)
            val x2 = minOf(W.toDouble(), (bx + bw + d) / rw * W); val y2 = minOf(H.toDouble(), (by + bh + d) / rh * H)
            val cw = (x2 - x1).toInt(); val ch = (y2 - y1).toInt()
            if (cw < 1 || ch < 1) return@mapNotNull null
            val vertical = ch > cw
            val text = recognize(page, Bitmap.createBitmap(page, x1.toInt(), y1.toInt(), cw, ch), vertical)
            if (text.isBlank()) null else Line(x1, y1, x2 - x1, y2 - y1, vertical, text)
        }
    }

    /** Connected regions of the map over [THRESH] (8-neighbours) whose mean is at least [BOX_THRESH]: (x, y, w, h). */
    private fun regions(pred: FloatArray, w: Int, h: Int): List<IntArray> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<IntArray>()
        for (start in pred.indices) {
            if (seen[start] || pred[start] <= THRESH) continue
            var top = 0; stack[top++] = start; seen[start] = true
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var sum = 0.0; var n = 0
            while (top > 0) {
                val i = stack[--top]; val x = i % w; val y = i / w
                sum += pred[i]; n++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (!seen[j] && pred[j] > THRESH) { seen[j] = true; stack[top++] = j }
                }
            }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (sum / n >= BOX_THRESH && minOf(bw, bh) >= 2) out += intArrayOf(x0, y0, bw, bh)
        }
        return out
    }

    private fun recognize(page: Bitmap, crop: Bitmap, vertical: Boolean): String {
        val line = if (vertical) Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(-90f) }, true) else crop
        val tw = (48.0 * line.width / maxOf(1, line.height)).let { Math.round(it).toInt() }.let { maxOf(16, minOf(if (it > 2000) 2400 else 640, it)) }
        val scaled = Bitmap.createScaledBitmap(line, tw, 48, true)
        val input = chw(scaled, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
        listOf(crop, line, scaled).distinct().filter { it !== page }.forEach { it.recycle() } // a crop of the whole page is the page
        @Suppress("UNCHECKED_CAST")
        val logits = run(rec, input, longArrayOf(1, 3, 48, tw.toLong())) { (it as Array<Array<FloatArray>>)[0] }
        val text = StringBuilder()
        var last = -1
        for (step in logits) {
            var best = 0
            for (k in step.indices) if (step[k] > step[best]) best = k
            if (best != 0 && best != last) text.append(vocab.getOrElse(best) { "" })
            last = best
        }
        return text.toString()
    }

    /** A bitmap as a CHW float tensor in BGR order (PaddleOCR is trained on OpenCV's BGR images), (v/255 - mean) / std. */
    private fun chw(b: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val out = FloatArray(3 * w * h)
        for (i in px.indices) {
            val p = px[i]
            val bgr = intArrayOf(p and 0xFF, (p shr 8) and 0xFF, (p shr 16) and 0xFF)
            for (c in 0..2) out[c * w * h + i] = (bgr[c] / 255f - mean[c]) / std[c]
        }
        return out
    }

    private fun <T> run(session: OrtSession, data: FloatArray, shape: LongArray, read: (Any) -> T): T =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { r -> read(r[0].value) }
        }

    class ModelFile(val name: String, val path: String, val size: Long, val sha256: String)

    /** A speech bubble: its box in page pixels, orientation, font size (px) and text. */
    class Bubble(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val vertical: Boolean, val font: Double, val text: String)

    companion object {
        private const val THRESH = 0.15f
        private const val BOX_THRESH = 0.25
        private const val UNCLIP = 1.4
        private const val HF = "https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/8ee29e094b6aac77dd067c70805438782fa16ab0/"
        val FILES = listOf(
            // FP32: the FP16 files use com.microsoft.Gelu in float16, which Android's ONNX Runtime has no kernel for.
            ModelFile("manga_det_v0.2.onnx", "det/manga_det_v0.2.onnx", 1816954, "d132078c46e292b226fb5a2ca52a7612ad319262dfdf493e7d8e3be435295978"),
            ModelFile("manga_rec_v0.2.onnx", "rec/manga_rec_v0.2.onnx", 21167540, "de12c84c63e62c80339e882e675983d886670dcb6f0147e1ed041afd6fa81888"),
            ModelFile("ppocrv6_dict.txt", "ppocrv6_dict.txt", 74947, "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d"),
        )

        private val JA = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]")
        private val KANJI = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")
        private val KATAKANA = Regex("[\\u30a0-\\u30ff]")
        private val NOISE = Regex("^[|—_ノヘく/\\\\:;]$")

        /**
         * Lines → speech bubbles: Manatan's merge (KolbyML/Manatan crates/ocr-server/src/merge.rs, MIT, Copyright (c)
         * 2025 Kolby Moroz Liebl), Japanese path: drop noise, ghost boxes and furigana, then join lines of the same
         * orientation and size that sit close (union-find), right to left for vertical text.
         */
        fun merge(all: List<Line>, w: Int, h: Int): List<Bubble> {
            val lines = filterBad(all, w, h)
            class P(val v: Boolean, val font: Double, val len: Double, val minMain: Double, val maxMain: Double, val minCross: Double, val maxCross: Double)
            val p = lines.map { l ->
                val v = if (l.text.length == 1) l.h > l.w * 0.8 else l.vertical || l.h > l.w
                if (v) P(true, l.w, l.h, l.y, l.y + l.h, l.x, l.x + l.w) else P(false, l.h, l.w, l.x, l.x + l.w, l.y, l.y + l.h)
            }
            fun mergeable(a: P, b: P): Boolean {
                if (a.v != b.v) return false
                val mn = minOf(a.font, b.font); val ratio = maxOf(a.font, b.font) / mn
                if (ratio > 3.0) return false
                val raw = minOf(a.maxMain, b.maxMain) - maxOf(a.minMain, b.minMain)
                if (raw < -mn * 0.5) return false
                val om = maxOf(0.0, raw)
                val gc = maxOf(0.0, b.minCross - a.maxCross, a.minCross - b.maxCross)
                val overlap = om / maxOf(a.len, b.len)
                if (gc < mn * 0.2) return true
                var allowed = if (ratio < 1.25) (if (overlap > 0.8) 2.0 else if (overlap > 0.4) 0.9 else 1.3) else if (overlap > 0.5) 0.8 else 0.0
                if (maxOf(a.len, b.len) / minOf(a.len, b.len) > 2.5) allowed = minOf(allowed, 0.8)
                if (gc > mn * 1.2 && ratio > 1.15) return false
                if (gc > mn * allowed) return false
                if (om <= 0 && maxOf(0.0, b.minMain - a.maxMain, a.minMain - b.maxMain) > mn * 0.6) return false
                return true
            }
            val parent = IntArray(p.size) { it }
            fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
            for (i in p.indices) for (j in i + 1 until p.size) if (mergeable(p[i], p[j])) parent[find(i)] = find(j)
            return p.indices.groupBy(::find).values.map { idx ->
                val v = p[idx[0]].v
                // ponytail: 5 px buckets stand in for Manatan's "within 5 px" comparison (same order in practice).
                val g = idx.map { lines[it] }.sortedWith(if (v) compareBy({ -Math.round((it.x + it.w) / 5.0) }, { it.y })
                    else compareBy({ Math.round(it.y / 5.0) }, { it.x }))
                val fonts = idx.map { p[it].font }.sorted()
                Bubble(g.minOf { it.x }, g.minOf { it.y }, g.maxOf { it.x + it.w }, g.maxOf { it.y + it.h }, v, fonts[fonts.size / 2],
                    g.joinToString("") { it.text })
            }
        }

        private fun filterBad(lines: List<Line>, w: Int, h: Int): List<Line> {
            val n = lines.size
            val page = w.toDouble() * h
            val keep = BooleanArray(n) { true }
            for (i in 0 until n) {
                val l = lines[i]; val t = l.text.trim(); val area = l.w * l.h
                if (t.length == 1 && (t[0] in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" || t[0].isDigit() || NOISE.matches(t))) { keep[i] = false; continue }
                if (area < page * 0.0005 && !JA.containsMatchIn(t)) { keep[i] = false; continue }
                if (area > page * 0.30 && t.length < 6) keep[i] = false
            }
            for (i in 0 until n) { // ghosts: a small box mostly inside one 3x bigger
                if (!keep[i]) continue
                for (j in 0 until n) {
                    if (i == j || !keep[j]) continue
                    val a = lines[i]; val b = lines[j]
                    val xo = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x); val yo = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                    if (xo <= 0 || yo <= 0) continue
                    val inter = xo * yo; val ba = b.w * b.h
                    if (inter > ba * 0.3 && a.w * a.h > ba * 3 && inter > ba * 0.8) {
                        if (JA.containsMatchIn(b.text) && b.text !in a.text) continue
                        keep[j] = false
                    }
                }
            }
            for (i in 0 until n) { // furigana: thin kana next to a line with kanji
                if (!keep[i] || !KANJI.containsMatchIn(lines[i].text)) continue
                for (j in 0 until n) {
                    if (i == j || !keep[j]) continue
                    val m = lines[i]; val s = lines[j]
                    if (KANJI.containsMatchIn(s.text) || KATAKANA.containsMatchIn(s.text)) continue
                    val mt = minOf(m.w, m.h); val st = minOf(s.w, s.h)
                    if (st > mt * 0.8) continue
                    val lim = mt * 0.5
                    val xg = s.x - (m.x + m.w); val yo = minOf(m.y + m.h, s.y + s.h) - maxOf(m.y, s.y)
                    val yg = m.y - (s.y + s.h); val xo = minOf(m.x + m.w, s.x + s.w) - maxOf(m.x, s.x)
                    if ((xg > -mt * 0.5 && xg < lim && yo > 0) || (yg > -mt * 0.5 && yg < lim && xo > 0)) keep[j] = false
                }
            }
            return lines.filterIndexed { i, _ -> keep[i] }
        }
    }
}
