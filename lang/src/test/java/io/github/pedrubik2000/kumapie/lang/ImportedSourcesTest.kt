package io.github.pedrubik2000.kumapie.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [ImportedSources]: one file per source, the known words are the union, an old single list migrates. */
class ImportedSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun oneSourceRefreshKeepsTheOthers() {
        val dir = tmp.newFolder()
        val s = ImportedSources(dir, File(dir, "imported.txt"))
        s.write(ImportedSources.JITEN, setOf("a", "b"))
        s.write(ImportedSources.PEERS, setOf("b", "c"))
        s.write(ImportedSources.PEERS, setOf("d")) // peers refresh with a shorter list
        assertEquals(setOf("a", "b", "d"), ImportedSources.union(s.read()))
        s.write(ImportedSources.JITEN, emptySet()) // jiten removed
        assertEquals(setOf("d"), ImportedSources.union(s.read()))
        assertEquals(setOf(ImportedSources.PEERS), s.read().keys)
    }

    @Test fun oldSingleListBecomesLegacyOnce() {
        val dir = tmp.newFolder()
        val old = File(dir, "imported.txt").apply { writeText("x\ny\n") }
        val s = ImportedSources(dir, old)
        assertEquals(mapOf(ImportedSources.LEGACY to setOf("x", "y")), s.read())
        assertTrue(old.exists()) // left as it was
        s.write(ImportedSources.LEGACY, setOf("x"))
        assertEquals(setOf("x"), ImportedSources.union(ImportedSources(dir, old).read())) // not migrated again
    }

    @Test fun nothingOldMeansNoSources() {
        val dir = tmp.newFolder()
        val s = ImportedSources(dir, File(dir, "imported.txt"))
        assertTrue(s.read().isEmpty())
        assertFalse(File(dir, "imported.new").exists())
    }

    @Test fun anUnreadableOldListIsNotMigratedAndIsTriedAgain() {
        val dir = tmp.newFolder()
        val old = File(dir, "imported.txt").apply { mkdir() } // reading a directory fails
        val s = ImportedSources(dir, old)
        assertFalse(File(dir, "imported").exists())
        assertFalse(File(dir, "imported.new").exists())
        assertFalse(s.write(ImportedSources.JITEN, setOf("a"))) // not silent: false, and no folder to lose the old list behind
        assertTrue(old.deleteRecursively())
        old.writeText("x")
        assertEquals(setOf("x"), ImportedSources(dir, old).read()[ImportedSources.LEGACY])
    }
}
