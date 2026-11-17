package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.lang.EpisodeBuilder.Cue
import io.github.pedrubik2000.kumapie.lang.JapaneseModel.Token
import org.junit.Assert.assertEquals
import org.junit.Test

/** newepisode.py's self-check, against the Kotlin builder: the same episode JSON. */
class EpisodeBuilderTest {
    @Test fun slicesOfOneLineAreOne() = assertEquals(listOf(0, 1, 3) to listOf(Cue(1.0, 3.0, "a"), Cue(1.5, 2.5, "b"), Cue(3.04, 4.0, "a")),
        EpisodeBuilder.unsplit(listOf(Cue(1.0, 2.0, "a"), Cue(1.5, 2.5, "b"), Cue(2.0, 3.0, "a"), Cue(3.04, 4.0, "a"))))

    @Test fun anEpisode() {
        val ep = EpisodeBuilder.build("ep", "Show", "1", 30.0, "",
            listOf(Cue(1.0, 2.5, "ねこ"), Cue(2.6, 4.0, "いぬ だ"), Cue(20.0, 21.0, "♪ うた"), Cue(20.0, 22.0, "♪ うた")),
            listOf(Cue(1.1, 2.4, "A cat"), Cue(2.7, 3.9, "It's a dog (barks)"), Cue(4.5, 5.0, "Hm")),
            listOf(listOf(Token("ねこ", "猫", "", "名詞", 0)), listOf(Token("いぬ", "犬", "", "名詞", 0), Token(" ", " ", "", "空白", 2), Token("だ", "だ", "", "助動詞", 3)),
                emptyList(), emptyList()))
        val sc = ep.getJSONArray("scenes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf(1, 1, 2), sc.map { it.getJSONArray("cues").length() }) // shown together: one line
        assertEquals(listOf(0, 1, 2), sc.map { it.getInt("i") })
        assertEquals(listOf(1.0 to 2.5, 2.6 to 4.0, 20.0 to 22.0), sc.map { it.getDouble("start") to it.getDouble("end") })
        assertEquals(listOf(listOf("A cat"), listOf("It's a dog", "Hm"), emptyList()),
            sc.map { s -> s.getJSONArray("english").let { a -> (0 until a.length()).map { a.getJSONArray(it).getString(2) } } })
        assertEquals("""[["いぬ","犬"],[" ",null],["だ","だ"]]""", sc[1].getJSONArray("cues").getJSONObject(0).getJSONArray("seg").toString())
        assertEquals(setOf("猫", "犬", "だ"), ep.getJSONObject("words").keys().asSequence().toSet())
        assertEquals(3, sc.map { it.getString("id") }.toSet().size)
    }
}
