package org.fossify.gallery.nas.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import org.fossify.gallery.nas.cache.AndroidNasImages
import org.fossify.gallery.nas.cache.NasCacheLimits
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.cache.NasDiskCache
import org.fossify.gallery.nas.catalog.NasCatalogDatabase
import org.fossify.gallery.nas.catalog.RoomNasCacheIndex
import org.fossify.gallery.nas.catalog.RoomNasCatalogStore
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Uses an isolated DB/cache and synthetic images. Never reads saved sources or invokes SMB. */
internal object NasStorageProbe {
    private val source = NasSource(NasSourceKey(UUID.fromString("6418eae0-f880-4000-8000-000000000004"), 1),
        NasHost.parse("fixture.invalid"), "photos", NasRelativePath.ROOT, NasConnectionMode.LAN, UUID.randomUUID())

    fun run(context: Context, phase: String): String {
        val dbFile = File(context.noBackupFilesDir, "nas-p4-probe.db")
        val cacheRoot = File(context.cacheDir, "nas-p4-probe")
        if (phase == "p4-write") {
            context.deleteDatabase(dbFile.absolutePath)
            cacheRoot.deleteRecursively()
        }
        val db = Room.databaseBuilder(context, NasCatalogDatabase::class.java, dbFile.absolutePath).build()
        try {
            if (phase == "p4-write") write(db, cacheRoot) else read(db, cacheRoot)
        } finally {
            db.close()
        }
        if (phase == "p4-read") {
            context.deleteDatabase(dbFile.absolutePath)
            cacheRoot.deleteRecursively()
        }
        return "PASS: $phase / Room, atomic cache, JPEG+PNG thumbnail, LRU lease, offline / pid=" + android.os.Process.myPid()
    }

    private fun write(db: NasCatalogDatabase, root: File) {
        val png = fixture(Bitmap.CompressFormat.PNG)
        val jpeg = fixture(Bitmap.CompressFormat.JPEG)
        val entries = listOf(entry("sample.png", png), entry("sample.jpg", jpeg))
        val files = mapOf("sample.png" to png, "sample.jpg" to jpeg, "third.png" to png)
        val limits = NasCacheLimits(originals = (png.size + jpeg.size).toLong())
        val cache = NasDiskCache(root, RoomNasCacheIndex(db.cache()), limits)
        val reader = object : NasReader {
            override fun list(source: NasSource, folder: NasRelativePath,
                              cancellation: NasCancellation) = NasListingResult.Complete(entries)
            override fun open(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasOpenResult =
                NasOpenResult.Opened(object : NasReadHandle {
                    override val input = files.getValue(entry.name).inputStream()
                    override fun close() = input.close()
                })
        }
        val repo = NasRepository(RoomNasCatalogStore(db), cache, reader, AndroidNasImages())
        check(repo.refreshDirectory(source, NasRelativePath.ROOT, NasCancellation()) is NasListingResult.Complete)
        check(repo.getDirectory(source.key, NasRelativePath.ROOT)!!.generation == 1L)
        entries.forEach { image ->
            val result = repo.fetchThumbnail(source, image, NasCancellation()) as NasCacheResult.Available
            result.lease.use { lease ->
                val decoded = checkNotNull(BitmapFactory.decodeFile(lease.file.absolutePath))
                check(maxOf(decoded.width, decoded.height) == 256)
                decoded.recycle()
            }
        }
        repo.getCachedOriginal(entries[1])!!.use { lease ->
            val third = repo.fetchOriginal(source, entry("third.png", png), NasCancellation()) as NasCacheResult.Available
            third.lease.close()
            check(lease.file.exists())
            check(repo.getCachedOriginal(entries[0]) == null)
        }
        check(root.walk().none { it.extension == "part" })
    }

    private fun read(db: NasCatalogDatabase, root: File) {
        val offline = object : NasReader {
            override fun list(source: NasSource, folder: NasRelativePath,
                              cancellation: NasCancellation) = NasListingResult.Failed(NasFailure.UNREACHABLE)
            override fun open(source: NasSource, entry: NasEntry,
                              cancellation: NasCancellation): NasOpenResult = error("Unexpected network request")
        }
        val repo = NasRepository(RoomNasCatalogStore(db), NasDiskCache(root, RoomNasCacheIndex(db.cache())),
            offline, AndroidNasImages())
        val snapshot = checkNotNull(repo.getDirectory(source.key, NasRelativePath.ROOT))
        check(snapshot.generation == 1L && snapshot.entries.size == 2)
        check(repo.getDirectory(source.key.copy(revision = 2), NasRelativePath.ROOT) == null)
        repo.refreshDirectory(source, NasRelativePath.ROOT, NasCancellation())
        check(repo.getDirectory(source.key, NasRelativePath.ROOT)!!.generation == 1L)
        snapshot.entries.forEach { entry ->
            repo.getCachedThumbnail(entry)!!.use { check(AndroidNasImages().validate(it.file)) }
        }
        repo.getCachedOriginal(snapshot.entries.single { it.name.endsWith("jpg") })!!.use {
            check(AndroidNasImages().validate(it.file))
        }
        repo.clearSourceCache(source.key.id)
        check(repo.getDirectory(source.key, NasRelativePath.ROOT) == null)
        check(db.cache().all().isEmpty())
        check(root.walk().none { it.isFile })
    }

    private fun entry(name: String, bytes: ByteArray) = NasEntry(
        NasRemoteKey(source.key, NasRelativePath.ROOT.child(name)), NasEntryKind.FILE, bytes.size.toLong(), 1)

    private fun fixture(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff336699.toInt())
            return ByteArrayOutputStream().use {
                check(bitmap.compress(format, 90, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
