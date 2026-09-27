package org.fossify.gallery.nas.transport

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NasCancelledException : CancellationException("NAS request cancelled")

/**
 * Own request-local resources BEFORE starting any blocking operation. Never register a shared session.
 * A transport must supply a close that aborts its request rather than waiting for a protocol logout.
 * cancel() executes closes on its caller: the adapter must call it on an I/O executor, not the UI thread.
 * This class is not a socket timeout and cannot make an arbitrary blocking close bounded.
 */
class NasCancellation {
    private val lock = Any()
    private val resources = LinkedHashSet<ResourceLease>()
    private val failures = AtomicInteger(0)
    @Volatile private var cancelled = false

    val isCancelled: Boolean get() = cancelled
    val cleanupFailureCount: Int get() = failures.get()

    fun throwIfCancelled() {
        if (cancelled) throw NasCancelledException()
    }

    /** Closing this lease removes and closes its resource exactly once, also across cancellation races. */
    fun own(resource: Closeable): Closeable {
        val lease = ResourceLease(resource)
        val accepted = synchronized(lock) {
            if (cancelled) false else {
                resources.add(lease)
                true
            }
        }
        if (!accepted) {
            closeQuietly(lease)
            throw NasCancelledException()
        }
        return lease
    }

    fun cancel(): Boolean {
        val pending = synchronized(lock) {
            if (cancelled) return false
            cancelled = true
            resources.toList().also { resources.clear() }
        }
        pending.asReversed().forEach(::closeQuietly)
        return true
    }

    private fun closeQuietly(resource: Closeable) {
        try {
            resource.close()
        } catch (ignored: Exception) {
            // Do not prevent other resources from closing, and do not retain/log secret-bearing exceptions.
            failures.incrementAndGet()
        }
    }

    private inner class ResourceLease(private val resource: Closeable) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) { resources.remove(this) }
            resource.close()
        }
    }
}
