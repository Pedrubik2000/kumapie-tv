package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private val LocalStepHeader = compositionLocalOf<MutableState<(@Composable () -> Unit)?>?> { null }

@Composable
internal fun TvStepPage(modifier: Modifier, content: @Composable () -> Unit) {
    val header = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    Column(modifier) {
        StepHeaderHost(header)
        Column(Modifier.weight(1f).padding(bottom = rememberHintRoom() + 8.dp).fadeBottom(28.dp).verticalScroll(rememberScrollState()).padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CompositionLocalProvider(LocalStepHeader provides header) { content() }
        }
    }
}

@Composable
private fun StepHeaderHost(header: MutableState<(@Composable () -> Unit)?>) {
    header.value?.let { Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
}

@Composable
internal fun TvStepHeader(caption: String?, title: String, sub: String = "") {
    val slot = LocalStepHeader.current
    val body = remember(caption, title, sub) {
        @Composable {
            if (caption != null) TvCaption(caption)
            Text(title, color = Colors.text, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotEmpty()) Text(sub, color = Colors.dim, fontSize = 16.sp)
        }
    }
    if (slot == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { body() } else {
        SideEffect { if (slot.value !== body) slot.value = body }
        DisposableEffect(slot) { onDispose { slot.value = null } }
    }
}
