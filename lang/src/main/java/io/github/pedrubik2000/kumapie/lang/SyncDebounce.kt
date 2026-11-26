package io.github.pedrubik2000.kumapie.lang

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SyncDebounce(private val scope: CoroutineScope, private val gapMs: Long = 5_000, private val run: suspend () -> Unit) {
    private var pending = false
    private var running = false

    fun request() {
        synchronized(this) { pending = true; if (running) return; running = true }
        scope.launch {
            var clean = false
            try {
                do {
                    delay(gapMs)
                    synchronized(this@SyncDebounce) { pending = false }
                    runCatching { run() }
                } while (synchronized(this@SyncDebounce) { if (pending) true else { running = false; clean = true; false } })
            } finally { if (!clean) synchronized(this@SyncDebounce) { running = false } }
        }
    }
}
