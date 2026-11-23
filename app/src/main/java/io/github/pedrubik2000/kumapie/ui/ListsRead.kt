package io.github.pedrubik2000.kumapie.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.github.pedrubik2000.kumapie.data.AniListLists
import io.github.pedrubik2000.kumapie.data.Mine
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias LibraryRead = (SharedPreferences, String, String, CoroutineScope, (List<Mine>) -> Unit) -> List<Mine>

internal const val REFRESH_EVERY_MS = 10 * 60_000L

@Composable
internal fun rememberLists(settings: Settings, read: LibraryRead, cacheOnly: Boolean): State<List<Mine>?> {
    val scope = rememberCoroutineScope()
    val all = remember { mutableStateOf<List<Mine>?>(null) }
    val user = settings.anilist
    val loggedIn = settings.anilistToken.isNotEmpty()
    LaunchedEffect(user, loggedIn) {
        all.value = if (!loggedIn || user.isEmpty()) emptyList()
        else withContext(Dispatchers.IO) {
            if (!cacheOnly && (LibraryMemory.account != user || System.currentTimeMillis() - LibraryMemory.at >= REFRESH_EVERY_MS)) {
                read(settings.prefs, user, settings.anilistToken, scope) {
                    LibraryMemory.account = user
                    LibraryMemory.at = System.currentTimeMillis()
                    all.value = it
                }
            } else AniListLists.cached(settings.prefs, user)
        }
    }
    return all
}

internal class Live {
    val focusIn = mutableStateOf(false)
    val touched = mutableStateOf(false)

    fun track(modifier: Modifier): Modifier = modifier.railGate().onFocusChanged { focusIn.value = it.hasFocus }.onPreviewKeyEvent { touched.value = true; false }
}

@Composable
internal fun EntryWhenIdle(shown: Any?, off: Boolean, live: Live, entry: FocusRequester) {
    LaunchedEffect(shown, live.focusIn.value, off, MissingKeyLine.focused) {
        if (shown == null || off || live.focusIn.value || MissingKeyLine.focused) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (!live.focusIn.value && !MissingKeyLine.focused) entry.focusSoon()
    }
}
