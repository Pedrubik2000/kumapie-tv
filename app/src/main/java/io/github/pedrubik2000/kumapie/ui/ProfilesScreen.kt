package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Profile

/** "Who's watching?" (Netflix-like): one square per person; the last one chosen has the focus. */
@Composable
fun ProfilesScreen(api: Api, last: String, onPick: (Profile) -> Unit, onSettings: () -> Unit) {
    var people by remember { mutableStateOf<List<Profile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { api.people() }.onSuccess { people = it }.onFailure { error = it.message ?: it.toString() }
    }
    when {
        error != null -> ErrorBox(error!!, onRetry = { attempt++ }, onSettings = onSettings)
        people == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("…", color = Colors.dim) }
        else -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // Asked before a profile is chosen, so in both languages.
            Text("Who's watching? · ¿Quién está viendo?", fontSize = 40.sp, color = Colors.text)
            val focus = remember { FocusRequester() }
            Row(Modifier.padding(top = 48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                people!!.forEachIndexed { i, p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Card(onClick = { onPick(p) }, shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                            modifier = Modifier.size(160.dp).let { if (p.id == last || (i == 0 && people!!.none { it.id == last })) it.focusRequester(focus) else it }) {
                            Box(Modifier.fillMaxSize().background(COLORS[i % COLORS.size]), contentAlignment = Alignment.Center) {
                                Text(p.name.take(1), fontSize = 72.sp, color = Color.White)
                            }
                        }
                        Text(p.name, fontSize = 20.sp, color = Colors.text, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
            LaunchedEffect(people) { runCatching { focus.requestFocus() } }
        }
    }
}

private val COLORS = listOf(Color(0xFF3B6FD8), Color(0xFFD8453B), Color(0xFF3BA55D), Color(0xFFD89A3B), Color(0xFF8E44AD))
