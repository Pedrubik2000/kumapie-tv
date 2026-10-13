package io.github.pedrubik2000.kumapie.mobile.read

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MangaTest {
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
    }.toByteArray()

    @Test fun mokuroBlocksKeepTheirTextAndPagesFindTheirImages() {
        val mokuro = """{"title": "Test", "volume": "1", "pages": [
            {"img_path": "vol/002.jpg", "img_width": 800, "img_height": 1200, "blocks": [
                {"box": [570, 140, 670, 460], "vertical": true, "font_size": 40, "lines": ["吾輩は", "猫である。"]},
                {"box": [1, 1, 2, 2], "vertical": false, "lines": [" "]}]},
            {"img_path": "vol/001.jpg", "img_width": 800, "img_height": 1200, "blocks": [
                {"box": [80, 860, 420, 940], "vertical": false, "font_size": 34, "lines": ["名前はまだ無い。"]}]}]}"""
        val v = Manga.read(zip(mapOf("Test/vol.mokuro" to mokuro.toByteArray(), "Test/vol/001.jpg" to byteArrayOf(1),
            "Test/vol/002.jpg" to byteArrayOf(2))))
        assertEquals("Test 1", v.title)
        assertEquals(listOf("吾輩は猫である。", "名前はまだ無い。"), v.pages.flatMap { p -> p.blocks!!.map { it.text } }) // the blank block is left out
        assertEquals(listOf(0, 1000), v.pages.flatMap { p -> p.blocks!!.map { it.p } })
        assertEquals(listOf("pages/0000.jpg", "pages/0001.jpg"), v.pages.map { it.image })
        assertEquals(2.toByte(), v.images["pages/0000.jpg"]!![0]) // 002.jpg, by its name, not by order
        // book.json round trip, and the page HTML has the block where Mokuro put it.
        val back = Manga.fromJson(JSONObject().put("pages", Manga.toJson(v.pages)).getJSONArray("pages"))
        assertEquals("吾輩は猫である。", back[0].blocks!![0].text)
        val html = Manga.html(back[0], mapOf(0 to listOf(Piece("猫", "猫"))), { "k" }, List(6) { "#000000" })
        assert("left:71.250%;top:11.667%;width:12.500%;height:26.667%" in html && "data-k=\"猫\"" in html && '$' !in html)
    }

    @Test fun withoutMokuroPagesWaitToBeRead() {
        val v = Manga.read(zip(mapOf("b/002.png" to byteArrayOf(2), "b/001.png" to byteArrayOf(1), "b/notes.txt" to byteArrayOf(0))))
        assertEquals(listOf("pages/0000.png", "pages/0001.png"), v.pages.map { it.image })
        assertEquals(1.toByte(), v.images["pages/0000.png"]!![0])
        assertNull(v.pages[0].blocks)
        val back = Manga.fromJson(JSONObject().put("pages", Manga.toJson(v.pages)).getJSONArray("pages"))
        assertNull(back[1].blocks)
    }

    /** Lines PP-OCRv6_manga read on はぐれアイドル地獄変 1, page 007 (C:\dojo\work\ppocr-manga\merge_check.py gives the same). */
    @Test fun mergeDropsFuriganaAndJoinsABubble() {
        val l = { x: Int, y: Int, w: Int, h: Int, v: Boolean, t: String -> MangaOcr.Line(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), v, t) }
        val lines = listOf(l(440, 232, 11, 67, true, "はえばる"), l(440, 310, 11, 45, true, "みそら"), l(413, 228, 24, 130, true, "南風原海空"),
            l(381, 225, 28, 56, true, "です"), l(328, 801, 22, 15, false, "クン"), l(73, 400, 24, 120, true, "沖縄出身の"),
            l(41, 400, 26, 121, true, "18歳でーす"))
        val bubbles = MangaOcr.merge(lines, 844, 1200)
        assertEquals(setOf("南風原海空です", "沖縄出身の18歳でーす", "クン"), bubbles.map { it.text }.toSet())
        val b = bubbles.first { it.text == "南風原海空です" }
        assertEquals(listOf(381.0, 225.0, 437.0, 358.0), listOf(b.x1, b.y1, b.x2, b.y2))
        assert(b.vertical)
    }
}
