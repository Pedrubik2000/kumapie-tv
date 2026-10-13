package io.github.pedrubik2000.kumapie.mobile.read

import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * Manga with Mokuro OCR (kumapie_plan.md, "Readers"; the format as chimahon reads it, Manhhao/chimahon `MokuroModels`):
 * a .cbz/.zip with the page images and the volume's `.mokuro` JSON ({title, volume, pages: [{img_path, img_width,
 * img_height, blocks: [{box: [x1, y1, x2, y2], vertical, font_size, lines}]}]}), or without one: then each page is read
 * on the device the first time it is shown ([MangaOcr]). A block (a speech bubble) is split, coloured and looked up as
 * the text reader's paragraphs; its id [Block.p] is page * 1000 + its index on the page.
 */
object Manga {
    class Block(val p: Int, val box: List<Double>, val vertical: Boolean, val fontSize: Double, val text: String)
    /** [blocks] null: not read yet (no .mokuro); [width]/[height] are known once it is. */
    class Page(val image: String, val width: Int, val height: Int, val blocks: List<Block>?)
    class Volume(val title: String, val pages: List<Page>, val images: Map<String, ByteArray>)

    private val IMAGE = Regex("""\.(jpe?g|png|webp|avif|gif)$""", RegexOption.IGNORE_CASE)

    fun isImage(name: String) = IMAGE.containsMatchIn(name)

    /** A whole archive in memory (tests, small files): [layout] plus the images under their stored paths. */
    fun read(zip: ByteArray): Volume {
        val files = LinkedHashMap<String, ByteArray>()
        var mokuro: ByteArray? = null
        ZipInputStream(zip.inputStream()).use { z ->
            generateSequence { z.nextEntry }.filter { !it.isDirectory }.forEach {
                if (it.name.endsWith(".mokuro", ignoreCase = true)) mokuro = z.readBytes() else if (isImage(it.name)) files[it.name] = z.readBytes()
            }
        }
        val (title, pages) = layout(files.keys.toList(), mokuro)
        return Volume(title, pages.map { it.first }, pages.associate { (page, source) -> page.image to files.getValue(source) })
    }

    /**
     * The volume from the archive's image names and its .mokuro (null: none): its title and each page with the archive
     * file it is stored from (`pages/0000.jpg`…). With a .mokuro, its pages and their bubbles; without, the images in
     * name order, read on the device when shown.
     */
    fun layout(images: List<String>, mokuro: ByteArray?): Pair<String, List<Pair<Page, String>>> {
        if (images.isEmpty()) error(io.github.pedrubik2000.kumapie.i18n.tr("No pictures in this archive."))
        fun stored(n: Int, source: String) = "pages/%04d.%s".format(n, source.substringAfterLast('.').lowercase())
        if (mokuro == null) {
            val sorted = images.sorted()
            return sorted.first().substringBefore('/', "") to sorted.mapIndexed { n, source -> Page(stored(n, source), 0, 0, null) to source }
        }
        val o = JSONObject(mokuro.toString(Charsets.UTF_8))
        val pages = o.getJSONArray("pages").let { ps ->
            (0 until ps.length()).mapNotNull { n ->
                val page = ps.getJSONObject(n)
                val source = image(images, page.optString("img_path"), n) ?: return@mapNotNull null
                val blocks = page.optJSONArray("blocks") ?: JSONArray()
                Page(stored(n, source), page.getInt("img_width"), page.getInt("img_height"), (0 until blocks.length()).mapNotNull { b ->
                    val block = blocks.getJSONObject(b)
                    val lines = block.getJSONArray("lines").let { l -> (0 until l.length()).map { l.getString(it) } }
                    val text = lines.joinToString("").trim()
                    val box = block.getJSONArray("box").let { x -> (0 until x.length()).map { x.getDouble(it) } }
                    if (text.isEmpty() || box.size < 4) return@mapNotNull null
                    Block(n * 1000 + b, box, block.optBoolean("vertical", true), block.optDouble("font_size", 0.0), text)
                }) to source
            }
        }
        if (pages.isEmpty()) error(io.github.pedrubik2000.kumapie.i18n.tr("None of the .mokuro file's pages is in the archive."))
        return listOf(o.optString("title"), o.optString("volume")).filter { it.isNotBlank() }.distinct().joinToString(" ") to pages
    }

    /** A page's image: the archive file [path] ends with, else one with its base name, else the [n]th image (chimahon's order). */
    private fun image(images: List<String>, path: String, n: Int): String? {
        val name = path.substringAfterLast('/')
        val base = name.substringBeforeLast('.')
        return images.firstOrNull { name.isNotEmpty() && (it.endsWith("/$name") || it == name) }
            ?: images.firstOrNull { base.isNotEmpty() && it.substringAfterLast('/').substringBeforeLast('.').equals(base, true) }
            ?: images.sorted().getOrNull(n)
    }

    /**
     * A mined card's picture: the page around [block] (the bubble and as much again on each side, so the panel shows),
     * at most 800 px wide, as a JPEG in [dir].
     */
    fun crop(image: java.io.File, block: Block, dir: java.io.File): java.io.File? {
        val page = android.graphics.BitmapFactory.decodeFile(image.path) ?: return null
        val (x1, y1, x2, y2) = block.box
        val w = x2 - x1; val h = y2 - y1
        val l = maxOf(0, (x1 - w).toInt()); val t = maxOf(0, (y1 - h).toInt())
        val r = minOf(page.width, (x2 + w).toInt()); val b = minOf(page.height, (y2 + h).toInt())
        if (r <= l || b <= t) return null
        val part = android.graphics.Bitmap.createBitmap(page, l, t, r - l, b - t)
        val scale = minOf(1.0, 800.0 / part.width)
        val out = if (scale < 1) android.graphics.Bitmap.createScaledBitmap(part, 800, (part.height * scale).toInt(), true) else part
        val file = java.io.File(dir, "bubble.jpg")
        file.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        listOf(page, part, out).distinct().forEach { it.recycle() }
        return file
    }

    fun toJson(pages: List<Page>) =JSONArray(pages.map { pg ->
        JSONObject().put("image", pg.image).put("w", pg.width).put("h", pg.height).put("blocks", pg.blocks?.let { bs -> JSONArray(bs.map { b ->
            JSONObject().put("p", b.p).put("box", JSONArray(b.box)).put("v", b.vertical).put("fs", b.fontSize).put("t", b.text)
        }) })
    })

    fun fromJson(a: JSONArray) = (0 until a.length()).map { i ->
        val pg = a.getJSONObject(i)
        val bs = pg.optJSONArray("blocks")
        Page(pg.getString("image"), pg.getInt("w"), pg.getInt("h"), bs?.let { (0 until it.length()).map { j ->
            val b = it.getJSONObject(j)
            val box = b.getJSONArray("box")
            Block(b.getInt("p"), (0 until box.length()).map { box.getDouble(it) }, b.getBoolean("v"), b.optDouble("fs", 0.0), b.getString("t"))
        } })
    }

    /**
     * One manga page as a WebView page (loaded with the book's folder as base URL): the picture fitted to the screen,
     * each block a box over its bubble; a tapped box shows its words, coloured by [shown], vertical where the bubble is;
     * a word in a shown box calls `word(p, i)` ([BookPage.JS_BRIDGE]). Turning as in Japanese manga (forward = the left
     * strip or a swipe to the right): `edge(±1)`; elsewhere outside a box `middle()`.
     */
    fun html(page: Page, pieces: Map<Int, List<Piece>>, shown: (String) -> String, colors: List<String>): String {
        val (bg, fg) = colors
        val boxes = StringBuilder()
        for (b in page.blocks.orEmpty()) {
            val (x1, y1, x2, y2) = b.box
            val pct = { v: Double, of: Int -> "%.3f%%".format(java.util.Locale.ROOT, 100 * v / of) }
            boxes.append("<div class=\"box").append(if (b.vertical) " v" else "").append("\" data-b=\"").append(b.p)
                .append("\" data-fs=\"").append(b.fontSize).append("\" style=\"left:").append(pct(x1, page.width)).append(";top:")
                .append(pct(y1, page.height)).append(";width:").append(pct(x2 - x1, page.width)).append(";height:")
                .append(pct(y2 - y1, page.height)).append("\">")
            pieces[b.p]?.forEachIndexed { i, piece ->
                if (piece.key == null) boxes.append(BookPage.esc(piece.text))
                else boxes.append("<span class=\"w ").append(BookPage.status(shown(piece.key))).append("\" data-p=\"").append(b.p)
                    .append("\" data-i=\"").append(i).append("\" data-k=\"").append(BookPage.esc(piece.key)).append("\">")
                    .append(BookPage.esc(piece.text)).append("</span>")
            }
            boxes.append("</div>\n")
        }
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=4, user-scalable=yes">
<style>
html, body { margin: 0; height: 100%; width: 100%; background: $bg; overflow: hidden; -webkit-user-select: none; user-select: none; }
#page { position: absolute; }
#page img { display: block; width: 100%; height: 100%; }
.box { position: absolute; box-sizing: border-box; color: transparent; line-height: 1.15; overflow: visible; white-space: nowrap; }
.box.v { writing-mode: vertical-rl; }
.box.open { color: $fg; background: rgba(20, 18, 28, 0.92); border-radius: 6px; z-index: 2; white-space: normal; padding: 2px; }
.box.open .k { color: $fg; } .box.open .d { color: ${colors[2]}; } .box.open .b { color: ${colors[3]}; } .box.open .u { color: ${colors[4]}; }
.box.open .sel { background: ${colors[5]}; border-radius: 3px; }
</style></head><body><div id="page"><img src="${BookPage.esc(page.image)}">
$boxes</div><script>
${script(page.width, page.height)}
</script></body></html>"""
    }

    private fun script(w: Int, h: Int) = """
var K = window.${BookPage.JS_BRIDGE}, W = $w, H = $h, P = document.getElementById('page');
function fit() {
  // The page in px, not 100%/vh: Android's WebView can resolve those to a few dozen px.
  document.documentElement.style.height = document.body.style.height = window.innerHeight + 'px';
  var s = Math.min(window.innerWidth / W, window.innerHeight / H);
  P.style.width = (W * s) + 'px'; P.style.height = (H * s) + 'px';
  // Centred in px: top/left 50% resolve against the same broken viewport height as vh.
  P.style.left = ((window.innerWidth - W * s) / 2) + 'px'; P.style.top = ((window.innerHeight - H * s) / 2) + 'px';
  document.querySelectorAll('.box').forEach(function (b) {
    var fs = +b.dataset.fs; b.style.fontSize = (fs > 0 ? fs * s : 16) + 'px';
  });
}
function select(p, i) {
  document.querySelectorAll('.sel').forEach(function (e) { e.classList.remove('sel'); });
  var e = document.querySelector('span.w[data-p="' + p + '"][data-i="' + i + '"]');
  if (e) e.classList.add('sel');
}
function recolor(map) {
  document.querySelectorAll('span.w').forEach(function (e) {
    var s = map[e.dataset.k];
    if (s) { e.classList.remove('k', 'd', 'b', 'u'); e.classList.add(s); }
  });
}
var x0 = null, y0 = null;
document.addEventListener('touchstart', function (e) {
  if (e.touches.length > 1) { x0 = null; return; } // a pinch
  x0 = e.touches[0].clientX; y0 = e.touches[0].clientY;
}, { passive: true });
document.addEventListener('touchend', function (e) {
  if (x0 === null || window.visualViewport && window.visualViewport.scale > 1.05) { x0 = null; return; } // zoomed: panning
  var dx = e.changedTouches[0].clientX - x0, dy = e.changedTouches[0].clientY - y0;
  x0 = null;
  if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy)) K.edge(dx > 0 ? 1 : -1);
});
document.addEventListener('click', function (e) {
  var x = e.clientX / window.innerWidth, zoomed = window.visualViewport && window.visualViewport.scale > 1.05;
  var w = e.target.closest('.box.open span.w');
  if (w) { K.word(+w.dataset.p, +w.dataset.i); return; }
  var box = e.target.closest('.box');
  if (box) {
    var was = box.classList.contains('open');
    document.querySelectorAll('.box.open').forEach(function (b) { b.classList.remove('open'); });
    if (!was) box.classList.add('open');
    return;
  }
  if (!zoomed && x < 0.15) { K.edge(1); return; }
  if (!zoomed && x > 0.85) { K.edge(-1); return; }
  document.querySelectorAll('.box.open').forEach(function (b) { b.classList.remove('open'); });
  K.middle();
});
window.addEventListener('resize', fit);
fit();
"""
}
