package org.fossify.gallery.nas.transport

import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasTransferResult
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

private const val MAX_EMPTY_READS = 8

/**
 * Copies into a caller-created PRIVATE temporary file, never into a completed cache entry or NAS.
 * Owns/closes both supplied streams, including on failures. P4 must rename a validated, closed .part
 * only for Complete. No local paths or filesystem permissions are handled by this P1 primitive.
 * Deadlines are checked between reads/writes; the transport MUST enforce idle/connect timeouts and
 * close its connection on cancellation. This copier cannot interrupt an arbitrary stalled stream.
 */
object NasStreamCopier {
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_TRANSFER_BYTES = 1024L * 1024 * 1024

    fun copy(
        input: InputStream,
        output: OutputStream,
        cancellation: NasCancellation,
        deadline: NasDeadline,
        maxBytes: Long,
        expectedBytes: Long? = null
    ): NasTransferResult {
        require(maxBytes in 1..MAX_TRANSFER_BYTES) { "Invalid transfer size limit" }
        require(expectedBytes == null || expectedBytes >= 0) { "Invalid expected length" }

        var inputOwner: Closeable? = null
        var outputOwner: Closeable? = null
        var result: NasTransferResult? = null
        var unexpected: Throwable? = null

        try {
            inputOwner = cancellation.own(input)
            outputOwner = cancellation.own(output)
            result = transfer(input, output, cancellation, deadline, maxBytes, expectedBytes)
        } catch (ignored: CancellationException) {
            result = NasTransferResult.Cancelled
        } catch (ignored: IOException) {
            result = ioFailure(cancellation)
        } catch (error: Throwable) {
            unexpected = error
        } finally {
            when (val closeFailure = closeOwnedStreams(output, inputOwner, outputOwner)) {
                null -> Unit
                is CancellationException -> {
                    if (unexpected == null) result = NasTransferResult.Cancelled
                    else unexpected = combineFailures(unexpected, closeFailure)
                }
                is IOException -> {
                    if (unexpected == null) result = ioFailure(cancellation)
                    else unexpected = combineFailures(unexpected, closeFailure)
                }
                else -> unexpected = combineFailures(unexpected, closeFailure)
            }
        }

        unexpected?.let { throw it }
        return finalizeResult(checkNotNull(result), cancellation, deadline)
    }

    private fun ioFailure(cancellation: NasCancellation): NasTransferResult =
        if (cancellation.isCancelled) NasTransferResult.Cancelled
        else NasTransferResult.Failed(NasFailure.IO_ERROR)

    private fun finalizeResult(
        result: NasTransferResult,
        cancellation: NasCancellation,
        deadline: NasDeadline
    ): NasTransferResult = when {
        cancellation.isCancelled -> NasTransferResult.Cancelled
        result is NasTransferResult.Complete && deadline.isExpired() -> NasTransferResult.Failed(NasFailure.TIMED_OUT)
        else -> result
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

        val progress = transferLoop(input, output, cancellation, deadline, maxBytes, expectedBytes)
        progress.failure?.let { return NasTransferResult.Failed(it) }

        if (expectedBytes != null && progress.copied != expectedBytes) {
            return NasTransferResult.Failed(NasFailure.CONTENT_CHANGED)
        }

        output.flush()
        return activeTransferResult(cancellation, deadline, progress.copied)
    }

    private fun transferLoop(
        input: InputStream,
        output: OutputStream,
        cancellation: NasCancellation,
        deadline: NasDeadline,
        maxBytes: Long,
        expectedBytes: Long?
    ): TransferProgress {
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        var emptyReads = 0
        var finished = false
        var failure: NasFailure? = null

        while (!finished && failure == null) {
            when (val read = readOnce(input, buffer, cancellation, deadline, copied, maxBytes, expectedBytes)) {
                ReadResult.End -> finished = true
                ReadResult.Empty -> {
                    emptyReads++
                    failure = emptyReadFailure(emptyReads)
                }
                is ReadResult.Data -> {
                    emptyReads = 0
                    output.write(buffer, 0, read.count)
                    copied += read.count
                }
                is ReadResult.Failed -> failure = read.reason
            }
        }

        return TransferProgress(copied, failure)
    }

    private fun readOnce(
        input: InputStream,
        buffer: ByteArray,
        cancellation: NasCancellation,
        deadline: NasDeadline,
        copied: Long,
        maxBytes: Long,
        expectedBytes: Long?
    ): ReadResult {
        cancellation.throwIfCancelled()
        deadlineFailure(deadline)?.let { return ReadResult.Failed(it) }

        val limit = minOf(buffer.size.toLong(), maxBytes - copied + 1).toInt()
        val count = input.read(buffer, 0, limit)

        cancellation.throwIfCancelled()
        val timedOut = deadlineFailure(deadline)
        return if (timedOut != null) {
            ReadResult.Failed(timedOut)
        } else {
            classifyRead(count, limit, copied, maxBytes, expectedBytes)
        }
    }

    private fun classifyRead(
        count: Int,
        limit: Int,
        copied: Long,
        maxBytes: Long,
        expectedBytes: Long?
    ): ReadResult = when {
        count == -1 -> ReadResult.End
        count !in 0..limit -> ReadResult.Failed(NasFailure.INVALID_RESPONSE)
        count == 0 -> ReadResult.Empty
        copied + count > maxBytes -> ReadResult.Failed(NasFailure.TRANSFER_TOO_LARGE)
        expectedBytes != null && copied + count > expectedBytes -> ReadResult.Failed(NasFailure.CONTENT_CHANGED)
        else -> ReadResult.Data(count)
    }

    private fun activeTransferResult(
        cancellation: NasCancellation,
        deadline: NasDeadline,
        copied: Long
    ): NasTransferResult {
        cancellation.throwIfCancelled()
        return if (deadline.isExpired()) {
            NasTransferResult.Failed(NasFailure.TIMED_OUT)
        } else {
            NasTransferResult.Complete(copied)
        }
    }

    private data class TransferProgress(val copied: Long, val failure: NasFailure?)

    private sealed interface ReadResult {
        object End : ReadResult
        object Empty : ReadResult
        data class Data(val count: Int) : ReadResult
        data class Failed(val reason: NasFailure) : ReadResult
    }
}

private fun closeOwnedStreams(
    output: OutputStream,
    inputOwner: Closeable?,
    outputOwner: Closeable?
): Throwable? {
    var failure: Throwable? = null
    val pendingOutput = outputOwner ?: output.takeIf { inputOwner == null }
    failure = combineFailures(failure, closeCapturing(pendingOutput))
    failure = combineFailures(failure, closeCapturing(inputOwner))
    return failure
}

private fun closeCapturing(resource: Closeable?): Throwable? {
    if (resource == null) return null
    return try {
        resource.close()
        null
    } catch (error: Throwable) {
        error
    }
}

private fun combineFailures(primary: Throwable?, additional: Throwable?): Throwable? {
    if (additional == null) return primary
    if (primary == null) return additional
    if (primary !== additional) primary.addSuppressed(additional)
    return primary
}

private fun deadlineFailure(deadline: NasDeadline): NasFailure? =
    NasFailure.TIMED_OUT.takeIf { deadline.isExpired() }

private fun emptyReadFailure(emptyReads: Int): NasFailure? =
    NasFailure.IO_ERROR.takeIf { emptyReads >= MAX_EMPTY_READS }
