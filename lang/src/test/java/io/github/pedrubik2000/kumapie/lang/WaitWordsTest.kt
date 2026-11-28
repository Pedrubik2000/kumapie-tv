package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.EpisodeDetail
import io.github.pedrubik2000.kumapie.data.Word
import io.github.pedrubik2000.kumapie.lang.EpisodeBuilder.Cue
import io.github.pedrubik2000.kumapie.lang.JapaneseModel.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitWordsTest {
    private fun token(base: String, begin: Int) = Token(base, base, "", "名詞", begin)

    private fun episode(states: Map<String, String>): EpisodeDetail {
        val texts = listOf("告白は手作り", "幼なじみと告白", "照れる", "告白と手作りと誕生日", "ない")
        val words = listOf(
            listOf(token("告白", 0), token("は", 2), token("手作り", 3)),
            listOf(token("幼なじみ", 0), token("と", 4), token("告白", 5)),
            listOf(token("照れる", 0)),
            listOf(token("告白", 0), token("と", 2), token("手作り", 3), token("と", 6), token("誕生日", 7)),
            listOf(token("ない", 0)),
        )
        val cues = texts.mapIndexed { i, t -> Cue(i * 3.0, i * 3.0 + 2, t) }
        val json = EpisodeBuilder.build("ep", "Show", "1", 30.0, "", cues, emptyList(), words)
        val ep = Api("").parseEpisode(json.toString())
        return ep.copy(words = ep.words.mapValues { (k, w) -> cardWord(w, states[k] ?: "k") })
    }

    private fun cardWord(w: Word, card: String) = w.copy(card = card)

    @Test fun mostHeardNewAndDueWordsFirstMinedAfterKnownAndParticlesNever() {
        val ep = episode(mapOf("告白" to "u", "手作り" to "u", "幼なじみ" to "d", "照れる" to "b", "誕生日" to "d", "は" to "u", "と" to "u", "ない" to "u"))
        val picked = WaitPick.pick(ep)
        assertEquals(listOf("告白", "手作り", "幼なじみ", "誕生日", "照れる"), picked.rows.map { it.word })
        assertEquals(listOf(WaitState.NEW, WaitState.NEW, WaitState.DUE, WaitState.DUE, WaitState.MINED), picked.rows.map { it.state })
    }

    @Test fun knownWordsAreLeftOutAndTheListIsCapped() {
        val ep = episode(mapOf("告白" to "k", "手作り" to "u", "幼なじみ" to "u", "照れる" to "u", "誕生日" to "u"))
        val picked = WaitPick.pick(ep, max = 2)
        assertEquals(listOf("手作り", "幼なじみ"), picked.rows.map { it.word })
        assertFalse(picked.rows.any { it.word == "告白" })
    }

    @Test fun tiesGoToTheWordHeardFirstAndTheTapPointsAtItsLine() {
        val picked = WaitPick.pick(episode(mapOf("幼なじみ" to "u", "照れる" to "u")))
        assertEquals(listOf("幼なじみ", "照れる"), picked.rows.map { it.word })
        assertEquals("幼なじみと告白", picked.rows[0].text)
        assertEquals(0, picked.rows[0].offset)
        assertEquals(4, picked.rows[0].length)
    }

    @Test fun summaryCountsEveryNewAndDueWord() {
        val picked = WaitPick.pick(episode(mapOf("告白" to "u", "手作り" to "l", "幼なじみ" to "d", "照れる" to "b")), max = 1)
        assertEquals(1, picked.rows.size)
        assertEquals(2, picked.newCount)
        assertEquals(1, picked.dueCount)
    }

    @Test fun shortKanaWordsAreNotWorthShowing() {
        assertFalse(WaitPick.worthy("ない"))
        assertFalse(WaitPick.worthy("それ"))
        assertTrue(WaitPick.worthy("照れる"))
        assertTrue(WaitPick.worthy("犬"))
        assertTrue(WaitPick.worthy("ありがとう"))
    }
}

class WaitNamesAndTitleTest {
    private fun token(base: String, begin: Int) = JapaneseModel.Token(base, base, "", "名詞", begin)

    private fun episode(): io.github.pedrubik2000.kumapie.data.EpisodeDetail {
        val texts = listOf("告白と手作り", "エリスが告白", "照れる", "告白と誕生日", "守る", "告白と守る")
        val starts = listOf(10.0, 60.0, 170.0, 179.9, 180.0, 900.0)
        val words = listOf(
            listOf(token("告白", 0), token("手作り", 3)),
            listOf(token("エリス", 0), token("告白", 4)),
            listOf(token("照れる", 0)),
            listOf(token("告白", 0), token("誕生日", 3)),
            listOf(token("守る", 0)),
            listOf(token("告白", 0), token("守る", 3)),
        )
        val cues = texts.mapIndexed { i, t -> EpisodeBuilder.Cue(starts[i], starts[i] + 2, t) }
        val ep = io.github.pedrubik2000.kumapie.data.Api("").parseEpisode(EpisodeBuilder.build("ep", "S", "1", 1000.0, "", cues, emptyList(), words).toString())
        return ep.copy(words = ep.words.mapValues { (_, w) -> w.copy(card = "u") })
    }

    @Test fun namesAreSkippedAndNotCounted() {
        val isName = { w: String -> w == "エリス" }
        assertTrue(WaitPick.pick(episode()).rows.any { it.word == "エリス" })
        assertFalse(WaitPick.pick(episode(), isName = isName).rows.any { it.word == "エリス" })
        assertFalse(WaitPick.pick(episode(), max = 2, isName = { it == "告白" }).rows.any { it.word == "告白" })
        assertTrue(WaitPick.katakanaOnly("エリス"))
        assertFalse(WaitPick.katakanaOnly("エリス君"))
        assertFalse(WaitPick.katakanaOnly("ありがとう"))
    }

    @Test fun theEpisodeTitleIsHiddenUntilWatchedAndOnlyWithTheSettingOn() {
        assertTrue(WaitPick.hideTitle(true, emptySet(), "kitsu:1:9"))
        assertFalse(WaitPick.hideTitle(true, setOf("kitsu:1:9"), "kitsu:1:9"))
        assertFalse(WaitPick.hideTitle(false, emptySet(), "kitsu:1:9"))
    }

    @Test fun theSummaryNamesTheWindowAndItsCounts() {
        assertEquals("2 new · 1 due today", waitSummary(WaitWords(emptyList(), 2, 1), false))
        assertEquals("Looking for the words in the subtitles…", waitSummary(null, false))
        assertEquals("No Japanese subtitles for this episode", waitSummary(null, true))
    }

    @org.junit.Test fun theLastEpisodesWordsComeBackUntilKnown() {
        val rows = listOf(WaitWord("一級魔法使い", WaitState.NEW, "いっきゅうまほうつかい", "first-class mage"), WaitWord("試験", WaitState.DUE, "しけん", "exam | test"),
            WaitWord("杖", WaitState.MINED, "つえ", "staff, cane"), WaitWord("名前", WaitState.NEW, "", ""))
        val kept = requireNotNull(WaitLast.decode(WaitLast.encode(26, "kitsu:46474:26", rows)))
        org.junit.Assert.assertEquals(26, kept.number)
        org.junit.Assert.assertEquals("kitsu:46474:26", kept.id)
        org.junit.Assert.assertEquals(rows.map { it.word }, kept.rows.map { it.word })
        org.junit.Assert.assertEquals("exam / test", kept.rows[1].gloss)
        val now = mapOf("一級魔法使い" to "b", "試験" to "k", "杖" to "d", "名前" to "u")
        val still = WaitLast.still(kept.rows) { now[it] }
        org.junit.Assert.assertEquals(listOf("一級魔法使い", "杖", "名前"), still.map { it.word })
        org.junit.Assert.assertEquals(listOf(WaitState.MINED, WaitState.DUE, WaitState.NEW), still.map { it.state })
        org.junit.Assert.assertEquals(1, WaitLast.still(kept.rows, max = 1) { "u" }.size)
        org.junit.Assert.assertNull(WaitLast.decode("not a file"))
        org.junit.Assert.assertNull(WaitLast.decode("26"))
        org.junit.Assert.assertNull(requireNotNull(WaitLast.decode(WaitLast.encode(null, "tt123", rows))).number)
    }

    @org.junit.Test fun oneFileASeason() {
        org.junit.Assert.assertEquals("kitsu:46474", WaitLast.season("kitsu:46474:27"))
        org.junit.Assert.assertEquals("kitsu:46474", WaitLast.season("kitsu:46474"))
        org.junit.Assert.assertEquals("tt123:2", WaitLast.season("tt123:2:5"))
        org.junit.Assert.assertEquals("tt123", WaitLast.season("tt123"))
        org.junit.Assert.assertEquals("wait_words/kitsu_46474.txt", WaitLast.file(java.io.File("d"), "kitsu:46474:27").path.replace('\\', '/').removePrefix("d/"))
    }
}
