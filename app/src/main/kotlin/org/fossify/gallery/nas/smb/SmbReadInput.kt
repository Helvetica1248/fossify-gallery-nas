package org.fossify.gallery.nas.smb

import org.fossify.gallery.nas.transport.NasCancellation
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** Uses File.read (which checks NTSTATUS), not SMBJ's stream that can hide zero-data error responses. */
internal class SmbReadInput(
    private val expectedSize: Long,
    private val cancellation: NasCancellation,
    private val owner: Closeable,
    private val readRemote: (ByteArray, Long, Int, Int) -> Int
) : InputStream() {
    private var offset = 0L
    @Volatile private var closed = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        require(off >= 0 && len >= 0 && off <= buffer.size - len)
        if (closed) throw IOException("NAS stream closed")
        cancellation.throwIfCancelled()
        if (len == 0) return 0
        val count = try {
            readRemote(buffer, offset, off, len)
        } catch (_: Exception) {
            cancellation.throwIfCancelled()
            throw IOException("NAS read failed")
        }
        cancellation.throwIfCancelled()
        if (count < 0) {
            if (offset != expectedSize) throw IOException("NAS content changed")
        } else {
            if (count == 0 || count > len || offset + count > expectedSize) throw IOException("Invalid NAS read")
            offset += count
        }
        return count
    }

    override fun close() {
        closed = true
        owner.close()
    }

    private companion object {
        const val BYTE_MASK = 0xff
    }
}
