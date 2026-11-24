package io.github.pedrubik2000.kumapie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr

@Composable
internal fun TvAniList(settings: Settings, focus: FocusRequester) {
    var changed by remember { mutableIntStateOf(0) }
    var phone by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val loggedIn = remember(changed) { settings.anilistToken.isNotEmpty() }
    val state = remember(changed) { AniListAccount.summary(settings) }
    Heading(tr("AniList"), Modifier.padding(top = 8.dp))
    Text(AniListAccount.why(), color = Colors.dim)
    Text(state, color = if (!loggedIn && settings.anilistLoggedOut) Colors.accent else Colors.text)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {
            when {
                !loggedIn -> phone = !phone
                !confirm -> confirm = true
                else -> { settings.logOutAniList(); confirm = false; changed++ }
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
    if (loggedIn && confirm) Text(tr("Log out of AniList?") + " " + tr("This only signs kumapie out. Your AniList stays as it is."), color = Colors.dim)
    if (phone && !loggedIn) PhonePage(settings, SetupPart.ANILIST, onSaved = { changed++ })
}
