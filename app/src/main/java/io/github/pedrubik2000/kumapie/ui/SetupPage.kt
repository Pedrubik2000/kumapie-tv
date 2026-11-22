package io.github.pedrubik2000.kumapie.ui

import android.content.Context
import android.net.Uri
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Multipart
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.Recommended
import io.github.pedrubik2000.kumapie.lang.YomitanDictionaries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

class SetupPage(private val settings: Settings, private val context: Context, private val onSaved: () -> Unit) {
    private val code = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
    private var socket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(): String? {
        val ip = lanAddress() ?: return null
        val s = ServerSocket(0).also { socket = it }
        thread(isDaemon = true, name = "setup-page") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                scope.launch { runCatching { handle(c) } }
            }
        }
        return "http://$ip:${s.localPort}/$code"
    }

    fun stop() { runCatching { socket?.close() }; scope.cancel() }

    private suspend fun handle(c: Socket) = c.use {
        val input = BufferedInputStream(c.getInputStream())
        val head = generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList().ifEmpty { return@use }
        val (method, path) = head[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        fun header(name: String) = head.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
        val length = header("Content-Length").toIntOrNull() ?: 0
        if (path.substringBefore('?') != "/$code") return@use answer(c, 404, "<p>Not here.</p>")
        if (method == "POST" && header("Content-Type").startsWith("multipart/form-data")) return@use answer(c, 200, upload(input, length, header("Content-Type")))
        if (method == "POST") {
            val body = String(read(input, length.coerceAtMost(8192)), Charsets.UTF_8)
            save(body.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8").trim() } }.toMap())
            onSaved()
            return@use answer(c, 200, "<h1>✓ ${tr("Saved")}</h1><p>${tr("Look at your TV: kumapie has it.")}</p>")
        }
        answer(c, 200, form())
    }

    private suspend fun upload(input: InputStream, length: Int, type: String): String {
        if (length > 64 shl 20) return skip(input, length, "<p>${tr("Too big: %d MB at most.", 64)}</p>")
        val free = YomitanDictionaries.get(context).base.usableSpace
        if (!Recommended.fits(length.toLong(), free)) return skip(input, length, "<h1>${tr("Not enough space on the TV")}</h1><p>" +
            tr("This dictionary needs about %1\$d MB free while it installs; the TV has %2\$d MB.", Recommended.mb(Recommended.needed(length.toLong())), Recommended.mb(free)) + "</p>")
        val (name, bytes) = Multipart.file(read(input, length), type)?.takeIf { it.first.endsWith(".zip", true) && it.second.isNotEmpty() }
            ?: return "<p>${tr("Pick a .zip first.")}</p>"
        val zip = File(context.cacheDir, name).apply { writeBytes(bytes) }
        val said = try { YomitanDictionaries.get(context).import(listOf(Uri.fromFile(zip)), Lang.JAPANESE) {} } finally { zip.delete() }
        return "<h1>${tr("A Japanese dictionary")}</h1><p>" + said.joinToString("<br>") { it.replace("&", "&amp;").replace("<", "&lt;") } + "</p>"
    }

    private fun line(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = i.read()
            if (x < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (x == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(x)
        }
    }

    private fun read(i: InputStream, n: Int) = ByteArray(n).also { DataInputStream(i).readFully(it) }

    private fun skip(i: InputStream, n: Int, said: String): String {
        val buf = ByteArray(1 shl 16)
        var left = n
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size, left)); if (r < 0) break; left -= r }
        return said
    }

    private fun save(f: Map<String, String>) {
        f["jimaku"]?.takeIf { it.isNotEmpty() }?.let { settings.jimakuKey = it }
        settings.setSources(kitsu = f["kitsu"] == "on", english = f["english"] == "on", rd = f["rd"].orEmpty())
        f["anilist"]?.let { settings.anilist = it }
    }

    private fun form(): String {
        val p = settings.prefs
        fun set(yes: Boolean) = if (yes) " <b>✓ ${tr("set")}</b>" else ""
        fun tick(addon: String) = if (settings.addons.isEmpty() || addon in settings.addons) " checked" else ""
        return """
            <h1>${tr("Set up kumapie")}</h1>
            <form method="post">
            <h2>${tr("Japanese subtitles")}${set(settings.jimakuKey.isNotEmpty())}</h2>
            <p>${tr("kumapie shows each line in Japanese while you watch. They come from Jimaku, a free site of anime subtitles: make an account there, then copy your API key from Account.")}
            <a href="https://jimaku.cc/login" target="_blank">jimaku.cc</a></p>
            <input name="jimaku" placeholder="${tr("Jimaku API key")}" autocomplete="off">
            <h2>${tr("What to watch")}${set(settings.addons.any { "torrentio.strem.fun" in it })}</h2>
            <p>${tr("Videos: Torrentio finds an episode's files and Real-Debrid streams them (a paid account). Copy your token from real-debrid.com/apitoken.")}
            <a href="https://real-debrid.com/apitoken" target="_blank">real-debrid.com/apitoken</a></p>
            <input name="rd" placeholder="${tr("Real-Debrid token")}" autocomplete="off">
            <label><input type="checkbox" name="kitsu"${tick(Addons.KITSU)}> ${tr("Kitsu: anime titles, posters and episodes to pick from")}</label>
            <label><input type="checkbox" name="english"${tick(Addons.OPENSUBTITLES)}> ${tr("English under the lines: OpenSubtitles (free, no account)")}</label>
            <p>${tr("Your AniList lists (optional): what you're watching and plan to watch, at the top of Discover. Your profile must be public.")}</p>
            <input name="anilist" placeholder="${tr("AniList username")}" value="${settings.anilist.replace("\"", "")}" autocomplete="off">
            <button>${tr("Save")}</button>
            </form>
            <h2>${tr("A Japanese dictionary")}</h2>
            <form method="post" enctype="multipart/form-data">
            <p>${tr("A Yomitan .zip from your phone, for example a frequency list (JPDB: each word's most common reading first).")}</p>
            <p>${tr("Free on the TV: %1\$d MB. A dictionary takes about %2\$d times its .zip once installed.", Recommended.mb(YomitanDictionaries.get(context).base.usableSpace), Recommended.UNPACKED)}</p>
            <input type="file" name="dict" accept=".zip">
            <button>${tr("Import")}</button>
            </form>""".trimIndent()
    }

    private fun answer(c: Socket, status: Int, body: String) {
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>kumapie</title><style>body{font:16px system-ui,sans-serif;background:#0b0b10;color:#eee;margin:0 auto;max-width:560px;padding:16px}
            a{color:#ffd23f}input{display:block;width:100%;box-sizing:border-box;margin:8px 0 4px;padding:12px;border-radius:10px;border:1px solid #444;background:#16161e;color:#eee;font-size:16px}
            label{display:block;margin:12px 0}button{margin-top:20px;width:100%;padding:14px;border:0;border-radius:12px;background:#ffd23f;color:#000;font-size:17px;font-weight:600}
            h2{font-size:18px;margin-top:28px}p{color:#bbb}</style></head><body>$body</body></html>"""
        val bytes = html.toByteArray(Charsets.UTF_8)
        val out = c.getOutputStream()
        out.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }
}
