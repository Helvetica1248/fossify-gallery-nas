package org.fossify.gallery.nas.repository

import org.fossify.gallery.nas.cache.NasCacheException
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.cache.NasDiskCache
import org.fossify.gallery.nas.cache.NasImageProcessor
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasTransferResult
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasDeadline
import org.fossify.gallery.nas.transport.NasOpenResult
import org.fossify.gallery.nas.transport.NasReadLimits
import org.fossify.gallery.nas.transport.NasReader
import org.fossify.gallery.nas.transport.NasStreamCopier
import java.io.File
import java.io.FileOutputStream

internal class NasTransfers(
    private val cache: NasDiskCache,
    private val reader: NasReader,
    private val images: NasImageProcessor
) {
    fun original(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasCacheResult {
        cache.reserve(entry, Variant.ORIGINAL).use { reservation ->
            val result = NasNetworkGate.run(cancellation) {
                download(source, entry, reservation.part, reservation.bytes, cancellation)
            }
            if (result !is NasTransferResult.Complete) return when (result) {
                is NasTransferResult.Failed -> NasCacheResult.Failed(result.reason)
                else -> NasCacheResult.Cancelled
            }
            if (!images.validate(reservation.part)) return NasCacheResult.Failed(NasFailure.INVALID_RESPONSE)
            return NasCacheResult.Available(cache.publish(reservation, cancellation))
        }
    }

    fun thumbnail(entry: NasEntry, original: File, cancellation: NasCancellation): NasCacheResult {
        cache.reserve(entry, Variant.THUMBNAIL).use { reservation ->
            cancellation.throwIfCancelled()
            if (!images.validate(original)) {
                cache.invalidate(entry, Variant.ORIGINAL)
                return NasCacheResult.Failed(NasFailure.INVALID_RESPONSE)
            }
            if (!images.thumbnail(original, reservation.part) || !images.validate(reservation.part)) {
                return NasCacheResult.Failed(NasFailure.INVALID_RESPONSE)
            }
            return NasCacheResult.Available(cache.publish(reservation, cancellation))
        }
    }

    private fun download(
        source: NasSource,
        entry: NasEntry,
        target: File,
        maxBytes: Long,
        cancellation: NasCancellation
    ): NasTransferResult {
        if (maxBytes <= 0) throw NasCacheException(NasFailure.INVALID_RESPONSE)
        return when (val opened = reader.open(source, entry, cancellation)) {
            is NasOpenResult.Failed -> NasTransferResult.Failed(opened.reason)
            NasOpenResult.Cancelled -> NasTransferResult.Cancelled
            is NasOpenResult.Opened -> opened.handle.use { handle ->
                val output = SyncOutput(target)
                NasStreamCopier.copy(handle.input, output, cancellation,
                    NasDeadline(NasReadLimits().transferTimeoutMillis), maxBytes, entry.size)
            }
        }
    }
}

private class SyncOutput(file: File) : FileOutputStream(file) {
    @Synchronized
    override fun close() {
        if (fd.valid()) {
            try {
                fd.sync()
            } finally {
                super.close()
            }
        }
    }
}
