package org.fossify.gallery.nas.next

import org.fossify.gallery.nas.external.NasPreviewBudget
import org.fossify.gallery.nas.external.NasPreviewReadCache
import org.fossify.gallery.nas.transport.NasSeekableHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class NasPreviewBudgetTest {
    @Test fun repeatedReadsConsumeTheSameByteBudget() {
        val budget = NasPreviewBudget(maxBytes = 4)
        budget.charge(2)
        budget.charge(2)
        assertThrows(IOException::class.java) { budget.charge(1) }
    }

    @Test fun deadlineAlsoBoundsMetadataAndQueueWait() {
        var now = 0L
        val budget = NasPreviewBudget(timeoutMillis = 2, now = { now })
        budget.charge(0)
        now = 2
        assertThrows(IOException::class.java) { budget.charge(0) }
    }

    @Test fun previewReadAheadReusesTwoRecentRemoteBlocks() {
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        var reads = 0
        val handle = object : NasSeekableHandle {
            override val size = bytes.size.toLong()
            override fun readAt(offset: Long, data: ByteArray, start: Int, length: Int): Int {
                reads++
                bytes.copyInto(data, start, offset.toInt(), offset.toInt() + length)
                return length
            }
            override fun close() = Unit
        }
        val cache = NasPreviewReadCache(NasPreviewBudget(maxBytes = 512L * 1024))
        val out = ByteArray(4096)
        assertEquals(out.size, cache.read(handle, 100, out.size, out))
        assertEquals(out.size, cache.read(handle, 200, out.size, out))
        assertEquals(1, reads)
        assertEquals(out.size, cache.read(handle, 128L * 1024 + 100, out.size, out))
        assertEquals(2, reads)
        assertEquals(out.size, cache.read(handle, 256L * 1024 + 100, out.size, out))
        assertEquals(3, reads)
        assertEquals(out.size, cache.read(handle, 100, out.size, out))
        assertEquals(4, reads)
    }
}
