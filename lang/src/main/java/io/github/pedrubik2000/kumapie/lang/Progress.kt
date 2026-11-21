package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.i18n.tr
import android.os.Build
import android.util.Log
import io.github.pedrubik2000.kumapie.data.Ranges
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Span
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * What Pedro did in kumapie (where each episode was left, scenes watched to the end, watch time per study day, words
 * looked up, words marked known), shared between his devices through the PC (`POST /api/tv/devices_progress`, since
 * 2026-11-16; kuma3's notes no longer, 2026-11-19): one part per device, written only by that device, plus "pc" (the
 * PC's history). Every device merges them: latest position, scenes seen anywhere, time and lookups added up, the latest
 * known/unknown mark. This device's part is kept in a file, the others' as the PC last gave them.
 */
class Progress internal constructor(dir: File, private val settings: Settings?) {
    private val mine = File(dir, "progress.json")
    private val othersFile = File(dir, "progress-others.json")
    private val lock = Mutex()
    @Volatile private var data: JSONObject = runCatching { JSONObject(mine.readText()) }.getOrDefault(empty())
    @Volatile private var others: JSONObject = runCatching { JSONObject(othersFile.readText()) }.getOrDefault(JSONObject())
    @Volatile var merged: View = merge()
        private set

    /** This device's name in Anki: model + a random part, made once. */
    val device: String = settings?.prefs?.let { p -> p.getString("progress_device", null) ?: "${Build.MODEL}-${UUID.randomUUID().toString().take(6)}"
        .also { p.edit().putString("progress_device", it).apply() } } ?: "test"

    class View(
        val pos: Map<String, Double>,
        val watch: Map<String, Double>, // study day -> seconds
        val lookups: Map<String, Int>,
        val marked: Set<String>,
        val gloss: Map<String, String>,
        /** Episode -> when it was marked not watched (seconds): scenes seen before that don't count. */
        val unwatched: Map<String, Long> = emptyMap(),
        /** Episode -> when its position was saved (seconds): "Continue watching" puts the newest first. */
        val posAt: Map<String, Long> = emptyMap(),
        /** Language code -> study day -> seconds; only since 2026-10-15 ([watch] has all of it). */
        val langWatch: Map<String, Map<String, Double>> = emptyMap(),
        /** Episode -> what was watched of it (any device, since its last "not watched"). */
        val ranges: Map<String, List<Span>> = emptyMap(),
        /** Episodes watched to the end (their spoken lines all played, or marked watched). */
        val done: Set<String> = emptySet(),
    )

    // ------------------------------------------------------------------ recording (this device)

    fun record(episode: String, pos: Double?, watched: Double, lang: String? = null,
               ranges: List<Span> = emptyList(), done: Boolean = false) = change {
        val now = now()
        if (ranges.isNotEmpty()) addRanges(it, episode, ranges, now)
        if (done) it.child("done").put(episode, now)
        if (pos != null) it.getJSONObject("pos").put(episode, JSONArray().put(pos).put(now))
        it.remove("seen")  // scene ids from before 2026-11-17: watched is time ranges now, the note shrinks
        if (watched > 0) it.getJSONObject("watch").let { w -> w.put(studyDay(), w.optDouble(studyDay(), 0.0) + minOf(watched, 600.0)) }
        if (watched > 0 && lang != null) {
            val lw = it.optJSONObject("lwatch") ?: JSONObject().also { o -> it.put("lwatch", o) }
            val w = lw.optJSONObject(lang) ?: JSONObject().also { o -> lw.put(lang, o) }
            w.put(studyDay(), w.optDouble(studyDay(), 0.0) + minOf(watched, 600.0))
        }
    }

    fun lookup(word: String): Int {
        change { it.getJSONObject("lookups").let { l -> l.put(word, l.optInt(word) + 1) } }
        return merged.lookups[word] ?: 1
    }

    /**
     * "Mark as not watched": the episode's scenes seen until now stop counting on every device (they keep their own
     * notes, so this is a time, not a deletion) and it starts from the beginning. Seeing a scene again counts again.
     */
    fun unwatch(episode: String) = change {
        val now = now()
        it.optJSONObject("ranges")?.remove(episode)
        it.optJSONObject("done")?.remove(episode)
        (it.optJSONObject("unwatched") ?: JSONObject().also { u -> it.put("unwatched", u) }).put(episode, now)
        it.getJSONObject("pos").put(episode, JSONArray().put(0.0).put(now))
    }

    /** This device's ranges of an episode: [since, [[start, end], ...]]; started again after a "not watched". */
    private fun addRanges(d: JSONObject, episode: String, ranges: List<Span>, now: Long) {
        val unwatched = merged.unwatched[episode] ?: -1
        val old = d.child("ranges").optJSONArray(episode)?.takeIf { it.getLong(0) > unwatched }
        d.child("ranges").put(episode, JSONArray().put(old?.getLong(0) ?: maxOf(now, unwatched + 1))
            .put(Ranges.json(Ranges.union(Ranges.parse(old?.getJSONArray(1)) + ranges))))
    }

    private fun JSONObject.child(name: String): JSONObject = optJSONObject(name) ?: JSONObject().also { put(name, it) }

    fun mark(word: String, known: Boolean) = change { it.getJSONObject("marked").put(word, JSONArray().put(known).put(now())) }

    private fun change(edit: (JSONObject) -> Unit) = synchronized(this) {
        val d = JSONObject(data.toString())
        edit(d)
        data = d
        mine.writeText(d.toString())
        merged = merge()
    }

    // ------------------------------------------------------------------ Anki and the PC

    /**
     * Sends this device's part to the PC and takes the other devices' parts and the PC's history ("pc") back. Without
     * the PC (none set, or not answering) it keeps what it had: until 2026-11-19 a sync without the PC replaced the
     * other devices' parts with kuma3's notes (0 of them on the tablet), and the PC's history, other devices' time and
     * marks were gone (Home 30.6 h -> 13.5 h, 682 fewer words known). kuma3 is no longer used here (Pedro, 2026-11-19).
     */
    suspend fun sync(api: io.github.pedrubik2000.kumapie.data.Api?) = lock.withLock {
        runCatching {
            val settings = settings?.takeIf { it.server.isNotEmpty() } ?: return@withLock // no PC: nothing to send or take; what came from it stays
            val fromPc = api?.let { runCatching { it.devicesProgress(device, data) }.onFailure { Log.w("kumapie", "progress to the PC: $it") }.getOrNull() }
                ?: return@withLock // the PC didn't answer: keep what we had
            val pcText = runCatching { api.history() }.getOrNull()?.let { JSONObject(it).toString() }
            val o = JSONObject()
            fromPc.keys().forEach { dev -> if (dev != device) o.put(dev, fromPc.getJSONObject(dev)) }
            // The PC's history: the new one, else the one we had (a failed read must not drop it).
            (pcText?.let(::JSONObject) ?: others.optJSONObject("pc"))?.let { o.put("pc", it) }
            Log.i("kumapie", "progress: ${fromPc.length()} devices from the PC")
            others = o
            othersFile.writeText(o.toString())
            merged = merge()
            // The PC (and so the TV) learns what counts as seen here: it blurs and counts episodes by it (item 14).
            if (api != null) {
                val sent = merged.unwatched.hashCode() * 31 + merged.ranges.hashCode()
                if (settings.prefs.getInt("device_seen_sent", 0) != sent) {
                    runCatching { api.deviceSeen(merged.unwatched, merged.ranges) }
                        .onSuccess { settings.prefs.edit().putInt("device_seen_sent", sent).apply() }
                        .onFailure { Log.w("kumapie", "device seen not sent: $it") }
                }
            }
        }.onFailure { Log.w("kumapie", "progress sync: $it") }
    }


    // ------------------------------------------------------------------ stats (the phone, and the TV without a PC)

    /**
     * Stats from what is merged here (this device, the others, the PC's history), so they need no PC: watch time per
     * study day, today / 7 days / total, the streak (days with at least a minute), lookups, marks; scenes from [shows]
     * (the PC's library: none on a TV without one).
     */
    fun stats(shows: List<io.github.pedrubik2000.kumapie.data.Show> = emptyList()): io.github.pedrubik2000.kumapie.data.Stats =
        statsOf(merged, shows, java.time.LocalDate.parse(studyDay()))

    // ------------------------------------------------------------------ merging

    /** This person's devices only, without the PC's history (the TV): what the PC gets as the phones' time. */
    fun devices(): View = merge(skip = "pc")

    private fun merge(skip: String? = null): View =
        merge(listOf(data) + others.keys().asSequence().filter { it != skip }.mapNotNull { others.optJSONObject(it) }.toList())

    internal fun parts(): JSONObject = JSONObject(others.toString()).put(device, JSONObject(data.toString()))

    internal fun useOthers(o: JSONObject) = synchronized(this) {
        others = o
        othersFile.writeText(o.toString())
        merged = merge()
    }

    companion object {
        fun merge(parts: List<JSONObject>): View {
            val pos = HashMap<String, Pair<Double, Long>>()
            val unwatched = HashMap<String, Long>()
            val watch = HashMap<String, Double>()
            val langWatch = HashMap<String, HashMap<String, Double>>()
            val lookups = HashMap<String, Int>()
            val gloss = HashMap<String, String>()
            val ranges = ArrayList<Map<String, Pair<Long, List<Span>>>>()
            val done = HashMap<String, Long>()
            for (p in parts) {
                p.optJSONObject("ranges")?.let { o ->
                    ranges += o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { it.getLong(0) to Ranges.parse(it.getJSONArray(1)) } }
                }
                p.optJSONObject("done")?.let { o -> for (k in o.keys()) done[k] = maxOf(done[k] ?: 0, o.getLong(k)) }
                p.optJSONObject("pos")?.let { o ->
                    for (k in o.keys()) {
                        val a = o.getJSONArray(k)
                        if ((pos[k]?.second ?: -1) <= a.optLong(1)) pos[k] = a.getDouble(0) to a.optLong(1)
                    }
                }
                p.optJSONObject("unwatched")?.let { o -> for (k in o.keys()) unwatched[k] = maxOf(unwatched[k] ?: 0, o.getLong(k)) }
                p.optJSONObject("watch")?.let { o -> for (k in o.keys()) watch[k] = (watch[k] ?: 0.0) + o.getDouble(k) }
                p.optJSONObject("lwatch")?.let { o ->
                    for (l in o.keys()) o.getJSONObject(l).let { w -> val m = langWatch.getOrPut(l) { HashMap() }; for (k in w.keys()) m[k] = (m[k] ?: 0.0) + w.getDouble(k) }
                }
                p.optJSONObject("lookups")?.let { o -> for (k in o.keys()) lookups[k] = (lookups[k] ?: 0) + o.getInt(k) }
                p.optJSONObject("gloss")?.let { o -> for (k in o.keys()) gloss[k] = o.getString(k) }
            }
            return View(pos.mapValues { it.value.first }, watch, lookups, io.github.pedrubik2000.kumapie.lang.LeavePc.newestMarks(parts), gloss, unwatched,
                pos.mapValues { it.value.second }, langWatch, stillWatched(ranges, unwatched),
                done.filter { (ep, at) -> at > (unwatched[ep] ?: -1) }.keys)
        }

        @Volatile private var one: Progress? = null

        /** One per app process (the player, and the condensed service each make a Library). */
        fun of(context: Context): Progress = one ?: synchronized(this) {
            one ?: Progress(context.applicationContext.filesDir, Settings(context.applicationContext)).also { one = it }
        }


        private fun empty() = JSONObject().put("pos", JSONObject()).put("watch", JSONObject())
            .put("lookups", JSONObject()).put("marked", JSONObject())

        private fun now() = System.currentTimeMillis() / 1000

        /** Episode -> its ranges that still count: each device's ([since] to spans) started after the last "not watched". */
        fun stillWatched(parts: List<Map<String, Pair<Long, List<Span>>>>, unwatched: Map<String, Long>): Map<String, List<Span>> =
            parts.flatMap { it.entries }.filter { (ep, r) -> r.first > (unwatched[ep] ?: -1) }
                .groupBy({ it.key }, { it.value.second }).mapValues { Ranges.union(it.value.flatten()) }

        /**
         * Seconds per study day in [lang]: its own time, plus its share of the time kept before languages were (and of
         * the PC's history), split like the episodes watched in each language ([sceneLang]: episode -> language; scenes until 2026-11-17).
         */
        // ponytail: old time split by scene share, not real time per language; exact only from 2026-10-15 on
        fun daysIn(lang: String, v: View, sceneLang: Map<String, String>): Map<String, Double> {
            val share = if (sceneLang.isEmpty()) 1.0 else sceneLang.values.count { it == lang }.toDouble() / sceneLang.size
            val own = v.langWatch[lang].orEmpty()
            return v.watch.mapValues { (day, all) ->
                val split = v.langWatch.values.sumOf { it[day] ?: 0.0 }
                (own[day] ?: 0.0) + (all - split).coerceAtLeast(0.0) * share
            }
        }

        fun sceneLang(v: View, shows: List<io.github.pedrubik2000.kumapie.data.Show>): Map<String, String> {
            val epLang = shows.flatMap { s -> s.episodes.map { it.id to s.lang } }.toMap()
            return v.ranges.keys.mapNotNull { ep -> epLang[ep]?.let { ep to it } }.toMap()
        }

        fun homeTotal(lang: String, v: View, shows: List<io.github.pedrubik2000.kumapie.data.Show>, known: Int,
                      today: java.time.LocalDate = today()): io.github.pedrubik2000.kumapie.data.LangTotal {
            val (now, week, total) = totals(daysIn(lang, v, sceneLang(v, shows)), today)
            return io.github.pedrubik2000.kumapie.data.LangTotal(now, week, total, known)
        }

        /** The study day (it starts at 4 am, like the PC's and Anki's). */
        fun studyDay(): String = today().toString()

        /** [studyDay] as a date. */
        fun today(): java.time.LocalDate = LocalDateTime.now().minusHours(4).toLocalDate()

        /** Seconds today, in the last 7 days (today included) and in all of [days] (study day -> seconds). */
        fun totals(days: Map<String, Double>, today: java.time.LocalDate = today()): Triple<Double, Double, Double> =
            Triple(days[today.toString()] ?: 0.0, (0L..6L).sumOf { days[today.minusDays(it).toString()] ?: 0.0 }, days.values.sum())

        /** [stats] for [m] on study day [today]. */
        fun statsOf(m: View, shows: List<io.github.pedrubik2000.kumapie.data.Show>, today: java.time.LocalDate): io.github.pedrubik2000.kumapie.data.Stats {
            fun day(d: java.time.LocalDate) = m.watch[d.toString()] ?: 0.0
            var streak = 0
            var d = if (day(today) < 60) today.minusDays(1) else today // today not started: the streak counts to yesterday
            while (day(d) >= 60) { streak++; d = d.minusDays(1) }
            val top = m.lookups.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(10)
                .map { io.github.pedrubik2000.kumapie.data.TopLookup(it.key, it.value, m.gloss[it.key] ?: "") }
            val (now, week, total) = totals(m.watch, today)
            return io.github.pedrubik2000.kumapie.data.Stats(
                days = m.watch, today = now, week = week, total = total,
                streak = streak, scenesSeen = shows.sumOf { s -> s.episodes.sumOf { it.seen } }, scenesTotal = shows.sumOf { s -> s.episodes.sumOf { it.scenes } },
                lookups = m.lookups.values.sum(), wordsLookedUp = m.lookups.size, topLookups = top, markedKnown = m.marked.size,
                shows = shows.map { s -> io.github.pedrubik2000.kumapie.data.ShowProgress(s.title, s.episodes.sumOf { it.seen }, s.episodes.sumOf { it.scenes }) } // lines heard of lines: the PC's count
                    .sortedByDescending { it.seen },
            )
        }
    }
}
