package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.DeviceFile
import io.github.pedrubik2000.kumapie.lang.DeviceFiles
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun DeviceFileSection(library: Library) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { DeviceFiles(context, library.languages.all) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Pair<File, DeviceFile.Plan>?>(null) }
    fun run(what: String, work: suspend () -> String) {
        busy = true
        message = what
        scope.launch {
            message = runCatching { work() }.getOrElse { tr("Couldn't do it: %s", it.message) }
            busy = false
        }
    }
    Text(tr("Your data"), color = Colors.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Text(tr("Everything kumapie keeps here in one file, for another device to import: words, cards, reviews, progress."), color = Colors.dim, fontSize = 14.sp)
    OutlinedButton(enabled = !busy, onClick = { run(tr("Exporting…")) { withContext(Dispatchers.IO) { files.export() }.line() } }) { Text(tr("Export everything")) }
    OutlinedButton(enabled = !busy, onClick = {
        run(tr("Reading…")) {
            withContext(Dispatchers.IO) {
                val f = files.incoming() ?: return@withContext tr("No export file found: put one in %s", context.getExternalFilesDir(null)?.path)
                asking = f to files.plan(f)
                ""
            }
        }
    }) { Text(tr("Import from another device")) }
    if (message.isNotEmpty()) Text(message, color = Colors.dim, fontSize = 14.sp)
    asking?.let { (f, plan) ->
        AlertDialog(onDismissRequest = { asking = null },
            title = { Text(tr("Import from %1\$s (%2\$s)?", plan.from, plan.person)) },
            text = { PlanTable(plan) },
            confirmButton = {
                TextButton(enabled = plan.changes, onClick = {
                    asking = null
                    run(tr("Importing…")) { tr("Imported. Your stores as they were: %s", withContext(Dispatchers.IO) { files.apply(plan, f) }.path) }
                }) { Text(tr("Import")) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(tr("Cancel")) } })
    }
}

@Composable
private fun PlanTable(plan: DeviceFile.Plan) = Column {
    @Composable fun row(a: String, b: String, c: String, d: String) = Row {
        Text(a, fontSize = 14.sp, modifier = Modifier.width(130.dp))
        listOf(b, c, d).forEach { Text(it, fontSize = 14.sp, modifier = Modifier.width(64.dp)) }
    }
    row("", tr("would add"), tr("would change"), tr("would keep"))
    plan.counts.forEach { row(it.what, "${it.add}", "${it.change}", "${it.keep}") }
    Text(if (plan.changes) tr("A dated copy of this device's stores is made first.") else tr("Nothing new in it."), fontSize = 14.sp, color = Colors.dim)
}
