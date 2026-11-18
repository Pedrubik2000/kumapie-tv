package io.github.pedrubik2000.kumapie.ui

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * The TV's setup page for the phone (kumapie_plan.md "Onboarding"; keys are long to type with a remote): a small HTTP
 * page on the home Wi-Fi, only while the onboarding shows it, under a random path the QR code carries. The phone posts
 * the Jimaku key, the Real-Debrid token (-> Torrentio), Kitsu and the AniList username; [onSaved] then.
 * ponytail: plain HTTP on the LAN, one request per connection; the random path keeps other devices on the Wi-Fi out.
 */
class SetupPage(private val settings: Settings, private val onSaved: () -> Unit) {
    private val code = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
    private var socket: ServerSocket? = null

    /** Starts it; answers its address (http://<the TV's Wi-Fi address>:<port>/<code>), null without a home network. */
    fun start(): String? {
        val ip = lanAddress() ?: return null
        val s = ServerSocket(0).also { socket = it }
        thread(isDaemon = true, name = "setup-page") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(c) } }
            }
        }
        return "http://$ip:${s.localPort}/$code"
    }

    fun stop() { runCatching { socket?.close() } }

    private fun handle(c: Socket) = c.use {
        val input = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
        val (method, path) = (input.readLine() ?: return@use).split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var length = 0
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (path.substringBefore('?') != "/$code") return@use answer(c, 404, "<p>Not here.</p>")
        if (method == "POST") {
            val body = CharArray(length.coerceAtMost(8192)).let { buf -> var n = 0; while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }; String(buf, 0, n) }
            save(body.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8").trim() } }.toMap())
            onSaved()
            return@use answer(c, 200, "<h1>✓ ${tr("Saved")}</h1><p>${tr("Look at your TV: kumapie has it.")}</p>")
        }
        answer(c, 200, form())
    }

    private fun save(f: Map<String, String>) {
        f["jimaku"]?.takeIf { it.isNotEmpty() }?.let { settings.prefs.edit().putString("jimaku_key", it).apply() }
        val add = listOfNotNull(Addons.KITSU.takeIf { f["kitsu"] == "on" }, Addons.OPENSUBTITLES.takeIf { f["english"] == "on" }, f["rd"]?.takeIf { it.isNotEmpty() }?.let(Addons::torrentio))
        // A new Real-Debrid token replaces the old Torrentio link.
        val kept = (if (f["rd"].isNullOrEmpty()) settings.addons else settings.addons.filterNot { "torrentio.strem.fun" in it })
            .filterNot { it == Addons.KITSU || it == Addons.OPENSUBTITLES } // an unticked box (not posted) takes it off
        settings.addons = (kept + add).distinct()
        f["anilist"]?.let { settings.prefs.edit().putString(Addons.ANILIST, it).apply() }
    }

    /** The page: keys are never sent back (a field already set says so). */
    private fun form(): String {
        val p = settings.prefs
        fun set(yes: Boolean) = if (yes) " <b>✓ ${tr("set")}</b>" else ""
        // Ticked when there, or on a first setup (no addons yet).
        fun tick(addon: String) = if (settings.addons.isEmpty() || addon in settings.addons) " checked" else ""
        return """
            <h1>${tr("Set up kumapie")}</h1>
            <form method="post">
            <h2>${tr("Japanese subtitles")}${set(!p.getString("jimaku_key", "").isNullOrEmpty())}</h2>
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
            <input name="anilist" placeholder="${tr("AniList username")}" value="${p.getString(Addons.ANILIST, "").orEmpty().replace("\"", "")}" autocomplete="off">
            <button>${tr("Save")}</button>
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
        /** The TV's address on the home network (Wi-Fi or Ethernet; not Tailscale's 100.x). */
        fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }
}
