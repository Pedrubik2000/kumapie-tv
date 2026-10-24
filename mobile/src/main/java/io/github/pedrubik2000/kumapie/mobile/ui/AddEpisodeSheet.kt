package io.github.pedrubik2000.kumapie.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import io.github.pedrubik2000.kumapie.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pedrubik2000.kumapie.mobile.local.Gemma
import io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.mobile.local.englishSource
import io.github.pedrubik2000.kumapie.mobile.local.jimakuKey
import io.github.pedrubik2000.kumapie.mobile.local.rdToken
import io.github.pedrubik2000.kumapie.mobile.local.sonioxKey
import io.github.pedrubik2000.kumapie.mobile.local.transcriber
import io.github.pedrubik2000.kumapie.mobile.local.videoHeight
import io.github.pedrubik2000.kumapie.mobile.offline.Library
import io.github.pedrubik2000.kumapie.ui.Colors
import kotlinx.coroutines.launch

/**
 * "Add an episode": a YouTube link, a magnet or Real-Debrid link (or one shared to kumapie), or video files on the
 * tablet become episodes here (download, Soniox, subtitles, English, scenes; ProcessWorker), under a show name (blank:
 * the channel / the file name's). Below: every job's progress.
 */
@Composable
fun AddEpisodeSheet(library: Library, sharedLink: String?, homeLang: String = "de", onSettings: (section: String) -> Unit = {}) {
    val context = LocalContext.current
    var link by remember { mutableStateOf(sharedLink?.let { Regex("""(https?://|magnet:)\S+""").findAll(it).joinToString("\n") { m -> m.value } } ?: "") }
    var show by remember { mutableStateOf("") }
    // The show it is (item 20): its type and the match picked in the search ("source:id"), for its poster and description.
    var type by remember { mutableStateOf<String?>(null) }
    var match by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var searching by remember { mutableStateOf(false) }
    val matchKey = match?.let { it.getString("source") + ":" + it.getString("id") }
    var height by remember { mutableStateOf(library.settings.videoHeight) }
    // Only the languages this device learns (a parent's phone: English, no chips); the Home language first.
    val langs = remember { listOf(Lang.GERMAN, Lang.JAPANESE, Lang.ENGLISH).filter { it.code in library.settings.learning() } }
    var lang by remember { mutableStateOf(langs.firstOrNull { it.code == homeLang } ?: langs.firstOrNull() ?: Lang.GERMAN) }
    // Language names mid-sentence: "German", but "alemán" in Spanish.
    fun named(l: String) = if (io.github.pedrubik2000.kumapie.i18n.Tr.spanish) l.lowercase() else l
    val spoken = named(lang.displayName)
    val translation = named(lang.translationDisplayName)
    val japanese = lang == Lang.JAPANESE
    val jobs by remember { ProcessWorker.jobs(context) }.collectAsState(initial = emptyList())
    // The job's choices, offered only when they can work (keys and downloads are in Settings); the last ones are remembered.
    val sonioxKey = library.settings.sonioxKey.isNotBlank()
    val parakeetReady = remember { io.github.pedrubik2000.kumapie.mobile.local.Parakeet(context).isReady }
    val parakeetJaReady = remember { io.github.pedrubik2000.kumapie.mobile.local.Parakeet(context, japanese = true).isReady }
    val gemmaDownloaded = remember { Gemma(context).isReady }
    var transcriber by remember { mutableStateOf(library.settings.transcriber) }
    var english by remember { mutableStateOf(library.settings.englishSource) }
    val parakeet = !japanese && transcriber == "parakeet"
    // Soniox's English comes with its transcription: not for Parakeet or Japanese (no transcription).
    val englishNow = if (english == "soniox" && (parakeet || japanese)) "device" else english
    val gemma = englishNow == "gemma"
    val gemmaReady = !gemma || gemmaDownloaded
    // Several links at once (one per line, or pasted together).
    val links = Regex("""(https?://|magnet:)\S+""").findAll(link).map { it.value }.toList()
    // "Make it on: the PC" (remembered): every link and video file goes to the PC's job queue (dojo-feed
    // /api/aniworld), which downloads, transcribes and lists it with its shows; AniWorld / s.to links always do, and
    // TikTok ones (TikTok wants a browser's TLS fingerprint: the PC's yt-dlp has curl_cffi, this device's Python can't).
    // Else it is made on this device.
    val pc = library.settings.server.isNotBlank() && library.settings.owner.isNotBlank()
    var onPcChoice by remember { mutableStateOf(library.settings.prefs.getBoolean(MAKE_ON_PC, false)) }
    val onPc = pc && onPcChoice
    val aniworld = links.filter { isAniWorld(it) || ProcessWorker.isTikTok(it) }
    val pcLinks = if (onPc) links else aniworld
    val onDevice = links - pcLinks.toSet()
    val pcOnly = onPc || (links.isNotEmpty() && onDevice.isEmpty()) // the device's own choices below don't apply
    var pcJobs by remember { mutableStateOf(emptyList<String>()) }
    if (pc) androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { pcJobs = aniworldJobs(library); kotlinx.coroutines.delay(5000) }
    }
    val needsRd = links.any { !ProcessWorker.viaYtDlp(it) && !isAniWorld(it) } // the PC gets this device's token too
    val youtube = links.any { ProcessWorker.isYouTube(it) }
    val tiktokAccount = links.any { ProcessWorker.isTikTokAccount(it) }
    val channelLink = links.any { Regex("""youtube\.com/(@|channel/|c/|user/)""").containsMatchIn(it) }
    val playlistLink = links.any { "list=" in it }
    // YouTube: this video, or the newest shorts / videos of its channel, or the playlist.
    var ytKind by remember(channelLink, playlistLink) { mutableStateOf(if (playlistLink) "playlist" else if (channelLink) "videos" else "video") }
    var ytCount by remember { mutableStateOf("10") }
    var follow by remember { mutableStateOf(false) }
    // Picked files: kumapie keeps the right to read them, as the job may run after the app is closed.
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ProcessWorker.start(context, uri.toString(), show.takeIf { it.isNotBlank() }, height, lang, transcriber, englishNow,
                type = type, match = matchKey, pc = onPc)
        }
        if (uris.isNotEmpty()) added(context, onPc)
    }
    val ready = gemmaReady && when {
        japanese -> library.languages.japanese.model.isReady && (if (youtube) parakeetJaReady else library.settings.jimakuKey.isNotBlank())
        parakeet -> library.languages.of(lang).model.isReady && parakeetReady
        else -> library.languages.of(lang).model.isReady && sonioxKey
    }

    // Scrolls: in landscape the sheet is taller than the screen (the button ended up under the navigation bar).
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp)
        .navigationBarsPadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Add an episode"), color = Colors.accent, fontSize = 18.sp)
        if (langs.size > 1) androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            langs.forEach { l ->
                androidx.compose.material3.FilterChip(selected = lang == l, label = { Text(l.displayName) }, onClick = { lang = l })
            }
        }
        OutlinedTextField(link, { link = it }, label = { Text(tr("YouTube, TikTok, AniWorld, magnet or Real-Debrid links (one or several)")) }, maxLines = 6,
            modifier = Modifier.fillMaxWidth())
        if (links.size > 1) Text(tr("%d links", links.size), fontSize = 13.sp, color = Colors.dim)
        if (pc) {
            Text(tr("Make it on"), fontSize = 14.sp, color = Colors.dim)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to tr("This device"), true to tr("The PC")).forEach { (v, label) ->
                    androidx.compose.material3.FilterChip(selected = onPcChoice == v, label = { Text(label) },
                        onClick = { onPcChoice = v; library.settings.prefs.edit().putBoolean(MAKE_ON_PC, v).apply() })
                }
            }
        }
        if (youtube) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(("video" to tr("This video")).takeIf { !channelLink }, "shorts" to tr("Channel's shorts"),
                    "videos" to tr("Channel's videos"), ("playlist" to tr("Playlist")).takeIf { playlistLink }).forEach { (k, label) ->
                    androidx.compose.material3.FilterChip(selected = ytKind == k, onClick = { ytKind = k }, label = { Text(label) })
                }
            }
        }
        // A channel's (or a TikTok account's: always its videos) newest few, and following it.
        val listKind = if (youtube) ytKind else if (tiktokAccount) "videos" else "video"
        if (listKind != "video") OutlinedTextField(ytCount, { ytCount = it.filter(Char::isDigit).take(3) },
            label = { Text(tr("How many (newest first)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (listKind == "shorts" || listKind == "videos") androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(tr("Follow: new ones (and older ones not here) download by themselves, at night (Settings)"), fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = follow, onCheckedChange = { follow = it })
        }
        OutlinedTextField(show, { show = it; match = null }, label = { Text(tr("Show (blank: the channel's / file's name)")) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (!youtube) androidx.compose.material3.TextButton(onClick = { searching = true }) { Text(tr("Find")) } })
        match?.let { m ->
            Text(tr("Show: %s", listOfNotNull(m.optString("title"), m.optInt("year").takeIf { it > 0 }?.toString()).joinToString(" · ")),
                fontSize = 13.sp, color = Colors.dim)
        }
        if (searching) MatchDialog(library.showMeta, type ?: if (japanese) "anime" else "shows",
            show.ifBlank { links.firstOrNull()?.let { ProcessWorker.guessShow(context, it) } ?: "" }, tr("Which show is it?"),
            onPick = { kind, c -> type = kind; match = c },
            onDismiss = { searching = false })
        if (!japanese && !pcOnly) {
            Text(tr("Transcription"), fontSize = 14.sp, color = Colors.dim)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sonioxKey) androidx.compose.material3.FilterChip(selected = !parakeet, label = { Text(tr("Soniox (best, paid)")) },
                    onClick = { transcriber = "soniox"; library.settings.transcriber = "soniox" })
                if (parakeetReady) androidx.compose.material3.FilterChip(selected = parakeet, label = { Text(tr("Parakeet (free, offline)")) },
                    onClick = { transcriber = "parakeet"; library.settings.transcriber = "parakeet" })
            }
        }
        if (!pcOnly) {
        Text(tr("%s subtitles", tr(io.github.pedrubik2000.kumapie.data.Lang.GERMAN.translationName)), fontSize = 14.sp, color = Colors.dim)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOfNotNull(
                ("soniox" to tr("Soniox (with the transcription)")).takeIf { !japanese && !parakeet && sonioxKey },
                ("gemma" to tr("Gemma (good, ~3 s a line)")).takeIf { gemmaDownloaded },
                "device" to tr("Google's translator (instant)"),
                "none" to tr("None"),
            ).forEach { (value, label) ->
                androidx.compose.material3.FilterChip(selected = englishNow == value, label = { Text(label) },
                    onClick = { english = value; library.settings.englishSource = value })
            }
        }
        }
        // Only yt-dlp downloads (YouTube, TikTok) pick a quality; magnets and files come as they are.
        if (links.any(ProcessWorker::viaYtDlp)) {
            Text(tr("Video quality"), fontSize = 14.sp, color = Colors.dim)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(360, 480, 720, 1080).forEach { h ->
                    androidx.compose.material3.FilterChip(selected = height == h, label = { Text("${h}p") },
                        onClick = { height = h; library.settings.videoHeight = h })
                }
            }
            Text(tr("Higher takes more space: roughly 5 / 8 / 15 / 30 MB a minute."), fontSize = 12.sp, color = Colors.dim)
        }
        // What blocks the button, with the way out: Settings, or (for the device's own models and keys) the PC.
        val blocker = when {
            pcOnly -> null
            !gemmaReady -> tr("First: Settings > download the translation model (Gemma), or pick another %s source.", translation)
            !ready && japanese -> if (youtube) tr("First: Settings > the Japanese words dictionary, and Parakeet ja.")
                else tr("First: Settings > the Japanese words dictionary, and your Jimaku API key.")
            !ready -> if (parakeet) tr("First: Settings > the %s model and the speech model (Parakeet).", spoken)
                else tr("First: Settings > the %s model, and your Soniox key.", spoken)
            else -> null
        }?.let { if (pc) it + " " + tr("Or make it on the PC.") else it }
        val rdBlocker = (needsRd && library.settings.rdToken.isBlank())
            .takeIf { it }?.let { tr("For magnets and Real-Debrid links: Settings > your Real-Debrid token.") }
        listOfNotNull(blocker, rdBlocker).forEach { Text(it, color = Colors.unknown, fontSize = 14.sp) }
        if (blocker != null || rdBlocker != null) androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = {
                // The words model lives in Known words; keys, Parakeet and Gemma in Making episodes.
                onSettings(if (!library.languages.of(lang).model.isReady) "words" else "episodes")
            }) { Text(tr("Open Settings")) }
            if (blocker != null && pc) androidx.compose.material3.OutlinedButton(onClick = {
                onPcChoice = true; library.settings.prefs.edit().putBoolean(MAKE_ON_PC, true).apply()
            }) { Text(tr("Make it on the PC")) }
        }
        // Disabled buttons stay readable (M3's 38% vanished on the dark sheet); the red line above says why.
        val offText = Colors.dim.copy(alpha = 0.6f)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    disabledContainerColor = Colors.dim.copy(alpha = 0.12f), disabledContentColor = offText),
                enabled = links.isNotEmpty() && (ready || onDevice.isEmpty()) && (pc || aniworld.isEmpty()) &&
                    !(needsRd && library.settings.rdToken.isBlank()),
                onClick = {
                    links.forEach { l ->
                        val kind = if (ProcessWorker.isYouTube(l) && ytKind != "video") ytKind
                            else if (ProcessWorker.isTikTokAccount(l)) "videos" else null
                        val toPc = l in pcLinks
                        if (follow && (kind == "shorts" || kind == "videos")) io.github.pedrubik2000.kumapie.mobile.local.Subscriptions(context).add(
                            io.github.pedrubik2000.kumapie.mobile.local.Subscriptions.Sub(l, show, kind, lang.code, 5, pc = toPc))
                        ProcessWorker.start(context, l, show.takeIf { it.isNotBlank() }, height, lang, transcriber, englishNow,
                            kind, ytCount.toIntOrNull()?.coerceIn(1, 200) ?: 10, type = type, match = matchKey, pc = toPc)
                    }
                    added(context, onDevice.isEmpty())
                    link = ""
                }) { Text(if (links.size > 1) tr("Make them episodes") else tr("Make it an episode")) }
            val filesOk = ready || onPc
            androidx.compose.material3.OutlinedButton(enabled = filesOk, onClick = { pick.launch(arrayOf("video/*")) },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(disabledContentColor = offText),
                border = if (filesOk) androidx.compose.material3.ButtonDefaults.outlinedButtonBorder(true)
                    else androidx.compose.foundation.BorderStroke(1.dp, Colors.dim.copy(alpha = 0.35f))) { Text(tr("Video files…")) }
        }
        // One plain line; the pipeline (Jimaku, Parakeet, MKV...) only for whoever asks.
        Text(if (pcOnly) tr("The PC makes it; it comes to this device on Wi-Fi when it's ready.")
            else tr("Takes a few minutes. Each episode shows up on Home when it's ready."), fontSize = 13.sp)
        var how by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(onClick = { how = !how }) { Text(tr(if (how) "Hide how it works" else "How it works")) }
        val noEnglish = englishNow == "none"
        if (!how) Unit
        else if (pcOnly) Unit
        else if (japanese) Text((if (youtube) tr("Download → the video's Japanese subtitles, else Parakeet ja on this device (free) → ")
            else tr("Download → Japanese subtitles from Jimaku, fitted to the audio → ")) + when {
                noEnglish -> tr("no %s (Settings)", translation)
                gemma -> tr("%s (Gemma on this device, about 3 s a line)", translation)
                else -> tr("%s (the device's translator)", translation)
            } + tr(" → scenes. Show blank: its " +
            "AniList name. MKV files keep their Japanese audio. Jobs run one after another in the background."),
            color = Colors.dim, fontSize = 13.sp)
        else Text(tr("Download → ") + (if (parakeet) tr("Parakeet on this device (free)") else tr("Soniox (paid, about \$0.10 an hour)")) +
            tr(" → %s subtitles → ", spoken) + when {
                noEnglish -> tr("no %s (Settings)", translation)
                gemma -> tr("%s (Gemma on this device, about 3 s a line)", translation)
                englishNow == "soniox" -> tr("%s (Soniox)", translation)
                else -> tr("%s (the device's translator)", translation)
            } + tr(" → scenes. A season (magnet) becomes one episode per file; MKV files keep their %s audio. Jobs run " +
            "one after another in the background; each episode appears on the home screen when done.", spoken), color = Colors.dim, fontSize = 13.sp)
        if (onPc) { if (how) Text(tr("On the PC: it downloads (video files are sent there first), uses the video's own subtitles, else " +
            "Jimaku's for Japanese, else transcribes it with Parakeet (free); the line under it only from subtitles. The " +
            "episode comes to this device with the PC's shows, on Wi-Fi. Jobs run there one after another."),
            color = Colors.dim, fontSize = 13.sp) }
        else if (links.any(::isAniWorld)) Text(if (pc) tr("AniWorld: the PC downloads the %s dub, transcribes it (Parakeet) and adds it to " +
            "its shows, a few minutes an episode. A season link brings the whole season, the show's own link every season.", spoken)
            else tr("AniWorld and TikTok links need the PC: Settings > server and whose device it is."), color = Colors.dim, fontSize = 13.sp)
        pcJobs.forEach { Text(it, fontSize = 14.sp) }
        // Only jobs still going: finished and failed ones (an old failure's text stayed here) are in Downloads.
        jobs.filter { it.state == "running" || it.state == "waiting" }.forEach { Text(it.stage, fontSize = 14.sp) }
    }
}

private const val MAKE_ON_PC = "make_on_pc"

/** The tap's feedback: the field clears and the job list is below the fold, so say it was added and where it shows up. */
private fun added(context: android.content.Context, onPc: Boolean) = android.widget.Toast.makeText(context,
    if (onPc) tr("Sent to the PC. Each episode comes to this device on Wi-Fi when it's done.")
    else tr("Added. Each episode appears on Home when it's done; progress is below."), android.widget.Toast.LENGTH_LONG).show()

private fun isAniWorld(link: String) = Regex("""^https://(aniworld\.to|s\.to|serienstream\.to)/""").containsMatchIn(link)

/** The PC's jobs (AniWorld, YouTube), one line each: "PC · show/staffel-2/episode-1: transcribing…". */
private suspend fun aniworldJobs(library: Library): List<String> = runCatching {
    val a = library.api.aniworldJobs()
    (0 until a.length()).map { i ->
        val j = a.getJSONObject(i)
        "PC · ${j.getString("url").substringAfter("/stream/").substringAfter("youtube.com/")}: " + when (j.getString("state")) {
            "done" -> tr("done")
            "failed" -> tr("failed: %s", j.optString("error"))
            "queued" -> tr("waiting")
            else -> j.optString("step")
        }
    }
}.getOrElse { emptyList() }
