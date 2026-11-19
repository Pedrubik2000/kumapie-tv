package io.github.pedrubik2000.kumapie.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.ui.PlayerView
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import io.github.pedrubik2000.kumapie.lang.KnownWords
import io.github.pedrubik2000.kumapie.lang.Miner
import io.github.pedrubik2000.kumapie.player.SceneController
import io.github.pedrubik2000.kumapie.player.WordPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val RATINGS = listOf("Again", "Hard", "Good", "Easy")

val RATEABLE = setOf("d", "b", "k")

class TvRating {
    val moves = ArrayList<Pair<KnownWords.Rated, Miner.Request>>()
    var open by mutableStateOf(false)
    var last by mutableStateOf<Pair<String, KnownWords.Rated>?>(null)
    var fresh by mutableStateOf(false)
    var said by mutableStateOf("")
    var since = 0L
    var busy = false
    var today by mutableStateOf(0)
        private set
    fun count(settings: Settings, n: Int) {
        val day = "rated_" + io.github.pedrubik2000.kumapie.lang.Progress.today()
        today = settings.prefs.getInt(day, 0) + n
        settings.prefs.edit().putInt(day, today).apply()
    }
}

fun ownCards(context: Context, settings: Settings, lang: Lang): Boolean =
    TvMining.languages(context, settings).of(lang).known.ankiApp() == io.github.pedrubik2000.kumapie.lang.SrsCards.PKG

fun pickedCard(ctl: SceneController, picker: WordPicker): Pair<String, String?>? {
    val segment = picker.selected ?: return null
    val key = segment.word ?: return null
    val w = ctl.words[key] ?: return null
    val own = w.formCard(segment.text)
    return if (own != null) segment.text to own else key to w.card.takeUnless { w.marked }
}

fun rateWord(context: Context, api: io.github.pedrubik2000.kumapie.data.Backend, settings: Settings, ctl: SceneController, picker: WordPicker, scope: CoroutineScope, rating: TvRating, ease: Int, view: PlayerView? = null) {
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    rating.open = false
    val (form, state) = pickedCard(ctl, picker) ?: return
    if (state !in RATEABLE) return say(tr("This word has no card to rate"))
    if (ctl.episode.srs) {
        if (!picker.cardOpen) return say(tr("See its meaning first"))
        if (rating.busy) return
        rating.busy = true
        scope.launch {
            runCatching { (api as Api).srsRate(form, ease, scene = ctl.scene.source) }.onSuccess { c ->
                setCard(ctl, form, c)
                rating.last = form to KnownWords.Rated(emptyList(), 1, PC_CARDS)
                rating.said = tr(RATINGS[ease - 1])
                rating.fresh = true
                rating.count(settings, 1)
                picker.right()
            }.onFailure { say(it.message ?: it.toString()) }
            rating.busy = false
        }
        return
    }
    if (TvMining.reading) return say(tr("Still reading your cards: try again in a moment"))
    if (!picker.cardOpen) return say(tr("See its meaning first"))
    if (rating.busy) return
    val language = TvMining.languages(context, settings).of(ctl.lang)
    val ms = System.currentTimeMillis() - rating.since
    val line = picker.line
    val surface = picker.selected?.text.orEmpty()
    val scene = ctl.scene
    rating.busy = true
    scope.launch {
        try {
            val done = language.known.rate(form, ease, ms, io.github.pedrubik2000.kumapie.lang.Srs.Watch(ctl.episode.id, ctl.scene.ms, settings.subtitles.name.lowercase(), null, ctl.speed.toDouble()))
            if (done == null) say(tr("The rating didn't go in."))
            else {
                val frame = runCatching { screenFrame(view, context.cacheDir, "rated-${System.nanoTime()}.webp") }.getOrNull()
                rating.moves += done to Miner.Request(ctl.episode, scene, line, Miner.Word(surface, form, null, ""), frame)
                rating.last = form to done
                rating.said = tr(RATINGS[ease - 1])
                rating.fresh = true
                rating.count(settings, done.cards)
                repaint(language, ctl)
                picker.right()
            }
        } catch (e: Exception) {
            say(e.message ?: e.toString())
        } finally {
            rating.busy = false
        }
    }
}

fun undoRating(context: Context, api: io.github.pedrubik2000.kumapie.data.Backend, settings: Settings, ctl: SceneController, scope: CoroutineScope, rating: TvRating) {
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    rating.open = false
    val (form, rated) = rating.last ?: return say(tr("Nothing to undo"))
    if (rating.busy) return
    if (rated.undoLabel == PC_CARDS) {
        rating.busy = true
        scope.launch {
            runCatching { (api as Api).srsUndo(scene = ctl.scene.source) }.onSuccess { (word, c) ->
                setCard(ctl, word, c)
                rating.last = null
                rating.fresh = false
                rating.count(settings, -1)
                say(tr("Undone: %s is due again", word))
            }.onFailure { say(it.message ?: it.toString()) }
            rating.busy = false
        }
        return
    }
    val language = TvMining.languages(context, settings).of(ctl.lang)
    rating.busy = true
    scope.launch {
        try {
            if (language.known.undo(rated)) {
                rating.moves.removeAll { it.first === rated }
                rating.last = null
                rating.fresh = false
                rating.count(settings, -rated.cards)
                language.known.refreshDue(force = true)
                repaint(language, ctl)
                say(tr("Undone: %s is due again", form))
            } else say(tr("Couldn't undo it (something else was done since?)."))
        } catch (e: Exception) {
            say(e.message ?: e.toString())
        } finally {
            rating.busy = false
        }
    }
}

private const val PC_CARDS = "pc"

private fun setCard(ctl: SceneController, word: String, c: String) {
    ctl.words[word]?.let { w -> ctl.words[word] = w.copy(card = c, status = if (c == "k" || c == "d" || w.marked) "k" else "u") }
}

internal fun mineOnPc(context: Context, api: Api, ctl: SceneController, picker: WordPicker, scope: CoroutineScope, key: String) {
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    val w = ctl.words[key]
    if (w?.card != null && w.card != "u") return say(tr("Already in your reviews"))
    val scene = ctl.scene
    val line = picker.line
    val meaning = scene.def(line, key)?.english?.takeIf { it.isNotBlank() } ?: (scene.meanings[key] ?: w?.meaning).orEmpty()
    scope.launch {
        runCatching { api.srsAdd(scene.source, scene.sourceLineOf(line), key, meaning) }
            .onSuccess { c -> setCard(ctl, key, c); say(tr("Added to your reviews")) }
            .onFailure { say(it.message ?: it.toString()) }
    }
}
