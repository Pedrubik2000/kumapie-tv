package io.github.pedrubik2000.kumapie.data

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicInteger

object Pending {
    private val running = AtomicInteger()
    val busy: Boolean get() = running.get() > 0

    fun track(job: Job): Job = job.also {
        running.incrementAndGet()
        it.invokeOnCompletion { running.decrementAndGet() }
    }
}
