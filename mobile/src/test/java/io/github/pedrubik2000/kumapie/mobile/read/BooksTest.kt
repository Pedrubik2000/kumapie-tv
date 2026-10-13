package io.github.pedrubik2000.kumapie.mobile.read

import org.junit.Assert.assertEquals
import org.junit.Test

class BooksTest {
    @Test fun paragraphsAreNonBlankLines() {
        assertEquals(listOf("Eins.", "Zwei."), Books.paragraphs("\uFEFFEins.\r\n\r\n  Zwei.  \n"))
    }

    @Test fun seriesFromVolumeTitles() {
        assertEquals("Hagure Idol Jigokuhen", Books.seriesOf("Hagure Idol Jigokuhen Volume 07"))
        assertEquals("Volume 07", Books.volumeOf("Hagure Idol Jigokuhen Volume 07"))
        assertEquals("hagure_idol", Books.seriesOf("hagure_idol 第01巻"))
        assertEquals("Jujutsu Kaisen", Books.seriesOf("Jujutsu Kaisen 30"))
        assertEquals("Jujutsu Kaisen", Books.seriesOf("Jujutsu Kaisen vol.3"))
        assertEquals("第01巻", Books.seriesOf("第01巻")) // nothing but a volume: its own series
        assertEquals("1984", Books.seriesOf("1984"))
    }

    @Test fun sentenceAroundAWord() {
        val de = "Ich rufe an. Er sagt: „Ja!“ Dann geht er"
        assertEquals("Ich rufe an.", Books.sentenceAt(de, 4))
        assertEquals("Dann geht er", Books.sentenceAt(de, de.length - 2))
        assertEquals("名前はまだ無い。", Books.sentenceAt("吾輩は猫である。名前はまだ無い。", 9))
        assertEquals("「行くよ！」", Books.sentenceAt("「行くよ！」と言った。", 2))
    }

    @Test fun pageHasEveryWordAsASpan() {
        val colors = listOf("#000000", "#ffffff", "#00ff00", "#0000ff", "#ff0000", "#333333")
        val html = BookPage.html(100, listOf("a<b"), listOf(listOf(Piece("a", "a"), Piece("<", null), Piece("b", "b\""))),
            { if (it == "a") "k" else "l" }, vertical = true, start = 100, atEnd = false, colors = colors)
        assert("vertical-rl" in html && "goToParagraph(100);" in html && '$' !in html)
        assert("<span class=\"w k\" data-p=\"100\" data-i=\"0\" data-k=\"a\">a</span>&lt;" in html)
        assert("<span class=\"w u\" data-p=\"100\" data-i=\"2\" data-k=\"b&quot;\">b</span>" in html) // "l" (learning) shows as unknown
    }

    @Test fun piecesCoverTheWholeText() {
        val text = "今日は、いい天気。"
        val pieces = Books.fill(text, listOf(Triple(0, "今日", "今日"), Triple(2, "は", "は"), Triple(4, "いい", "いい"),
            Triple(6, "天気", "天気"), Triple(5, "い", "い"))) // an overlapping token is dropped
        assertEquals(text, pieces.joinToString("") { it.text })
        assertEquals(listOf(null, null), pieces.filter { it.text == "、" || it.text == "。" }.map { it.key })
        assertEquals(listOf("今日", "は", "いい", "天気"), pieces.mapNotNull { it.key })
    }
}
