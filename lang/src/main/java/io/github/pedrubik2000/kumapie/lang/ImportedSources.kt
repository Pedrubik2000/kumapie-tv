package io.github.pedrubik2000.kumapie.lang

import android.util.Log
import java.io.File

/**
 * The words imported as known, one file per source in `imported/<source>.txt`, so one source refreshing never drops
 * another's; the known words are the union. Sources: [JITEN] (this device's Jiten import), [PEERS] (the list other
 * devices and the PC send, which is their union), [LEGACY] (the single imported.txt of older versions, moved in once).
 * The migration reads imported.txt first and renames the new folder in last: when either fails nothing is half done,
 * imported.txt stays (and is read as [LEGACY] meanwhile) and it is tried again at the next start. A source is replaced whole (empty: it goes);
 * a failed write is logged and answered false. [ImportedLists] says what a local action and a newer list from elsewhere replace.
 */
class ImportedSources(private val dir: File, private val legacyFile: File) {
    private val folder = File(dir, "imported")

    init { migrate() }

    private fun migrate(): Boolean {
        if (folder.exists()) return true
        val building = File(dir, "imported.new")
        return runCatching {
            val old = if (legacyFile.exists()) legacyFile.readLines().filter { it.isNotEmpty() } else emptyList()
            building.deleteRecursively()
            building.mkdirs()
            if (old.isNotEmpty()) File(building, "$LEGACY.txt").writeText(old.joinToString("\n"))
            check(building.renameTo(folder)) { "imported.new could not be renamed" }
        }.onFailure { building.deleteRecursively(); warn("imported words: not migrated, imported.txt stays: $it") }.isSuccess
    }

    fun read(): Map<String, Set<String>> =
        if (folder.exists()) files().associate { it.nameWithoutExtension to lines(it) }
        else lines(legacyFile).takeIf { it.isNotEmpty() }?.let { mapOf(LEGACY to it) }.orEmpty()

    private fun lines(f: File) = runCatching { f.readLines().filter(String::isNotEmpty).toSet() }.getOrDefault(emptySet())

    fun write(source: String, words: Set<String>): Boolean {
        if (!migrate()) return false
        val f = File(folder, "$source.txt")
        return runCatching { if (words.isEmpty()) f.delete() else f.writeText(words.sorted().joinToString("\n")); true }
            .onFailure { warn("imported words: $source not written: $it") }.getOrDefault(false)
    }

    fun stamp(): Long = files().sumOf { it.lastModified() + it.length() } + folder.lastModified()

    private fun files() = folder.listFiles { f -> f.extension == "txt" }.orEmpty().toList()

    private fun warn(text: String) { runCatching { Log.w("kumapie", text) } }

    companion object {
        const val JITEN = "jiten"
        const val PEERS = "peers"
        const val LEGACY = "legacy"

        fun union(sources: Map<String, Set<String>>): Set<String> = sources.values.flatten().toSet()
    }
}
