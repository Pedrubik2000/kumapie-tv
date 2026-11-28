package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

@Composable
fun BoxScope.TopNote(text: String?) {
    if (text != null) Text(text, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)
        .background(Color(0x99000000)).padding(12.dp, 6.dp))
}
