package org.fossify.gallery.nas.external

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.repository.NasNetworkGate
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasRangeRead
import org.fossify.gallery.nas.transport.NasSeekableHandle
import java.io.FileNotFoundException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** No local file/cache and no HTTP server. PDF/video clients can seek without a whole-file download. */
internal object NasProxyFile {
    private const val MAX_DESCRIPTORS = 4
    private const val CALL_TIMEOUT_SECONDS = 30L
    private const val IDLE_MINUTES = 10L
    private val slots = Semaphore(MAX_DESCRIPTORS)
    private val timers = ScheduledThreadPoolExecutor(2) { task ->
        Thread(task, "nas-range-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun open(context: Context, entry: NasEntry, signal: CancellationSignal?,
             budget: NasPreviewBudget? = null): ParcelFileDescriptor {
        if (!slots.tryAcquire()) throw FileNotFoundException("Close the previous NAS document or video first")
        val thread = HandlerThread("nas-range").apply { start() }
        val callback = Callback(context, entry, thread, budget)
        return try {
            signal?.setOnCancelListener { callback.abort() }
            context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
        } catch (_: Exception) {
            callback.abort()
            throw FileNotFoundException("NAS stream unavailable")
        }
    }

    private class Callback(val context: Context, val entry: NasEntry, val thread: HandlerThread,
                           val budget: NasPreviewBudget?) :
        ProxyFileDescriptorCallback() {
        private val cancellation = NasCancellation()
        private val closed = AtomicBoolean()
        private var remote: NasSeekableHandle? = null
        private var idle: ScheduledFuture<*>? = null
        private val previewReads = budget?.let(::NasPreviewReadCache)
        private val lifetime = budget?.let { timers.schedule({ abort() }, it.timeoutMillis, TimeUnit.MILLISECONDS) }

        init { touch() }
        override fun onGetSize(): Long = guarded { budget?.charge(0); handle().size }
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = guarded {
            val file = handle()
            previewReads?.read(file, offset, size, data) ?: NasRangeRead.read(file, offset, size, data)
        }
        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
            throw ErrnoException("NAS read-only", OsConstants.EROFS)
        override fun onRelease() = abort()

        private fun handle(): NasSeekableHandle {
            cancellation.throwIfCancelled()
            synchronized(this) { remote?.let { return it } }
            val opened = NasExternalFiles.open(context, entry, cancellation)
            synchronized(this) {
                if (!closed.get()) { remote = opened; return opened }
            }
            opened.close()
            throw ErrnoException("NAS closed", OsConstants.EIO)
        }

        private fun <T> guarded(action: () -> T): T {
            if (closed.get()) throw ErrnoException("NAS closed", OsConstants.EIO)
            touch()
            val deadline = timers.schedule({ abort() }, CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return try {
                NasNetworkGate.run(cancellation, action)
            } catch (_: Exception) {
                abort()
                // No SMB exception, host, share or credential text crosses the provider boundary.
                throw ErrnoException("NAS read failed", OsConstants.EIO)
            } finally {
                deadline.cancel(false)
            }
        }

        @Synchronized
        private fun touch() {
            idle?.cancel(false)
            if (!closed.get()) idle = timers.schedule({ abort() }, IDLE_MINUTES, TimeUnit.MINUTES)
        }

        fun abort() {
            if (!closed.compareAndSet(false, true)) return
            val owned = synchronized(this) {
                idle?.cancel(false)
                lifetime?.cancel(false)
                remote.also { remote = null }
            }
            try {
                runCatching { cancellation.cancel() }
                runCatching { owned?.close() }
            } finally { slots.release(); thread.quitSafely() }
        }
    }
}
