package org.fossify.gallery.nas.transport

import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasTransferResult
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies into a caller-created PRIVATE temporary file, never into a completed cache entry or NAS.
 * Owns/closes both supplied streams, including on failures. P4 must rename a validated, closed .part
 * only for Complete. No local paths or filesystem permissions are handled by this P1 primitive.
 * Deadlines are checked between reads/writes; the transport MUST enforce idle/connect timeouts and
 * close its connection on cancellation. This copier cannot interrupt an arbitrary stalled stream.
 */
object NasStreamCopier {
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_EMPTY_READS = 8

    fun copy(
        input: InputStream,
        output: OutputStream,
        cancellation: NasCancellation,
        deadline: NasDeadline,
        maxBytes: Long,
        expectedBytes: Long? = null
    ): NasTransferResult {
        require(maxBytes in 1..(1024L * 1024 * 1024)) { "Invalid transfer size limit" }
        require(expectedBytes == null || expectedBytes >= 0) { "Invalid expected length" }
        // Acquire both so early cancellation cannot leave the output stream unclosed.
        var inputOwner: java.io.Closeable? = null
        var outputOwner: java.io.Closeable? = null
        var result: NasTransferResult = NasTransferResult.Cancelled
        try {
            inputOwner = cancellation.own(input)
            outputOwner = cancellation.own(output)
            result = transfer(input, output, cancellation, deadline, maxBytes, expectedBytes)
        } catch (ignored: NasCancelledException) {
            result = NasTransferResult.Cancelled
        } catch (ignored: IOException) {
            result = if (cancellation.isCancelled) {
                NasTransferResult.Cancelled
            } else {
                NasTransferResult.Failed(NasFailure.IO_ERROR)
            }
        } finally {
            // If own() rejected a stream, it already closed it. A never-submitted output still needs closing.
            var closeFailed = false
            try {
                if (outputOwner != null) outputOwner.close() else if (inputOwner == null) output.close()
            } catch (ignored: IOException) {
                closeFailed = true
            } finally {
                try {
                    inputOwner?.close()
                } catch (ignored: IOException) {
                    closeFailed = true
                }
            }
            if (closeFailed && result is NasTransferResult.Complete) {
                result = NasTransferResult.Failed(NasFailure.IO_ERROR)
            }
        }
        return when {
            cancellation.isCancelled -> NasTransferResult.Cancelled
            result is NasTransferResult.Complete && deadline.isExpired() -> {
                NasTransferResult.Failed(NasFailure.TIMED_OUT)
            }
            else -> result
        }
    }

    private fun transfer(
        input: InputStream,
        output: OutputStream,
        cancellation: NasCancellation,
        deadline: NasDeadline,
        maxBytes: Long,
        expectedBytes: Long?
    ): NasTransferResult {
        if (expectedBytes != null && expectedBytes > maxBytes) {
            return NasTransferResult.Failed(NasFailure.TRANSFER_TOO_LARGE)
        }
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        var emptyReads = 0
        while (true) {
            cancellation.throwIfCancelled()
            if (deadline.isExpired()) return NasTransferResult.Failed(NasFailure.TIMED_OUT)
            val limit = minOf(buffer.size.toLong(), maxBytes - copied + 1).toInt()
            val count = input.read(buffer, 0, limit)
            cancellation.throwIfCancelled()
            if (deadline.isExpired()) return NasTransferResult.Failed(NasFailure.TIMED_OUT)
            if (count == -1) break
            if (count !in 0..limit) return NasTransferResult.Failed(NasFailure.INVALID_RESPONSE)
            if (count == 0) {
                if (++emptyReads >= MAX_EMPTY_READS) return NasTransferResult.Failed(NasFailure.IO_ERROR)
                continue
            }
            emptyReads = 0
            if (copied + count > maxBytes) return NasTransferResult.Failed(NasFailure.TRANSFER_TOO_LARGE)
            if (expectedBytes != null && copied + count > expectedBytes) {
                return NasTransferResult.Failed(NasFailure.CONTENT_CHANGED)
            }
            output.write(buffer, 0, count)
            copied += count
        }
        if (expectedBytes != null && copied != expectedBytes) {
            return NasTransferResult.Failed(NasFailure.CONTENT_CHANGED)
        }
        output.flush()
        cancellation.throwIfCancelled()
        if (deadline.isExpired()) return NasTransferResult.Failed(NasFailure.TIMED_OUT)
        return NasTransferResult.Complete(copied)
    }
}
