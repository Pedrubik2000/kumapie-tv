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
import io.github.pedrubik2000.kumapie.ui.arcadeBackdrop
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
    /** [open]: a title kept on Home, opened straight away. */
    data class Discover(val open: io.github.pedrubik2000.kumapie.data.Found? = null) : Screen
    data class Review(val lang: String) : Screen
}

/** The last profile chosen on "Who's watching?" (it gets the focus there next time). */
var Settings.profile: String
    get() = prefs.getString("profile", "") ?: ""
    set(value) = prefs.edit().putString("profile", value).apply()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The bear splash goes the moment Home's first frame is ready: Android's own fade-out took ~330 ms over a Home
        // already drawn (tablet, 2026-11-19).
        if (android.os.Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        // Home's top picture (the last one it showed) decoded at its size (the whole screen, cropped) while Home is built:
        // it came 100-200 ms after Home's first frame, decoded from disk (tablet, 2026-11-19).
        io.github.pedrubik2000.kumapie.data.Backdrops.lastHero(this)?.let { image ->
            val m = resources.displayMetrics
            coil3.SingletonImageLoader.get(this).enqueue(coil3.request.ImageRequest.Builder(this).data(image)
                .size(m.widthPixels, m.heightPixels).scale(coil3.size.Scale.FILL).build())
        }
        Updater.repo = BuildConfig.UPDATE_REPO
        Updater.version = BuildConfig.VERSION_NAME
        Updater.asset = "kumapie-tv-"
        val settings = Settings(this)
        fromAdb(intent, settings)
        // No PC: streams' words. 5 s in, after Home: loading it at once (~95 MB, a GC every 0.6 s) slowed the
        // Chromecast's cold start to Home settled (2026-11-19).
        if (settings.server.isBlank()) window.decorView.postDelayed({ io.github.pedrubik2000.kumapie.lang.JapaneseModel(applicationContext).warm() }, 5000)
        window.decorView.postDelayed({ Thread { io.github.pedrubik2000.kumapie.data.Playable.device }.start() }, 3000) // a stream list's decoders (Playable.device)
        // No PC: Continue watching's episodes split before they're opened (AloneStream.prefetch), after Jiten's warm-up.
        if (settings.server.isBlank()) window.decorView.postDelayed({ Thread { io.github.pedrubik2000.kumapie.lang.AloneStream.prefetch(applicationContext, settings.prefs,
            io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs).goingNow()) }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start() }, 8000)
        io.github.pedrubik2000.kumapie.data.Jimaku.cache = cacheDir // an episode's lines kept for the next time it opens
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class) // LocalBringIntoViewSpec: the TV glide
        setContent { KumapieTheme { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.pedrubik2000.kumapie.ui.TvScroll) { App(settings) } } }
    }

    // kumapie already open: adb's intent comes here, not to onCreate. Saved, then the screens start again with it.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (fromAdb(intent, Settings(this))) recreate()
    }

    /** The server address can be sent from the PC instead of typed with the remote:
     *    adb shell am start -n <app id>/io.github.pedrubik2000.kumapie.MainActivity --es server https://...
     *  Without a PC, the same way (long to type with a remote): --es addon <address> adds one ('' clears), --es jimaku <key>. */
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
    var standalone by remember { mutableStateOf(settings.standalone) } // no PC server: chosen on the first run
    var onboarded by remember { mutableStateOf(settings.prefs.getBoolean(io.github.pedrubik2000.kumapie.ui.TV_ONBOARDED, false)) }
    // Without a PC: JMdict and JMnedict download in the background, on the first run (and again if it didn't finish).
    LaunchedEffect(standalone, server) { if (standalone && server.isEmpty()) io.github.pedrubik2000.kumapie.ui.startJapaneseDictionaries(context, settings) }
    var profile by remember { mutableStateOf<Profile?>(null) } // asked at every start, like Netflix
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }

    LaunchedEffect(Unit) { // once per start; debug builds have their own app id, so they don't self-update
        if (!BuildConfig.DEBUG) update = runCatching { Updater.newer() }.getOrNull()
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize().arcadeBackdrop()
        .onBackKey(enabled = stack.size > 1 && (server.isNotEmpty() || standalone)) { stack.removeAt(stack.lastIndex) }) {
        if (server.isEmpty() && !standalone) {
            SettingsScreen(settings, firstRun = true, onSaved = { server = settings.server; standalone = settings.standalone },
                onUpdate = { update = it })
        } else if (server.isEmpty() && !onboarded) { // without a PC: the setup (language, keys from the phone, downloads), once
            io.github.pedrubik2000.kumapie.ui.TvOnboarding(settings, onDone = { onboarded = true })
        } else if (server.isEmpty()) {
            // No server: no profiles to ask for (one person) and nothing to list yet; the language picked in the onboarding.
            LaunchedEffect(Unit) { Tr.use(settings.prefs.getString(io.github.pedrubik2000.kumapie.ui.ALONE_LANG, "").orEmpty()) }
            when (stack.last()) {
                Screen.Settings -> SettingsScreen(settings, firstRun = false, onButtons = { stack.add(Screen.Buttons) },
                    onSaved = { server = settings.server }, onUpdate = { update = it })
                Screen.Buttons -> ButtonsScreen(settings)
                // This TV's own time, lookups and marks (Progress, as the phone's).
                Screen.Stats -> StatsScreen({ io.github.pedrubik2000.kumapie.lang.Progress.of(context).stats() }, onSettings = { stack.add(Screen.Settings) })
                is Screen.Review -> io.github.pedrubik2000.kumapie.ui.ReviewScreen(io.github.pedrubik2000.kumapie.lang.ownReview(context, (stack.last() as Screen.Review).lang), server = false)
                is Screen.Discover -> {
                    val addons = remember(settings.addons) { io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs) }
                    io.github.pedrubik2000.kumapie.ui.DiscoverScreen(addons, (stack.last() as Screen.Discover).open, onHome = { while (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        onReview = { stack.add(Screen.Review("ja")) }, onSettings = { stack.add(Screen.Settings) })
                }
                // The PC's Home with this TV's own data: the streamed titles kept, where each was left, its own cards.
                else -> HomeScreen(remember(settings.addons) { io.github.pedrubik2000.kumapie.data.Addons(settings.addons, settings.prefs) }, settings, "", "",
                    onShow = { s -> stack.add(Screen.Discover(io.github.pedrubik2000.kumapie.data.streamFound(s, s.episodes.first()))) },
                    onPlay = { s, e -> stack.add(Screen.Discover(io.github.pedrubik2000.kumapie.data.streamFound(s, e))) },
                    onSettings = { stack.add(Screen.Settings) }, onStats = { stack.add(Screen.Stats) }, onProfiles = {}, onReview = { stack.add(Screen.Review(it)) },
                    onDiscover = { stack.add(Screen.Discover()) }, onDiscovered = { stack.add(Screen.Discover(it)) })
            }
        } else {
            val api = remember(server, profile) { Api(server, profile?.id.orEmpty()) }
            LaunchedEffect(api) { api.findLan() }
            val screen = stack.last()
            if (profile == null && screen != Screen.Settings) {
                ProfilesScreen(api, settings.profile, onPick = {
                    settings.profile = it.id
                    Tr.use(it.menu)
                    settings.speaks = if (it.menu == "es") "es" else "en" // translation line and help texts
                    profile = it
                }, onSettings = { stack.add(Screen.Settings) })
            } else when (screen) {
                Screen.Home -> HomeScreen(api, settings, profile?.id.orEmpty(), profile?.name.orEmpty(),
                    onShow = { stack.add(Screen.ShowEpisodes(it)) }, onPlay = { s, e ->
                        when { // a streamed card: its prepared file, else its page to pick one
                            s.kind != "stream" -> stack.add(Screen.Player(s, e))
                            e.ready -> context.startActivity(android.content.Intent(context, StreamActivity::class.java).setData(
                                io.github.pedrubik2000.kumapie.data.streamQuery(s, e).entries.fold(android.net.Uri.parse("kumapie://watch").buildUpon()) { b, (k, v) ->
                                    b.appendQueryParameter(k, v) }.build()))
                            else -> stack.add(Screen.Discover(io.github.pedrubik2000.kumapie.data.streamFound(s, e)))
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
                    onSaved = { server = settings.server; standalone = settings.standalone }, onUpdate = { update = it }) // standalone: "Use without the PC"
            }
        }
        update?.let { UpdateDialog(it, onClose = { update = null }) }
    }
}
