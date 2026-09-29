package org.fossify.gallery.nas.external

import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import java.util.Locale

/** Only explicit document/video types are handed to another application. */
internal object NasExternalTypes {
    private val types = mapOf(
        "pdf" to "application/pdf", "mp4" to "video/mp4", "m4v" to "video/mp4",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
        "avi" to "video/x-msvideo", "3gp" to "video/3gpp", "mpeg" to "video/mpeg", "mpg" to "video/mpeg",
        "ts" to "video/mp2t"
    )
    fun mime(entry: NasEntry): String? = if (entry.kind == NasEntryKind.FILE) {
        types[entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)]
    } else null
}
