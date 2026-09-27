package org.fossify.gallery.nas.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

internal class AndroidNasImages : NasImageProcessor {
    override fun validate(file: File): Boolean = safely {
        decode(file)?.let { bitmap -> bitmap.recycle(); true } ?: false
    }

    override fun thumbnail(original: File, target: File): Boolean = safely {
        val bitmap = decode(original) ?: return@safely false
        try {
            val scale = minOf(1.0, THUMBNAIL_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height))
            val small = Bitmap.createScaledBitmap(bitmap,
                maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
            try {
                FileOutputStream(target).use { output ->
                    if (!small.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)) return@safely false
                    output.fd.sync()
                }
                true
            } finally {
                if (small !== bitmap) small.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(file: File): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outMimeType !in FORMATS) return null
        if (options.outWidth !in 1..MAX_EDGE ||
            options.outHeight !in 1..MAX_EDGE || options.outWidth.toLong() * options.outHeight > MAX_PIXELS) return null
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > THUMBNAIL_EDGE * 2) {
            options.inSampleSize *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun safely(action: () -> Boolean): Boolean = try {
        action()
    } catch (ignored: OutOfMemoryError) {
        false
    }

    private companion object {
        const val MAX_EDGE = 32768
        const val MAX_PIXELS = 100_000_000L
        const val PNG_QUALITY = 100
        val FORMATS = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
