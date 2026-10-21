package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Cue
import io.github.pedrubik2000.kumapie.data.Segment
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.data.unknownIn
import org.junit.Assert.assertEquals
import org.junit.Test

/** Scene levels by card colours (plan step 5): red words count, each once; Japanese by dictionary form. */
class LevelTest {
    private fun cue(vararg segs: Pair<String, String?>) = Cue(0.0, 1.0, segs.map { Segment(it.first, it.second) })

    @Test fun spaced() {
        val words = mapOf(
            "gehst" to Word("k", "", card = "u"),          // known by stability, but no card of its own: red
            "tante" to Word("u", "", card = "k"),          // studied card: white
            "hier" to Word("u", "", card = "d"),           // due card: green
            "neu" to Word("u", "", card = "b"),            // mined, never reviewed: blue, still counts
            "ja" to Word("u", "", card = "u", marked = true), // marked known: white
            "alt" to Word("u", ""),                        // no card states (old Anki): never studied
        )
        val c = cue("Gehst" to "gehst", " " to null, "gehst" to "gehst", "Tante" to "tante", "hier" to "hier", "neu" to "neu", "ja" to "ja", "alt" to "alt")
        assertEquals(3, unknownIn(listOf(c), words)) // gehst (once), neu, alt
    }

    @Test fun japanese() {
        val words = mapOf("届く" to Word("u", "", card = "k", forms = mapOf("届いた" to "u", "届く" to "k", "届かない" to "b")))
        val c = cue("届いた" to "届く", "届く" to "届く", "届かない" to "届く", "届いた" to "届く")
        assertEquals(0, unknownIn(listOf(c), words)) // all coloured by 届く's card
        // No card for 躱す: a card mined as 躱した colours only that form; the rest counts once.
        val noLemma = mapOf("躱す" to Word("u", "", card = "u", forms = mapOf("躱した" to "k", "躱し" to "u", "躱す" to "u")))
        assertEquals(1, unknownIn(listOf(cue("躱した" to "躱す", "躱し" to "躱す", "躱す" to "躱す")), noLemma))
    }
}
