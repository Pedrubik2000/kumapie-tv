package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
fun rememberNow(enabled: Boolean = true, everyMs: Long = 5_000, clock: () -> Long = System::currentTimeMillis): Long {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(enabled, everyMs) {
        if (!enabled) return@LaunchedEffect
        now = clock()
        while (true) { delay(everyMs); now = clock() }
    }
    return now
}