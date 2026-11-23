package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

internal object MissingKeyLine {
    val focus = FocusRequester()
    var shown by mutableStateOf(false)
    var focused by mutableStateOf(false)
    var from: FocusRequester? = null
    var home: FocusRequester? = null

    fun up(from: FocusRequester): FocusRequester {
        if (!shown) return FocusRequester.Default
        this.from = from
        return focus
    }

    fun down(): Boolean {
        val targets = listOfNotNull(from, home)
        from = null
        return targets.any { r ->
            val moved = runCatching { r.requestFocus() }
            moved.isSuccess && (moved.getOrNull() as Any?) != false
        }
    }
}
