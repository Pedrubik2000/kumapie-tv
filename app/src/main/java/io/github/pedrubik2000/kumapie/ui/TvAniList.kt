package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.pedrubik2000.kumapie.data.AniListAccount
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr

@Composable
internal fun TvAniList(settings: Settings, focus: FocusRequester) {
    var changed by remember { mutableIntStateOf(0) }
    var phone by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<AniListAccount.Last?>(null) }
    val sync = remember { AniListSync(settings.prefs) }
    val loggedIn = remember(changed) { settings.anilistToken.isNotEmpty() }
    val state = remember(changed) { AniListAccount.summary(settings) }
    LaunchedEffect(loggedIn) { phone = false; confirm = false }
    LaunchedEffect(confirm) { last = null; if (confirm) last = AniListAccount.sendLast(sync) }
    Heading(tr("AniList"), Modifier.padding(top = 8.dp))
    Text(AniListAccount.why(), color = Colors.dim)
    Text(state, color = if (!loggedIn && settings.anilistLoggedOut) Colors.accent else Colors.text)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {
            when {
                !loggedIn -> phone = !phone
                !confirm -> confirm = true
                last != null -> { if (last?.expired != true) settings.logOutAniList(); confirm = false; changed++ }
            }
        }, modifier = Modifier.focusRequester(focus)) {
            Text(when {
                !loggedIn -> if (phone) tr("Hide the QR code") else tr("Log in with AniList")
                confirm -> tr("Yes, log out")
                else -> tr("Log out")
            })
        }
        if (loggedIn && confirm) Button(onClick = { confirm = false }) { Text(tr("Keep me logged in")) }
    }
    if (loggedIn && confirm) Text(tr("Log out of AniList?") + " " + AniListAccount.confirmText(last), color = if (last?.expired == true || (last?.waiting ?: 0) > 0) Colors.accent else Colors.dim)
    if (phone && !loggedIn) PhonePage(settings, SetupPart.ANILIST, onSaved = { changed++ })
}
