package io.github.pedrubik2000.kumapie.data

/**
 * What the player needs from the server while an episode plays. [Api] talks to the PC directly; the phone app
 * wraps it so a downloaded episode plays from local files and its reports wait in a queue while offline.
 */
interface Backend {
    /** Where the episode was left, scenes watched to their end, and seconds watched since the last report. */
    suspend fun progress(episode: String, pos: Double, seen: Collection<String>, watched: Double)

    /** A word looked up; answers how many times it has been looked up. */
    suspend fun lookup(word: String, scene: String): Int

    /** Marks a word known without a card (or undoes it); answers its status now: "k", "l" or "u". */
    suspend fun markKnown(word: String, known: Boolean): String

    /** The word read aloud: a URL or a local file path the media player can open. */
    fun wordAudio(surface: String): String

    /** A line's German definition read aloud. */
    fun definitionAudio(scene: String, line: Int, word: String): String
}
