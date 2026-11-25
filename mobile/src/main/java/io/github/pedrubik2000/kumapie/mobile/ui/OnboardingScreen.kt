package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.Onboarding
import io.github.pedrubik2000.kumapie.data.Onboarding.State
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Startup
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.LivePairing
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors

private val SAVES = setOf(Onboarding.ANILIST, Onboarding.VIDEOS, Onboarding.COLOURING)

private fun details(settings: Settings, pairing: String): Map<String, String> = buildMap {
    put(Onboarding.LANGUAGE, when (settings.menuLanguage) { "en" -> "English"; "es" -> "Español"; else -> tr("Automatic") })
    pairing.ifEmpty { MainDeviceFlow.hub(settings.prefs) }.takeIf { it.isNotEmpty() }?.let { put(Onboarding.MAIN, it) }
}

@Composable
fun OnboardingScreen(library: Library, onDone: () -> Unit) {
    val settings = library.settings
    val prefs = settings.prefs
    val context = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val ja = "ja" in settings.learning()
    val done = remember(rev) { Onboarding.done(prefs) }
    val copied = remember(rev) { Onboarding.copied(prefs) }
    val current = opened ?: Onboarding.next(done, copied, ja) ?: Onboarding.DOWNLOADS
    val flow = remember { MainDeviceFlow(prefs, LivePairing(context.applicationContext)) }
    val phase by flow.phase.collectAsState()
    val pairing = ((phase as? MainDeviceFlow.Phase.Waiting)?.hub ?: (phase as? MainDeviceFlow.Phase.Ready)?.hub)?.let { it.name.ifEmpty { it.address } }.orEmpty()
    val items = remember(rev, current, pairing) { Onboarding.list(done, copied, ja, details(settings, pairing), current) }
    val counted = Onboarding.counted(ja)
    val caption = tr("Step %1\$d of %2\$d", counted.indexOf(current) + 1, counted.size) + " · " +
        if (current == Onboarding.MAIN) tr("Optional · saves %d steps", counted.count { it in SAVES }) else if (items.first { it.step == current }.state == State.OPTIONAL) tr("Optional") else tr("Needed")
    val previous = items.map { it.step }.let { s -> s.getOrNull(s.indexOf(current) - 1) }
    fun go(step: String?) { opened = step; rev++ }
    fun complete(step: String) { Onboarding.markDone(prefs, step); go(null) }
    val from = MainDeviceFlow.hub(prefs).takeIf { it.isNotEmpty() }
    if (current == Onboarding.COLOURING) {
        WordColouringScreen(library, true, { go(previous) }, counted.indexOf(current) + 1, counted.size, { complete(current) }, { complete(current) })
        return
    }
    Scaffold { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val body: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (current) {
                        Onboarding.LANGUAGE -> LanguagePage(settings) { complete(current) }
                        Onboarding.MAIN -> MainDeviceStep(flow, caption, Onboarding.MAIN !in done) { go(null) }
                        Onboarding.ANILIST -> AniListPage(settings, caption, from.takeIf { Onboarding.ANILIST in copied }) { complete(current) }
                        Onboarding.VIDEOS -> VideosPage(settings, caption, from.takeIf { Onboarding.VIDEOS in copied }) { complete(current) }
                        else -> DownloadsPage(library, caption) {
                            MainDeviceFlow.clear(prefs)
                            Startup.onboard(prefs)
                            onDone()
                        }
                    }
                    if (previous != null) Text(tr("Back"), color = Glass.dim, fontSize = 14.sp, modifier = Modifier.clickable { go(previous) }.padding(vertical = 14.dp))
                }
            }
            val open = { step: String -> items.first { it.step == step }.state.let { if (it == State.DONE || it == State.COPIED) go(step) } }
            if (wide) Row(Modifier.fillMaxSize()) {
                SideList(items, current, open)
                body()
            } else Column(Modifier.fillMaxSize()) {
                Segments(items, current, open)
                body()
            }
        }
    }
}

@Composable
private fun SideList(items: List<Onboarding.Item>, current: String, open: (String) -> Unit) {
    Column(Modifier.width(300.dp).fillMaxHeight().background(Glass.solid).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Setup"), color = Colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
        items.forEach { i ->
            val on = i.step == current
            val ink = if (on) Color.Black else Color.White
            Column(Modifier.fillMaxWidth().clickable { open(i.step) }.then(if (on) Modifier.background(Color.White, RoundedCornerShape(14.dp)) else Modifier).padding(12.dp)) {
                Text((if (i.state == State.DONE || i.state == State.COPIED) "✓ " else "") + i.label, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(i.sub, color = if (on) Color(0xFF444444) else Glass.dim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Segments(items: List<Onboarding.Item>, current: String, open: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { i ->
            val colour = when {
                i.step == current -> Glass.accent
                i.state == State.DONE || i.state == State.COPIED -> Color.White
                else -> Glass.line
            }
            Box(Modifier.weight(1f).height(48.dp).clickable { open(i.step) }, contentAlignment = Alignment.Center) { Box(Modifier.fillMaxWidth().height(4.dp).background(colour, RoundedCornerShape(2.dp))) }
        }
    }
}
