package io.github.pedrubik2000.kumapie.data

object AnkiSource {
    const val NONE = "none"
    const val ANY = "any"
    const val KUMA3 = "kuma3"
    const val ANKIDROID = "ankidroid"
    const val DEFAULT_WORD = "Word"
    const val DEFAULT_READING = "Reading"
    val CHOICES = listOf(NONE, ANY, KUMA3, ANKIDROID)

    private const val SEP = "\u001f"
    private val KUMA3_APPS = listOf("io.github.pedrubik2000.kuma3", "com.ichi2.anki.debug")
    private val ANKIDROID_APPS = listOf("com.ichi2.anki")

    fun appFor(choice: String, installed: Collection<String>): String? {
        val wanted = when (choice) {
            KUMA3 -> KUMA3_APPS
            ANKIDROID -> ANKIDROID_APPS
            ANY -> KUMA3_APPS + ANKIDROID_APPS
            else -> emptyList()
        }
        return wanted.firstOrNull { it in installed }
    }

    fun decks(stored: String?, lang: Lang): List<String> = stored.orEmpty().split(SEP).filter { it.isNotBlank() }.ifEmpty { listOf(lang.deck) }

    fun encode(decks: List<String>): String = decks.joinToString(SEP)

    fun search(decks: List<String>): String {
        val terms = decks.map { "\"deck:${it.replace("\"", "")}\"" }
        return if (terms.size == 1) terms.first() else terms.joinToString(" or ", "(", ")")
    }

    fun reviewedSearch(decks: List<String>): String = search(decks) + " -is:new"
}
