@file:Suppress("MagicNumber")

package org.fossify.gallery.nas.next

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import org.fossify.gallery.nas.cache.AndroidNasPreviews
import org.fossify.gallery.nas.external.NasExternalFiles
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasSeekableHandle
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real PDF/video decoders and proxy descriptors, synthetic data only. No stored NAS settings/SMB. */
internal object NasPreviewProbe {
    var stage = "start"
    private val source = NasSourceKey(UUID.randomUUID(), 1)

    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val target = File(context.cacheDir, "nas-preview-probe.png")
        val decoderPids = mutableMapOf<String, MutableList<Int>>()
        val renderer = AndroidNasPreviews(context) { mime, pid ->
            synchronized(decoderPids) { decoderPids.getOrPut(mime) { mutableListOf() }.add(pid) }
        }
        try {
            val video = instrumentation.context.assets.open("nas-preview-fixture.mp4").use { it.readBytes() }
            val files = listOf("fixture.pdf" to pdf(), "fixture.mp4" to video)
            for ((name, bytes) in files) {
                stage = name
                val closes = AtomicInteger()
                NasExternalFiles.fixture = { _, cancel -> memory(bytes, cancel, closes) }
                check(renderer.thumbnail(entry(name, bytes), target, NasCancellation()))
                val bitmap = checkNotNull(BitmapFactory.decodeFile(target.absolutePath))
                try {
                    check(bitmap.width in 1..256 && bitmap.height in 1..256)
                    check(bitmap.getPixel(bitmap.width / 4, bitmap.height / 4) !=
                        bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4))
                } finally { bitmap.recycle() }
                waitForClose(closes)
                target.delete()
                instrumentation.waitForIdleSync()
            }
            stage = "warm video decoder"
            val warmCloses = AtomicInteger()
            NasExternalFiles.fixture = { _, cancel -> memory(video, cancel, warmCloses) }
            check(renderer.thumbnail(entry("fixture.mp4", video), target, NasCancellation()))
            waitForClose(warmCloses)
            target.delete()
            val videoPids = synchronized(decoderPids) { decoderPids["video/mp4"].orEmpty().toList() }
            check(videoPids.size >= 2 && videoPids.takeLast(2).distinct().size == 1)
            stage = "invalid PDF"
            val invalid = byteArrayOf(1, 2, 3)
            NasExternalFiles.fixture = { _, cancel -> memory(invalid, cancel, AtomicInteger()) }
            check(!renderer.thumbnail(entry("invalid.pdf", invalid), target, NasCancellation()))
            stage = "cancel blocking decode"
            cancel(renderer, target)
            return "PASS: PDF first page/video frame, warm video decoder reuse, 256px bounds, invalid PDF fallback, " +
                "blocking-read cancellation and stream cleanup"
        } finally {
            NasExternalFiles.fixture = null
            target.delete()
        }
    }

    private fun cancel(renderer: AndroidNasPreviews, target: File) {
        val entered = CountDownLatch(1)
        val closes = AtomicInteger()
        val token = NasCancellation()
        NasExternalFiles.fixture = { _, cancel ->
            object : NasSeekableHandle {
                override val size = 8192L
                override fun readAt(offset: Long, data: ByteArray, start: Int, length: Int): Int {
                    entered.countDown()
                    while (!cancel.isCancelled) SystemClock.sleep(10)
                    cancel.throwIfCancelled()
                    return -1
                }
                override fun close() { closes.incrementAndGet() }
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit<Boolean> {
                runCatching { renderer.thumbnail(entry("blocked.pdf", ByteArray(8192)), target, token) }.isFailure
            }
            check(entered.await(10, TimeUnit.SECONDS))
            token.cancel()
            check(pending.get(5, TimeUnit.SECONDS))
            waitForClose(closes)
        } finally { token.cancel(); pool.shutdownNow() }
    }

    private fun waitForClose(closes: AtomicInteger) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (closes.get() == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        check(closes.get() == 1)
    }

    private fun entry(name: String, bytes: ByteArray) = NasEntry(
        NasRemoteKey(source, NasRelativePath.parse(name)), NasEntryKind.FILE, bytes.size.toLong(), 1)

    private fun memory(bytes: ByteArray, cancel: NasCancellation, closes: AtomicInteger) = object : NasSeekableHandle {
        override val size = bytes.size.toLong()
        override fun readAt(offset: Long, data: ByteArray, start: Int, length: Int): Int {
            cancel.throwIfCancelled()
            val count = minOf(length, bytes.size - offset.toInt())
            bytes.copyInto(data, start, offset.toInt(), offset.toInt() + count)
            return count
        }
        override fun close() { closes.incrementAndGet() }
    }

    private fun pdf(): ByteArray {
        val doc = PdfDocument()
        return try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawRect(0f, 0f, 300f, 800f, Paint().apply { color = Color.BLUE })
            doc.finishPage(page)
            ByteArrayOutputStream().use { doc.writeTo(it); it.toByteArray() }
        } finally { doc.close() }
    }
}
