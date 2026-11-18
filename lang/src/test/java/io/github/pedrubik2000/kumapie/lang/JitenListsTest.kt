package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JitenListsTest {
    @Test fun picksTheTickedLists() {
        val file = sequenceOf("# comment", "particles\tは", "counters\tページ", "grammar\tられる", "bad line", "particles\t")
        assertEquals(setOf("は", "られる"), Jiten.lists(file, setOf("particles", "grammar")))
    }

    /** The published file (wordlists/ja.tsv) has every list the app offers. */
    @Test fun repoFileHasEveryList() {
        val file = generateSequence(File("..").absoluteFile) { it.parentFile }.map { File(it, "wordlists/ja.tsv") }.firstOrNull { it.exists() }
            ?: return // not in the repo (a copy of the module elsewhere)
        for (slug in Jiten.SETS.keys) assertTrue(slug, file.useLines { Jiten.lists(it, setOf(slug)) }.isNotEmpty())
    }
}
