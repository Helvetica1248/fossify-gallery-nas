package org.fossify.gallery.nas.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.cache.NasDiskCache
import org.fossify.gallery.nas.cache.NasImageProcessor
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
import org.fossify.gallery.nas.settings.SavedNasSource
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasOpenResult
import org.fossify.gallery.nas.transport.NasReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NasUiTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = NasSourceKey(UUID.randomUUID(), 1)
    private val source = NasSource(key, NasHost.parse("fixture.invalid"), "photos", NasRelativePath.ROOT,
        NasConnectionMode.VPN, UUID.randomUUID())
    private val path = NasRelativePath.ROOT
    private var snapshot: NasDirectorySnapshot? = null
    private var network = 0
    private val rows = mutableMapOf<String, CacheRow>()
    private val cache by lazy {
        NasDiskCache(temp.newFolder(), object : NasCacheIndex {
            override fun get(key: String) = rows[key]
            override fun all() = rows.values.toList()
            override fun put(row: CacheRow) { rows[row.key] = row }
            override fun remove(key: String) { rows.remove(key) }
        })
    }
    private val data by lazy {
        NasUiData(repository(), NasFavoriteStore(File(temp.root, "favorites.bin"))) {
            listOf(SavedNasSource(source, "fixture"))
        }
    }

    @Test fun supportedExtensionsAreCaseInsensitiveAndExcludeVideoAndSpecialFormats() {
        val entries = listOf("a.JPG", "b.jPeG", "c.PNG", "d.WebP", "e.GIF", "f.mp4", "g.avif", "h.raw", "i.svg")
            .map { entry(it) }
        assertEquals(5, entries.count(NasBrowseModel::isImage))
        assertFalse(NasBrowseModel.isImage(entry("folder.jpg").copy(kind = NasEntryKind.DIRECTORY)))
    }

    @Test fun nameAndModifiedOrderingKeepDirectoriesFirst() {
        val folder = entry("z").copy(kind = NasEntryKind.DIRECTORY)
        val a = entry("a.jpg").copy(modifiedEpochMillis = 2)
        val b = entry("B.png").copy(modifiedEpochMillis = 3)
        assertEquals(listOf(folder, a, b), NasBrowseModel.sorted(listOf(b, folder, a), NasSort.NAME, false))
        assertEquals(listOf(folder, b, a), NasBrowseModel.sorted(listOf(a, folder, b), NasSort.MODIFIED, true))
    }

    @Test fun viewerResolvesOnlyCorrectSourceFolderAndSupportedRemotePath() {
        val image = entry("a.jpg")
        val snap = NasDirectorySnapshot(key, path, 1, 1, listOf(image, entry("b.mp4")))
        assertEquals(image, NasBrowseModel.selected(snap, key, path, image.key.path))
        assertNull(NasBrowseModel.selected(snap, key.copy(revision = 2), path, image.key.path))
        assertNull(NasBrowseModel.selected(snap, key, path.child("other"), image.key.path))
        assertNull(NasBrowseModel.selected(snap, key, path, path.child("b.mp4")))
    }

    @Test fun cachedFolderNeedsNoNetworkAndRefreshFailureKeepsIt() {
        snapshot = NasDirectorySnapshot(key, path, 1, 1, listOf(entry("a.jpg")))
        assertEquals(snapshot, data.folder(key, path, false, NasCancellation()).snapshot)
        assertEquals(0, network)
        val failed = data.folder(key, path, true, NasCancellation())
        assertEquals(snapshot, failed.snapshot)
        assertEquals(NasFailure.VPN_REQUIRED, failed.failure)
        assertEquals(1, network)
    }

    @Test fun cachedThumbnailAndOriginalRemainAvailableWithoutVpn() {
        val image = entry("a.jpg")
        listOf(Variant.ORIGINAL, Variant.THUMBNAIL).forEach { variant ->
            cache.reserve(image, variant).use {
                it.part.writeBytes(byteArrayOf(1, 2, 3, 4))
                cache.publish(it, NasCancellation()).close()
            }
            val result = data.image(image, variant, NasCancellation()) as NasCacheResult.Available
            result.lease.use { assertTrue(it.file.exists()) }
        }
        assertEquals(0, network)
    }

    @Test fun offlineCacheMissReturnsSafeFailureWithoutRetryLoop() {
        assertEquals(NasCacheResult.Failed(NasFailure.VPN_REQUIRED),
            data.image(entry("a.jpg"), Variant.ORIGINAL, NasCancellation()))
        assertEquals(1, network)
        val old = entry("a.jpg").copy(key = NasRemoteKey(key.copy(revision = 2), path.child("a.jpg")))
        assertEquals(NasCacheResult.Failed(NasFailure.NOT_FOUND), data.image(old, Variant.ORIGINAL, NasCancellation()))
        assertEquals(1, network)
    }

    @Test fun cancelledUiDiscardsLateLeaseWithoutDeliveringToDestroyedView() = runBlocking {
        val begun = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val closes = AtomicInteger()
        val request = NasUiRequest()
        request.start(this, work = {
            begun.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            cache.reserve(entry("late.jpg"), Variant.ORIGINAL).use { pending ->
                pending.part.writeBytes(byteArrayOf(1, 2, 3, 4))
                cache.publish(pending, NasCancellation())
            }
        }, discard = { it.close(); closes.incrementAndGet(); disposed.countDown() }) { deliveries.incrementAndGet() }
        withContext(Dispatchers.IO) { assertTrue(begun.await(5, TimeUnit.SECONDS)) }
        val cancelled = request.cancel()
        release.countDown()
        cancelled?.join()
        withContext(Dispatchers.IO) { assertTrue(disposed.await(5, TimeUnit.SECONDS)) }
        assertEquals(0, deliveries.get())
        assertEquals(1, closes.get())
        // Source cleanup can physically delete the file only after the late lease was released.
        cache.clearSource(key.id)
        assertTrue(temp.root.walk().none { it.isFile })
    }

    @Test fun favoriteFoldersPersistDeduplicateAndRemoveWithoutChangingOtherSources() {
        val file = File(temp.root, "favorite-test.bin")
        val store = NasFavoriteStore(file)
        val favorite = NasFavoriteFolder(key.id, path.child("写真"))
        val other = NasFavoriteFolder(UUID.randomUUID(), favorite.path)
        store.set(favorite, true)
        store.set(favorite, true)
        store.set(other, true)
        assertEquals(listOf(favorite, other), NasFavoriteStore(file).list())
        store.set(favorite, false)
        assertEquals(listOf(other), NasFavoriteStore(file).list())
        val root = NasFavoriteFolder(key.id, NasRelativePath.ROOT)
        store.set(root, true)
        assertEquals(listOf(other, root), NasFavoriteStore(file).list())
    }

    @Test fun failedFavoriteWritePreservesPreviouslySavedBookmarks() {
        val file = File(temp.root, "favorite-failure.bin")
        val store = NasFavoriteStore(file)
        val favorite = NasFavoriteFolder(key.id, path.child("photo"))
        store.set(favorite, true)
        // Valid SMB path whose modified UTF encoding exceeds DataOutput's limit.
        val longPath = NasRelativePath.parse(List(100) { "写".repeat(240) }.joinToString("/"))
        assertTrue(runCatching { store.set(NasFavoriteFolder(key.id, longPath), true) }.isFailure)
        assertEquals(listOf(favorite), NasFavoriteStore(file).list())
    }

    @Test fun folderCoverSelectsOneSupportedImageFromCachedChildrenWithoutNetwork() {
        val selected = entry("a.JPG")
        snapshot = NasDirectorySnapshot(key, path, 1, 1, listOf(entry("b.png"), entry("0.mp4"),
            entry("0-folder").copy(kind = NasEntryKind.DIRECTORY), selected))
        assertEquals(selected, data.cover(key, path, NasCancellation()))
        assertEquals(0, network)
        snapshot = NasDirectorySnapshot(key, path, 2, 2,
            listOf(entry("nested").copy(kind = NasEntryKind.DIRECTORY)))
        assertNull(data.cover(key, path, NasCancellation()))
        assertEquals(0, network) // No recursive listing to hunt for a cover.
    }

    @Test fun folderCoverFailureAndCancellationDoNotLoopOrEraseCachedListing() {
        assertNull(data.cover(key, path, NasCancellation()))
        assertEquals(1, network)
        val cancellation = NasCancellation().apply { cancel() }
        assertTrue(runCatching { data.cover(key, path, cancellation) }.isFailure)
        assertEquals(1, network)
    }

    private fun entry(name: String) = NasEntry(NasRemoteKey(key, path.child(name)), NasEntryKind.FILE, 4, 1)
    private fun repository(): NasRepository {
        val catalog = object : NasCatalogStore {
            override fun get(source: NasSourceKey, folder: NasRelativePath) = snapshot
            override fun publish(source: NasSourceKey, folder: NasRelativePath,
                                 entries: List<NasEntry>, now: Long) = Unit
            override fun clear(sourceId: UUID) = Unit
        }
        val reader = object : NasReader {
            override fun list(source: NasSource, folder: NasRelativePath,
                              cancellation: NasCancellation): NasListingResult {
                network++
                return NasListingResult.Failed(NasFailure.VPN_REQUIRED)
            }
            override fun open(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasOpenResult {
                network++
                return NasOpenResult.Failed(NasFailure.VPN_REQUIRED)
            }
        }
        val images = object : NasImageProcessor {
            override fun validate(file: File) = true
            override fun thumbnail(original: File, target: File) = false
        }
        return NasRepository(catalog, cache, reader, images)
    }
}
