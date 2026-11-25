package io.github.pedrubik2000.kumapie.lang

import android.content.SharedPreferences
import androidx.media3.exoplayer.ExoPlayer
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Jimaku
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.player.LiveLines
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class AloneStream(
    private val prefs: SharedPreferences, private val addons: Addons?, private val player: ExoPlayer,
    val id: String, val type: String, val show: String, val title: String, val video: String, private val file: String,
    onError: (String?) -> Unit,
) {
    private val t0 = android.os.SystemClock.elapsedRealtime()
    private val sync = io.github.pedrubik2000.kumapie.data.AniListSync(prefs)

    val live = LiveLines(player, jimaku = {
        runCatching { Jimaku.lines(prefs.getString("jimaku_key", "").orEmpty(), show, null, episodeNumber(id), file, duration = ::duration) }
            .onFailure { onError(it.message) }.getOrDefault(emptyList()).map { Triple(it.start, it.end, it.text) }
    }, others = { skip ->
        Jimaku.lines(prefs.getString("jimaku_key", "").orEmpty(), show, null, episodeNumber(id), file, skip).map { Triple(it.start, it.end, it.text) }
    }, switched = { l -> Jimaku.keep(show, null, episodeNumber(id), file, l.map { Jimaku.Line(it.first, it.second, it.third) }, fitted = true) }).also {
        prefs.edit().putString(fileKey(id), file).apply()
        it.offset = prefs.getFloat(offKey(id, file), 0f).toDouble()
        it.guess = it.offset
    }

    private var lastMs = 0L
    private var lastDurationMs = 0L

    var built: Double? = null
        private set

    private val englishFiles = addons?.let { a ->
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { runCatching { a.englishFiles(type, id) }.getOrDefault(emptyList()) }
    }

    suspend fun build(context: android.content.Context, languages: Languages, duration: Double, position: () -> Double, onWait: () -> Unit): EpisodeDetail {
        val tLines = android.os.SystemClock.elapsedRealtime()
        val english = runCatching { englishFiles?.await()?.let { Addons.fitEnglish(it, live.lines.map { l -> l.first + live.offset }, duration) } }.getOrNull().orEmpty()
        addonPlaced = english.isNotEmpty()
        val tEnglish = android.os.SystemClock.elapsedRealtime()
        var ep: EpisodeDetail? = null
        while (ep == null) {
            ep = EpisodeBuilder.fromLines(context, "alone:$id", show, title, video, duration, live.lines, live.offset, position(), english)
            if (ep == null) { onWait(); delay(5_000) }
        }
        val built0 = ep
        val tBuilt = android.os.SystemClock.elapsedRealtime()
        ep = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            languages.of(built0.lang).known.run { refreshDue(); apply(built0) }
        }
        built = live.offset
        saveOffset()
        live.stopUnlessFitting()
        android.util.Log.i("kumapie", "words ready: lines ${tLines - t0} ms after open, english ${tEnglish - tLines}, " +
            "build ${android.os.SystemClock.elapsedRealtime() - tEnglish} (builder ${tBuilt - tEnglish}, colours ${android.os.SystemClock.elapsedRealtime() - tBuilt}; ${live.source}, ${live.lines.size} lines, ${if (live.canFit) "fit later" else "no track to fit"})")
        return ep.copy(video = video, resume = position())
    }

    fun fitLanded(shift: (Double) -> Unit): Boolean {
        if (fitApplied) return true
        val by = lateShift(built, live.offset, live.fitted) ?: return false
        if (by != 0.0) shift(by)
        android.util.Log.i("kumapie", "late fit: ${live.fitted} s from ${live.refCount} lines, lines moved $by s")
        built = live.offset
        saveOffset()
        fitApplied = true
        return true
    }

    fun englishLanded(ctl: io.github.pedrubik2000.kumapie.player.SceneController) {
        if (!useEmbedded(addonPlaced, live.english)) return
        val placed = withEnglish(ctl.scenes, live.english, live.englishOpen)
        if (placed.map { it.english } != ctl.scenes.map { it.english }) ctl.rejoin(placed, emptySet())
    }

    private var addonPlaced = false

    private var fitApplied = false
    private var moved = 1

    fun piecesLanded(shift: (Double, Double) -> Unit) {
        if (!fitApplied) return
        moved = moves(live.pieces, moved, shift)
    }

    private fun saveOffset() = prefs.edit().putFloat(offKey(id, file), live.offset.toFloat()).apply()

    private suspend fun duration(): Double? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        kotlinx.coroutines.withTimeoutOrNull(DURATION_WAIT) {
            while (player.duration == androidx.media3.common.C.TIME_UNSET) delay(50)
            player.duration / 1000.0
        }
    }

    fun savePlace(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        lastMs = positionMs
        lastDurationMs = durationMs
        sync.watchingAt(id, type, positionMs, durationMs)
        val at = place(positionMs / 1000.0, durationMs / 1000.0)
        addons?.played(id, at, durationMs / 1000.0) ?: prefs.edit().putFloat("alone_pos_$id", at.toFloat()).apply()
        prefs.edit().putString(fileKey(id), file).apply()
    }

    suspend fun keepPlace() {
        while (true) { delay(PLACE_EVERY); savePlace(player.currentPosition, player.duration) }
    }

    fun leave() {
        savePlace(player.currentPosition, player.duration)
        addons?.left(id, type, lastMs, lastDurationMs)
        if (built == null) saveOffset()
    }

    companion object {
        const val PLACE_EVERY = 5_000L

        const val DURATION_WAIT = 3_000L

        fun offKey(id: String, file: String = "") = "alone_off_" + id.removePrefix("alone:") + if (file.isEmpty()) "" else "|$file"

        fun fitNote(live: LiveLines): String? = if (live.source == "jimaku" && (live.fitted == null || live.unfitted)) tr("not fitted to this video") else null

        fun moves(pieces: List<Pair<Double, Double?>>, done: Int, shift: (Double, Double) -> Unit): Int {
            val p = pieces.mapNotNull { (from, o) -> o?.let { from to it } }
            for (k in done until p.size) shift(p[k].second - p[k - 1].second, p[k].first + p[k - 1].second - p[k].second)
            return maxOf(done, p.size)
        }

        fun shift(ctl: io.github.pedrubik2000.kumapie.player.SceneController, prefs: SharedPreferences, by: Double) {
            ctl.shift(by)
            prefs.edit().putFloat(keptKey(prefs, ctl.episode.id), kept(prefs, ctl.episode.id).toFloat() + by.toFloat()).apply()
        }

        private fun keptKey(prefs: SharedPreferences, id: String) = offKey(id, prefs.getString(fileKey(id), "").orEmpty())

        fun kept(prefs: SharedPreferences, id: String): Double = prefs.getFloat(keptKey(prefs, id), 0f).toDouble()

        fun fileKey(id: String) = "alone_file_" + io.github.pedrubik2000.kumapie.data.Addons.titleOf(id.removePrefix("alone:"))

        fun prefetch(context: android.content.Context, prefs: SharedPreferences, going: List<Pair<io.github.pedrubik2000.kumapie.data.Show, io.github.pedrubik2000.kumapie.data.Episode>>, max: Int = 5) {
            val key = prefs.getString("jimaku_key", "").orEmpty()
            val model = JapaneseModel(context)
            if (key.isBlank() || !model.splitterReady) return
            for ((show, e) in going.take(max)) runCatching {
                val t = android.os.SystemClock.elapsedRealtime()
                val lines = kotlinx.coroutines.runBlocking { Jimaku.lines(key, show.title, null, episodeNumber(e.id), prefs.getString(fileKey(e.id), "").orEmpty()) }
                if (lines.isNotEmpty()) model.words(lines.map { it.text }, background = true)
                android.util.Log.i("kumapie", "prefetched ${show.title} ${e.id}: ${lines.size} lines in ${android.os.SystemClock.elapsedRealtime() - t} ms")
            }
        }

        fun useEmbedded(addonPlaced: Boolean, embedded: List<Jimaku.Line>) = !addonPlaced && embedded.isNotEmpty()

        fun withEnglish(scenes: List<io.github.pedrubik2000.kumapie.data.Scene>, cues: List<Jimaku.Line>, open: Set<Int> = emptySet()): List<io.github.pedrubik2000.kumapie.data.Scene> {
            val spans = scenes.map { it.start to it.end }
            val by = cues.withIndex().groupBy { (i, c) -> Jimaku.owner(spans, c.start, if (i in open) c.start + 0.001 else c.end) }
            return scenes.mapIndexed { i, s ->
                s.copy(english = by[i].orEmpty().map { it.value }.sortedBy { it.start }.map { io.github.pedrubik2000.kumapie.data.Line(it.start, it.end, it.text) })
            }
        }

        fun episodeNumber(id: String) = id.substringAfterLast(':').toIntOrNull()

        fun place(at: Double, length: Double) = if (at > length - 120) 0.0 else at

        fun lateShift(built: Double?, offset: Double, fitted: Double?): Double? =
            if (built == null || fitted == null) null else offset - built
    }
}
