package io.github.pedrubik2000.kumapie.data

import android.view.KeyEvent
import io.github.pedrubik2000.kumapie.player.Action
import io.github.pedrubik2000.kumapie.player.Binding
import io.github.pedrubik2000.kumapie.player.KeyContext
import io.github.pedrubik2000.kumapie.player.defaultBindings
import org.json.JSONObject

/** The TV's buttons for a context (kept in the shared settings file): what you set in Settings > Change buttons, else the defaults. */
fun Settings.keys(context: KeyContext): Map<Binding, Action> {
    val json = prefs.getString("keys_${context.name}", null) ?: return defaultBindings(context)
    return runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().associate { k ->
            val (kind, code) = k.split(":", limit = 2)
            Binding(code.toInt(), kind == "long") to Action.valueOf(o.getString(k))
        }.let { if (context == KeyContext.WATCH) upgradedWatchKeys(it) else it }
    }.getOrElse { defaultBindings(context) }
}

fun upgradedWatchKeys(saved: Map<Binding, Action>): Map<Binding, Action> {
    val down = Binding(KeyEvent.KEYCODE_DPAD_DOWN, false)
    val keys = saved.mapValues { (b, a) -> if (b == down && a == Action.CYCLE_SUBTITLES) Action.WORD_PICKER else a }
    if (keys[down] != Action.WORD_PICKER) return keys
    return keys.filterNot { (b, a) -> b.long && b.code in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && a == Action.WORD_PICKER }
}

fun Settings.setKeys(context: KeyContext, bindings: Map<Binding, Action>) {
    val o = JSONObject()
    bindings.forEach { (b, a) -> o.put((if (b.long) "long:" else "short:") + b.code, a.name) }
    prefs.edit().putString("keys_${context.name}", o.toString()).apply()
}

fun Settings.resetKeys(context: KeyContext) = prefs.edit().remove("keys_${context.name}").apply()
