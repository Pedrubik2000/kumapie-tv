package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.data.AniList
import io.github.pedrubik2000.kumapie.data.AniListAccount
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.ui.Colors

@Composable
fun AniListSection(settings: Settings) {
    val context = LocalContext.current
    val changed by aniListLogins
    var confirm by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf<Int?>(null) }
    val sync = remember { AniListSync(settings.prefs) }
    LaunchedEffect(confirm) { waiting = null; if (confirm) waiting = AniListAccount.sendLast(sync) }
    val loggedIn = changed >= 0 && settings.anilistToken.isNotEmpty()
    Text(AniListAccount.why(), color = Colors.dim, fontSize = 14.sp)
    Text(AniListAccount.summary(settings), fontSize = 17.sp,
        color = if (!loggedIn && settings.anilistLoggedOut) Colors.accent else Colors.text)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loggedIn) OutlinedButton(onClick = { confirm = true }) { Text(tr("Log out")) }
        else Button(onClick = {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(AniList.loginUrl()))) }
        }) { Text(tr("Log in with AniList")) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(tr("Log out of AniList?")) },
        text = { Text(AniListAccount.confirmText(waiting)) },
        confirmButton = { TextButton(enabled = waiting != null, onClick = { settings.logOutAniList(); aniListLogins.intValue++; confirm = false }) { Text(tr("Yes, log out")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Keep me logged in")) } })
}
