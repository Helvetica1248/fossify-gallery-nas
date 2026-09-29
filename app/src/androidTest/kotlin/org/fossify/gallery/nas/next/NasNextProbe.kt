package org.fossify.gallery.nas.next

import android.content.Context
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import androidx.room.Room
import org.fossify.gallery.nas.catalog.NasCatalogDatabase
import org.fossify.gallery.nas.catalog.RoomNasCatalogStore
import org.fossify.gallery.nas.external.NasExternalFiles
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.transport.NasSeekableHandle
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Local fixture only: real Android seekable descriptors/Room, no settings, credentials, SMB or NAS. */
internal object NasNextProbe {
    fun run(context: Context): String {
        val bytes = ByteArray(8192) { (it % 251).toByte() }
        val closes = AtomicInteger()
        val key = NasSourceKey(UUID.randomUUID(), 1)
        val entry = NasEntry(NasRemoteKey(key, NasRelativePath.parse("fixture.pdf")),
            NasEntryKind.FILE, bytes.size.toLong(), 1)
        NasExternalFiles.fixture = { _, cancellation ->
            object : NasSeekableHandle {
                override val size = bytes.size.toLong()
                override fun readAt(offset: Long, data: ByteArray, start: Int, length: Int): Int {
                    cancellation.throwIfCancelled()
                    val count = minOf(29, length, bytes.size - offset.toInt())
                    bytes.copyInto(data, start, offset.toInt(), offset.toInt() + count)
                    return count
                }
                override fun close() { closes.incrementAndGet() }
            }
        }
        val token = NasExternalFiles.tokens.issue(entry)
        val uri = NasExternalFiles.uri(context, token)
        try {
            check(context.contentResolver.getType(uri) == "application/pdf")
            context.contentResolver.query(uri, null, null, null, null)!!.use {
                check(it.moveToFirst())
                check(it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) == "fixture.pdf")
            }
            check(runCatching { context.contentResolver.openFileDescriptor(uri, "rw")?.close() }.isFailure)
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                check(Os.fstat(descriptor.fileDescriptor).st_size == bytes.size.toLong())
                val data = ByteArray(128)
                check(Os.pread(descriptor.fileDescriptor, data, 0, data.size, 4093) == data.size)
                check(data.contentEquals(bytes.copyOfRange(4093, 4093 + data.size)))
                check(Os.pread(descriptor.fileDescriptor, data, 0, data.size, 3) == data.size)
                check(data.contentEquals(bytes.copyOfRange(3, 3 + data.size)))
                check(Os.pread(descriptor.fileDescriptor, data, 0, data.size, bytes.size.toLong()) == 0)
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (closes.get() == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            check(closes.get() == 1)
            catalog(context, key)
            return "PASS: local proxy seek/short-read/EOF/read-only/close and Room catalog retention"
        } finally {
            NasExternalFiles.tokens.revoke(token)
            NasExternalFiles.fixture = null
        }
    }

    private fun catalog(context: Context, key: NasSourceKey) {
        val db = Room.inMemoryDatabaseBuilder(context, NasCatalogDatabase::class.java).build()
        try {
            val store = RoomNasCatalogStore(db, maxFolders = 2, maxEntries = 10)
            val root = NasRelativePath.ROOT
            store.publish(key, root, emptyList(), 1)
            store.publish(key, root.child("one"), emptyList(), 2)
            store.publish(key, root.child("two"), emptyList(), 3)
            check(store.get(key, root) == null)
            check(store.get(key, root.child("two")) != null)
            check(db.catalog().budgetRows().size == 2)
        } finally { db.close() }
    }
}
