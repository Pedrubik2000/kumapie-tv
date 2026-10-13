package io.github.pedrubik2000.kumapie.mobile.read

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubTest {
    private fun epub(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun spineOrderRubyAndEntities() {
        val book = Epub.read(epub(mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<package><metadata><dc:title>吾輩&amp;猫</dc:title></metadata>
                <manifest><item id="c2" href="text/two.xhtml"/><item id="c1" href="text/one%20a.xhtml"/><item id="css" href="s.css"/></manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/text/one a.xhtml" to """<?xml version="1.0"?><html><head><title>skip me</title><style>p{}</style></head><body>
                <h1>第一章</h1><p>吾輩は<ruby>猫<rp>(</rp><rt>ねこ</rt><rp>)</rp></ruby>である。</p><!-- <p>no</p> -->
                <p>名前は&nbsp;まだ<br/>無い。</p></body></html>""",
            "OEBPS/text/two.xhtml" to """<html><body><div><p>Zwei&#x2026;</p></div><p> </p></body></html>""",
        )))
        assertEquals("吾輩&猫", book.title)
        assertEquals(listOf("第一章", "吾輩は猫である。", "名前は まだ", "無い。", "Zwei…"), book.paragraphs)
    }
}
