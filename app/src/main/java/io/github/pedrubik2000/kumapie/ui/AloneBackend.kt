package io.github.pedrubik2000.kumapie.ui

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun joinNames(context: Context, settings: Settings, episode: io.github.pedrubik2000.kumapie.data.EpisodeDetail): io.github.pedrubik2000.kumapie.data.EpisodeDetail {
    val language = TvMining.languages(context, settings).of(episode.lang)
    return withContext(Dispatchers.Default) { language.joinNames(episode) }
}

class AloneBackend(private val context: Context, private val settings: Settings, private val lang: String = "ja") : io.github.pedrubik2000.kumapie.data.Backend {
    private val progress = io.github.pedrubik2000.kumapie.lang.Progress.of(context)

    override suspend fun progress(episode: String, pos: Double, watched: Double, ranges: List<io.github.pedrubik2000.kumapie.data.Span>, done: Boolean) =
        withContext(Dispatchers.IO) { progress.record(episode, pos, watched, lang, ranges, done) }

    override suspend fun lookup(word: String, scene: String): Int = withContext(Dispatchers.IO) { progress.lookup(word) }

    override suspend fun markKnown(word: String, known: Boolean, scene: String): String = withContext(Dispatchers.IO) {
        val language = TvMining.languages(context, settings).of(lang)
        progress.mark(language.markKey(word), known)
        language.known.mark(word, known)
    }

    override fun wordAudio(surface: String, lang: String): String = TvMining.recording(context, settings, surface, lang) ?: "tts:$surface"

    override fun speak(text: String) {
        TvMining.languages(context, settings).of(lang).voice.speak(text)
    }

    override fun definitionAudio(scene: String, line: Int, word: String) = ""
}
