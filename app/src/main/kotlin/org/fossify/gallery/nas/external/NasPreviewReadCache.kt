package org.fossify.gallery.nas.external

import org.fossify.gallery.nas.transport.NasRangeRead
import org.fossify.gallery.nas.transport.NasSeekableHandle
import java.io.IOException
import java.util.LinkedHashMap

/** Small preview-only read-ahead. External viewers keep exact on-demand range reads. */
internal class NasPreviewReadCache(
    private val budget: NasPreviewBudget,
    private val blockBytes: Int = DEFAULT_BLOCK_BYTES,
    private val maxBlocks: Int = DEFAULT_BLOCKS
) {
    private val blocks = LinkedHashMap<Long, ByteArray>(maxBlocks, LOAD_FACTOR, true)

    init {
        require(blockBytes > 0 && maxBlocks > 0)
    }

    fun read(handle: NasSeekableHandle, offset: Long, requested: Int, target: ByteArray): Int {
        require(offset >= 0 && requested >= 0 && requested <= target.size)
        if (requested == 0 || offset >= handle.size) return 0
        val length = minOf(requested.toLong(), handle.size - offset).toInt()
        var copied = 0
        while (copied < length) {
            val absolute = offset + copied
            val blockStart = absolute / blockBytes * blockBytes
            val block = blocks[blockStart] ?: load(handle, blockStart)
            val inside = (absolute - blockStart).toInt()
            val count = minOf(length - copied, block.size - inside)
            if (count <= 0) throw IOException("NAS preview range unavailable")
            block.copyInto(target, copied, inside, inside + count)
            copied += count
        }
        return copied
    }

    private fun load(handle: NasSeekableHandle, start: Long): ByteArray {
        val wanted = minOf(blockBytes.toLong(), handle.size - start).toInt()
        if (wanted <= 0) throw IOException("NAS preview range unavailable")
        budget.charge(wanted)
        val data = ByteArray(wanted)
        if (NasRangeRead.read(handle, start, wanted, data) != wanted) {
            throw IOException("NAS preview range incomplete")
        }
        blocks[start] = data
        while (blocks.size > maxBlocks) {
            val eldest = blocks.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return data
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
        const val DEFAULT_BLOCK_BYTES = 128 * 1024
        const val DEFAULT_BLOCKS = 2
    }
}
