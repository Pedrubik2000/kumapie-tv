package io.github.pedrubik2000.kumapie.lang

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One sync in flight, requests within [gapMs] of each other make one: a burst waits [gapMs] then runs once; a request
 * that arrives while [run] is going makes exactly one more run after another [gapMs].
 */
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
                } while (synchronized(this@SyncDebounce) { if (pending) true else { running = false; clean = true; false } }) // one lock: no request lost between the check and the reset
            } finally { if (!clean) synchronized(this@SyncDebounce) { running = false } } // a cancel gets here still running
        }
    }
}
