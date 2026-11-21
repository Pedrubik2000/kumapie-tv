package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Backend
import io.github.pedrubik2000.kumapie.data.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NoPcBackend internal constructor(
    private val progress: Progress,
    private val lang: String,
    private val markKey: (String) -> String,
    private val markList: (String, Boolean) -> String,
    private val audio: (String, String) -> String,
    private val say: (String) -> Unit,
    private val changed: () -> Unit,
) : Backend {
    constructor(progress: Progress, lang: String, languageOf: (String) -> Language, audio: (surface: String, lang: String) -> String,
                say: (String) -> Unit, changed: () -> Unit = {}) :
        this(progress, lang, { languageOf(it).markKey(it) }, { w, k -> languageOf(w).known.mark(w, k) }, audio, say, changed)

    override suspend fun progress(episode: String, pos: Double, watched: Double, ranges: List<Span>, done: Boolean) =
        withContext(Dispatchers.IO) { progress.record(episode, pos, watched, lang, ranges, done); changed() }

    override suspend fun lookup(word: String, scene: String): Int = withContext(Dispatchers.IO) { progress.lookup(word).also { changed() } }

    override suspend fun markKnown(word: String, known: Boolean, scene: String): String = withContext(Dispatchers.IO) {
        progress.mark(markKey(word), known)
        markList(word, known).also { changed() }
    }

    override fun wordAudio(surface: String, lang: String): String = audio(surface, lang)

    override fun speak(text: String) = say(text)

    override fun definitionAudio(scene: String, line: Int, word: String) = ""
}
