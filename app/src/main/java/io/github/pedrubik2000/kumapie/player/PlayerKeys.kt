package io.github.pedrubik2000.kumapie.player

import android.view.KeyEvent

/** Where a button press counts: while watching, or inside the word picker (each has its own buttons). */
enum class KeyContext(val label: String) { WATCH("While watching"), PICKER("In the word picker") }

/** Everything a button can do in the player. */
enum class Action(val label: String, val context: KeyContext = KeyContext.WATCH) {
    PLAY_PAUSE("Play / pause (at a scene end: next scene)"),
    NEXT_SCENE("Next scene"),
    PREVIOUS_SCENE("Previous scene"),
    REPLAY_LINE("Replay line"),
    REPLAY_SCENE("Replay scene"),
    CYCLE_SUBTITLES("Subtitles: hidden → blurred → German → both (at a scene end: pick a word)"),
    TOGGLE_GERMAN("German subtitles"),
    TOGGLE_ENGLISH("English subtitles"),
    SLOW("Speed 0.75x"),
    PAUSE_AT_SCENE_END("Pause at scene end / play on"),
    OPTIONS("Options"),
    HELP("Help (this list of buttons)"),
    WORD_PICKER("Pick a word"),
    // inside the word picker
    PICK_LEFT("Previous word", KeyContext.PICKER),
    PICK_RIGHT("Next word", KeyContext.PICKER),
    PICK_UP("Line above / into the definition", KeyContext.PICKER),
    PICK_DOWN("Line below / out of the definition", KeyContext.PICKER),
    PICK_AUDIO("Show the meaning; then hear the word (twice: the definition)", KeyContext.PICKER),
    PICK_REPLAY_LINE("Replay the word's line", KeyContext.PICKER),
    PICK_MARK_KNOWN("Mark known / undo", KeyContext.PICKER),
    PICK_CLOSE("Close the picker", KeyContext.PICKER),
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
            KeyEvent.KEYCODE_BUTTON_SELECT to Action.WORD_PICKER,
        )
        val DEFAULT_LONG = mapOf(
            KeyEvent.KEYCODE_DPAD_CENTER to Action.WORD_PICKER,
            KeyEvent.KEYCODE_ENTER to Action.WORD_PICKER,
            KeyEvent.KEYCODE_BUTTON_A to Action.WORD_PICKER,
            KeyEvent.KEYCODE_DPAD_LEFT to Action.REPLAY_SCENE,
            KeyEvent.KEYCODE_DPAD_DOWN to Action.OPTIONS,
        )

        /** While the word picker is open. Back closes it (handled here so it doesn't close the player). */
        val PICKER = KeyMap(
            short = mapOf(
                KeyEvent.KEYCODE_DPAD_LEFT to Action.PICK_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT to Action.PICK_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP to Action.PICK_UP,
                KeyEvent.KEYCODE_DPAD_DOWN to Action.PICK_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER to Action.PICK_AUDIO,
                KeyEvent.KEYCODE_ENTER to Action.PICK_AUDIO,
                KeyEvent.KEYCODE_BUTTON_A to Action.PICK_AUDIO,
                KeyEvent.KEYCODE_BUTTON_X to Action.PICK_REPLAY_LINE,
                KeyEvent.KEYCODE_BUTTON_Y to Action.PICK_MARK_KNOWN,
                KeyEvent.KEYCODE_BUTTON_SELECT to Action.PICK_CLOSE,
                KeyEvent.KEYCODE_BUTTON_B to Action.PICK_CLOSE,
                KeyEvent.KEYCODE_BACK to Action.PICK_CLOSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Action.PICK_REPLAY_LINE,
            ),
            long = mapOf(
                KeyEvent.KEYCODE_DPAD_CENTER to Action.PICK_REPLAY_LINE,
                KeyEvent.KEYCODE_ENTER to Action.PICK_REPLAY_LINE,
                KeyEvent.KEYCODE_BUTTON_A to Action.PICK_REPLAY_LINE,
                KeyEvent.KEYCODE_DPAD_DOWN to Action.PICK_MARK_KNOWN,
            ),
        )
    }
}

/** Turns raw key events into actions, with long presses. Returns true when the event was used. */
class KeyHandler(private val keyMap: () -> KeyMap, private val onAction: (Action) -> Unit) {
    private val longFired = mutableSetOf<Int>()
    // Keys whose press was ours: their release is ours too, even if the map changed in between
    // (Back closes the picker on press; its release must not then close the player).
    private val pressed = mutableSetOf<Int>()

    fun handle(event: KeyEvent): Boolean {
        val keys = keyMap()
        val code = event.keyCode
        val short = keys.short[code]
        val long = keys.long[code]
        if (short == null && long == null) {
            if (event.action == KeyEvent.ACTION_UP && pressed.remove(code)) return true
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                pressed += code
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
                if (long != null && code !in longFired && code in pressed) short?.let(onAction)
                longFired -= code
                pressed -= code
            }
        }
        return true
    }
}

// ------------------------------------------------------------ remappable buttons (Settings > Change buttons)

/** One button: its key code, pressed briefly or held. */
data class Binding(val code: Int, val long: Boolean) {
    val label: String get() = (if (long) "hold " else "") + keyLabel(code)
}

/** The default buttons of a context, as bindings. */
fun defaultBindings(context: KeyContext): Map<Binding, Action> {
    val map = if (context == KeyContext.WATCH) KeyMap() else KeyMap.PICKER
    return (map.short.mapKeys { Binding(it.key, false) } + map.long.mapKeys { Binding(it.key, true) })
        .filterKeys { !isReservedKey(it.code) }
}

/** Bindings -> a KeyMap. Back always closes the picker (it can't be remapped, so there's always a way out). */
fun keyMapOf(context: KeyContext, bindings: Map<Binding, Action>): KeyMap {
    val short = bindings.filterKeys { !it.long }.mapKeys { it.key.code }.toMutableMap()
    val long = bindings.filterKeys { it.long }.mapKeys { it.key.code }
    if (context == KeyContext.PICKER) short[KeyEvent.KEYCODE_BACK] = Action.PICK_CLOSE
    return KeyMap(short, long)
}

/** Keys that can't be given to an action: Back and Home belong to the system. */
fun isReservedKey(code: Int): Boolean = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME

/** A short, readable name for a key: "OK", "→", "A", "L2", "Play/Pause". */
fun keyLabel(code: Int): String = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "OK"
    KeyEvent.KEYCODE_DPAD_UP -> "↑"
    KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
    KeyEvent.KEYCODE_DPAD_LEFT -> "←"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
    KeyEvent.KEYCODE_BUTTON_A -> "A"
    KeyEvent.KEYCODE_BUTTON_B -> "B"
    KeyEvent.KEYCODE_BUTTON_X -> "X"
    KeyEvent.KEYCODE_BUTTON_Y -> "Y"
    KeyEvent.KEYCODE_BUTTON_L1 -> "L"
    KeyEvent.KEYCODE_BUTTON_R1 -> "R"
    KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
    KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
    KeyEvent.KEYCODE_BUTTON_START -> "Start (+)"
    KeyEvent.KEYCODE_BUTTON_SELECT -> "Select (−)"
    KeyEvent.KEYCODE_BUTTON_MODE -> "Home (gamepad)"
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
    KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
    KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Fast-forward"
    KeyEvent.KEYCODE_MEDIA_REWIND -> "Rewind"
    KeyEvent.KEYCODE_MEDIA_NEXT -> "Next"
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous"
    KeyEvent.KEYCODE_MENU -> "Menu"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replace('_', ' ')
}
