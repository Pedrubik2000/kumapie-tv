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
import io.github.pedrubik2000.kumapie.data.Gate
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
import io.github.pedrubik2000.kumapie.ui.glowCovered
import io.github.pedrubik2000.kumapie.ui.pageBackdrop
import io.github.pedrubik2000.kumapie.ui.onBackKey
import io.github.pedrubik2000.kumapie.update.Updater

sealed interface Screen {
    data object Home : Screen
    data class ShowEpisodes(val show: Show) : Screen
    data class Player(val show: Show, val episode: Episode) : Screen
    data object Settings : Screen
    data object Buttons : Screen
    data object Stats : Screen
    data class Discover(val open: io.github.pedrubik2000.kumapie.data.Found? = null) : Screen
    data class Review(val lang: String) : Screen
}

var Settings.profile: String
    get() = prefs.getString("profile", "") ?: ""
    set(value) = prefs.edit().putString("profile", value).apply()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        io.github.pedrubik2000.kumapie.data.Backdrops.lastHero(this)?.let { image ->
            val m = resources.displayMetrics
            coil3.SingletonImageLoader.get(this).enqueue(coil3.request.ImageRequest.Builder(this).data(image)
                .size(m.widthPixels, m.heightPixels).scale(coil3.size.Scale.FILL).build())
        }
        io.github.pedrubik2000.kumapie.data.Startup.updates(BuildConfig.UPDATE_REPO, BuildConfig.VERSION_NAME, "kumapie-tv-")
        val settings = Settings(this)
        fromAdb(intent, settings)
        window.decorView.postDelayed({ io.github.pedrubik2000.kumapie.lang.DeviceSync.start(applicationContext) }, 8000)
        if (settings.server.isBlank()) window.decorView.postDelayed({ io.github.pedrubik2000.kumapie.lang.JapaneseModel(applicationContext).warm() }, 5000)
        window.decorView.postDelayed({ Thread { io.github.pedrubik2000.kumapie.data.Playable.device }.start() }, 3000)
        if (settings.server.isBlank()) window.decorView.postDelayed({ Thread { io.github.pedrubik2000.kumapie.lang.AloneStream.prefetch(applicationContext, settings.prefs,
            io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs).goingNow()) }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start() }, 8000)
        io.github.pedrubik2000.kumapie.data.Jimaku.cache = cacheDir
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        setContent { KumapieTheme { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.pedrubik2000.kumapie.ui.TvScroll) { App(settings) } } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (fromAdb(intent, Settings(this))) recreate()
    }

    private fun fromAdb(intent: android.content.Intent?, settings: Settings): Boolean {
        var any = false
        intent?.getStringExtra("server")?.let { settings.server = it; any = true }
        intent?.getStringExtra("addon")?.let { a -> settings.addons = if (a.isBlank()) emptyList() else (settings.addons + a.trim()).distinct(); any = true }
        intent?.getStringExtra("jimaku")?.let { settings.jimakuKey = it; any = true }
        return any
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        io.github.pedrubik2000.kumapie.ui.Pad.seen(event)
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun App(settings: Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var server by remember { mutableStateOf(settings.server) }
    var standalone by remember { mutableStateOf(settings.standalone) }
    var onboarded by remember { mutableStateOf(io.github.pedrubik2000.kumapie.data.Startup.onboarded(settings.prefs.all)) }
    LaunchedEffect(standalone, server) { if (standalone && server.isEmpty()) io.github.pedrubik2000.kumapie.ui.startJapaneseDictionaries(context, settings) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }

    LaunchedEffect(Unit) {
        update = io.github.pedrubik2000.kumapie.data.Startup.update(BuildConfig.DEBUG)
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    val gate = io.github.pedrubik2000.kumapie.data.Startup.gate(server, standalone, onboarded)

    Box(Modifier.fillMaxSize().pageBackdrop { glowCovered.value }
        .onBackKey(enabled = stack.size > 1 && gate != Gate.FIRST_RUN) { stack.removeAt(stack.lastIndex) }) {
        when {
            gate == Gate.FIRST_RUN -> SettingsScreen(settings, firstRun = true,
                onSaved = { server = settings.server; standalone = settings.standalone }, onUpdate = { update = it })
            gate == Gate.ONBOARDING -> io.github.pedrubik2000.kumapie.ui.TvOnboarding(settings, onDone = { onboarded = true })
            server.isEmpty() -> AloneScreens(settings, stack, onSaved = { server = settings.server }, onUpdate = { update = it })
            else -> PcScreens(settings, server, stack, onSaved = { server = settings.server; standalone = settings.standalone }, onUpdate = { update = it })
        }
        update?.let { UpdateDialog(it, onClose = { update = null }) }
    }
}

@Composable
private fun AloneScreens(settings: Settings, stack: androidx.compose.runtime.snapshots.SnapshotStateList<Screen>, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { io.github.pedrubik2000.kumapie.ui.moveAloneLang(settings); Tr.use(settings.menuLanguage) }
    when (stack.last()) {
        Screen.Settings -> SettingsScreen(settings, firstRun = false, onButtons = { stack.add(Screen.Buttons) },
            onSaved = onSaved, onUpdate = onUpdate)
        Screen.Buttons -> ButtonsScreen(settings)
        Screen.Stats -> StatsScreen({ io.github.pedrubik2000.kumapie.lang.Progress.of(context).stats() }, onSettings = { stack.add(Screen.Settings) })
        is Screen.Review -> io.github.pedrubik2000.kumapie.ui.ReviewScreen(io.github.pedrubik2000.kumapie.lang.ownReview(context, (stack.last() as Screen.Review).lang), server = false)
        is Screen.Discover -> {
            val addons = remember(settings.addons) { io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs) }
            io.github.pedrubik2000.kumapie.ui.DiscoverScreen(addons, (stack.last() as Screen.Discover).open, onHome = { while (stack.size > 1) stack.removeAt(stack.lastIndex) },
                onReview = { stack.add(Screen.Review("ja")) }, onSettings = { stack.add(Screen.Settings) })
        }
        else -> HomeScreen(remember(settings.addons) { io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs) }, settings, "", "",
            onShow = { s -> stack.add(Screen.Discover(io.github.pedrubik2000.kumapie.data.streamFound(s, s.episodes.first()))) },
            onPlay = { s, e -> stack.add(Screen.Discover(io.github.pedrubik2000.kumapie.data.streamFound(s, e))) },
            onSettings = { stack.add(Screen.Settings) }, onStats = { stack.add(Screen.Stats) }, onProfiles = {}, onReview = { stack.add(Screen.Review(it)) },
            onDiscover = { stack.add(Screen.Discover()) }, onDiscovered = { stack.add(Screen.Discover(it)) })
    }
    if (stack.last().let { it == Screen.Home || it is Screen.Discover }) io.github.pedrubik2000.kumapie.ui.RdMissingLine(settings) { stack.add(Screen.Settings) }
}

@Composable
private fun PcScreens(settings: Settings, server: String, stack: androidx.compose.runtime.snapshots.SnapshotStateList<Screen>, onSaved: () -> Unit, onUpdate: (Updater.Release) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var profile by remember { mutableStateOf<Profile?>(null) }
    val api = remember(server, profile) { Api(server, profile?.id.orEmpty()) }
    LaunchedEffect(api) { api.findLan() }
    val screen = stack.last()
    if (profile == null && screen != Screen.Settings) {
        ProfilesScreen(api, settings.profile, onPick = {
            settings.profile = it.id
            Tr.use(it.menu)
            settings.speaks = if (it.menu == "es") "es" else "en"
            profile = it
        }, onSettings = { stack.add(Screen.Settings) })
    } else when (screen) {
        Screen.Home -> HomeScreen(api, settings, profile?.id.orEmpty(), profile?.name.orEmpty(),
            onShow = { stack.add(Screen.ShowEpisodes(it)) }, onPlay = { s, e ->
                when (val r = io.github.pedrubik2000.kumapie.data.route(s, e, hasPc = true)) {
                    io.github.pedrubik2000.kumapie.data.Route.Player -> stack.add(Screen.Player(s, e))
                    is io.github.pedrubik2000.kumapie.data.Route.Watch -> context.startActivity(StreamActivity.intent(context, r.query))
                    is io.github.pedrubik2000.kumapie.data.Route.Discover -> stack.add(Screen.Discover(r.found))
                }
            },
            onSettings = { stack.add(Screen.Settings) }, onStats = { stack.add(Screen.Stats) },
            onProfiles = { profile = null }, onReview = { stack.add(Screen.Review(it)) }, onDiscover = { stack.add(Screen.Discover()) },
            onDiscovered = { stack.add(Screen.Discover(it)) })
        is Screen.Discover -> io.github.pedrubik2000.kumapie.ui.DiscoverScreen(api, screen.open, onHome = { while (stack.size > 1) stack.removeAt(stack.lastIndex) },
            onReview = { stack.add(Screen.Review("ja")) }, onSettings = { stack.add(Screen.Settings) })
        is Screen.Review -> io.github.pedrubik2000.kumapie.ui.ReviewScreen(io.github.pedrubik2000.kumapie.ui.pcReview(api, screen.lang))
        Screen.Stats -> StatsScreen({ api.stats() }, onSettings = { stack.add(Screen.Settings) })
        is Screen.ShowEpisodes -> ShowScreen(api, settings, screen.show, onEpisode = { s, e -> stack.add(Screen.Player(s, e)) })
        is Screen.Player -> PlayerScreen(api, settings, screen.show, screen.episode,
            onClose = { stack.removeAt(stack.lastIndex) })
        Screen.Buttons -> ButtonsScreen(settings)
        Screen.Settings -> SettingsScreen(settings, firstRun = false, onButtons = { stack.add(Screen.Buttons) },
            onSaved = onSaved, onUpdate = onUpdate)
    }
}
