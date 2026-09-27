package org.fossify.gallery.nas.transport

data class NasReadLimits(
    val connectTimeoutMillis: Int = 15_000,
    val idleTimeoutMillis: Int = 15_000,
    val transferTimeoutMillis: Long = 120_000,
    val maxOriginalBytes: Long = 128L * 1024 * 1024,
    val maxConcurrentRequests: Int = 2
) {
    init {
        require(connectTimeoutMillis in 1..120_000) { "Invalid connection timeout" }
        require(idleTimeoutMillis in 1..120_000) { "Invalid idle timeout" }
        require(transferTimeoutMillis in 1..86_400_000) { "Invalid transfer timeout" }
        require(maxOriginalBytes in 1..(1024L * 1024 * 1024)) { "Invalid original size limit" }
        require(maxConcurrentRequests in 1..2) { "Invalid NAS concurrency limit" }
    }
}

fun interface NasMonotonicClock {
    fun nanoTime(): Long
}

/** Uses elapsed monotonic time, not the wall clock; nanosecond counter wrap is supported. */
class NasDeadline(
    timeoutMillis: Long,
    private val clock: NasMonotonicClock = NasMonotonicClock(System::nanoTime)
) {
    private val started = clock.nanoTime()
    private val timeoutNanos: Long

    init {
        require(timeoutMillis in 1..86_400_000) { "Invalid NAS deadline" }
        timeoutNanos = timeoutMillis * 1_000_000
    }

    fun isExpired(): Boolean = clock.nanoTime() - started >= timeoutNanos
}
