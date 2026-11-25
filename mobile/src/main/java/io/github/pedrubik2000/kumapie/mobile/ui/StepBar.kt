package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

internal val LocalStepBar = compositionLocalOf<MutableState<(@Composable () -> Unit)?>?> { null }

@Composable
internal fun StepBar(content: @Composable () -> Unit) {
    val slot = LocalStepBar.current
    if (slot == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() } else {
        SideEffect { slot.value = content }
        DisposableEffect(slot) { onDispose { slot.value = null } }
    }
}
