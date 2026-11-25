package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow.Phase
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow.Hub
import io.github.pedrubik2000.kumapie.lang.SetupBundle
import io.github.pedrubik2000.kumapie.lang.SetupBundle.Got
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun rowLabel(row: String) = when (row) {
    "anilist" -> tr("AniList account and list")
    "keys" -> tr("Real-Debrid + Jimaku keys")
    "colouring" -> tr("Word colouring and known words")
    SetupBundle.SYNC -> tr("Mined words and reviews")
    else -> tr("Menu and meanings language")
}

private fun available(row: String, got: Got?) = when (got) {
    is Got.Ok -> row == SetupBundle.SYNC || row in got.parts
    is Got.Old -> row == SetupBundle.SYNC
    else -> true
}

private fun rowSub(row: String, hub: Hub, got: Got?): String? = when (got) {
    is Got.Ok -> if (row == SetupBundle.SYNC) tr("Stays paired and syncs") else got.summary[row]?.takeIf { it.isNotBlank() } ?: tr("Nothing to copy")
    is Got.Old -> if (row == SetupBundle.SYNC) tr("Stays paired and syncs") else tr("Update kumapie on %s to copy this", hub.name)
    else -> null
}

@Composable
internal fun MainDeviceStep(flow: MainDeviceFlow, host: CoroutineScope, caption: String, canFresh: Boolean, onPcSelected: (Boolean) -> Unit, onPc: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val phase by flow.phase.collectAsState()
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var unticked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(flow) { flow.start() }
    val shownNow = phase
    val chosen = if (typing) null else (shownNow as? Phase.Pick)?.let { p -> p.pcs.firstOrNull { it.address == (picked ?: p.auto) } }
    LaunchedEffect(chosen != null) { onPcSelected(chosen != null) }
    LaunchedEffect(shownNow is Phase.PcDone) { if (shownNow is Phase.PcDone) onPc() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).glass(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Sync, null, tint = Color.White) }
            Column {
                ColourCaption(caption)
                Text(tr("Set up from your main device or PC?"), color = Colors.text, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(tr("A kumapie or a kumapie PC on the home Wi-Fi already has your AniList, keys and known words."), color = Glass.dim, fontSize = 15.sp)
        val shown = phase
        val hub = when (shown) {
            is Phase.Waiting -> shown.hub
            is Phase.Ready -> shown.hub
            else -> null
        }
        val got = (shown as? Phase.Ready)?.got
        when (shown) {
            is Phase.Looking -> Text(tr("Looking for kumapie on this network…"), color = Glass.dim)
            is Phase.Pick -> PickHub(shown, picked ?: shown.auto, typing, typed, { picked = it; typing = false }, { typing = true; picked = null }, { typed = it }) {
                scope.launch { flow.look() }
            }
            is Phase.Waiting -> WaitingHub(shown)
            is Phase.Done -> DoneCard(shown)
            is Phase.PcDone -> Unit
            is Phase.Connecting -> Text(tr("Connecting…"), color = Glass.dim)
            is Phase.Ready -> if (shown.got is Got.Pc) Text(tr("This hub uses a PC: there is nothing to copy from it. Set this device up here."), color = Colors.text)
        }
        val deviceChosen = !typing && (shown as? Phase.Pick)?.let { p -> p.hubs.any { it.address == (picked ?: p.auto) } } == true
        if ((shown is Phase.Ready && got !is Got.Pc) || shown is Phase.Waiting || deviceChosen) Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp))) {
            ColourCaption(tr("Copy from it · untick to set up here instead"), Modifier.padding(16.dp, 14.dp, 16.dp, 0.dp))
            MainDeviceFlow.ROWS.forEach { row ->
                val on = available(row, got)
                val sub = rowSub(row, hub ?: Hub("", ""), got)
                Row(Modifier.fillMaxWidth().alpha(if (on) 1f else 0.5f).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColourCheck(on && row !in unticked, rowLabel(row), on) { unticked = if (it) unticked - row else unticked + row }
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(rowLabel(row), color = Colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        if (sub != null) Text(sub, color = Glass.dim, fontSize = 14.sp)
                    }
                }
            }
        }
        val target = if (typing) typed.trim().takeIf { it.isNotEmpty() }?.let { Hub("", it) } else (shown as? Phase.Pick)?.hubs?.firstOrNull { it.address == (picked ?: shown.auto) }
        StepBar {
            when {
                shown is Phase.Done -> Pill(tr("Continue"), onDone, primary = true)
                got != null && hub != null -> Pill(tr("Continue"), {
                    if (!busy) {
                        busy = true
                        host.launch {
                            withContext(Dispatchers.IO) {
                                if (got is Got.Pc) flow.fresh() else flow.finish(hub, got, MainDeviceFlow.ROWS.filter { it !in unticked && available(it, got) }.toSet())
                            }
                            onDone()
                        }
                    }
                }, primary = true)
                chosen != null -> Pill(tr("Use this PC") + " ›", { if (!busy) { busy = true; scope.launch { flow.usePc(chosen, wan = true); busy = false } } }, primary = true)
                shown is Phase.Pick -> Pill(tr("Pair and copy"), { if (target != null) scope.launch { flow.pair(target) } }, Modifier.alpha(if (target != null) 1f else 0.4f), primary = true)
            }
            if (canFresh && shown !is Phase.Done) {
                Text(tr("or"), color = Glass.dim, fontSize = 14.sp)
                Text(tr("Start fresh"), color = Glass.dim, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.sizeIn(minHeight = 48.dp).clickable { if (!busy) { busy = true; host.launch { withContext(Dispatchers.IO) { flow.fresh() }; onDone() } } }.padding(vertical = 14.dp))
                Text(": " + tr("this is my first device"), color = Glass.dim, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PickHub(pick: Phase.Pick, selected: String?, typing: Boolean, typed: String, onPick: (String) -> Unit, onType: () -> Unit, onTyped: (String) -> Unit, onAgain: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pick.notice.isNotEmpty()) Text(pick.notice, color = Colors.unknown, fontSize = 15.sp)
        pick.hubs.forEach { h ->
            ColourRadioCard(!typing && h.address == selected, tr("kumapie on %s", h.name), tr("%1\$s · main server · found on this network", h.address)) { onPick(h.address) }
        }
        pick.pcs.forEach { (a) ->
            val on = !typing && a == selected
            ColourRadioCard(on, tr("kumapie PC at %s", a), tr("everything comes from the PC · found on this network")) { onPick(a) }
            if (on) Text(tr("AniList, keys, word colouring and downloads all come from the PC: setup ends here."), color = Glass.dim, fontSize = 14.sp)
        }
        if (typing) OutlinedTextField(typed, onTyped, Modifier.fillMaxWidth(), label = { Text(tr("Address of the main device (ip:port)")) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (!typing) LinkText(tr("Type the address"), onType)
            LinkText(tr("Search again"), onAgain)
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) =
    Text(text, color = Glass.accent, fontSize = 15.sp, modifier = Modifier.sizeIn(minHeight = 48.dp).clickable(onClick = onClick).padding(vertical = 14.dp))

@Composable
private fun WaitingHub(w: Phase.Waiting) {
    val name = w.hub.name.ifEmpty { w.hub.address }
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(tr("kumapie on %s", name), color = Colors.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tr("%1\$s · main server · found on this network", w.hub.address), color = Glass.dim, fontSize = 14.sp)
        }
        w.code.forEach { d -> Box(Modifier.size(34.dp, 44.dp).glass(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text("$d", color = Colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold) } }
    }
    if (w.code.isNotEmpty()) Text(tr("Pair code: tap Allow on %1\$s when it shows %2\$s.", name, w.code), color = Glass.dim, fontSize = 15.sp)
    Text(tr("Waiting for you to tap Allow on %s…", name), color = Colors.text, fontSize = 15.sp)
    if (w.old) Text(tr("Allow shows in Settings > Sync with another device on %s", name), color = Glass.dim, fontSize = 13.sp)
}

@Composable
private fun DoneCard(done: Phase.Done) {
    val copied = io.github.pedrubik2000.kumapie.data.Onboarding.list(emptySet(), done.copied, true).filter { it.state == io.github.pedrubik2000.kumapie.data.Onboarding.State.COPIED }.joinToString { it.label }
    val line = when {
        done.hub.isEmpty() -> tr("Set up fresh")
        copied.isEmpty() -> tr("Paired with %s", done.hub)
        else -> tr("Copied from %1\$s: %2\$s", done.hub, copied)
    }
    Text(line, color = Colors.text, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(16.dp))
}
