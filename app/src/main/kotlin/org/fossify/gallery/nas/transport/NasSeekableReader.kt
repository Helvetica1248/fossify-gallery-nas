package org.fossify.gallery.nas.transport

import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasSource
import java.io.Closeable
import java.io.IOException

/** Separate read-only range capability; NasReader and its existing test doubles remain unchanged. */
internal interface NasSeekableReader {
    fun openSeekable(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasSeekableResult
}
internal interface NasSeekableHandle : Closeable {
    val size: Long
    fun readAt(offset: Long, data: ByteArray, start: Int, length: Int): Int
}
internal sealed class NasSeekableResult {
    class Opened(val handle: NasSeekableHandle) : NasSeekableResult()
    data class Failed(val reason: NasFailure) : NasSeekableResult()
    object Cancelled : NasSeekableResult()
}

/** Proxy descriptors require full reads except at EOF; SMB may return a shorter successful read. */
internal object NasRangeRead {
    private const val CHUNK = 128 * 1024
    fun read(handle: NasSeekableHandle, offset: Long, requested: Int, data: ByteArray): Int {
        require(offset >= 0 && requested >= 0 && requested <= data.size && handle.size >= 0)
        if (offset >= handle.size || requested == 0) return 0
        val length = minOf(requested.toLong(), handle.size - offset).toInt()
        var copied = 0
        while (copied < length) {
            val amount = minOf(CHUNK, length - copied)
            val count = handle.readAt(offset + copied, data, copied, amount)
            if (count <= 0 || count > amount) throw IOException("Incomplete NAS range")
            copied += count
        }
        return copied
    }
}
