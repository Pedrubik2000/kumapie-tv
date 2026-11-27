package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OldStoresTest {
    private fun tree(): File {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        for ((p, names) in mapOf("pedro" to listOf("ja.db", "ja.db-journal", "de.db", "de.db-journal", "en.db"),
            "giovanna" to listOf("en.db"), "me" to listOf("ja.db", "en.db"))) {
            File(dir, "srs/$p").mkdirs()
            names.forEach { File(dir, "srs/$p/$it").writeText("x") }
        }
        return dir
    }

    @Test fun pedroOnlyAndNeverJapanese() {
        val dir = tree()
        assertEquals(3, OldStores.purge(dir, "").size)
        assertEquals(listOf("ja.db", "ja.db-journal"), File(dir, "srs/pedro").list()!!.sorted())
        assertEquals(listOf("en.db"), File(dir, "srs/giovanna").list()!!.toList())
        assertEquals(listOf("en.db", "ja.db"), File(dir, "srs/me").list()!!.sorted())
        assertTrue(OldStores.purge(dir, "").isEmpty())
        assertTrue(OldStores.purge(File(dir, "nothing"), "pedro").isEmpty())
    }

    @Test fun pedrosOwnLocalStoreToo() {
        val dir = tree()
        assertEquals(4, OldStores.purge(dir, "pedro").size)
        assertEquals(listOf("ja.db"), File(dir, "srs/me").list()!!.toList())
        assertEquals(listOf("en.db"), File(dir, "srs/giovanna").list()!!.toList())
    }
}
