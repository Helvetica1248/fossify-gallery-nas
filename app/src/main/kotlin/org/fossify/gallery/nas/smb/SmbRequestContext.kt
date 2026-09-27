package org.fossify.gallery.nas.smb

import org.fossify.gallery.nas.transport.NasCancelledException
import java.io.Closeable
import java.net.Socket

/** Request-local ownership. Abort raw I/O first; never hold the lock during network cleanup. */
internal class SmbRequestContext : Closeable {
    private val lock = Any()
    private val resources = ArrayList<AutoCloseable>()
    private var socket: Socket? = null
    private var closed = false

    fun <T : AutoCloseable> own(resource: T): T {
        synchronized(lock) {
            if (!closed) {
                resources.add(resource)
                return resource
            }
        }
        closeQuietly(resource)
        throw NasCancelledException()
    }

    fun ownSocket(value: Socket): Socket {
        synchronized(lock) {
            check(socket == null) { "One socket per NAS request" }
            if (!closed) {
                socket = value
                return value
            }
        }
        closeQuietly(value)
        throw NasCancelledException()
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            val result = Pair(socket, resources.asReversed().toList())
            socket = null
            resources.clear()
            result
        }
        pending.first?.let(::closeQuietly)
        pending.second.forEach(::closeQuietly)
    }

    private fun closeQuietly(resource: AutoCloseable) {
        try {
            resource.close()
        } catch (_: Exception) {
            // Do not retain secret-bearing exceptions or skip subsequent cleanup.
        }
    }
}
