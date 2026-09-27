package org.fossify.gallery.nas.repository

import android.content.Context
import androidx.room.Room
import org.fossify.gallery.nas.cache.AndroidNasImages
import org.fossify.gallery.nas.cache.NasDiskCache
import org.fossify.gallery.nas.catalog.NasCatalogDatabase
import org.fossify.gallery.nas.catalog.RoomNasCacheIndex
import org.fossify.gallery.nas.catalog.RoomNasCatalogStore
import org.fossify.gallery.nas.settings.AndroidNasSettings
import org.fossify.gallery.nas.settings.SavedNasSource
import org.fossify.gallery.nas.smb.AndroidSmbNetwork
import org.fossify.gallery.nas.smb.SmbNasReader
import java.io.File
import java.util.UUID

object AndroidNasRepository {
    private var instance: NasRepository? = null

    /** Lazy initialization only; does not scan a source or contact the network. Call off the main thread. */
    @Synchronized
    fun get(context: Context): NasRepository = instance ?: run {
        val app = context.applicationContext
        val db = Room.databaseBuilder(app, NasCatalogDatabase::class.java,
            File(app.noBackupFilesDir, "nas-catalog.db").absolutePath).build()
        val settings = AndroidNasSettings.get(app)
        val reader = SmbNasReader(AndroidSmbNetwork(app)) { source ->
            settings.credentials(SavedNasSource(source, ""))
        }
        NasRepository(RoomNasCatalogStore(db), NasDiskCache(File(app.cacheDir, "nas"), RoomNasCacheIndex(db.cache())),
            reader, AndroidNasImages()).also { instance = it }
    }

    fun sourceDeleted(context: Context, sourceId: UUID) {
        // A cache/Room failure must never undo or block deletion of the P2 source and credentials.
        runCatching { get(context).clearSourceCache(sourceId) }
    }
}
