package org.fossify.gallery.nas.catalog

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "snapshots", primaryKeys = ["sourceId", "revision", "folder"])
internal data class SnapshotRow(
    val sourceId: String,
    val revision: Long,
    val folder: String,
    val generation: Long,
    val refreshedAt: Long
)

// Flat rows intentionally keep remote identity alongside each entry/cache record.
@Suppress("LongParameterList")
@Entity(tableName = "entries", primaryKeys = ["sourceId", "revision", "folder", "path"])
internal data class EntryRow(
    val sourceId: String,
    val revision: Long,
    val folder: String,
    val path: String,
    val displayName: String,
    val kind: String,
    val size: Long?,
    val modified: Long?,
    val fileId: String?
)

@Suppress("LongParameterList")
@Entity(tableName = "cache")
internal data class CacheRow(
    @PrimaryKey val key: String,
    val sourceId: String,
    val revision: Long,
    val path: String,
    val variant: String,
    val localPath: String,
    val bytes: Long,
    val lastUsed: Long,
    val remoteSize: Long?,
    val modified: Long?,
    val fileId: String?,
    val decoderRevision: Int,
    val complete: Boolean
)

@Dao
internal interface NasCatalogDao {
    @Query("SELECT * FROM snapshots WHERE sourceId = :id AND revision = :revision AND folder = :folder")
    fun snapshot(id: String, revision: Long, folder: String): SnapshotRow?

    @Query("SELECT * FROM entries WHERE sourceId = :id AND revision = :revision AND folder = :folder ORDER BY path")
    fun entries(id: String, revision: Long, folder: String): List<EntryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSnapshot(row: SnapshotRow)

    @Insert
    fun putEntries(rows: List<EntryRow>)

    @Query("DELETE FROM entries WHERE sourceId = :id AND revision = :revision AND folder = :folder")
    fun deleteFolder(id: String, revision: Long, folder: String)

    @Query("DELETE FROM entries WHERE sourceId = :id")
    fun deleteEntries(id: String)

    @Query("DELETE FROM snapshots WHERE sourceId = :id")
    fun deleteSnapshots(id: String)
}

@Dao
internal interface NasCacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key")
    fun get(key: String): CacheRow?

    @Query("SELECT * FROM cache")
    fun all(): List<CacheRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: CacheRow)

    @Query("DELETE FROM cache WHERE `key` = :key")
    fun remove(key: String)
}

@Database(entities = [SnapshotRow::class, EntryRow::class, CacheRow::class], version = 1, exportSchema = false)
internal abstract class NasCatalogDatabase : RoomDatabase() {
    abstract fun catalog(): NasCatalogDao
    abstract fun cache(): NasCacheDao
}
