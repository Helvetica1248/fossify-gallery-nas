package org.fossify.gallery.nas.storage

import org.fossify.gallery.nas.cache.NasCacheException
import org.fossify.gallery.nas.cache.NasCacheLimits
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.cache.NasDiskCache
import org.fossify.gallery.nas.cache.NasImageProcessor
import org.fossify.gallery.nas.cache.NasPreviewProcessor
import org.fossify.gallery.nas.catalog.CacheRow
import org.fossify.gallery.nas.catalog.NasCacheIndex
import org.fossify.gallery.nas.catalog.NasCatalogStore
import org.fossify.gallery.nas.data.NasDirectorySnapshot
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasHost
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.repository.NasRepository
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasOpenResult
import org.fossify.gallery.nas.transport.NasReadHandle
import org.fossify.gallery.nas.transport.NasReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NasStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = NasSource(NasSourceKey(UUID.randomUUID(), 1), NasHost.parse("fixture.invalid"),
        "photos", NasRelativePath.ROOT, NasConnectionMode.LAN, UUID.randomUUID())
    private val folder = NasRelativePath.ROOT
    private val index = MemoryIndex()
    private val catalog = MemoryCatalog()
    private val reader = FixtureReader()
    private val images = object : NasImageProcessor {
        override fun validate(file: File) = file.readBytes().firstOrNull() == 7.toByte()
        override fun thumbnail(original: File, target: File): Boolean {
            target.writeBytes(byteArrayOf(7, 1))
            return true
        }
    }
    private val cache by lazy { NasDiskCache(temp.newFolder(), index, NasCacheLimits(12, 12, 8, 4)) }
    private val repo by lazy { NasRepository(catalog, cache, reader, images) }

    @Test fun onlyCompletePublishesAndFailuresKeepPreviousSnapshot() {
        reader.listing = NasListingResult.Complete(listOf(entry()))
        assertTrue(refresh() is NasListingResult.Complete)
        listOf(NasListingResult.Incomplete, NasListingResult.Cancelled,
            NasListingResult.Failed(NasFailure.TIMED_OUT)).forEach { result ->
            reader.listing = result
            refresh()
            assertEquals(1L, repo.getDirectory(source.key, folder)!!.generation)
            assertEquals(1, repo.getDirectory(source.key, folder)!!.entries.size)
        }
        reader.listing = NasListingResult.Complete(emptyList())
        refresh()
        assertEquals(2L, repo.getDirectory(source.key, folder)!!.generation)
        assertTrue(repo.getDirectory(source.key, folder)!!.entries.isEmpty())
    }

    @Test fun supersededRefreshCannotReplaceNewerCompleteGeneration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldReader = object : NasReader by reader {
            override fun list(source: NasSource, folder: NasRelativePath,
                              cancellation: NasCancellation): NasListingResult {
                if (Thread.currentThread().name == "old-refresh") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return NasListingResult.Complete(listOf(entry("old")))
                }
                return NasListingResult.Complete(listOf(entry("new")))
            }
        }
        val repository = NasRepository(catalog, cache, oldReader, images)
        val pool = Executors.newSingleThreadExecutor { Thread(it, "old-refresh") }
        try {
            val old = pool.submit<NasListingResult> { repository.refreshDirectory(source, folder, NasCancellation()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repository.refreshDirectory(source, folder, NasCancellation())
            release.countDown()
            assertEquals(NasListingResult.Cancelled, old.get(5, TimeUnit.SECONDS))
            assertEquals("new", repository.getDirectory(source.key, folder)!!.entries.single().name)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun failedAndCancelledDownloadsNeverPublishOrLeaveParts() {
        reader.broken = true
        assertTrue(repo.fetchOriginal(source, entry(), NasCancellation()) is NasCacheResult.Failed)
        assertNull(repo.getCachedOriginal(entry()))
        assertTrue(index.all().isEmpty())
        assertTrue(temp.root.walk().none { it.extension == "part" })
        reader.broken = false
        reader.beforeRead = { reader.cancellation!!.cancel() }
        assertEquals(NasCacheResult.Cancelled, repo.fetchOriginal(source, entry(), NasCancellation()))
        assertTrue(index.all().isEmpty())
        assertTrue(temp.root.walk().none { it.extension == "part" })
    }

    @Test fun successfulDownloadPublishesClosedValidatedFileAndWorksOffline() {
        val result = repo.fetchOriginal(source, entry(), NasCancellation()) as NasCacheResult.Available
        result.lease.use { assertEquals(4, it.file.length().toInt()) }
        assertTrue(reader.closed)
        assertTrue(index.all().single().complete)
        reader.broken = true
        repo.getCachedOriginal(entry())!!.use { assertEquals(7, it.file.readBytes()[0].toInt()) }
        assertTrue(temp.root.walk().none { it.extension == "part" })
    }

    @Test fun lruEvictsUnleasedFilesAndReservationsPreventOvercommit() {
        var now = 0L
        val limited = NasDiskCache(temp.newFolder(), index, NasCacheLimits(8, 8, 4, 4)) { ++now }
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        publish(limited, a).close()
        publish(limited, b).close()
        limited.acquire(a, Variant.ORIGINAL)!!.use { lease ->
            publish(limited, c).close()
            assertTrue(lease.file.exists())
            assertNull(limited.acquire(b, Variant.ORIGINAL))
            limited.reserve(entry("d"), Variant.ORIGINAL).use {
                val failure = runCatching { limited.reserve(entry("e"), Variant.ORIGINAL) }.exceptionOrNull()
                assertEquals(NasFailure.LOW_STORAGE, (failure as NasCacheException).reason)
            }
        }
    }

    @Test fun identityAndCorruptOrMissingFilesAreCacheMisses() {
        publish(cache, entry()).close()
        val changed = entry().copy(key = NasRemoteKey(source.key.copy(revision = 2), entry().key.path))
        assertNull(cache.acquire(changed, Variant.ORIGINAL))
        val other = entry().copy(key = NasRemoteKey(NasSourceKey(UUID.randomUUID(), 1), entry().key.path))
        assertNull(cache.acquire(other, Variant.ORIGINAL))
        cache.acquire(entry(), Variant.ORIGINAL)!!.use { it.file.writeBytes(byteArrayOf(1)) }
        assertNull(cache.acquire(entry(), Variant.ORIGINAL))
        publish(cache, entry()).use { it.file.delete() }
        assertNull(cache.acquire(entry(), Variant.ORIGINAL))
    }

    @Test fun forcedRefreshCannotReplaceLeaseAcquiredDuringDownload() {
        publish(cache, entry()).close()
        cache.reserve(entry(), Variant.ORIGINAL).use { pending ->
            pending.part.writeBytes(byteArrayOf(7, 3, 2, 1))
            cache.acquire(entry(), Variant.ORIGINAL)!!.use { lease ->
                val failure = runCatching { cache.publish(pending, NasCancellation()) }.exceptionOrNull()
                assertEquals(NasFailure.IO_ERROR, (failure as NasCacheException).reason)
                assertEquals(listOf<Byte>(7, 1, 2, 3), lease.file.readBytes().toList())
            }
        }
        assertTrue(index.all().single().complete)
        assertTrue(temp.root.walk().none { it.extension == "part" })
    }

    @Test fun sourceDeletionRetiresLeasesAndRejectsInFlightPublication() {
        reader.listing = NasListingResult.Complete(listOf(entry()))
        refresh()
        val lease = publish(cache, entry())
        val pending = cache.reserve(entry("pending"), Variant.ORIGINAL)
        pending.part.writeBytes(byteArrayOf(7, 1, 2, 3))
        repo.clearSourceCache(source.key.id)
        assertNull(repo.getDirectory(source.key, folder))
        assertNull(cache.acquire(entry(), Variant.ORIGINAL))
        assertTrue(lease.file.exists())
        lease.close()
        assertFalse(lease.file.exists())
        pending.use {
            assertTrue(runCatching { cache.publish(it, NasCancellation()) }.isFailure)
        }
        assertTrue(index.all().isEmpty())
    }

    @Test fun lazyThumbnailReusesOriginalAndInvalidatesDecodeCorruption() {
        val first = repo.fetchThumbnail(source, entry(), NasCancellation()) as NasCacheResult.Available
        first.lease.close()
        assertEquals(1, reader.opens)
        reader.broken = true
        (repo.fetchThumbnail(source, entry(), NasCancellation()) as NasCacheResult.Available).lease.close()
        assertEquals(1, reader.opens)
        repo.invalidateCache(entry(), Variant.THUMBNAIL)
        repo.getCachedOriginal(entry())!!.use { it.file.writeBytes(byteArrayOf(0, 0, 0, 0)) }
        assertTrue(repo.fetchThumbnail(source, entry(), NasCancellation()) is NasCacheResult.Failed)
        assertNull(repo.getCachedOriginal(entry()))
    }

    @Test fun externalPreviewsCacheOnlyTheThumbnailAndWorkOfflineForLargeVideos() {
        var renders = 0
        val previewRepo = NasRepository(catalog, cache, reader, images, NasPreviewProcessor { _, target, _ ->
            renders++
            target.writeBytes(byteArrayOf(7, 1))
            true
        })
        // Larger than this fixture's original quota: only the bounded thumbnail may be reserved.
        val video = entry("large.mp4").copy(size = Long.MAX_VALUE)
        repeat(2) {
            (previewRepo.fetchThumbnail(source, video, NasCancellation()) as NasCacheResult.Available).lease.close()
        }
        assertEquals(1, renders)
        assertEquals(0, reader.opens)
        assertNull(previewRepo.getCachedOriginal(video))
        assertEquals(Variant.THUMBNAIL.name, index.all().single().variant)
    }

    @Test fun failedOrCancelledExternalPreviewDoesNotPublishOrKeepPartialFiles() {
        val token = NasCancellation()
        val previewRepo = NasRepository(catalog, cache, reader, images, NasPreviewProcessor { _, target, cancel ->
            target.writeBytes(byteArrayOf(7, 1))
            cancel.cancel()
            true
        })
        assertTrue(previewRepo.fetchThumbnail(source, entry("test.pdf"), token) is NasCacheResult.Cancelled)
        assertTrue(index.all().isEmpty())
        assertEquals(0, reader.opens)
        assertTrue(temp.root.walk().none { it.extension == "part" })
    }

    private fun entry(name: String = "fixture.png") = NasEntry(
        NasRemoteKey(source.key, folder.child(name)), NasEntryKind.FILE, 4, 1)
    private fun refresh() = repo.refreshDirectory(source, folder, NasCancellation())
    private fun publish(target: NasDiskCache, entry: NasEntry) = target.reserve(entry, Variant.ORIGINAL).use {
        it.part.writeBytes(byteArrayOf(7, 1, 2, 3))
        target.publish(it, NasCancellation())
    }
}

private class MemoryIndex : NasCacheIndex {
    private val rows = mutableMapOf<String, CacheRow>()
    override fun get(key: String) = rows[key]
    override fun all() = rows.values.toList()
    override fun put(row: CacheRow) { rows[row.key] = row }
    override fun remove(key: String) { rows.remove(key) }
}

private class MemoryCatalog : NasCatalogStore {
    private val rows = mutableMapOf<Pair<NasSourceKey, NasRelativePath>, NasDirectorySnapshot>()
    override fun get(source: NasSourceKey, folder: NasRelativePath) = rows[source to folder]
    override fun publish(source: NasSourceKey, folder: NasRelativePath, entries: List<NasEntry>, now: Long) {
        val generation = (get(source, folder)?.generation ?: 0) + 1
        rows[source to folder] = NasDirectorySnapshot(source, folder, generation, now, entries)
    }
    override fun clear(sourceId: UUID) { rows.keys.removeAll { it.first.id == sourceId } }
}

private class FixtureReader : NasReader {
    var listing: NasListingResult = NasListingResult.Incomplete
    var broken = false
    var closed = false
    var opens = 0
    var cancellation: NasCancellation? = null
    var beforeRead: () -> Unit = {}
    override fun list(source: NasSource, folder: NasRelativePath, cancellation: NasCancellation) = listing
    override fun open(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasOpenResult {
        opens++
        this.cancellation = cancellation
        return NasOpenResult.Opened(object : NasReadHandle {
            override val input = object : InputStream() {
                private var offset = 0
                override fun read(): Int {
                    beforeRead()
                    if (broken && offset > 0) throw IOException("fixture failure")
                    return if (offset++ < 4) 7 else -1
                }
            }
            override fun close() { closed = true; input.close() }
        })
    }
}
