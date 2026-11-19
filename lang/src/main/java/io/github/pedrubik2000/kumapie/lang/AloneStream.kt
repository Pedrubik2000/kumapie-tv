package io.github.pedrubik2000.kumapie.lang

import android.content.SharedPreferences
import androidx.media3.exoplayer.ExoPlayer
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Jimaku
import io.github.pedrubik2000.kumapie.player.LiveLines
import kotlinx.coroutines.delay

/**
 * An addon's stream without a PC, the same on the TV (StreamActivity.Alone) and the phone/tablet (WatchScreen): its
 * Japanese lines live ([live]: the file's own track, else Jimaku's file with the user's key), the episode made on the
 * device as soon as Jimaku's lines are there ([build]: words at once), the fit to the file's other subtitle track
 * landing later ([fitLanded]: the scenes' lines move by it), the shift ([offKey]) and the place kept on this device.
 * Each app keeps only its screen.
 */
class AloneStream(
    private val prefs: SharedPreferences, private val addons: Addons?, player: ExoPlayer,
    val id: String, val type: String, val show: String, val title: String, val video: String, file: String,
    onError: (String?) -> Unit,
) {
    private val t0 = android.os.SystemClock.elapsedRealtime()

    val live = LiveLines(player, jimaku = {
        runCatching { Jimaku.lines(prefs.getString("jimaku_key", "").orEmpty(), show, null, episodeNumber(id), file) }
            .onFailure { onError(it.message) }.getOrDefault(emptyList()).map { Triple(it.start, it.end, it.text) }
        // (empty: [LiveLines.noLines] says so once the file turned out to have no Japanese track either)
    }, others = { skip ->
        Jimaku.lines(prefs.getString("jimaku_key", "").orEmpty(), show, null, episodeNumber(id), file, skip).map { Triple(it.start, it.end, it.text) }
    }, switched = { l -> Jimaku.keep(show, null, episodeNumber(id), file, l.map { Jimaku.Line(it.first, it.second, it.third) }) }).also {
        it.offset = prefs.getFloat(offKey(id), 0f).toDouble()
        // A saved shift is a starting point: this play's fit replaces it (false fits were saved before 2026-11-19: Eden of
        // the East 2 kept 2.8 s, the true one being -5.6 s); a shift nudged by hand now wins.
        it.guess = it.offset
    }

    /** The offset the scenes were made with; null until [build] made them. */
    var built: Double? = null
        private set

    /**
     * The episode from Jimaku's lines (call once [LiveLines.lines] is filled), English under them from the user's
     * subtitle addon if there is one. While Sudachi's dictionary downloads (the first time) it waits, [onWait] each
     * round. Throws what the builder throws. After it the lines are the scenes': [LiveLines] goes on only to fit.
     */
    suspend fun build(context: android.content.Context, duration: Double, position: () -> Double, onWait: () -> Unit): EpisodeDetail {
        val tLines = android.os.SystemClock.elapsedRealtime()
        val english = runCatching { addons?.english(type, id, live.lines.map { it.first + live.offset }) }.getOrNull().orEmpty()
        val tEnglish = android.os.SystemClock.elapsedRealtime()
        var ep: EpisodeDetail? = null
        while (ep == null) {
            ep = EpisodeBuilder.fromLines(context, "alone:$id", show, title, video, duration, live.lines, live.offset, position(), english)
            if (ep == null) { onWait(); delay(5_000) }
        }
        built = live.offset
        saveOffset() // the word player adds its hand shifts to it
        live.stopUnlessFitting() // else it listens to the file's track until the fit lands
        // tvbench reads this line ("words ready").
        android.util.Log.i("kumapie", "words ready: lines ${tLines - t0} ms after open, english ${tEnglish - tLines}, " +
            "build ${android.os.SystemClock.elapsedRealtime() - tEnglish} (${live.source}, ${live.lines.size} lines, ${if (live.canFit) "fit later" else "no track to fit"})")
        return ep.copy(video = video, resume = position())
    }

    /** The late fit, once it landed after [build]: the scenes' lines move by [shift] (a saved hand shift stays). True: done. */
    fun fitLanded(shift: (Double) -> Unit): Boolean {
        val by = lateShift(built, live.offset, live.fitted) ?: return false
        if (by != 0.0) shift(by)
        android.util.Log.i("kumapie", "late fit: ${live.fitted} s from ${live.refCount} lines, lines moved $by s")
        built = live.offset
        saveOffset()
        live.stop()
        return true
    }

    private fun saveOffset() = prefs.edit().putFloat(offKey(id), live.offset.toFloat()).apply()

    /** Where it plays now, for next time and Home's Continue watching (near the end: from the start). */
    fun savePlace(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val at = place(positionMs / 1000.0, durationMs / 1000.0)
        addons?.played(id, at, durationMs / 1000.0) ?: prefs.edit().putFloat("alone_pos_$id", at.toFloat()).apply()
    }

    /** The screen goes: a shift nudged before the words came is kept (after, the word player keeps its own). */
    fun leave() { if (built == null) saveOffset() }

    companion object {
        /** Where [id] was left on this device (seconds); null: from the start. Read before the player is made. */
        fun resume(prefs: SharedPreferences, id: String): Double? = prefs.getFloat("alone_pos_$id", 0f).toDouble().takeIf { it > 5 }

        /** The device's shift for a no-PC episode's lines (the word player's hand shifts add to it). */
        fun offKey(id: String) = "alone_off_" + id.removePrefix("alone:")

        fun episodeNumber(id: String) = id.substringAfterLast(':').toIntOrNull()

        /** Left within 2 minutes of the end: from the start next time. */
        fun place(at: Double, length: Double) = if (at > length - 120) 0.0 else at

        /** How far the built scenes' lines move when the fit lands; null: nothing to move yet. */
        fun lateShift(built: Double?, offset: Double, fitted: Double?): Double? =
            if (built == null || fitted == null) null else offset - built
    }
}
