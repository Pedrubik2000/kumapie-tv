package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Multipart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core plan 5f: JPDB (a rank per reading) joins the recommended Japanese install; a zip comes in by hand on the TV too. */
class RecommendedTest {
    private fun dict(title: String, freq: Long = 0) = YomitanDictionaries.Dict(title, title, "ja", "", if (freq > 0) 0 else 1000, freq, 0, 0)

    @Test fun jpdbIsInTheJapaneseSets() {
        for (key in listOf("ja", "ja-es")) assertEquals(key, Recommended.JPDB, Recommended.SETS.getValue(key).last())
        for (key in listOf("de", "en", "en-es")) assertFalse(key, Recommended.JPDB in Recommended.SETS.getValue(key))
        assertEquals("a link to Kuuube's release, no data", "JPDBv2㋕", Recommended.ZIPS[Recommended.JPDB])
    }

    @Test fun aDeviceWithTheOldSetGetsJpdbAlone() {
        val ja = Recommended.SETS.getValue("ja")
        assertEquals("nothing yet: JPDB (the index ones go by their index)", listOf(Recommended.JPDB), Recommended.missingZips(ja, emptyList()))
        assertEquals("JMdict and JMnedict from before 5f", listOf(Recommended.JPDB),
            Recommended.missingZips(ja, listOf(dict("JMdict"), dict("JMnedict"))))
        assertEquals("already there (auto or by hand)", emptyList<String>(),
            Recommended.missingZips(ja, listOf(dict("JMdict"), dict("JPDBv2㋕", freq = 500_000))))
        assertEquals("another frequency list isn't JPDB", listOf(Recommended.JPDB), Recommended.missingZips(ja, listOf(dict("Jiten", freq = 1))))
    }

    @Test fun aZipFromThePhonePage() {
        val zip = byteArrayOf(0x50, 0x4b, 3, 4, 13, 10, 45, 45, -1, -128, 0, 13, 10, 13, 10)
        val body = "--XyZ\r\nContent-Disposition: form-data; name=\"dict\"; filename=\"[JA Freq] JPDB_v2.2.zip\"\r\nContent-Type: application/zip\r\n\r\n"
            .toByteArray() + zip + "\r\n--XyZ--\r\n".toByteArray()
        val (name, bytes) = requireNotNull(Multipart.file(body, "multipart/form-data; boundary=XyZ"))
        assertEquals("[JA Freq] JPDB_v2.2.zip", name)
        assertArrayEquals(zip, bytes)
        assertEquals("a path from the browser: its name only", "a.zip",
            Multipart.file(body.toString(Charsets.UTF_8).replace("[JA Freq] JPDB_v2.2.zip", "C:\\\\x\\\\a.zip").toByteArray(), "multipart/form-data; boundary=XyZ")?.first)
        assertNull("no file part", Multipart.file("--XyZ\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nb\r\n--XyZ--".toByteArray(), "multipart/form-data; boundary=XyZ"))
        assertNull("no boundary", Multipart.file(body, "multipart/form-data"))
    }

    @Test fun aZipFitsWhenItsUnpackedSizeAndTheZipFit() {
        val mb = 1L shl 20
        val jpdb = 6 * mb
        assertEquals("JPDB: 6 MB zip, ~41 MB installed, the zip beside it while it installs", 48L, Recommended.mb(Recommended.needed(jpdb)))
        assertFalse("41 MB free: refused", Recommended.fits(jpdb, 41 * mb))
        assertTrue("exactly enough", Recommended.fits(jpdb, 48 * mb))
        assertFalse("one byte short", Recommended.fits(jpdb, 48 * mb - 1))
        assertTrue("the Chromecast's ~600 MB", Recommended.fits(jpdb, 600 * mb))
        assertTrue("an empty upload always fits", Recommended.fits(0, 0))
        assertEquals("rounded to the nearest MB", 41L, Recommended.mb(40 * mb + 600_000))
    }
}
