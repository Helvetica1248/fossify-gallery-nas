package org.fossify.gallery.nas.transport

private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
private const val DEFAULT_IDLE_TIMEOUT_MILLIS = 15_000
private const val DEFAULT_TRANSFER_TIMEOUT_MILLIS = 120_000L
private const val DEFAULT_MAX_ORIGINAL_BYTES = 128L * 1024 * 1024
private const val MAX_NETWORK_TIMEOUT_MILLIS = 120_000
private const val MAX_OPERATION_TIMEOUT_MILLIS = 86_400_000L
private const val MAX_ORIGINAL_BYTES = 1024L * 1024 * 1024
private const val NANOS_PER_MILLI = 1_000_000L

data class NasReadLimits(
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val idleTimeoutMillis: Int = DEFAULT_IDLE_TIMEOUT_MILLIS,
    val transferTimeoutMillis: Long = DEFAULT_TRANSFER_TIMEOUT_MILLIS,
    val maxOriginalBytes: Long = DEFAULT_MAX_ORIGINAL_BYTES,
    val maxConcurrentRequests: Int = 2
) {
    init {
        require(connectTimeoutMillis in 1..MAX_NETWORK_TIMEOUT_MILLIS) { "Invalid connection timeout" }
        require(idleTimeoutMillis in 1..MAX_NETWORK_TIMEOUT_MILLIS) { "Invalid idle timeout" }
        require(transferTimeoutMillis in 1..MAX_OPERATION_TIMEOUT_MILLIS) { "Invalid transfer timeout" }
        require(maxOriginalBytes in 1..MAX_ORIGINAL_BYTES) { "Invalid original size limit" }
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
        require(timeoutMillis in 1..MAX_OPERATION_TIMEOUT_MILLIS) { "Invalid NAS deadline" }
        timeoutNanos = timeoutMillis * NANOS_PER_MILLI
    }

    fun isExpired(): Boolean = clock.nanoTime() - started >= timeoutNanos
}
