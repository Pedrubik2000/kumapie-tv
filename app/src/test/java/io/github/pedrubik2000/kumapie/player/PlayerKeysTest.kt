package io.github.pedrubik2000.kumapie.player

import android.view.KeyEvent
import io.github.pedrubik2000.kumapie.data.upgradedWatchKeys
import io.github.pedrubik2000.kumapie.ui.forInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's keys on a six-button remote (Chromecast: ← → ↑ ↓ OK Back; core plan 5b way 3 and 13, Pedro 2026-11-21):
 * ↓ picks a word whether the line plays or has ended (the player has one map for both), hold ↓ opens the options
 * (subtitles are changed there); in the picker OK shows the meaning, hold OK is the next reading (the player replays
 * the line for a word with one reading).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerKeysTest {
    private val six = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)

    private fun press(code: Int, held: Boolean, map: KeyMap = KeyMap.PICKER): List<Action> {
        val got = ArrayList<Action>()
        val keys = KeyHandler({ map }) { got += it }
        keys.handle(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0))
        if (held) keys.handle(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 1))
        keys.handle(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0))
        return got
    }

    @Test fun holdOkInThePickerIsTheNextReading() {
        assertEquals(listOf(Action.PICK_READING), press(KeyEvent.KEYCODE_DPAD_CENTER, held = true))
        assertEquals(listOf(Action.PICK_AUDIO), press(KeyEvent.KEYCODE_DPAD_CENTER, held = false))
        assertEquals(listOf(Action.PICK_MARK_KNOWN), press(KeyEvent.KEYCODE_DPAD_DOWN, held = true))
        assertEquals(listOf(Action.PICK_DOWN), press(KeyEvent.KEYCODE_DPAD_DOWN, held = false))
        // The pad and a remote with Play/Pause keep a button of their own for the line.
        assertEquals(Action.PICK_REPLAY_LINE, KeyMap.PICKER.short[KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE])
        assertEquals(Action.PICK_REPLAY_LINE, KeyMap.PICKER.short[KeyEvent.KEYCODE_BUTTON_X])
    }

    @Test fun downPicksAWordAndHoldDownOpensTheOptions() {
        val watch = KeyMap()
        assertEquals(listOf(Action.WORD_PICKER), press(KeyEvent.KEYCODE_DPAD_DOWN, held = false, map = watch))
        assertEquals(listOf(Action.OPTIONS), press(KeyEvent.KEYCODE_DPAD_DOWN, held = true, map = watch))
        assertEquals(listOf(Action.PLAY_PAUSE), press(KeyEvent.KEYCODE_DPAD_CENTER, held = false, map = watch))
        // Hold OK no longer picks: OK acts at once, and holding it is still one play/pause.
        assertEquals(listOf(Action.PLAY_PAUSE), press(KeyEvent.KEYCODE_DPAD_CENTER, held = true, map = watch))
        // Subtitles have no remote key left but stay in the options and for whoever maps a key to them.
        assertTrue(Action.CYCLE_SUBTITLES !in watch.short.filterKeys { it in six }.values)
        assertEquals(Action.CYCLE_SUBTITLES, watch.short[KeyEvent.KEYCODE_BUTTON_L1])
    }

    @Test fun theHintRowNamesTheSixButtonGesture() {
        val watch = defaultBindings(KeyContext.WATCH)
        fun shown(action: Action) = forInput(watch.filterValues { it == action }.keys).first().label
        assertEquals("OK", shown(Action.PLAY_PAUSE))
        assertEquals("↓", shown(Action.WORD_PICKER))
        assertEquals("↑", shown(Action.REPLAY_LINE))
        assertEquals("hold ↓", shown(Action.OPTIONS))
        assertEquals("←", shown(Action.PREVIOUS_SCENE))
    }

    @Test fun everyActionHasASixButtonGestureOrIsInTheOptions() {
        val inOptions = setOf(Action.CYCLE_SUBTITLES, Action.TOGGLE_GERMAN, Action.TOGGLE_ENGLISH, Action.SLOW,
            Action.PAUSE_AT_SCENE_END, Action.HELP)
        // PICK_HEAR is OK on a word that isn't due; PICK_UNDO is hold ↑ right after a rating; PICK_REPLAY_LINE is hold OK
        // on a word with one reading (else Back, then ↑); PICK_CLOSE is Back.
        val inPicker = setOf(Action.PICK_HEAR, Action.PICK_UNDO, Action.PICK_REPLAY_LINE, Action.PICK_CLOSE)
        val reached = (defaultBindings(KeyContext.WATCH).entries + defaultBindings(KeyContext.PICKER).entries)
            .filter { it.key.code in six }.map { it.value }.toSet()
        assertEquals(emptySet<Action>(), Action.entries.toSet() - reached - inOptions - inPicker)
    }

    @Test fun aSavedMapGetsTheNewDownUnlessDownWasSetToSomethingElse() {
        val down = Binding(KeyEvent.KEYCODE_DPAD_DOWN, false)
        val holdOk = Binding(KeyEvent.KEYCODE_DPAD_CENTER, true)
        val ok = Binding(KeyEvent.KEYCODE_DPAD_CENTER, false)
        val old = mapOf(down to Action.CYCLE_SUBTITLES, holdOk to Action.WORD_PICKER, ok to Action.PLAY_PAUSE)
        val up = upgradedWatchKeys(old)
        assertEquals(Action.WORD_PICKER, up[down])
        assertEquals(null, up[holdOk])
        assertEquals(Action.PLAY_PAUSE, up[ok])
        val own = old + (down to Action.SLOW)
        assertEquals(own, upgradedWatchKeys(own))
    }
}
