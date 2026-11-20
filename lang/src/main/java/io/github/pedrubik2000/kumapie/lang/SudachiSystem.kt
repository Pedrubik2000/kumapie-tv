package io.github.pedrubik2000.kumapie.lang

import com.worksap.nlp.sudachi.dictionary.BinaryDictionary
import java.nio.file.Path

object SudachiSystem {
    private val loaded = HashMap<String, BinaryDictionary>()

    @Synchronized
    fun of(path: Path): BinaryDictionary = loaded.getOrPut(path.toString()) { BinaryDictionary.loadSystem(path.toString()) }
}
