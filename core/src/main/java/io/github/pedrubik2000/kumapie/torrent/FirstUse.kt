package io.github.pedrubik2000.kumapie.torrent

import java.io.File

class FirstUse {
    private var done = false

    @Synchronized fun take(): Boolean {
        val first = !done
        done = true
        return first
    }

    fun wipe(parent: File) {
        parent.deleteRecursively()
    }
}
