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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.mobile.ui.GlassMenu
import io.github.pedrubik2000.kumapie.mobile.ui.MoreSheet
import io.github.pedrubik2000.kumapie.mobile.ui.destinations
import io.github.pedrubik2000.kumapie.mobile.ui.wide
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
import io.github.pedrubik2000.kumapie.mobile.ui.ImproveScreen
import io.github.pedrubik2000.kumapie.mobile.ui.HomeScreen
import io.github.pedrubik2000.kumapie.mobile.ui.MobileTheme
import io.github.pedrubik2000.kumapie.mobile.ui.PlayerScreen
import io.github.pedrubik2000.kumapie.mobile.ui.PrepareScreen
import io.github.pedrubik2000.kumapie.mobile.ui.DiscoverScreen
import io.github.pedrubik2000.kumapie.mobile.ui.WatchScreen
import io.github.pedrubik2000.kumapie.mobile.ui.SettingsScreen
import io.github.pedrubik2000.kumapie.mobile.ui.ShowScreen
import io.github.pedrubik2000.kumapie.mobile.ui.StatsScreen
import io.github.pedrubik2000.kumapie.mobile.ui.UpdateDialog
import io.github.pedrubik2000.kumapie.ui.Colors
import io.github.pedrubik2000.kumapie.update.Updater
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class ShowEpisodes(val showId: String) : Screen
    data class Player(val show: Show, val episode: Episode, val startAt: Double? = null) : Screen
    /** [open]: a title kept on Home, opened straight away. */
    data class Discover(val open: io.github.pedrubik2000.kumapie.data.Found? = null) : Screen
    /** A Discover file: the PC makes its words, then it streams (the next episode at the video's end). */
    data class Watch(val query: Map<String, String>) : Screen
    data object Feed : Screen
    data object Improve : Screen
    data class Settings(val section: String? = null) : Screen
    data object Stats : Screen
    data object Search : Screen
    data object Downloads : Screen
    /** Review of kumapie's own cards (no PC). */
    data object Review : Screen
    /** A kumapie://prepare link's query: the PC makes the episode, then it plays. */
    data class Prepare(val query: Map<String, String>) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.repo = BuildConfig.UPDATE_REPO
        Updater.version = BuildConfig.VERSION_NAME
        Updater.asset = "kumapie-v" // kumapie-vX.Y.Z-mobile.apk (sorts after kumapie-tv-*, see release.yml)
        val settings = Settings(this)
        if (settings.server.isBlank()) io.github.pedrubik2000.kumapie.lang.JapaneseModel(applicationContext).warm() // no PC: streams' words
        io.github.pedrubik2000.kumapie.data.Jimaku.cache = cacheDir // an episode's lines kept for the next time it opens
        // Downloaded voices (Settings > Word audio) before the device's own: Thorsten (German), Michael (English).
        val voices = io.github.pedrubik2000.kumapie.mobile.local.NeuralVoices(applicationContext)
        io.github.pedrubik2000.kumapie.lang.Voice.neural = { l, t, r, f -> voices.say(l, t, f, r) }
        // The server address can be sent from the PC instead of typed:
        //   adb shell am start -n <app id>/io.github.pedrubik2000.kumapie.mobile.MainActivity --es server https://...
        intent?.getStringExtra("server")?.let { settings.server = it }
        if (savedInstanceState == null) addonsAndKey(intent, settings) // not again on rotation: an addon removed since came back
        // The download notification (Android 13+ asks once).
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        enableEdgeToEdge()
        val library = Library(applicationContext, settings)
        // kumapie://prepare?person&lang&type&id (the kumapie add-on's stream in Stremio): the PC makes the episode, then it plays.
        val prepare = intent?.takeIf { it.action == android.content.Intent.ACTION_VIEW }?.data?.takeIf { it.host == "prepare" }
            ?.let { u -> u.queryParameterNames.associateWith { u.getQueryParameter(it).orEmpty() } }
        // A link shared to kumapie, or a magnet link opened in the browser, opens "Add an episode" with it.
        val shared = intent?.takeIf { it.action == android.content.Intent.ACTION_SEND }?.getStringExtra(android.content.Intent.EXTRA_TEXT)
            ?: intent?.takeIf { it.action == android.content.Intent.ACTION_VIEW && prepare == null && !aniListLogin(it) }?.dataString
        // Japanese text to look up (other apps' "share" or `am start ... --es search 本当に`): opens the dictionary search.
        val search = intent?.getStringExtra("search")
            ?: intent?.takeIf { it.action == android.content.Intent.ACTION_PROCESS_TEXT }?.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString()
        search?.let { settings.prefs.edit().putString("search_text", it).apply() }
        // A card to improve (Anki XP's "Improve this card first": `--el improve_note <note id>`): opens "Cards that don't stick" on it.
        // Or any card of the Home language's Mined deck by its word: `--es improve_word helfen`.
        val improve = intent?.getLongExtra("improve_note", 0L) ?: 0L
        if (improve != 0L) settings.prefs.edit().putLong("improve_note", improve).apply()
        val improveWord = intent?.getStringExtra("improve_word")
        // A voice test from the PC (`--es say_to_file "ja|箸|はし"`): each text said into files/tts/<n>.wav (item 32).
        intent?.getStringExtra("say_to_file")?.let { spec ->
            val parts = spec.split("|")
            val l = library.languages.of(parts[0])
            val dir = java.io.File(getExternalFilesDir(null), "tts").apply { mkdirs() }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                parts.drop(1).forEachIndexed { i, t -> android.util.Log.i("kumapie", "say_to_file $i: ${l.voice.toFile(t, java.io.File(dir, "$i.wav"))}") }
            }
        }
        improveWord?.let { settings.prefs.edit().putString("improve_word", it).apply() }
        setContent { MobileTheme { App(library, shared, openSearch = search != null, openImprove = improve != 0L || improveWord != null, prepare = prepare) } }
    }

    private fun aniListLogin(intent: android.content.Intent): Boolean {
        val data = intent.data?.takeIf { it.host == "anilist-auth" } ?: return false
        val token = io.github.pedrubik2000.kumapie.data.AniList.tokenOf(data.toString()) ?: return true
        val settings = Settings(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val said = runCatching { settings.logInAniList(token) }.fold({ tr("✓ AniList: %s", it) }, { it.message ?: it.toString() })
            android.widget.Toast.makeText(applicationContext, said, android.widget.Toast.LENGTH_LONG).show()
            io.github.pedrubik2000.kumapie.mobile.ui.aniListLogins.intValue++
        }
        return true
    }

    /** A server address sent while the app is already open: save it and start over with it. A prepare link too. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (aniListLogin(intent)) return
        if (intent.data?.host == "prepare") {
            setIntent(intent)
            recreate()
            return
        }
        intent.getStringExtra("server")?.let {
            Settings(this).server = it
            recreate()
        }
        if (addonsAndKey(intent, Settings(this))) recreate()
    }

    /** Without a PC, the same way as on the TV (long to type; the keyboard autocorrects addresses): --es addon <address>
     *  adds one ('' clears them), --es jimaku <key>. Answers whether anything came. */
    private fun addonsAndKey(intent: android.content.Intent?, settings: Settings): Boolean {
        var any = false
        intent?.getStringExtra("addon")?.let { a -> settings.addons = if (a.isBlank()) emptyList() else (settings.addons + a.trim()).distinct(); any = true }
        intent?.getStringExtra("jimaku")?.let { settings.jimakuKey = it; any = true }
        return any
    }
}

@Composable
fun App(library: Library, sharedLink: String? = null, openSearch: Boolean = false, openImprove: Boolean = false, prepare: Map<String, String>? = null) {
    val settings = library.settings
    var server by remember { mutableStateOf(settings.server) }
    var standalone by remember { mutableStateOf(settings.standalone) } // no PC server: chosen on the first run, as on the TV
    var onboarded by remember { mutableStateOf(settings.prefs.getBoolean(io.github.pedrubik2000.kumapie.mobile.ui.ONBOARDED, false)) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home).apply { if (openSearch) add(Screen.Search); if (openImprove) add(Screen.Improve); if (prepare != null) add(Screen.Prepare(prepare)) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    // The show list, kept here so the episode list and the player see the same (refreshed) shows.
    var shows by remember { mutableStateOf<List<Show>>(emptyList()) }
    // Home's language: Home, the feed and the i+1 list show only its shows.
    // Without a PC: Japanese (Discover is there), the only language at launch.
    var homeLang by remember { mutableStateOf(settings.prefs.getString("home_lang", null) ?: if (settings.server.isEmpty()) "ja" else "de") }
    val langShows = shows.filter { it.lang == homeLang }

    LaunchedEffect(Unit) { // opened straight on another screen (an intent): Home, which loads the shows, isn't shown
        if (stack.last() != Screen.Home) runCatching { library.shows().first }.onSuccess { shows = it }
    }
    LaunchedEffect(Unit) { // once per start; debug builds have their own app id, so they don't self-update
        if (!BuildConfig.DEBUG) update = runCatching { Updater.newer() }.getOrNull()
    }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // Without a PC, as on the TV: JMdict and JMnedict download in the background on the first run (and again if it
    // didn't finish), so a word has a meaning without a trip to Settings.
    // Not during onboarding: its last step installs them (a second install waited on the first, "Starting…").
    LaunchedEffect(standalone, server, onboarded) {
        if (!standalone || server.isNotEmpty() || !onboarded) return@LaunchedEffect
        val dicts = io.github.pedrubik2000.kumapie.lang.YomitanDictionaries.get(appContext)
        if (dicts.of(io.github.pedrubik2000.kumapie.data.Lang.JAPANESE).isEmpty())
            runCatching { dicts.installRecommended(io.github.pedrubik2000.kumapie.data.Lang.JAPANESE) {} }
                .onSuccess { it.forEach { l -> android.util.Log.i("kumapie", "ja dictionary: $l") } }
    }
    LaunchedEffect(Unit) { // the books' and manga's characters (Hoshi, chimahon): titles to the PC, its dictionary back
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ReadingCharacters.sync(appContext, library.settings) }
    }
    LaunchedEffect(Unit) { // fresh word colours from Anki (cheap after the first time: only new or changed notes are parsed)
        // Only once reading Anki was allowed here (Settings > Allow reading Anki, done after kuma3 is set up): reading
        // wakes AnkiDroid, and an AnkiDroid that was never opened then sets itself up with a new empty collection in
        // /sdcard/AnkiDroid. Before the first read every word is red ([KnownWords.apply]); kumapie_plan.md item 7.
        library.languages.all.map { it.known }.filter { k ->
            k.modelReady && (k.ready || k.ankiApp()?.let(k::hasPermission) == true)
        }.forEach { it.refresh() }
        library.syncProgress() // positions, scenes, time and marks from the other devices (through Anki)
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    // Glass menu (tablet) beside the top-level screens: a button goes there from Home, so Back returns Home.
    fun top(s: Screen?) { while (stack.size > 1) stack.removeAt(stack.lastIndex); if (s != null) stack += s }
    var more by remember { mutableStateOf(false) }
    val (main, extra) = destinations(homeLang, { top(Screen.Discover()) }, { top(Screen.Feed) }, { top(Screen.Downloads) },
        { top(Screen.Stats) }, { top(Screen.Improve) }, { top(Screen.Search) }, if (server.isEmpty()) ({ top(Screen.Review) }) else null, refresh = stack.last()) { top(Screen.Settings(null)) }
    val tab: String? = when (stack.last()) { // the button shown selected: "" = Home, null = More, "-" = no menu
        is Screen.Discover -> tr("Discover")
        // Feed: upright and fullscreen like the player, so no menu beside it (it squeezed the video).
        Screen.Downloads -> tr("Downloads")
        Screen.Review -> tr("Review")
        Screen.Stats, Screen.Improve, Screen.Search, is Screen.Settings -> null
        is Screen.ShowEpisodes -> ""
        else -> "-"
    }

    Row(Modifier.fillMaxSize().background(Colors.background)) {
    if (wide() && (server.isNotEmpty() || standalone) && tab != "-") Box(Modifier.fillMaxHeight().width(98.dp), contentAlignment = Alignment.CenterStart) {
        GlassMenu(main, tab, { top(null) }, { more = true })
    }
    Box(Modifier.weight(1f).fillMaxHeight()) {
        if (server.isEmpty() && !standalone) {
            SettingsScreen(library, firstRun = true, onSaved = { server = settings.server; standalone = settings.standalone }, onUpdate = { update = it }, onBack = {})
        } else if (server.isEmpty() && !onboarded) { // without a PC: the setup (languages, Jimaku, sources, downloads), once
            io.github.pedrubik2000.kumapie.mobile.ui.OnboardingScreen(library, onDone = { onboarded = true })
        } else when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(
                library,
                onShows = { shows = it },
                // A streamed show (Discover) has no library page: its title page, with its episodes.
                onShow = { s ->
                    if (s.kind != "stream") stack += Screen.ShowEpisodes(s.id)
                    else s.episodes.firstOrNull()?.let { e -> stack += Screen.Discover(io.github.pedrubik2000.kumapie.data.streamFound(s, e)) }
                },
                onPlay = { s, e ->
                    stack += when { // a streamed card: its prepared file, else its page to pick one
                        s.kind != "stream" -> Screen.Player(s, e)
                        e.ready -> Screen.Watch(io.github.pedrubik2000.kumapie.data.streamQuery(s, e))
                        else -> Screen.Discover(io.github.pedrubik2000.kumapie.data.streamFound(s, e))
                    }
                },
                onStats = { stack += Screen.Stats },
                onFeed = { stack += Screen.Feed },
                onImprove = { stack += Screen.Improve },
                onSearch = { stack += Screen.Search },
                onDiscover = { stack += Screen.Discover() },
                onDiscovered = { stack += Screen.Discover(it) },
                onDownloads = { stack += Screen.Downloads },
                onReview = if (server.isEmpty()) ({ stack += Screen.Review }) else null,
                homeLang = homeLang,
                onHomeLang = { homeLang = it; settings.prefs.edit().putString("home_lang", it).apply() },
                sharedLink = sharedLink,
                onSettings = { stack += Screen.Settings(it) },
            )
            is Screen.ShowEpisodes -> shows.firstOrNull { it.id == screen.showId }?.let { show ->
                ShowScreen(library, show, onPlay = { stack += Screen.Player(show, it) }, onBack = { stack.removeAt(stack.lastIndex) })
            }
            is Screen.Player -> PlayerScreen(library, screen.show, screen.episode, screen.startAt, onBack = { stack.removeAt(stack.lastIndex) })
            is Screen.Discover -> DiscoverScreen(library, onWatch = { stack += Screen.Watch(it) }, onBack = { stack.removeAt(stack.lastIndex) }, start = screen.open)
            is Screen.Watch -> androidx.compose.runtime.key(screen.query) { // the next episode: a new screen, not this one's state
                WatchScreen(library, screen.query, onNext = { stack[stack.lastIndex] = Screen.Watch(it) }, onBack = { stack.removeAt(stack.lastIndex) })
            }
            is Screen.Prepare -> PrepareScreen(library, screen.query, onPlay = { s, e -> stack[stack.lastIndex] = Screen.Player(s, e) }, onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Feed -> FeedScreen(library, langShows, homeLang, onBack = { stack.removeAt(stack.lastIndex) })
            is Screen.Settings -> SettingsScreen(library, firstRun = false, onSaved = { server = settings.server; standalone = settings.standalone },
                onUpdate = { update = it }, onBack = { stack.removeAt(stack.lastIndex) }, section = screen.section)
            Screen.Improve -> ImproveScreen(library, langShows, homeLang, onPlay = { s, e, at -> stack += Screen.Player(s, e, at) },
                onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Stats -> StatsScreen(library, onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Search -> io.github.pedrubik2000.kumapie.mobile.ui.SearchScreen(library, onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Downloads -> io.github.pedrubik2000.kumapie.mobile.ui.DownloadsScreen(onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Review -> io.github.pedrubik2000.kumapie.mobile.ui.ReviewScreen(remember(homeLang) { io.github.pedrubik2000.kumapie.lang.ownReview(context, homeLang) }, onBack = { stack.removeAt(stack.lastIndex) })
        }
        update?.let { UpdateDialog(it, onClose = { update = null }) }
    }
    }
    if (more) MoreSheet(extra) { more = false }
}
