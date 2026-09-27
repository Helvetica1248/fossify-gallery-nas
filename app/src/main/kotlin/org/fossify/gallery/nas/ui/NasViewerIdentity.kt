package org.fossify.gallery.nas.ui

import android.os.Bundle
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSourceKey
import java.util.UUID

internal data class NasViewerIdentity(
    val source: NasSourceKey,
    val folder: NasRelativePath,
    val selected: NasRelativePath,
    val sort: NasSort,
    val descending: Boolean
) {
    fun bundle() = Bundle().apply {
        putString("source", source.id.toString())
        putLong("revision", source.revision)
        putString("folder", folder.value)
        putString("selected", selected.value)
        putString("sort", sort.name)
        putBoolean("descending", descending)
    }

    companion object {
        fun read(bundle: Bundle?): NasViewerIdentity? = runCatching {
            val data = checkNotNull(bundle)
            NasViewerIdentity(NasSourceKey(UUID.fromString(data.getString("source")), data.getLong("revision")),
                NasRelativePath.parse(checkNotNull(data.getString("folder"))),
                NasRelativePath.parse(checkNotNull(data.getString("selected"))),
                NasSort.valueOf(data.getString("sort") ?: NasSort.NAME.name), data.getBoolean("descending"))
        }.getOrNull()
    }
}
