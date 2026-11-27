package io.github.pedrubik2000.kumapie.lang

import java.io.File

object OldStores {
    fun purge(filesDir: File, owner: String): List<String> {
        val keep = listOf("pedro") + if (owner == "pedro") listOf("me") else emptyList()
        val gone = keep.flatMap { p ->
            File(filesDir, "srs/$p").listFiles { f -> f.isFile && ".db" in f.name && !f.name.startsWith("ja.db") }.orEmpty().toList()
        }.filter { it.delete() }.map { "${it.parentFile?.name}/${it.name}" }
        return gone
    }
}
