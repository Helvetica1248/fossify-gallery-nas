package org.fossify.gallery.nas.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.gallery.nas.transport.NasCancellation
import java.util.concurrent.Executors

/** Owns one visible UI request, including results produced while cancellation races with blocking I/O. */
internal class NasUiRequest {
    private var job: Job? = null
    private var cancellation: NasCancellation? = null

    fun <T : Any> start(scope: CoroutineScope, work: (NasCancellation) -> T,
                        discard: (T) -> Unit = {}, deliver: (Result<T>) -> Unit) {
        cancel()
        val token = NasCancellation()
        cancellation = token
        job = scope.launch {
            var result: T? = null
            try {
                val outcome = try {
                    withContext(Dispatchers.IO) { result = work(token) }
                    Result.success(checkNotNull(result))
                } catch (error: CancellationException) {
                    throw error
                } catch (ignored: Exception) {
                    // Never forward storage/SMB exception text to a view or logger.
                    Result.failure(IllegalStateException("NAS operation failed"))
                }
                ensureActive()
                deliver(outcome)
                result = null
            } finally {
                result?.let { value -> withContext(NonCancellable + Dispatchers.IO) { discard(value) } }
            }
        }
    }

    fun cancel(): Job? {
        val pending = job
        job?.cancel()
        job = null
        cancellation?.let { token -> cancellations.execute { token.cancel() } }
        cancellation = null
        return pending
    }

    companion object {
        fun dispose(action: () -> Unit) { cancellations.execute(action) }

        private val cancellations = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "nas-ui-cancel").apply { isDaemon = true }
        }
    }
}
