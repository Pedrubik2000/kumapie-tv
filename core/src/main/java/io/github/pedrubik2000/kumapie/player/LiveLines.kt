package io.github.pedrubik2000.kumapie.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * A Discover stream's Japanese lines while its episode is still being made (SubMiner's way: one line at a time, as it
 * plays, nothing prepared): the file's own Japanese text track as ExoPlayer reads it, else a Jimaku file's lines
 * ([jimaku]: [[start, end, text]] from the PC) shown by the player's clock, [offset] seconds moved (it may be timed for
 * another release). [line] is the one on screen now. [stop] before the player goes on with the episode's scenes.
 */
class LiveLines(private val player: ExoPlayer, private val jimaku: suspend () -> List<Triple<Double, Double, String>>) : Player.Listener {
    var line by mutableStateOf<String?>(null)
        private set

    /** "file" (its own track), "jimaku", "" (not known yet / none). */
    var source by mutableStateOf("")
        private set
    var offset by mutableDoubleStateOf(0.0)
    private var needJimaku = false

    init {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage("ja").build()
        player.addListener(this)
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (tracks.isEmpty || source.isNotEmpty() || needJimaku) return
        val ja = tracks.groups.any { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).language.orEmpty().startsWith("ja") }
        }
        if (ja) source = "file" else {
            // No Japanese track: no other language's lines either (an English default track would be picked).
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            needJimaku = true
        }
    }

    override fun onCues(cueGroup: CueGroup) {
        // Netflix's Japanese track is closed captions: （モモ）, （男子生徒の話し声）, ♪ go, as in a Jimaku file. Line by line,
        // so a two-line cue keeps its break.
        if (source == "file") line = cueGroup.cues.flatMap { it.text?.toString().orEmpty().lines() }
            .map(io.github.pedrubik2000.kumapie.data.Jimaku::spoken).filter { it.isNotEmpty() }.joinToString("\n").ifBlank { null }
    }

    /** Jimaku's lines by the clock, once the file turned out to have no Japanese track; runs until cancelled. */
    suspend fun run() {
        while (!needJimaku) delay(200)
        val got = runCatching { jimaku() }.getOrDefault(emptyList())
        if (got.isEmpty()) return
        source = "jimaku"
        while (true) {
            val t = player.currentPosition / 1000.0 - offset
            line = got.lastOrNull { it.first <= t }?.takeIf { t < it.second }?.third
            delay(100)
        }
    }

    fun stop() {
        player.removeListener(this)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        line = null
    }
}
