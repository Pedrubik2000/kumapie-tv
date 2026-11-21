package io.github.pedrubik2000.kumapie.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The word picker's OK on a six-button remote (Chromecast: ← → ↑ ↓ OK Back; core plan 5b way 3, Pedro 2026-11-21):
 * OK shows the meaning, hold OK is the next reading (the player replays the line for a word with one reading).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerKeysTest {
    private fun press(code: Int, held: Boolean): List<Action> {
        val got = ArrayList<Action>()
        val keys = KeyHandler({ KeyMap.PICKER }) { got += it }
        keys.handle(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0))
        if (held) keys.handle(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 1))
        keys.handle(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0))
        return got
    }

    @Test fun holdOkInThePickerIsTheNextReading() {
        assertEquals(listOf(Action.PICK_READING), press(KeyEvent.KEYCODE_DPAD_CENTER, held = true))
        assertEquals(listOf(Action.PICK_AUDIO), press(KeyEvent.KEYCODE_DPAD_CENTER, held = false))
        assertEquals(listOf(Action.PICK_MARK_KNOWN), press(KeyEvent.KEYCODE_DPAD_DOWN, held = true))
        // The pad and a remote with Play/Pause keep a button of their own for the line.
        assertEquals(Action.PICK_REPLAY_LINE, KeyMap.PICKER.short[KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE])
        assertEquals(Action.PICK_REPLAY_LINE, KeyMap.PICKER.short[KeyEvent.KEYCODE_BUTTON_X])
    }
}
