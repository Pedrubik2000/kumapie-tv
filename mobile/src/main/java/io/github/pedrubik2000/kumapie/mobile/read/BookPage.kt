package io.github.pedrubik2000.kumapie.mobile.read

/**
 * One section of a book as a WebView page with page turning (kumapie_plan.md, "Readers"), Hoshi Reader's way
 * (Manhhao/Hoshi-Reader-Android, GPL-3.0, `ReaderContentStyles` paginated mode): the page is the viewport, the body
 * is laid out in CSS columns exactly one page long, and a page turn scrolls by one page. Japanese is vertical
 * (`writing-mode: vertical-rl`: its columns stack downwards, so pages are scrollTop steps), the rest horizontal.
 *
 * Every word is `<span class="w <status>" data-p data-i data-k>`; the script tells Kotlin ([JS_BRIDGE]) about a
 * tapped word (`word(p, i, y)`, y: 0 top .. 1 bottom), the first paragraph on the page after a turn (`page(p)`), a turn past either end of the
 * section (`edge(±1)`) and a tap in the middle (`middle()`). Kotlin calls `select(p, i)`, `recolor({key: status})`.
 */
object BookPage {
    const val JS_BRIDGE = "Kuma"

    /**
     * [paragraphs] from paragraph number [first] (their [pieces]), coloured by [shown] ("k"/"d"/"b"/"u"), opening at
     * paragraph [start] or, with [atEnd], at the section's last page. [colors]: background, text, due, mined, unknown,
     * picked (CSS colours).
     */
    fun html(first: Int, paragraphs: List<String>, pieces: List<List<Piece>>, shown: (String) -> String, vertical: Boolean,
             start: Int, atEnd: Boolean, colors: List<String>, fontPx: Int = if (vertical) 22 else 20): String {
        val body = StringBuilder()
        paragraphs.forEachIndexed { n, text ->
            val p = first + n
            body.append("<p data-p=\"").append(p).append("\">")
            val ps = pieces.getOrNull(n)
            if (ps == null) body.append(esc(text)) else ps.forEachIndexed { i, piece ->
                if (piece.key == null) body.append(esc(piece.text))
                else body.append("<span class=\"w ").append(status(shown(piece.key))).append("\" data-p=\"").append(p)
                    .append("\" data-i=\"").append(i).append("\" data-k=\"").append(esc(piece.key)).append("\">")
                    .append(esc(piece.text)).append("</span>")
            }
            body.append("</p>\n")
        }
        val (bg, fg, due, mined, unknown, picked) = colors
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
html, body { margin: 0; padding: 0; overflow: hidden; height: var(--h, 100vh); width: var(--w, 100vw); background: $bg; color: $fg;
  writing-mode: ${if (vertical) "vertical-rl" else "horizontal-tb"}; -webkit-user-select: none; user-select: none; }
body { box-sizing: border-box; font-size: ${fontPx}px; line-height: 1.8; text-orientation: mixed;
  padding: var(--pad); column-width: var(--col); column-gap: var(--gap); column-fill: auto; }
p { margin: 0 0 0.6em 0; text-indent: 1em; orphans: 1; widows: 1; }
.k { color: $fg; } .d { color: $due; } .b { color: $mined; } .u { color: $unknown; }
.sel { background: $picked; border-radius: 3px; }
</style></head><body>
$body<script>
${script(vertical, start, atEnd)}
</script></body></html>"""
    }

    private operator fun <T> List<T>.component6() = this[5]

    /** "k" known, "d" due, "b" its Mined card never reviewed, anything else unknown ("u"). */
    fun status(shown: String) = when (shown) { "k", "d", "b" -> shown; else -> "u" }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun script(vertical: Boolean, start: Int, atEnd: Boolean) = """
var V = $vertical, PAD = 32, page = 0, K = window.$JS_BRIDGE;
var B = document.body;
function size() { return V ? window.innerHeight : window.innerWidth; }
function setup() {
  var s = size(), st = document.documentElement.style;
  // The page in px, not 100vh: Android's WebView can resolve vh to a few dozen px (Hoshi Reader does the same).
  st.setProperty('--w', window.innerWidth + 'px');
  st.setProperty('--h', window.innerHeight + 'px');
  st.setProperty('--pad', PAD + 'px');
  st.setProperty('--col', (s - 2 * PAD) + 'px');
  st.setProperty('--gap', (2 * PAD) + 'px');
}
function pages() { return Math.max(1, Math.round((V ? B.scrollHeight : B.scrollWidth) / size())); }
function go(n) {
  page = Math.max(0, Math.min(pages() - 1, n));
  if (V) B.scrollTop = page * size(); else B.scrollLeft = page * size();
  K.page(firstShown());
}
function turn(d) {
  if (page + d < 0 || page + d >= pages()) { K.edge(d); return; }
  go(page + d);
}
function onPage(r) { return r.right > 0 && r.left < window.innerWidth && r.bottom > 0 && r.top < window.innerHeight; }
// The first paragraph that starts on this page (reopening goes back to this very page), else the one running through it.
function firstShown() {
  var ps = document.querySelectorAll('p'), through = -1;
  for (var i = 0; i < ps.length; i++) {
    var rs = ps[i].getClientRects();
    if (rs.length && onPage(rs[0])) return +ps[i].dataset.p;
    if (through < 0) for (var j = 1; j < rs.length; j++) if (onPage(rs[j])) { through = +ps[i].dataset.p; break; }
  }
  return through;
}
function goToParagraph(p) {
  var el = document.querySelector('p[data-p="' + p + '"]');
  if (!el) { go(0); return; }
  var r = el.getClientRects()[0] || el.getBoundingClientRect();
  var at = V ? r.top + B.scrollTop : r.left + B.scrollLeft;
  go(Math.floor(at / size()));
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
// Japanese books turn right to left: forward is a tap on the left or a swipe to the right.
var FORWARD_LEFT = V, x0 = null, y0 = null;
document.addEventListener('touchstart', function (e) { x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; }, { passive: true });
document.addEventListener('touchend', function (e) {
  if (x0 === null) return;
  var dx = e.changedTouches[0].clientX - x0, dy = e.changedTouches[0].clientY - y0;
  x0 = null;
  if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy)) turn((dx > 0) === FORWARD_LEFT ? 1 : -1);
});
// The empty margins turn pages (a word anywhere on the page stays tappable: vertical text fills the outer columns);
// a word is looked up; the rest closes the word panel. Swipes turn too.
document.addEventListener('click', function (e) {
  var edge = PAD + 12;
  if (e.clientX < edge) { turn(FORWARD_LEFT ? 1 : -1); return; }
  if (e.clientX > window.innerWidth - edge) { turn(FORWARD_LEFT ? -1 : 1); return; }
  var w = e.target.closest('span.w');
  if (w) K.word(+w.dataset.p, +w.dataset.i, e.clientY / window.innerHeight); else K.middle();
});
window.addEventListener('resize', function () { var p = firstShown(); setup(); goToParagraph(p); });
setup();
requestAnimationFrame(function () { ${if (atEnd) "go(pages() - 1);" else "goToParagraph($start);"} });
"""
}
