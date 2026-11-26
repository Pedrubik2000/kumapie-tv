package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.BuildConfig
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.AloneStream
import io.github.pedrubik2000.kumapie.lang.CrashCollector
import io.github.pedrubik2000.kumapie.lang.SubReports
import io.github.pedrubik2000.kumapie.player.SceneController

@Composable
internal fun SyncNudge(ctl: SceneController) {
    val prefs = ctl.settings.prefs
    var kept by remember { mutableStateOf(AloneStream.kept(prefs, ctl.episode.id)) }
    fun shift(by: Double) {
        AloneStream.shift(ctl, prefs, by)
        kept = AloneStream.kept(prefs, ctl.episode.id)
    }
    Text(tr("Subtitle timing") + " " + "%+.1f s".format(java.util.Locale.ROOT, kept), color = Colors.dim, fontSize = 18.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Button(onClick = { shift(-0.5) }, modifier = Modifier.weight(1f)) { Text("−0.5 s") }
        Button(onClick = { shift(0.5) }, modifier = Modifier.weight(1f)) { Text("+0.5 s") }
    }
    Button(onClick = { AloneStream.reset(ctl, prefs); kept = AloneStream.kept(prefs, ctl.episode.id) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Reset sync")) }
    val context = LocalContext.current
    var reporting by remember { mutableStateOf(false) }
    Button(onClick = { reporting = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("Report subtitle timing")) }
    if (reporting) {
        val report = remember { SubReports.of(ctl, prefs) }
        val env = remember { CrashCollector.env(context, "tv", BuildConfig.VERSION_NAME) }
        TvCrashDialog({ SubReports.send(context, report, env, io.github.pedrubik2000.kumapie.lang.CrashReports.QR_CAP) }, {}, { reporting = false },
            question = tr("Report the subtitle timing of this episode to your PC?"),
            detail = tr("Sends the title, episode, subtitle file, the shifts and the lines on screen, nothing else."))
    }
}
