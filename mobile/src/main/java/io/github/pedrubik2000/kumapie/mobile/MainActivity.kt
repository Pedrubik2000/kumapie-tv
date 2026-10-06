package io.github.pedrubik2000.kumapie.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.mobile.ui.FeedScreen
import io.github.pedrubik2000.kumapie.mobile.ui.HomeScreen
import io.github.pedrubik2000.kumapie.mobile.ui.IPlusOneScreen
import io.github.pedrubik2000.kumapie.mobile.ui.MobileTheme
import io.github.pedrubik2000.kumapie.mobile.ui.PlayerScreen
import io.github.pedrubik2000.kumapie.mobile.ui.SettingsScreen
import io.github.pedrubik2000.kumapie.mobile.ui.ShowScreen
import io.github.pedrubik2000.kumapie.mobile.ui.StatsScreen
import io.github.pedrubik2000.kumapie.mobile.ui.UpdateDialog
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.update.Updater

sealed interface Screen {
    data object Home : Screen
    data class ShowEpisodes(val showId: String) : Screen
    data class Player(val show: Show, val episode: Episode, val startAt: Double? = null) : Screen
    data object IPlusOne : Screen
    data object Feed : Screen
    data object Settings : Screen
    data object Stats : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.repo = BuildConfig.UPDATE_REPO
        Updater.version = BuildConfig.VERSION_NAME
        Updater.asset = "kumapie-v" // kumapie-vX.Y.Z-mobile.apk (sorts after kumapie-tv-*, see release.yml)
        val settings = Settings(this)
        // The server address can be sent from the PC instead of typed:
        //   adb shell am start -n <app id>/io.github.pedrubik2000.kumapie.mobile.MainActivity --es server https://...
        intent?.getStringExtra("server")?.let { settings.server = it }
        // The download notification (Android 13+ asks once).
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        enableEdgeToEdge()
        val library = Library(applicationContext, settings)
        setContent { MobileTheme { App(library) } }
    }

    /** A server address sent while the app is already open: save it and start over with it. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("server")?.let {
            Settings(this).server = it
            recreate()
        }
    }
}

@Composable
fun App(library: Library) {
    val settings = library.settings
    var server by remember { mutableStateOf(settings.server) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    // The show list, kept here so the episode list and the player see the same (refreshed) shows.
    var shows by remember { mutableStateOf<List<Show>>(emptyList()) }

    LaunchedEffect(Unit) { // once per start; debug builds have their own app id, so they don't self-update
        if (!BuildConfig.DEBUG) update = runCatching { Updater.newer() }.getOrNull()
    }
    LaunchedEffect(Unit) { // fresh word colours from Anki (cheap after the first time: only new or changed notes are parsed)
        // Only once Anki has been read here by hand: reading wakes AnkiDroid, and an AnkiDroid that was never opened
        // then sets itself up with a new empty collection in /sdcard/AnkiDroid.
        if (library.known.ready && library.known.model.isReady) library.known.refresh()
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize().background(Colors.background)) {
        if (server.isEmpty()) {
            SettingsScreen(library, firstRun = true, onSaved = { server = settings.server }, onUpdate = { update = it }, onBack = {})
        } else when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(
                library,
                onShows = { shows = it },
                onShow = { stack += Screen.ShowEpisodes(it.id) },
                onStats = { stack += Screen.Stats },
                onIPlusOne = { stack += Screen.IPlusOne },
                onFeed = { stack += Screen.Feed },
                onSettings = { stack += Screen.Settings },
            )
            is Screen.ShowEpisodes -> shows.firstOrNull { it.id == screen.showId }?.let { show ->
                ShowScreen(library, show, onPlay = { stack += Screen.Player(show, it) }, onBack = { stack.removeAt(stack.lastIndex) })
            }
            is Screen.Player -> PlayerScreen(library, screen.show, screen.episode, screen.startAt, onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Feed -> FeedScreen(library, shows, onBack = { stack.removeAt(stack.lastIndex) })
            Screen.IPlusOne -> IPlusOneScreen(library, shows, onPlay = { s, e, at -> stack += Screen.Player(s, e, at) },
                onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Settings -> SettingsScreen(library, firstRun = false, onSaved = { server = settings.server },
                onUpdate = { update = it }, onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Stats -> StatsScreen(library, onBack = { stack.removeAt(stack.lastIndex) })
        }
        update?.let { UpdateDialog(it, onClose = { update = null }) }
    }
}
