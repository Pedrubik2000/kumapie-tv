package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.i18n.tr

/** Every new-episode job: the running one with its stage and progress, the queue in order, then the finished ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val jobs by remember { ProcessWorker.jobs(context) }.collectAsState(initial = emptyList())
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Downloads")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = {
                if (jobs.any { it.state == "done" || it.state == "failed" }) TextButton(onClick = { ProcessWorker.clearFinished(context) }) { Text(tr("Clear finished")) }
                if (jobs.any { it.state == "running" || it.state == "waiting" }) TextButton(onClick = { ProcessWorker.stopAll(context) }) { Text(tr("Stop all")) }
            },
        )
    }) { padding ->
        if (jobs.isEmpty()) {
            Text(tr("Nothing downloading. Add episodes with + on the home screen."), color = Colors.dim,
                modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val waiting = jobs.count { it.state == "waiting" }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { if (waiting > 0) Text(tr("%d waiting", waiting), color = Colors.dim, fontSize = 14.sp) }
            items(jobs, key = { it.id }) { job ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row {
                        Text(job.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Text(job.stage, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = when (job.state) {
                        "failed" -> Colors.unknown
                        "done" -> Colors.levelZero
                        else -> Colors.dim
                    })
                    if (job.state == "running") LinearProgressIndicator(progress = { job.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}
