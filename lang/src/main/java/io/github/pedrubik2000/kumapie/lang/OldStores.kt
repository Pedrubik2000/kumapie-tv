package io.github.pedrubik2000.kumapie.lang

import java.io.File

object OldStores {
    fun purge(filesDir: File): List<String> {
        val gone = File(filesDir, "srs").listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { p -> p.listFiles { f -> f.isFile && ".db" in f.name && !f.name.startsWith("ja.db") }.orEmpty().toList() }
            .filter { it.delete() }.map { "${it.parentFile?.name}/${it.name}" }
        return gone
    }
}
