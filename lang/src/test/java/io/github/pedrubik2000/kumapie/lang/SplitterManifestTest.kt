package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplitterManifestTest {
    private val json = """{"version":"2026-10-11","base":"x","files":[
        {"name":"a.gz","size":3,"sha256":"BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"},
        {"name":"b.dic","size":9,"sha256":"00"}]}"""

    @Test fun parsesVersionAndFiles() {
        val m = SplitterManifest.parse(json)
        assertEquals("2026-10-11", m.version)
        assertEquals(listOf("a.gz", "b.dic"), m.files.map { it.name })
        assertEquals(3L, m.files[0].size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", m.files[0].sha256)
    }

    @Test fun verifiesSizeAndHash() {
        val f = File.createTempFile("split", ".bin").apply { writeText("abc"); deleteOnExit() }
        val entry = SplitterManifest.parse(json).files[0]
        assertTrue(entry.matches(f))
        f.writeText("abd")
        assertFalse(entry.matches(f))
        f.writeText("abcd")
        assertFalse(entry.matches(f))
    }
}
