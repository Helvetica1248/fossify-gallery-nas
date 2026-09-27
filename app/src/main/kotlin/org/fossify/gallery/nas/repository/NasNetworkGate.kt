package org.fossify.gallery.nas.repository

import org.fossify.gallery.nas.cache.NasCacheException
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasDeadline
import org.fossify.gallery.nas.transport.NasReadLimits
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Shared by directory and original requests. No retry or background scheduling. */
internal object NasNetworkGate {
    private val limits = NasReadLimits()
    private val permits = Semaphore(limits.maxConcurrentRequests, true)
    private const val WAIT_MILLIS = 100L

    fun <T> run(cancellation: NasCancellation, action: () -> T): T = try {
        awaitAndRun(cancellation, action)
    } catch (ignored: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CancellationException("NAS request interrupted")
    }

    private fun <T> awaitAndRun(cancellation: NasCancellation, action: () -> T): T {
        val deadline = NasDeadline(limits.transferTimeoutMillis)
        cancellation.throwIfCancelled()
        while (!permits.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            cancellation.throwIfCancelled()
            if (deadline.isExpired()) throw NasCacheException(NasFailure.TIMED_OUT)
        }
        try {
            cancellation.throwIfCancelled()
            return action()
        } finally {
            permits.release()
        }
    }
}
