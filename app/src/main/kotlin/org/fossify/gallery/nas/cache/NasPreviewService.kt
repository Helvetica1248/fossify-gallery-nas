package org.fossify.gallery.nas.cache

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

internal object NasPreviewProtocol {
    const val RENDER = 1
    const val DESCRIPTOR = "descriptor"
    const val MIME = "mime"
    const val PNG = "png"
    const val PID = "pid"
    const val MAX_PNG_BYTES = 512 * 1024
}

/** Bound PDF decoder in an isolated process; video subclass uses a separate app process for media APIs. */
open class NasPreviewService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == NasPreviewProtocol.RENDER) {
            val data = message.data
            val reply = message.replyTo
            worker.execute { render(data, reply) }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = receiver.binder

    @Suppress("TooGenericExceptionCaught") // Untrusted native decoders have varied failure types.
    private fun render(data: Bundle, reply: Messenger?) {
        val png = try {
            decode(data)?.let(::encode)
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        runCatching {
            reply?.send(Message.obtain(null, NasPreviewProtocol.RENDER).apply {
                this.data = Bundle().apply {
                    putByteArray(NasPreviewProtocol.PNG, png)
                    putInt(NasPreviewProtocol.PID, Process.myPid())
                }
            })
        }
    }

    @Suppress("DEPRECATION") // Typed Bundle getter is only available from API 33.
    private fun decode(data: Bundle): Bitmap? {
        data.classLoader = ParcelFileDescriptor::class.java.classLoader
        return data.getParcelable<ParcelFileDescriptor>(NasPreviewProtocol.DESCRIPTOR)?.use { fd ->
            if (data.getString(NasPreviewProtocol.MIME) == "application/pdf") pdf(fd) else video(fd)
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray? = try {
        ByteArrayOutputStream().use { output ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output) &&
                output.size() <= NasPreviewProtocol.MAX_PNG_BYTES) output.toByteArray() else null
        }
    } finally { bitmap.recycle() }

    private fun pdf(fd: ParcelFileDescriptor): Bitmap? = ParcelFileDescriptor.dup(fd.fileDescriptor).use { input ->
        PdfRenderer(input).use { renderer ->
            if (renderer.pageCount == 0) null else renderer.openPage(0).use(::renderPage)
        }
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = THUMBNAIL_EDGE.toDouble() / maxOf(page.width, page.height).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()),
            maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
        var rendered = false
        return try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            rendered = true
            bitmap
        } finally { if (!rendered) bitmap.recycle() }
    }

    private fun video(fd: ParcelFileDescriptor): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(fd.fileDescriptor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(FRAME_MICROS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMBNAIL_EDGE, THUMBNAIL_EDGE)
            } else legacyFrame(retriever)
        } finally { retriever.release() }
    }

    private fun legacyFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0
        if (width !in 1..LEGACY_MAX_EDGE || height !in 1..LEGACY_MAX_EDGE ||
            width * height > LEGACY_MAX_PIXELS) return null
        val bitmap = retriever.getFrameAtTime(FRAME_MICROS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = minOf(1.0, THUMBNAIL_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height))
        return try {
            Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()),
                maxOf(1, (bitmap.height * scale).toInt()), true).also { if (it !== bitmap) bitmap.recycle() }
        } catch (error: OutOfMemoryError) { bitmap.recycle(); throw error }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
        // A timed-out native decode may ignore interruption. Only dedicated decoder processes host this service.
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val PNG_QUALITY = 100
        const val FRAME_MICROS = 1_000_000L
        const val LEGACY_MAX_EDGE = 4096L
        const val LEGACY_MAX_PIXELS = 2_073_600L
    }
}

/** MediaMetadataRetriever cannot obtain the system media service from an isolated UID on Pixel. */
class NasVideoPreviewService : NasPreviewService()
