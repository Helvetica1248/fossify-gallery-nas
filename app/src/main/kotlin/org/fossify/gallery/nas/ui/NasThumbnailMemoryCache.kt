package org.fossify.gallery.nas.ui

import android.graphics.Bitmap
import android.util.LruCache
import org.fossify.gallery.nas.model.NasCacheKey
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasEntry

/** Process-local decoded thumbnail cache. Persistent bytes remain owned by the bounded P4 disk cache. */
internal object NasThumbnailMemoryCache {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    fun get(entry: NasEntry): Bitmap? {
        val key = NasCacheKey.forEntry(entry, Variant.THUMBNAIL)
        val value = cache.get(key) ?: return null
        return if (value.isRecycled) {
            cache.remove(key)
            null
        } else value
    }

    fun put(entry: NasEntry, bitmap: Bitmap) {
        if (!bitmap.isRecycled) cache.put(NasCacheKey.forEntry(entry, Variant.THUMBNAIL), bitmap)
    }
}
