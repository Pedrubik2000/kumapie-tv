package io.github.pedrubik2000.kumapie.player

import android.view.KeyEvent

/** Everything a button can do in the player. */
enum class Action(val label: String) {
    PLAY_PAUSE("Play / pause"),
    NEXT_SCENE("Next scene"),
    PREVIOUS_SCENE("Previous scene"),
    REPLAY_LINE("Replay line"),
    REPLAY_SCENE("Replay scene"),
    CYCLE_SUBTITLES("Subtitles: none → German → both"),
    TOGGLE_GERMAN("German subtitles"),
    TOGGLE_ENGLISH("English subtitles"),
    SLOW("Speed 0.75x"),
    PAUSE_AT_SCENE_END("Pause at scene end"),
    OPTIONS("Options"),
    WORD_PICKER("Pick a word"),
}

/**
 * Which key does what. A key with a long-press action acts when it is released (short) or held (long);
 * other keys act at once. Back (remote Back, gamepad B) is left to the system: it closes the player.
 * The gamepad's D-pad arrives as the same DPAD keys as the remote's.
 */
class KeyMap(
    val short: Map<Int, Action> = DEFAULT_SHORT,
    val long: Map<Int, Action> = DEFAULT_LONG,
) {
    companion object {
        val DEFAULT_SHORT = mapOf(
            // remote
            KeyEvent.KEYCODE_DPAD_CENTER to Action.PLAY_PAUSE,
            KeyEvent.KEYCODE_ENTER to Action.PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_RIGHT to Action.NEXT_SCENE,
            KeyEvent.KEYCODE_DPAD_LEFT to Action.PREVIOUS_SCENE,
            KeyEvent.KEYCODE_DPAD_UP to Action.REPLAY_LINE,
            KeyEvent.KEYCODE_DPAD_DOWN to Action.CYCLE_SUBTITLES,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Action.PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY to Action.PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE to Action.PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to Action.NEXT_SCENE,
            KeyEvent.KEYCODE_MEDIA_NEXT to Action.NEXT_SCENE,
            KeyEvent.KEYCODE_MEDIA_REWIND to Action.REPLAY_SCENE,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to Action.PREVIOUS_SCENE,
            KeyEvent.KEYCODE_MENU to Action.OPTIONS,
            // gamepad
            KeyEvent.KEYCODE_BUTTON_A to Action.PLAY_PAUSE,
            KeyEvent.KEYCODE_BUTTON_X to Action.REPLAY_LINE,
            KeyEvent.KEYCODE_BUTTON_Y to Action.REPLAY_SCENE,
            KeyEvent.KEYCODE_BUTTON_L1 to Action.TOGGLE_GERMAN,
            KeyEvent.KEYCODE_BUTTON_R1 to Action.TOGGLE_ENGLISH,
            KeyEvent.KEYCODE_BUTTON_L2 to Action.SLOW,
            KeyEvent.KEYCODE_BUTTON_R2 to Action.PAUSE_AT_SCENE_END,
            KeyEvent.KEYCODE_BUTTON_START to Action.OPTIONS,
            KeyEvent.KEYCODE_BUTTON_SELECT to Action.OPTIONS,
        )
        val DEFAULT_LONG = mapOf(
            KeyEvent.KEYCODE_DPAD_CENTER to Action.WORD_PICKER,
            KeyEvent.KEYCODE_ENTER to Action.WORD_PICKER,
            KeyEvent.KEYCODE_BUTTON_A to Action.WORD_PICKER,
            KeyEvent.KEYCODE_DPAD_LEFT to Action.REPLAY_SCENE,
            KeyEvent.KEYCODE_DPAD_DOWN to Action.OPTIONS,
        )
    }
}

/** Turns raw key events into actions, with long presses. Returns true when the event was used. */
class KeyHandler(private val keys: KeyMap, private val onAction: (Action) -> Unit) {
    private val longFired = mutableSetOf<Int>()

    fun handle(event: KeyEvent): Boolean {
        val code = event.keyCode
        val short = keys.short[code]
        val long = keys.long[code]
        if (short == null && long == null) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (long == null) {
                    if (event.repeatCount == 0) onAction(short!!)
                } else if (event.repeatCount == 0) {
                    longFired -= code
                } else if (code !in longFired && (event.isLongPress || event.repeatCount >= 1)) {
                    longFired += code
                    onAction(long)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (long != null && code !in longFired) short?.let(onAction)
                longFired -= code
            }
        }
        return true
    }
}
