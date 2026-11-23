package io.github.pedrubik2000.kumapie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import io.github.pedrubik2000.kumapie.lang.TitleDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@Stable
class Levels(private val queue: JitenQueue, private val results: State<Map<String, TitleDifficulty?>>) {
    fun of(kitsu: String?): TitleDifficulty? = kitsu?.let { results.value[it] ?: queue.cached(it) }

    fun ask(ids: List<String>, priority: Boolean) = queue.ask(ids, priority)
}

@Composable
internal fun rememberLevels(queue: JitenQueue?): Levels? {
    queue ?: return null
    val results = queue.results.collectAsStateWithLifecycle()
    return remember(queue, results) { Levels(queue, results) }
}

@Composable
internal fun AskLevels(levels: Levels?, rows: List<List<String>>, focus: HomeFocus) {
    levels ?: return
    LaunchedEffect(levels, rows) {
        androidx.compose.runtime.snapshotFlow { focus.row }.collectLatest { at ->
            withContext(Dispatchers.Default) {
                rows.forEachIndexed { i, ids -> if (i != at) levels.ask(ids, false) }
                rows.getOrNull(at)?.let { levels.ask(it, true) }
            }
        }
    }
}
