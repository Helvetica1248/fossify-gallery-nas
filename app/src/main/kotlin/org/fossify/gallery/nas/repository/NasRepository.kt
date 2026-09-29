package org.fossify.gallery.nas.repository

import org.fossify.gallery.nas.cache.NasCacheException
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.cache.NasDiskCache
import org.fossify.gallery.nas.cache.NasImageProcessor
import org.fossify.gallery.nas.cache.NasPreviewProcessor
import org.fossify.gallery.nas.external.NasExternalTypes
import org.fossify.gallery.nas.catalog.NasCatalogStore
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasReader
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Blocking I/O API for P5. Call on an I/O executor, only for explicitly requested entries/folders. */
class NasRepository internal constructor(
    private val catalog: NasCatalogStore,
    private val cache: NasDiskCache,
    private val reader: NasReader,
    images: NasImageProcessor,
    private val previews: NasPreviewProcessor? = null
) {
    private val refreshes = mutableMapOf<Pair<NasSourceKey, NasRelativePath>, Any>()
    private val transfers = NasTransfers(cache, reader, images)

    fun getDirectory(source: NasSourceKey, folder: NasRelativePath) = catalog.get(source, folder)

    /** Complete means this call published. Superseded calls are Cancelled; previous data remains readable. */
    fun refreshDirectory(source: NasSource, folder: NasRelativePath, cancellation: NasCancellation): NasListingResult {
        val key = source.key to folder
        val ticket = Any()
        synchronized(refreshes) { refreshes[key] = ticket }
        try {
            val result = NasNetworkGate.run(cancellation) { reader.list(source, folder, cancellation) }
            return synchronized(refreshes) {
                if (refreshes[key] !== ticket || cancellation.isCancelled) {
                    NasListingResult.Cancelled
                } else if (result is NasListingResult.Complete) {
                    publish(source.key, folder, result)
                } else {
                    result
                }
            }
        } catch (ignored: CancellationException) {
            return NasListingResult.Cancelled
        } catch (error: IOException) {
            return NasListingResult.Failed((error as? NasCacheException)?.reason ?: NasFailure.IO_ERROR)
        } finally {
            synchronized(refreshes) { if (refreshes[key] === ticket) refreshes.remove(key) }
        }
    }

    fun getCachedOriginal(entry: NasEntry) = cache.acquire(entry, Variant.ORIGINAL)
    fun getCachedThumbnail(entry: NasEntry) = cache.acquire(entry, Variant.THUMBNAIL)

    fun fetchOriginal(source: NasSource, entry: NasEntry, cancellation: NasCancellation,
                      force: Boolean = false): NasCacheResult = cacheOperation {
        cancellation.throwIfCancelled()
        if (source.key != entry.key.source) return@cacheOperation NasCacheResult.Failed(NasFailure.INVALID_RESPONSE)
        if (!force) cache.acquire(entry, Variant.ORIGINAL)?.let { return@cacheOperation NasCacheResult.Available(it) }
        transfers.original(source, entry, cancellation)
    }

    fun fetchThumbnail(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasCacheResult =
        cacheOperation {
            cancellation.throwIfCancelled()
            if (source.key != entry.key.source) return@cacheOperation NasCacheResult.Failed(NasFailure.INVALID_RESPONSE)
            cache.acquire(entry, Variant.THUMBNAIL)?.let { return@cacheOperation NasCacheResult.Available(it) }
            if (NasExternalTypes.mime(entry) != null) {
                return@cacheOperation transfers.preview(entry, previews, cancellation)
            }
            when (val original = fetchOriginal(source, entry, cancellation)) {
                is NasCacheResult.Available -> original.lease.use { transfers.thumbnail(entry, it.file, cancellation) }
                else -> original
            }
        }

    /** P5 must call this when its decoder rejects a previously completed file. */
    fun invalidateCache(entry: NasEntry, variant: Variant) = cache.invalidate(entry, variant)

    fun clearSourceCache(sourceId: UUID) {
        synchronized(refreshes) {
            refreshes.keys.removeAll { it.first.id == sourceId }
            try {
                catalog.clear(sourceId)
            } finally {
                cache.clearSource(sourceId)
            }
        }
    }

    private fun publish(source: NasSourceKey, folder: NasRelativePath,
                        result: NasListingResult.Complete): NasListingResult {
        val entries = result.entries
        val invalid = entries.size > MAX_ENTRIES ||
            entries.any { it.key.source != source || it.key.path.parent != folder } ||
            entries.map { it.key.path }.toSet().size != entries.size
        if (invalid) return NasListingResult.Failed(NasFailure.INVALID_RESPONSE)
        catalog.publish(source, folder, entries, System.currentTimeMillis())
        return result
    }

    private fun cacheOperation(action: () -> NasCacheResult): NasCacheResult = try {
        action()
    } catch (ignored: CancellationException) {
        NasCacheResult.Cancelled
    } catch (error: IOException) {
        NasCacheResult.Failed((error as? NasCacheException)?.reason ?: NasFailure.IO_ERROR)
    }

    private companion object {
        const val MAX_ENTRIES = 100_000
    }
}
