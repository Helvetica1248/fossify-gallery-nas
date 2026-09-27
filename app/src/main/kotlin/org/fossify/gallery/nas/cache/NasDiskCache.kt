package org.fossify.gallery.nas.cache

import org.fossify.gallery.nas.catalog.CacheRow
import org.fossify.gallery.nas.catalog.NasCacheIndex
import org.fossify.gallery.nas.model.NasCacheKey
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.transport.NasCancellation
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/** One instance per root/process. All metadata, reservations and leases share this monitor. */
@Suppress("TooManyFunctions") // Keep the small cache state machine under one lock.
internal class NasDiskCache(
    private val root: File,
    private val index: NasCacheIndex,
    val limits: NasCacheLimits = NasCacheLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val leases = mutableMapOf<String, Int>()
    private val deletedSources = mutableSetOf<UUID>()
    private val retired = mutableSetOf<String>()
    private val pending = mutableMapOf<String, Reservation>()

    init {
        ensureDirectories()
        // No live writers exist when the process singleton is first created.
        File(root, "tmp").listFiles()?.filter { it.name.matches(PART_NAME) }?.forEach { it.delete() }
    }

    private fun ensureDirectories() {
        listOf("original", "thumbnail", "tmp").forEach { name ->
            val dir = File(root, name)
            if (!dir.isDirectory && !dir.mkdirs()) throw NasCacheException(NasFailure.LOW_STORAGE)
        }
    }

    @Synchronized
    fun acquire(entry: NasEntry, variant: Variant): NasCacheLease? {
        val key = NasCacheKey.forEntry(entry, variant, DECODER_REVISION)
        val row = index.get(key) ?: return null
        val file = file(key, variant)
        val expected = metadata(entry, variant, file.length(), row.lastUsed)
        if (row != expected || !file.isFile || retired.contains(key)) {
            discard(key, variant)
            return null
        }
        index.put(row.copy(lastUsed = clock()))
        leases[key] = (leases[key] ?: 0) + 1
        return NasCacheLease(file) { release(key, variant) }
    }

    @Synchronized
    fun reserve(entry: NasEntry, variant: Variant): Reservation {
        val bytes = reservationSize(entry, variant)
        if (deletedSources.contains(entry.key.source.id)) throw NasCacheException(NasFailure.NOT_FOUND)
        val key = NasCacheKey.forEntry(entry, variant, DECODER_REVISION)
        if (pending.containsKey(key) || leases.containsKey(key)) throw NasCacheException(NasFailure.IO_ERROR)
        ensureDirectories()
        makeRoom(variant, bytes)
        val part = File(root, "tmp/$key.part")
        preparePart(part)
        return Reservation(entry, variant, key, part, bytes).also { pending[key] = it }
    }

    @Synchronized
    fun publish(reservation: Reservation, cancellation: NasCancellation): NasCacheLease {
        cancellation.throwIfCancelled()
        // A viewer may acquire the old completed file after a forced download reserved this key.
        if (pending[reservation.key] !== reservation || reservation.invalid || leases.containsKey(reservation.key)) {
            throw NasCacheException(NasFailure.IO_ERROR)
        }
        val bytes = reservation.part.length()
        if (!reservation.part.isFile || bytes <= 0 || bytes > reservation.bytes) {
            throw NasCacheException(NasFailure.INVALID_RESPONSE)
        }
        val target = file(reservation.key, reservation.variant)
        // If metadata publication fails after rename, the file is an invisible, evictable orphan.
        index.remove(reservation.key)
        Files.move(reservation.part.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        index.put(metadata(reservation.entry, reservation.variant, bytes, clock()))
        pending.remove(reservation.key)
        return checkNotNull(acquire(reservation.entry, reservation.variant))
    }

    @Synchronized
    fun invalidate(entry: NasEntry, variant: Variant) {
        discard(NasCacheKey.forEntry(entry, variant, DECODER_REVISION), variant)
    }

    @Synchronized
    fun clearSource(sourceId: UUID) {
        deletedSources.add(sourceId)
        pending.values.filter { it.entry.key.source.id == sourceId }.forEach { it.invalid = true }
        index.all().filter { it.sourceId == sourceId.toString() }.forEach {
            discard(it.key, Variant.valueOf(it.variant))
        }
    }

    private fun reservationSize(entry: NasEntry, variant: Variant): Long {
        if (entry.kind != NasEntryKind.FILE) throw NasCacheException(NasFailure.INVALID_RESPONSE)
        val max = if (variant == Variant.ORIGINAL) limits.singleOriginal else limits.singleThumbnail
        val bytes = if (variant == Variant.ORIGINAL) entry.size ?: max else max
        if (bytes > max) throw NasCacheException(NasFailure.TRANSFER_TOO_LARGE)
        return bytes
    }

    private fun preparePart(part: File) {
        if (part.exists() && !part.delete()) throw NasCacheException(NasFailure.IO_ERROR)
        if (!part.createNewFile()) throw NasCacheException(NasFailure.IO_ERROR)
    }

    private fun makeRoom(variant: Variant, bytes: Long) {
        val quota = if (variant == Variant.ORIGINAL) limits.originals else limits.thumbnails
        val directory = File(root, variant.name.lowercase(Locale.ROOT))
        val rows = index.all().filter { it.variant == variant.name }.associateBy { it.key }
        val files = directory.listFiles()?.filter { it.isFile && it.name.matches(CACHE_NAME) }.orEmpty()
        val reserved = pending.values.filter { it.variant == variant }.sumOf { maxOf(it.bytes, it.part.length()) }
        // Undeletable abandoned parts consume space too (charged conservatively to either quota).
        val abandoned = File(root, "tmp").listFiles()?.filter { part ->
            part.name.matches(PART_NAME) && pending.values.none { it.part == part }
        }.orEmpty().sumOf { it.length() }
        var used = files.sumOf { it.length() } + reserved + abandoned
        files.sortedBy { rows[it.name]?.lastUsed ?: Long.MIN_VALUE }.forEach { candidate ->
            if (used + bytes > quota && !leases.containsKey(candidate.name) && !pending.containsKey(candidate.name)) {
                val length = candidate.length()
                index.remove(candidate.name)
                if (candidate.delete()) used -= length
            }
        }
        if (used + bytes > quota || root.usableSpace < bytes) throw NasCacheException(NasFailure.LOW_STORAGE)
    }

    private fun discard(key: String, variant: Variant) {
        index.remove(key)
        if (leases.containsKey(key)) retired.add(key) else file(key, variant).delete()
    }

    @Synchronized
    private fun release(key: String, variant: Variant) {
        val count = checkNotNull(leases[key]) - 1
        if (count > 0) {
            leases[key] = count
        } else {
            leases.remove(key)
            if (retired.remove(key)) file(key, variant).delete()
        }
    }

    @Synchronized
    private fun abort(reservation: Reservation) {
        if (pending[reservation.key] === reservation) {
            pending.remove(reservation.key)
            reservation.part.delete()
        }
    }

    private fun file(key: String, variant: Variant) = File(root, "${variant.name.lowercase(Locale.ROOT)}/$key")

    private fun metadata(entry: NasEntry, variant: Variant, bytes: Long, now: Long): CacheRow {
        val key = NasCacheKey.forEntry(entry, variant, DECODER_REVISION)
        return CacheRow(key, entry.key.source.id.toString(), entry.key.source.revision, entry.key.path.value,
            variant.name, "${variant.name.lowercase(Locale.ROOT)}/$key", bytes, now, entry.size,
            entry.modifiedEpochMillis, entry.fileId, DECODER_REVISION, true)
    }

    internal inner class Reservation(
        val entry: NasEntry,
        val variant: Variant,
        val key: String,
        val part: File,
        val bytes: Long
    ) : Closeable {
        var invalid = false
        override fun close() = abort(this)
    }

    private companion object {
        val CACHE_NAME = Regex("[0-9a-f]{64}")
        val PART_NAME = Regex("[0-9a-f]{64}\\.part")
    }
}
