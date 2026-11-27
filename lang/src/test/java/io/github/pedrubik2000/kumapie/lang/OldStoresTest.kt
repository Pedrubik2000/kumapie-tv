package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OldStoresTest {
    @Test fun deletesOtherLanguagesAndKeepsJapanese() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val p = File(dir, "srs/pedro").apply { mkdirs() }
        listOf("ja.db", "ja.db-journal", "de.db", "de.db-journal", "en.db", "fr.db").forEach { File(p, it).writeText("x") }
        File(dir, "srs/giovanna").mkdirs()
        File(dir, "srs/giovanna/en.db").writeText("x")
        assertEquals(5, OldStores.purge(dir).size)
        assertEquals(listOf("ja.db", "ja.db-journal"), p.list()!!.sorted())
        assertTrue(OldStores.purge(dir).isEmpty())
        assertTrue(OldStores.purge(File(dir, "nothing")).isEmpty())
    }
}
