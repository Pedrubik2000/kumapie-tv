package io.github.pedrubik2000.kumapie.lang

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun File.replaceText(text: String) {
    val part = File("$path.part").apply { writeText(text) }
    Files.move(part.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
