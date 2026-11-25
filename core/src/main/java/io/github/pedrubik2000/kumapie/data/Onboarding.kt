package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import io.github.pedrubik2000.kumapie.i18n.tr

object Onboarding {
    const val DONE = "onb_done"
    const val COPIED = "onb_copied"
    const val HUB = "onb_hub"

    const val LANGUAGE = "language"
    const val MAIN = "main"
    const val ANILIST = "anilist"
    const val VIDEOS = "videos"
    const val COLOURING = "colouring"
    const val DOWNLOADS = "downloads"
    val STEPS = listOf(LANGUAGE, MAIN, ANILIST, VIDEOS, COLOURING, DOWNLOADS)
    val FROM_PC = setOf(ANILIST, VIDEOS, COLOURING, DOWNLOADS)

    enum class State { DONE, COPIED, NEEDED, OPTIONAL, PC }

    data class Item(val step: String, val label: String, val state: State, val sub: String)

    private fun applies(step: String, learningJa: Boolean) = step != COLOURING || learningJa

    fun next(done: Set<String>, copied: Set<String>, learningJa: Boolean): String? =
        STEPS.firstOrNull { it !in done && it !in copied && applies(it, learningJa) }

    fun counted(learningJa: Boolean): List<String> = STEPS.filter { it != LANGUAGE && applies(it, learningJa) }

    fun list(done: Set<String>, copied: Set<String>, learningJa: Boolean, detail: Map<String, String> = emptyMap(), current: String? = null, pc: Boolean = false): List<Item> =
        STEPS.filter { applies(it, learningJa) }.map { s ->
            val state = when {
                pc && s in FROM_PC -> State.PC
                s in copied -> State.COPIED
                s in done -> State.DONE
                s == MAIN || s == COLOURING || s == DOWNLOADS -> State.OPTIONAL
                else -> State.NEEDED
            }
            val sub = if (s == current && s in detail) detail.getValue(s) else when (state) {
                State.COPIED -> tr("copied")
                State.DONE -> detail[s] ?: tr("Done")
                State.OPTIONAL -> tr("Optional")
                State.PC -> tr("from the PC")
                State.NEEDED -> tr("Needed")
            }
            Item(s, label(s), state, sub)
        }

    private fun label(step: String) = when (step) {
        LANGUAGE -> tr("Language")
        MAIN -> tr("Main server")
        ANILIST -> tr("AniList")
        VIDEOS -> tr("Videos")
        COLOURING -> tr("Word colouring")
        else -> tr("Downloads")
    }

    fun done(prefs: SharedPreferences): Set<String> = prefs.getStringSet(DONE, emptySet()).orEmpty()
    fun copied(prefs: SharedPreferences): Set<String> = prefs.getStringSet(COPIED, emptySet()).orEmpty()

    fun markDone(prefs: SharedPreferences, step: String) =
        prefs.edit().putStringSet(DONE, done(prefs) + step).apply()

    fun markCopied(prefs: SharedPreferences, steps: Collection<String>) =
        prefs.edit().putStringSet(COPIED, copied(prefs) + steps).apply()

    fun startFresh(prefs: SharedPreferences) =
        prefs.edit().putStringSet(DONE, done(prefs) + MAIN).remove(COPIED).apply()

    fun reset(prefs: SharedPreferences) = prefs.edit().remove(DONE).remove(COPIED).apply()
}
