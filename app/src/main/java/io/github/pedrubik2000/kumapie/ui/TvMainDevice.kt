package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow.Hub
import io.github.pedrubik2000.kumapie.lang.MainDeviceFlow.Phase
import io.github.pedrubik2000.kumapie.lang.Pairing
import io.github.pedrubik2000.kumapie.lang.SetupBundle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val ROWS = MainDeviceFlow.ROWS

private fun available(phase: Phase, part: String): Boolean = when (val p = phase as? Phase.Ready) {
    null -> true
    else -> when (val g = p.got) {
        is SetupBundle.Got.Ok -> part == SetupBundle.SYNC || g.parts.containsKey(part)
        SetupBundle.Got.Old -> part == SetupBundle.SYNC
        else -> false
    }
}

@Composable
internal fun TvMainDevice(prefs: android.content.SharedPreferences, pairing: Pairing, steps: Int, onPcSelected: (Boolean) -> Unit, onPc: () -> Unit, onAdvance: () -> Unit) {
    val flow = remember { MainDeviceFlow(prefs, pairing) }
    val phase by flow.phase.collectAsState()
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var ticked by remember { mutableStateOf(ROWS.toSet()) }
    var address by remember { mutableStateOf("") }
    var picked by remember(phase) { mutableStateOf((phase as? Phase.Pick)?.auto) }
    val card = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val pairButton = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    fun run(block: suspend () -> Unit) { job?.cancel(); job = scope.launch { block() } }
    fun pair() {
        if (phase is Phase.Waiting) return
        val hubs = (phase as? Phase.Pick)?.hubs.orEmpty()
        val to = address.trim().ifEmpty { picked }
        if (to == null) {
            note = tr("Pick a kumapie or type its address")
            runCatching { (if (hubs.isNotEmpty()) card else field).requestFocus() }
            return
        }
        note = ""
        run { flow.pair(hubs.firstOrNull { it.address == to } ?: Hub("", to)) }
    }
    val pcPicked = address.isBlank() && (phase as? Phase.Pick)?.pcs?.contains(picked) == true
    LaunchedEffect(pcPicked) { onPcSelected(pcPicked) }
    LaunchedEffect(phase is Phase.PcDone) { if (phase is Phase.PcDone) onPc() }
    LaunchedEffect(Unit) { run { flow.start() } }
    LaunchedEffect(phase == Phase.Looking) { if (phase == Phase.Looking && !inside) runCatching { pairButton.requestFocus() } }
    LaunchedEffect(phase is Phase.Pick) {
        val p = phase as? Phase.Pick
        if (p != null && !inside) runCatching { (if (p.hubs.isNotEmpty() || p.pcs.isNotEmpty()) card else field).requestFocus() }
    }
    val saves = ROWS.take(3).count { it in ticked && available(phase, it) }
    Column(Modifier.fillMaxWidth().onFocusChanged { inside = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TvStepHeader(tr("Step %1\$d of %2\$d", 1, steps) + " · " + if (saves > 0) tr("Optional · saves %d steps", saves) else tr("Optional"),tr("Set up from your main device or PC?"),
            tr("A kumapie or a kumapie PC on the home Wi-Fi already has your AniList, keys and known words."))
        MainHubs(phase, picked.takeIf { address.isBlank() }, card) { picked = it; address = ""; note = "" }
        MainStatus(phase)
        if (note.isNotEmpty()) Text(note, color = Colors.accent, fontSize = 18.sp)
        if (!pcPicked) {
            TvCaption(tr("Copy from it · untick to set up here instead"), Modifier.padding(top = 6.dp))
            MainTicks(phase, ticked) { ticked = it }
        }
        TvCaption(tr("Type the address"), Modifier.padding(top = 6.dp))
        TvTextField(address, { address = it }, onDone = ::pair,
            modifier = Modifier.width(560.dp).focusRequester(field).focusProperties { down = pairButton; right = pairButton },
            placeholder = tr("Address of the main device (ip:port)"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            val ready = phase as? Phase.Ready
            TvContinue(if (ready != null) tr("Continue") + " ›" else if (pcPicked) tr("Use this PC") + " ›" else tr("Pair and copy"), Modifier.focusRequester(pairButton)) {
                if (pcPicked) run { flow.usePc(picked.orEmpty()) }
                else if (ready != null) { flow.finish(ready.hub, ready.got, ticked.filter { available(phase, it) }.toSet()); onAdvance() } else pair()
            }
            Text(tr("or") + " ", color = Colors.dim, fontSize = 16.sp)
            TvLink(tr("Start fresh")) { job?.cancel(); flow.fresh(); onAdvance() }
            Text(": " + tr("this is my first device"), color = Colors.dim, fontSize = 16.sp)
        }
    }
}

@Composable
private fun MainHubs(phase: Phase, picked: String?, card: FocusRequester, onPick: (String) -> Unit) {
    when (phase) {
        is Phase.Pick -> {
            phase.hubs.forEachIndexed { i, h ->
                HubCard(tr("kumapie on %s", h.name), hubSub(h), h.address == picked, null, if (i == 0) Modifier.focusRequester(card) else Modifier) { onPick(h.address) }
            }
            phase.pcs.forEachIndexed { i, a ->
                HubCard(tr("kumapie PC at %s", a), tr("everything comes from the PC · found on this network"), a == picked, null,
                    if (i == 0 && phase.hubs.isEmpty()) Modifier.focusRequester(card) else Modifier) { onPick(a) }
                if (a == picked) Text(tr("AniList, keys, word colouring and downloads all come from the PC: setup ends here."), color = Colors.dim, fontSize = 16.sp)
            }
        }
        is Phase.Waiting -> HubCard(tr("kumapie on %s", phase.hub.name), hubSub(phase.hub), true, phase.code.ifEmpty { null }) {}
        is Phase.Ready -> HubCard(tr("kumapie on %s", phase.hub.name), hubSub(phase.hub), true, null) {}
        Phase.Looking, is Phase.Done, is Phase.PcDone -> Unit
    }
}

@Composable
private fun MainStatus(phase: Phase) {
    when (phase) {
        Phase.Looking -> Text(tr("Looking for kumapie on this network…"), color = Colors.text, fontSize = 18.sp)
        is Phase.Pick -> {
            if (phase.notice.isNotEmpty()) Text(phase.notice, color = Colors.accent, fontSize = 18.sp)
            if (phase.hubs.isEmpty() && phase.pcs.isEmpty()) Text(tr("No other kumapie found: type its address below, or start fresh."), color = Colors.text, fontSize = 18.sp)
        }
        is Phase.Waiting -> if (phase.code.isEmpty()) Text(tr("Pairing…"), color = Colors.text, fontSize = 18.sp) else {
            Text(tr("Pair code: tap Allow on %1\$s when it shows %2\$s.", phase.hub.name, phase.code), color = Colors.text, fontSize = 18.sp)
            Text(tr("Waiting for you to tap Allow on %s…", phase.hub.name), color = Colors.dim, fontSize = 16.sp)
            if (phase.old) Text(tr("Allow shows in Settings > Sync with another device on %s", phase.hub.name), color = Colors.dim, fontSize = 14.sp)
        }
        is Phase.Done, is Phase.PcDone -> Unit
        is Phase.Ready -> if (phase.got == SetupBundle.Got.Pc) Text(tr("This hub uses a PC: there is nothing to copy from it. Set this device up here."), color = Colors.accent, fontSize = 18.sp)
    }
}

@Composable
private fun MainTicks(phase: Phase, ticked: Set<String>, onTicked: (Set<String>) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val ready = phase as? Phase.Ready
    Column(Modifier.fillMaxWidth().background(Arcade.slab, shape).border(1.dp, Arcade.line, shape)) {
        ROWS.forEach { part ->
            val enabled = available(phase, part)
            val label = when (part) {
                "anilist" -> tr("AniList account and list")
                "keys" -> tr("Real-Debrid + Jimaku keys")
                "colouring" -> tr("Word colouring and known words")
                SetupBundle.SYNC -> tr("Mined words and reviews")
                else -> tr("Menu and meanings language")
            }
            val sub = when {
                ready == null -> ""
                !enabled -> if (ready.got == SetupBundle.Got.Old) tr("Update kumapie on %s to copy this", ready.hub.name) else tr("Nothing to copy")
                part == SetupBundle.SYNC -> tr("Paired with %s", ready.hub.name)
                else -> (ready.got as? SetupBundle.Got.Ok)?.summary?.get(part).orEmpty()
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TvCheck(part in ticked && enabled, label, enabled) { onTicked(if (it) ticked + part else ticked - part) }
                Column {
                    Text(label, color = if (enabled) Colors.text else Colors.dim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (sub.isNotEmpty()) Text(sub, color = Colors.dim, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun hubSub(hub: Hub) = tr("%1\$s · main server · found on this network", hub.address)

@Composable
private fun HubCard(title: String, sub: String, selected: Boolean, code: String?, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().hint(HintKind.CHOOSE).tvRing(shape, source)
        .background(if (selected) Arcade.band else Arcade.slab, shape).border(if (selected) 1.5.dp else 1.dp, if (selected) Color.White else Arcade.line, shape)
        .selectable(selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onSelect).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Color(0xFF6CC47C), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(title, color = Colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Colors.dim, fontSize = 14.sp)
        }
        code?.forEach { digit ->
            Box(Modifier.size(width = 44.dp, height = 52.dp).background(Arcade.band, RoundedCornerShape(10.dp)).border(1.dp, Arcade.line, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(digit.toString(), color = Colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
