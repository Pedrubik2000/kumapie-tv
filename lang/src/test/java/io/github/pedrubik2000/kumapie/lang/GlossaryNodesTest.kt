package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GlossaryNodes] on small entries in the Yomitan structured-content schema (Jitendex / Daijirin / plain shapes). */
class GlossaryNodesTest {
    @Test fun plainStringAndTextItems() {
        assertEquals(listOf("hello"), GlossaryNodes.build("hello").map { it.text })
        assertEquals(listOf("a"), GlossaryNodes.build("""["a"]""").map { it.text })
        // Several items are numbered.
        assertEquals(listOf("1. a", "2. b"), GlossaryNodes.build("""["a","b"]""").map { it.text })
    }

    @Test fun listsAndPills() {
        val raw = """[{"type":"structured-content","content":[
            {"tag":"span","data":{"content":"partOfSpeech"},"content":"noun"},
            {"tag":"ul","data":{"content":"glossary"},"content":[{"tag":"li","content":"to eat"},{"tag":"li","content":"to live"}]}]}]"""
        val blocks = GlossaryNodes.build(raw)
        assertEquals(listOf("noun", "• to eat", "• to live"), blocks.map { it.text })
        assertTrue(blocks[0].runs[0].pill)
        assertEquals(0, blocks[1].indent)
    }

    @Test fun orderedAndNestedLists() {
        val raw = """[{"type":"structured-content","content":{"tag":"ol","content":[
            {"tag":"li","content":["one",{"tag":"ul","content":{"tag":"li","content":"sub"}}]},{"tag":"li","content":"two"}]}}]"""
        val b = GlossaryNodes.build(raw)
        assertEquals(listOf("1. one", "• sub", "2. two"), b.map { it.text })
        assertEquals(listOf(0, 1, 0), b.map { it.indent })
    }

    @Test fun rubyBecomesBracketedReading() {
        val raw = """[{"type":"structured-content","content":{"tag":"ruby","content":["食",{"tag":"rp","content":"("},{"tag":"rt","content":"た"},{"tag":"rp","content":")"},"べる"]}}]"""
        assertEquals("食[た]べる", GlossaryNodes.build(raw).single().text)
    }

    @Test fun styleBoldItalicSmallAndNestedDivs() {
        val raw = """[{"type":"structured-content","content":{"tag":"div","content":[
            {"tag":"div","content":{"tag":"span","style":{"fontWeight":"bold","fontSize":"0.8em"},"content":"head"}},
            {"tag":"div","content":[{"tag":"span","style":{"fontStyle":"italic"},"content":"note"},{"tag":"br"},"tail"]}]}}]"""
        val b = GlossaryNodes.build(raw)
        assertEquals(listOf("head", "note\ntail"), b.map { it.text })
        assertTrue(b[0].runs[0].bold); assertEquals(0.8f, b[0].runs[0].size, 0.001f)
        assertTrue(b[1].runs[0].italic)
    }

    @Test fun tableFlattenedToRowsAndImageTitles() {
        val raw = """[{"type":"structured-content","content":[{"tag":"table","content":[
            {"tag":"tr","content":[{"tag":"th","content":"a"},{"tag":"th","content":"b"}]},
            {"tag":"tr","content":[{"tag":"td","content":"1"},{"tag":"td","content":"2"}]}]},
            {"tag":"div","content":[{"tag":"img","path":"x.png","title":"漢"},{"type":"image","path":"y.png"}]}]}]"""
        val b = GlossaryNodes.build(raw)
        assertEquals(listOf("a   b", "1   2", "漢"), b.map { it.text })
        assertTrue(b[0].runs[0].bold)
    }

    @Test fun formOfAndUnknownTagsAndBadJson() {
        assertEquals("past of 食べる", GlossaryNodes.build("""[["食べる",["past"]]]""").single().text)
        assertEquals("kept", GlossaryNodes.build("""[{"type":"structured-content","content":{"tag":"blink","content":"kept"}}]""").single().text)
        assertEquals("[not json", GlossaryNodes.build("[not json").single().text)
    }

    @Test fun hugeAndDeepEntriesAreCapped() {
        var deep = "\"x\""
        repeat(500) { deep = """{"tag":"span","content":$deep}""" }
        assertTrue(GlossaryNodes.build("[$deep]").isEmpty())
        val rows = (1..2000).joinToString(",") { """{"tag":"li","content":"row $it with some text to fill the entry up"}""" }
        val b = GlossaryNodes.build("""[{"type":"structured-content","content":{"tag":"ul","content":[$rows]}}]""")
        assertTrue(b.size <= GlossaryNodes.MAX_BLOCKS + 1) // + the "…" line
        assertTrue(b.sumOf { it.text.length } <= GlossaryNodes.MAX_CHARS + 100)
    }

    @Test fun pillIsOneRunAndDoesNotSpread() {
        val raw = """[{"type":"structured-content","content":[
            {"tag":"span","data":{"content":"tag"},"content":[{"tag":"span","content":"vt"},{"tag":"span","content":"vi"}]},
            {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"div","content":[{"tag":"ruby","content":["例",{"tag":"rt","content":"れい"}]},"文"]}}]}]"""
        val b = GlossaryNodes.build(raw)
        assertEquals(1, b[0].runs.size); assertEquals("vtvi", b[0].runs[0].text); assertTrue(b[0].runs[0].pill)
        assertEquals("例[れい]文", b[1].text)
        assertTrue(b[1].runs.none { it.pill })
    }

    @Test fun cardBudgetStopsWithEllipsisAndTextTakesOnlyTheRoom() {
        val budget = GlossaryNodes.Budget(chars = 25, blocks = 400)
        val a = GlossaryNodes.build("""["${"x".repeat(100)}"]""", budget)
        assertEquals("x".repeat(25), a.first().text.take(25)); assertEquals("…", a.last().text)
        assertEquals(26, a.sumOf { it.text.length })
        assertEquals(5, GlossaryNodes.build("a".repeat(5) + "[", GlossaryNodes.Budget(5, 5)).first().text.length)
    }

    @Test fun listItemStartingWithBlockKeepsItsBullet() {
        val raw = """[{"type":"structured-content","content":{"tag":"ul","content":{"tag":"li","content":{"tag":"div","content":"text"}}}}]"""
        assertEquals(listOf("• text"), GlossaryNodes.build(raw).map { it.text })
    }

    @Test fun nonFiniteSizesIgnored() {
        val raw = """[{"type":"structured-content","content":{"tag":"span","style":{"fontSize":"NaNem"},"content":"t"}}]"""
        assertEquals(1f, GlossaryNodes.build(raw).single().runs[0].size, 0f)
    }
}
