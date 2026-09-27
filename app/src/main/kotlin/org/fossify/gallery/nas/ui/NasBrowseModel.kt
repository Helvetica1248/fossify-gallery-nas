package org.fossify.gallery.nas.ui

import org.fossify.gallery.nas.external.NasExternalTypes
import org.fossify.gallery.nas.data.NasDirectorySnapshot
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSourceKey
import java.util.Locale

internal enum class NasSort { NAME, MODIFIED }

/** Shared browser/viewer ordering; NAS entries never become Gallery Medium or Directory objects. */
internal object NasBrowseModel {
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun isImage(entry: NasEntry): Boolean = entry.kind == NasEntryKind.FILE &&
        entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions

    fun sorted(entries: List<NasEntry>, sort: NasSort, descending: Boolean): List<NasEntry> {
        val names = compareBy<NasEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.key.path.value }
        val order = if (sort == NasSort.NAME) names else compareBy<NasEntry> { it.modifiedEpochMillis ?: 0 }.then(names)
        val direction = if (descending) order.reversed() else order
        return entries.filter { it.kind == NasEntryKind.DIRECTORY || isImage(it) || NasExternalTypes.mime(it) != null }
            .sortedWith(compareBy<NasEntry> { it.kind != NasEntryKind.DIRECTORY }.then(direction))
    }

    fun pages(snapshot: NasDirectorySnapshot, sort: NasSort, descending: Boolean): List<NasEntry> =
        sorted(snapshot.entries, sort, descending).filter(::isImage)

    fun selected(snapshot: NasDirectorySnapshot?, source: NasSourceKey,
                 folder: NasRelativePath, path: NasRelativePath): NasEntry? =
        snapshot?.takeIf { it.source == source && it.folder == folder }?.entries
            ?.firstOrNull { it.key.path == path && isImage(it) }
}
