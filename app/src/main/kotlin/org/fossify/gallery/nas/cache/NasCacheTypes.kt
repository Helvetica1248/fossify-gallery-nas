package org.fossify.gallery.nas.cache

import org.fossify.gallery.nas.model.NasFailure
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal const val DECODER_REVISION = 1
internal const val THUMBNAIL_EDGE = 256
private const val MIB = 1024L * 1024
private const val ORIGINAL_QUOTA = 512 * MIB
private const val THUMBNAIL_QUOTA = 128 * MIB
private const val SINGLE_ORIGINAL = 128 * MIB

internal data class NasCacheLimits(
    val originals: Long = ORIGINAL_QUOTA,
    val thumbnails: Long = THUMBNAIL_QUOTA,
    val singleOriginal: Long = SINGLE_ORIGINAL,
    val singleThumbnail: Long = 2 * MIB
) {
    init {
        require(originals > 0 && thumbnails > 0 && singleOriginal > 0 && singleThumbnail > 0)
    }
}

/** Keep this lease open for the entire time a viewer/decoder uses file. Always close it afterwards. */
class NasCacheLease internal constructor(val file: File, private val release: () -> Unit) : Closeable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

sealed class NasCacheResult {
    class Available(val lease: NasCacheLease) : NasCacheResult()
    data class Failed(val reason: NasFailure) : NasCacheResult()
    object Cancelled : NasCacheResult()
}

internal class NasCacheException(val reason: NasFailure) : IOException("NAS cache operation failed")

internal interface NasImageProcessor {
    fun validate(file: File): Boolean
    fun thumbnail(original: File, target: File): Boolean
}
