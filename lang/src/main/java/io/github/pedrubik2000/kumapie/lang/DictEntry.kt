package io.github.pedrubik2000.kumapie.lang

/** One dictionary entry: a word as one part of speech, with all its senses. */
data class DictEntry(
    val word: String,
    val pos: String,
    /** The headword line. */
    val head: String,
    val ipa: String,
    val senses: List<Sense>,
    val online: Boolean = false,
    /** The Yomitan dictionary's title. */
    val dict: String = "",
)

data class Sense(val gloss: String, val tags: String, val examples: List<Pair<String, String>>)
