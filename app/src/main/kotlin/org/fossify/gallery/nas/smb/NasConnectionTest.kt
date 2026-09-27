package org.fossify.gallery.nas.smb

import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.policy.NasRequestReason
import org.fossify.gallery.nas.policy.NasRetryGate
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasReader

/** Visible-screen gate only. No startup, timer or background network observer is attached. */
internal class NasConnectionTest(private val reader: NasReader) {
    private val gate = NasRetryGate()

    fun run(source: NasSource, cancellation: NasCancellation,
            reason: NasRequestReason = NasRequestReason.USER_RETRY): NasListingResult {
        if (!gate.tryAcquire(reason, 0)) return NasListingResult.Cancelled
        val result = reader.list(source, NasRelativePath.ROOT, cancellation)
        when (result) {
            is NasListingResult.Complete -> gate.recordSuccess()
            is NasListingResult.Failed -> gate.recordFailure(result.reason)
            else -> gate.deactivate()
        }
        return result
    }
}
