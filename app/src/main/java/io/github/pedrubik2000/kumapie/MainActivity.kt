package io.github.pedrubik2000.kumapie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Profile
import io.github.pedrubik2000.kumapie.i18n.Tr
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.ui.ButtonsScreen
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.ui.HomeScreen
import io.github.pedrubik2000.kumapie.ui.KumapieTheme
import io.github.pedrubik2000.kumapie.ui.PlayerScreen
import io.github.pedrubik2000.kumapie.ui.ProfilesScreen
import io.github.pedrubik2000.kumapie.ui.SettingsScreen
import io.github.pedrubik2000.kumapie.ui.ShowScreen
import io.github.pedrubik2000.kumapie.ui.StatsScreen
import io.github.pedrubik2000.kumapie.ui.UpdateDialog
import io.github.pedrubik2000.kumapie.ui.onBackKey
import io.github.pedrubik2000.kumapie.update.Updater

/** The app's screens. Back goes one screen back; on Home it leaves the app. */
sealed interface Screen {
    data object Home : Screen
    data class ShowEpisodes(val show: Show) : Screen
    data class Player(val show: Show, val episode: Episode) : Screen
    data object Settings : Screen
    data object Buttons : Screen
    data object Stats : Screen
}

/** The last profile chosen on "Who's watching?" (it gets the focus there next time). */
var Settings.profile: String
    get() = prefs.getString("profile", "") ?: ""
    set(value) = prefs.edit().putString("profile", value).apply()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.repo = BuildConfig.UPDATE_REPO
        Updater.version = BuildConfig.VERSION_NAME
        Updater.asset = "kumapie-tv-"
        val settings = Settings(this)
        // The server address can be sent from the PC instead of typed with the remote:
        //   adb shell am start -n <app id>/io.github.pedrubik2000.kumapie.MainActivity --es server https://...
        intent?.getStringExtra("server")?.let { settings.server = it }
        setContent { KumapieTheme { App(settings) } }
    }
}

@Composable
fun App(settings: Settings) {
    var server by remember { mutableStateOf(settings.server) }
    var profile by remember { mutableStateOf<Profile?>(null) } // asked at every start, like Netflix
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }

    LaunchedEffect(Unit) { // once per start; debug builds have their own app id, so they don't self-update
        if (!BuildConfig.DEBUG) update = runCatching { Updater.newer() }.getOrNull()
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize().background(Colors.background)
        .onBackKey(enabled = stack.size > 1 && server.isNotEmpty()) { stack.removeAt(stack.lastIndex) }) {
        if (server.isEmpty()) {
            SettingsScreen(settings, firstRun = true, onSaved = { server = settings.server }, onUpdate = { update = it })
        } else {
            val api = remember(server, profile) { Api(server, profile?.id.orEmpty()) }
            val screen = stack.last()
            if (profile == null && screen != Screen.Settings) {
                ProfilesScreen(api, settings.profile, onPick = {
                    settings.profile = it.id
                    Tr.use(it.menu)
                    profile = it
                }, onSettings = { stack.add(Screen.Settings) })
            } else when (screen) {
                Screen.Home -> HomeScreen(api, profile?.name.orEmpty(), onShow = { stack.add(Screen.ShowEpisodes(it)) },
                    onSettings = { stack.add(Screen.Settings) }, onStats = { stack.add(Screen.Stats) },
                    onProfiles = { profile = null })
                Screen.Stats -> StatsScreen(api, onSettings = { stack.add(Screen.Settings) })
                is Screen.ShowEpisodes -> ShowScreen(api, screen.show, onEpisode = { s, e -> stack.add(Screen.Player(s, e)) })
                is Screen.Player -> PlayerScreen(api, settings, screen.show, screen.episode,
                    onClose = { stack.removeAt(stack.lastIndex) })
                Screen.Buttons -> ButtonsScreen(settings)
                Screen.Settings -> SettingsScreen(settings, firstRun = false, onButtons = { stack.add(Screen.Buttons) },
                    onSaved = { server = settings.server }, onUpdate = { update = it })
            }
        }
        update?.let { UpdateDialog(it, onClose = { update = null }) }
    }
}
