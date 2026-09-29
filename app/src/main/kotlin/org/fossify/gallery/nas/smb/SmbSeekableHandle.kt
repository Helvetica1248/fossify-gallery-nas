package org.fossify.gallery.nas.smb

import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasSeekableHandle
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class SmbSeekableHandle(
    override val size: Long,
    private val cancellation: NasCancellation,
    private val owner: Closeable,
    private val readRemote: (ByteArray, Long, Int, Int) -> Int
) : NasSeekableHandle {
    private val closed = AtomicBoolean()

    override fun readAt(offset: Long, data: ByteArray, start: Int, length: Int): Int {
        require(offset >= 0 && start >= 0 && length >= 0 && start <= data.size - length)
        if (closed.get()) throw IOException("NAS range closed")
        cancellation.throwIfCancelled()
        if (length == 0) return 0
        if (offset >= size) return -1
        val wanted = minOf(length.toLong(), size - offset).toInt()
        val count = try {
            readRemote(data, offset, start, wanted)
        } catch (_: Exception) {
            cancellation.throwIfCancelled()
            throw IOException("NAS range unavailable")
        }
        cancellation.throwIfCancelled()
        if (count <= 0 || count > wanted) throw IOException("NAS range changed")
        return count
    }

    override fun close() { if (closed.compareAndSet(false, true)) owner.close() }
}
