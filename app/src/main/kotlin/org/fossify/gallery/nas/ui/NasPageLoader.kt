package org.fossify.gallery.nas.ui

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.FutureTarget
import org.fossify.gallery.nas.cache.NasCacheLease
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.transport.NasCancellation
import pl.droidsonroids.gif.GifDrawable
import java.io.IOException
import java.io.Closeable
import java.util.concurrent.ExecutionException

internal class NasPageImage(val drawable: Drawable, private val lease: NasCacheLease,
                            private val releaseDecoder: () -> Unit) : Closeable {
    override fun close() {
        try { releaseDecoder() } finally { lease.close() }
    }
}

internal data class NasPageResult(val image: NasPageImage? = null, val failure: NasFailure? = null)

/** Runs entirely on IO. A cancelled UI waits for this bounded decode before releasing its lease. */
internal object NasPageLoader {
    fun load(context: Context, entry: NasEntry, cancellation: NasCancellation, width: Int, height: Int): NasPageResult {
        val data = NasUiData.get(context)
        return when (val result = data.image(entry, Variant.ORIGINAL, cancellation)) {
            is NasCacheResult.Available -> if (entry.name.endsWith(".gif", ignoreCase = true)) {
                gif(data, entry, result.lease)
            } else decode(context, data, entry, result.lease, width, height)
            is NasCacheResult.Failed -> NasPageResult(failure = result.reason)
            NasCacheResult.Cancelled -> NasPageResult(failure = NasFailure.IO_ERROR)
        }
    }

    private fun gif(data: NasUiData, entry: NasEntry, lease: NasCacheLease): NasPageResult {
        var owned = true
        try {
            val drawable = GifDrawable(lease.file)
            val image = NasPageImage(drawable, lease) { drawable.recycle() }
            owned = false
            return NasPageResult(image)
        } catch (ignored: IOException) {
            data.repository.invalidateCache(entry, Variant.ORIGINAL)
            return NasPageResult(failure = NasFailure.INVALID_RESPONSE)
        } catch (ignored: OutOfMemoryError) {
            return NasPageResult(failure = NasFailure.INVALID_RESPONSE)
        } finally {
            if (owned) lease.close()
        }
    }

    private fun decode(context: Context, data: NasUiData, entry: NasEntry, lease: NasCacheLease,
                       width: Int, height: Int): NasPageResult {
        val manager = Glide.with(context.applicationContext)
        var target: FutureTarget<Drawable>? = null
        var owned = true
        try {
            // File contents determine format; the SHA-256 cache path intentionally has no extension.
            target = manager.load(lease.file).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                .fitCenter().submit(width.coerceAtMost(MAX_DECODE_EDGE), height.coerceAtMost(MAX_DECODE_EDGE))
            val drawable = target.get()
            val retained = target
            val image = NasPageImage(drawable, lease) { manager.clear(retained) }
            owned = false
            return NasPageResult(image)
        } catch (ignored: ExecutionException) {
            data.repository.invalidateCache(entry, Variant.ORIGINAL)
            return NasPageResult(failure = NasFailure.INVALID_RESPONSE)
        } catch (ignored: OutOfMemoryError) {
            return NasPageResult(failure = NasFailure.INVALID_RESPONSE)
        } finally {
            if (owned) {
                target?.let { manager.clear(it) }
                lease.close()
            }
        }
    }

    private const val MAX_DECODE_EDGE = 2048
}
