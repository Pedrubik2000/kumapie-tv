package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Show

/** Scene-by-scene player: comes in the next step (plan step 4). */
@Composable
fun PlayerScreen(show: Show, episode: Episode) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${show.title} · ${episode.title}", fontSize = 28.sp, color = Colors.text)
        Text("The scene player comes in the next version.", color = Colors.dim)
    }
}
