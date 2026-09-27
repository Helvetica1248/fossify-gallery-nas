package org.fossify.gallery.nas.catalog

import org.fossify.gallery.nas.data.NasDirectorySnapshot
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSourceKey
import java.util.UUID
import java.util.concurrent.Callable

internal interface NasCatalogStore {
    fun get(source: NasSourceKey, folder: NasRelativePath): NasDirectorySnapshot?
    fun publish(source: NasSourceKey, folder: NasRelativePath, entries: List<NasEntry>, now: Long)
    fun clear(sourceId: UUID)
}

internal class RoomNasCatalogStore(
    private val db: NasCatalogDatabase,
    private val maxFolders: Int = MAX_FOLDERS,
    private val maxEntries: Long = MAX_ENTRIES
) : NasCatalogStore {
    init { require(maxFolders > 0 && maxEntries > 0) }
    override fun get(source: NasSourceKey, folder: NasRelativePath): NasDirectorySnapshot? =
        db.runInTransaction(Callable {
            val dao = db.catalog()
            val row = dao.snapshot(source.id.toString(), source.revision, folder.value)
            row?.let {
                val entries = dao.entries(it.sourceId, it.revision, it.folder).map { entry ->
                    NasEntry(
                        NasRemoteKey(source, NasRelativePath.parse(entry.path)), NasEntryKind.valueOf(entry.kind),
                        entry.size, entry.modified, entry.fileId
                    )
                }
                NasDirectorySnapshot(source, folder, it.generation, it.refreshedAt, entries)
            }
        })

    override fun publish(source: NasSourceKey, folder: NasRelativePath, entries: List<NasEntry>, now: Long) {
        db.runInTransaction {
            val dao = db.catalog()
            val id = source.id.toString()
            val generation = dao.snapshot(id, source.revision, folder.value)?.generation ?: 0
            check(generation < Long.MAX_VALUE) { "NAS generation exhausted" }
            dao.deleteFolder(id, source.revision, folder.value)
            dao.putEntries(entries.map {
                EntryRow(id, source.revision, folder.value, it.key.path.value, it.name,
                    it.kind.name, it.size, it.modifiedEpochMillis, it.fileId)
            })
            // Presence of a snapshot is the complete marker; no pending generation is ever stored.
            dao.putSnapshot(SnapshotRow(id, source.revision, folder.value, generation + 1, now))
            trimCatalog(dao, source, folder)
        }
    }

    private fun trimCatalog(dao: NasCatalogDao, current: NasSourceKey, folder: NasRelativePath) {
        val rows = dao.budgetRows()
        var count = rows.size
        var entries = rows.sumOf { it.entryCount }
        for (row in rows) {
            if (count <= maxFolders && entries <= maxEntries) break
            val old = row.snapshot
            if (old.sourceId == current.id.toString() && old.revision == current.revision &&
                old.folder == folder.value) {
                continue
            }
            dao.deleteFolder(old.sourceId, old.revision, old.folder)
            dao.deleteSnapshot(old.sourceId, old.revision, old.folder)
            count--
            entries -= row.entryCount
        }
    }

    override fun clear(sourceId: UUID) {
        db.runInTransaction {
            db.catalog().deleteEntries(sourceId.toString())
            db.catalog().deleteSnapshots(sourceId.toString())
        }
    }

    private companion object {
        const val MAX_FOLDERS = 512
        const val MAX_ENTRIES = 100_000L
    }
}

internal interface NasCacheIndex {
    fun get(key: String): CacheRow?
    fun all(): List<CacheRow>
    fun put(row: CacheRow)
    fun remove(key: String)
}

internal class RoomNasCacheIndex(private val dao: NasCacheDao) : NasCacheIndex {
    override fun get(key: String) = dao.get(key)
    override fun all() = dao.all()
    override fun put(row: CacheRow) = dao.put(row)
    override fun remove(key: String) = dao.remove(key)
}
