package android.os

/** Desktop stand-in: a monotonic clock in ms, as Android's uptime is for :core's timers. */
object SystemClock {
    fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
}
