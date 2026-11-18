package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** The services whose keys kumapie checks, with an endpoint that needs the key and answers 401/403 to a wrong one. */
enum class KeyService(val label: String, val url: String, val bearer: Boolean, val where: String) {
    SONIOX("Soniox", "https://api.soniox.com/v1/files?limit=1", true, "soniox.com > API keys"),
    REAL_DEBRID("Real-Debrid", "https://api.real-debrid.com/rest/1.0/user", true, "real-debrid.com/apitoken"),
    JIMAKU("Jimaku", "https://jimaku.cc/api/entries/search?query=a", false, "jimaku.cc > Account"),
}

/** "ok", "rejected", "offline" or "http <code>": the key tried once against [service]. */
suspend fun checkKey(service: KeyService, key: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val c = java.net.URL(service.url).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("Authorization", if (service.bearer) "Bearer $key" else key)
        c.setRequestProperty("User-Agent", "kumapie")
        c.connectTimeout = 8000; c.readTimeout = 8000
        try { c.responseCode } finally { c.disconnect() }
    }.fold({ code -> when (code) { in 200..299 -> "ok"; 401, 403 -> "rejected"; else -> "http $code" } }, { "offline" })
}

/**
 * Settings > Check setup (idea from HolyArrs' `doctor`: every line OK, WARN or FAIL, and each WARN or FAIL says how to
 * fix it): the PC, the keys, the addons and AniList, each tried once. Keys never show; addons show their host only.
 */
object SetupCheck {
    enum class Verdict { OK, WARN, FAIL }
    class Line(val what: String, val verdict: Verdict, val say: String)

    suspend fun run(settings: Settings): List<Line> = coroutineScope {
        val p = settings.prefs
        val noPc = settings.server.isEmpty() // with one, the PC finds the subtitles and videos
        val jimaku = p.getString("jimaku_key", "").orEmpty()
        // The phone keeps the token by itself; the TV only inside Torrentio's address.
        val rd = p.getString("rd_token", "").orEmpty().ifEmpty {
            settings.addons.firstNotNullOfOrNull { Regex("realdebrid=([^/|%]+)").find(it)?.groupValues?.get(1) }.orEmpty()
        }
        val anilist = p.getString(Addons.ANILIST, "").orEmpty()
        val alone = noPc && settings.standalone
        listOfNotNull(
            async { listOf(pc(settings)) },
            // Jimaku and Real-Debrid matter without a PC (streams) or when set; a parent with a PC needs neither.
            when {
                jimaku.isNotEmpty() -> async { listOf(key(KeyService.JIMAKU, jimaku, tr("Japanese subtitles"))) }
                alone -> async { listOf(Line("Jimaku", Verdict.WARN, tr("No key: streams get no Japanese subtitles. Your free key: jimaku.cc > Account."))) }
                else -> null
            },
            if (rd.isNotEmpty()) async { listOf(key(KeyService.REAL_DEBRID, rd, tr("Videos to stream"))) } else null,
            if (alone) async { addons(settings) } else null,
            if (anilist.isNotEmpty()) async { listOf(aniList(anilist, p)) } else null,
        ).awaitAll().flatten()
    }

    private suspend fun pc(settings: Settings): Line = when {
        settings.server.isNotEmpty() -> runCatching { Api(settings.server).info() }.fold(
            { Line(tr("The PC"), Verdict.OK, tr("Answers: %d episodes.", it.episodes)) },
            { Line(tr("The PC"), Verdict.FAIL, tr("Doesn't answer (%s). Is the PC on, and Tailscale connected on this device?", it.message ?: it.toString())) })
        settings.standalone -> Line(tr("The PC"), Verdict.OK, tr("None: kumapie works on this device alone."))
        else -> Line(tr("The PC"), Verdict.WARN, tr("Not set: type its address in Settings > Server, or use kumapie without one."))
    }

    private suspend fun key(s: KeyService, key: String, what: String): Line = when (val r = checkKey(s, key)) {
        "ok" -> Line(s.label, Verdict.OK, tr("%1\$s accepted the key (%2\$s).", s.label, what))
        "rejected" -> Line(s.label, Verdict.FAIL, tr("%1\$s didn't accept this key. Copy it again from %2\$s.", s.label, s.where))
        "offline" -> Line(s.label, Verdict.WARN, tr("Couldn't reach %s. Is this device online?", s.label))
        else -> Line(s.label, Verdict.WARN, tr("%1\$s answered %2\$s. Try again later.", s.label, r.removePrefix("http ")))
    }

    private suspend fun addons(settings: Settings): List<Line> {
        val all = settings.addons
        if (all.isEmpty()) return listOf(Line(tr("Stremio addons"), Verdict.FAIL, tr("None: add one that lists titles (Kitsu) and one that gives links (Torrentio) in Settings.")))
        val names = runCatching { Addons(all, settings.prefs).names() }.getOrDefault(all.map { "" })
        return all.mapIndexed { i, a ->
            val host = Addons.base(a).substringAfter("//").substringBefore('/') // the address can carry a debrid key
            val name = names.getOrNull(i).orEmpty()
            if (name.isNotEmpty()) Line(name, Verdict.OK, tr("Answers (%s).", host))
            else Line(host, Verdict.FAIL, tr("Doesn't answer: check its address, or remove it in Settings."))
        }
    }

    private suspend fun aniList(user: String, store: android.content.SharedPreferences): Line =
        runCatching { AniList.rows(user, store) }.fold(
            { Line("AniList", Verdict.OK, tr("%1\$s: %2\$d lists for Discover.", user, it.size)) },
            { Line("AniList", Verdict.FAIL, tr("Couldn't read %s's lists: check the username, and that the profile is public.", user)) })
}
