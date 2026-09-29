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
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun interface NasPreviewProcessor {
    fun thumbnail(entry: NasEntry, target: File, cancellation: NasCancellation): Boolean
}

/** One decoder at a time; cancellation/timeouts unbind and terminate its dedicated process. Call on IO. */
internal class AndroidNasPreviews(
    context: Context,
    private val decoderObserver: (String, Int) -> Unit = { _, _ -> }
) : NasPreviewProcessor {
    private val context = context.applicationContext
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
                val connection = PreviewConnection(descriptor, mime, decoderObserver)
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
        val binding = PreviewBinding(context, intent, connection)
        if (!binding.bind()) return null
        var reusable = false
        return try {
            connection.await(cancellation, timeout).also { reusable = it != null }
        } finally {
            signal.cancel()
            if (reusable) {
                PreviewWarmBindings.keep(binding)
            } else {
                PreviewWarmBindings.reset(binding.key)
                binding.unbind()
                connection.awaitShutdown()
            }
        }
    }

    private companion object {
        val decoder = Semaphore(1, true)
        const val POLL_MILLIS = 100L
    }
}

private class PreviewBinding(
    private val context: Context,
    intent: Intent,
    private val connection: ServiceConnection
) {
    val key: String = checkNotNull(intent.component).className
    private val intent = intent
    private val bound = AtomicBoolean()

    fun bind(): Boolean {
        val result = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (result) bound.set(true)
        return result
    }

    fun unbind() {
        if (bound.compareAndSet(true, false)) runCatching { context.unbindService(connection) }
    }
}

private object PreviewWarmBindings {
    private const val WARM_MILLIS = 3_000L
    private val lock = Any()
    private val bindings = mutableMapOf<String, MutableSet<PreviewBinding>>()
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "nas-preview-warm").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun keep(binding: PreviewBinding) {
        synchronized(lock) { bindings.getOrPut(binding.key) { mutableSetOf() }.add(binding) }
        timer.schedule({ release(binding) }, WARM_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun reset(key: String) {
        val stale = synchronized(lock) { bindings.remove(key)?.toList().orEmpty() }
        stale.forEach(PreviewBinding::unbind)
    }

    private fun release(binding: PreviewBinding) {
        synchronized(lock) {
            bindings[binding.key]?.let {
                it.remove(binding)
                if (it.isEmpty()) bindings.remove(binding.key)
            }
        }
        binding.unbind()
    }
}

private class PreviewConnection(
    private val descriptor: ParcelFileDescriptor,
    private val mime: String,
    private val decoderObserver: (String, Int) -> Unit
) : ServiceConnection {
    private val completed = CountDownLatch(1)
    private val died = CountDownLatch(1)
    @Volatile private var connected = false
    private val png = AtomicReference<ByteArray?>()
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        png.set(message.data.getByteArray(NasPreviewProtocol.PNG))
        message.data.getInt(NasPreviewProtocol.PID, -1).takeIf { it > 0 }?.let {
            decoderObserver(mime, it)
        }
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
