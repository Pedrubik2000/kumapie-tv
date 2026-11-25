package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class SplitterManifestTest {
    private fun json(a: String = "jiten-lexicon.tsv.gz", b: String = "jiten_user_core.dic") = """{"version":"2026-10-11","base":"x","files":[
        {"name":"$a","size":3,"sha256":"BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"},
        {"name":"$b","size":9,"sha256":"00"}]}"""

    @Test fun parsesVersionAndFiles() {
        val m = SplitterManifest.parse(json())
        assertEquals("2026-10-11", m.version)
        assertEquals(JapaneseModel.SPLITTER_FILES, m.files.map { it.name })
        assertEquals(3L, m.files[0].size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", m.files[0].sha256)
    }

    @Test fun refusesOtherNames() {
        for (bad in listOf(json(a = "../x.gz"), json(b = "other.dic"))) {
            try { SplitterManifest.parse(bad); fail("accepted $bad") } catch (_: IOException) {}
        }
    }

    @Test fun verifiesSizeAndHash() {
        val f = File.createTempFile("split", ".bin").apply { writeText("abc"); deleteOnExit() }
        val entry = SplitterManifest.parse(json()).files[0]
        assertTrue(entry.matches(f))
        f.writeText("abd")
        assertFalse(entry.matches(f))
        f.writeText("abcd")
        assertFalse(entry.matches(f))
    }
}
