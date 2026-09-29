package org.fossify.gallery.nas.external

import java.io.IOException

/** Cumulative bytes, including repeated seeks; the remote file's total size is not a download quota. */
internal class NasPreviewBudget(
    private val maxBytes: Long = MAX_BYTES,
    val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }
) {
    private val started = now()
    private var consumed = 0L

    init { require(maxBytes > 0 && timeoutMillis > 0) }

    @Synchronized
    fun charge(bytes: Int) {
        require(bytes >= 0)
        if (now() - started >= timeoutMillis || bytes > maxBytes - consumed) {
            throw IOException("NAS preview budget exhausted")
        }
        consumed += bytes
    }

    private companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
        const val TIMEOUT_MILLIS = 20_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
