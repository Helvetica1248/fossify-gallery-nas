package org.fossify.gallery.nas.cache

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.fossify.gallery.nas.external.NasExternalTypes
import org.fossify.gallery.nas.external.NasPreviewBudget
import org.fossify.gallery.nas.external.NasProxyFile
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.transport.NasCancellation
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun interface NasPreviewProcessor {
    fun thumbnail(entry: NasEntry, target: File, cancellation: NasCancellation): Boolean
}

/** One decoder at a time; cancellation/timeouts unbind and terminate its dedicated process. Call on IO. */
internal class AndroidNasPreviews(private val context: Context) : NasPreviewProcessor {
    override fun thumbnail(entry: NasEntry, target: File, cancellation: NasCancellation): Boolean {
        val budget = NasPreviewBudget()
        while (!decoder.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            cancellation.throwIfCancelled()
            budget.charge(0)
        }
        return try {
            cancellation.throwIfCancelled()
            render(entry, target, cancellation)
        } finally { decoder.release() }
    }

    private fun render(entry: NasEntry, target: File, cancellation: NasCancellation): Boolean {
        val mime = NasExternalTypes.mime(entry) ?: return false
        val signal = CancellationSignal()
        cancellation.own(Closeable { signal.cancel() }).use {
            val budget = NasPreviewBudget()
            NasProxyFile.open(context, entry, signal, budget).use { descriptor ->
                val connection = PreviewConnection(descriptor, mime)
                val service = if (mime == "application/pdf") NasPreviewService::class.java
                    else NasVideoPreviewService::class.java
                val bytes = request(Intent(context, service), connection, cancellation, signal, budget.timeoutMillis)
                    ?: return false
                FileOutputStream(target).use { output -> output.write(bytes); output.fd.sync() }
                return true
            }
        }
    }

    private fun request(intent: Intent, connection: PreviewConnection, cancellation: NasCancellation,
                        signal: CancellationSignal, timeout: Long): ByteArray? {
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) return null
        return try { connection.await(cancellation, timeout) }
        finally {
            signal.cancel()
            context.unbindService(connection)
            connection.awaitShutdown()
        }
    }

    private companion object {
        val decoder = Semaphore(1, true)
        const val POLL_MILLIS = 100L
    }
}

private class PreviewConnection(private val descriptor: ParcelFileDescriptor, private val mime: String) :
    ServiceConnection {
    private val completed = CountDownLatch(1)
    private val died = CountDownLatch(1)
    @Volatile private var connected = false
    private val png = AtomicReference<ByteArray?>()
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        png.set(message.data.getByteArray(NasPreviewProtocol.PNG))
        completed.countDown()
        true
    })

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        runCatching {
            binder.linkToDeath({ died.countDown() }, 0)
            connected = true
            val payload = Bundle().apply {
                putParcelable(NasPreviewProtocol.DESCRIPTOR, descriptor)
                putString(NasPreviewProtocol.MIME, mime)
            }
            Messenger(binder).send(Message.obtain(null, NasPreviewProtocol.RENDER).apply {
                replyTo = receiver
                data = payload
            })
        }.onFailure { completed.countDown() }
    }

    override fun onServiceDisconnected(name: ComponentName) { completed.countDown() }
    override fun onNullBinding(name: ComponentName) { completed.countDown() }
    override fun onBindingDied(name: ComponentName) { completed.countDown() }

    fun awaitShutdown() {
        // Do not send the next file to a process whose onDestroy is about to kill it.
        if (connected) died.await(SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun await(cancellation: NasCancellation, timeout: Long): ByteArray? {
        val started = SystemClock.elapsedRealtime()
        while (!completed.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            cancellation.throwIfCancelled()
            if (SystemClock.elapsedRealtime() - started >= timeout) return null
        }
        cancellation.throwIfCancelled()
        return png.get()?.takeIf { it.size in 1..NasPreviewProtocol.MAX_PNG_BYTES }
    }

    private companion object {
        const val POLL_MILLIS = 100L
        const val SHUTDOWN_MILLIS = 3000L
    }
}
