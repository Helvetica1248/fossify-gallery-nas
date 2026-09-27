package org.fossify.gallery.nas.policy

import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasFailure

/** VPN presence is only a necessary hint. It does not identify Tailscale or authenticate a NAS. */
data class NasNetworkState(val hasNetwork: Boolean, val hasVpnRoute: Boolean)

object NasConnectionPolicy {
    fun blockedReason(mode: NasConnectionMode, network: NasNetworkState): NasFailure? = when {
        mode == NasConnectionMode.VPN && !network.hasVpnRoute -> NasFailure.VPN_REQUIRED
        !network.hasNetwork -> NasFailure.UNREACHABLE
        else -> null
    }
}

enum class NasRequestReason { USER_OPEN, USER_RETRY, PREFETCH, NETWORK_CHANGED, APP_START }

/**
 * One gate per source configuration, owned by a visible NAS screen.
 * An Android lifecycle adapter must discard queued requests when the screen stops.
 * No timer, DNS, route fallback, package detection, or background work is started here.
 */
class NasRetryGate {
    private var armed = false
    private var failure: NasFailure? = null
    private var lastAttemptNetworkEpoch = -1L

    @Synchronized
    fun tryAcquire(reason: NasRequestReason, networkEpoch: Long): Boolean {
        require(networkEpoch >= 0) { "Invalid network epoch" }
        return when (reason) {
            NasRequestReason.APP_START -> false
            NasRequestReason.USER_OPEN, NasRequestReason.USER_RETRY -> {
                armed = true
                lastAttemptNetworkEpoch = maxOf(lastAttemptNetworkEpoch, networkEpoch)
                true
            }
            NasRequestReason.PREFETCH -> armed && failure == null
            NasRequestReason.NETWORK_CHANGED -> {
                if (!armed || failure !in TRANSIENT_FAILURES || networkEpoch <= lastAttemptNetworkEpoch) {
                    false
                } else {
                    lastAttemptNetworkEpoch = networkEpoch
                    true
                }
            }
        }
    }

    @Synchronized
    fun recordFailure(reason: NasFailure) {
        failure = reason
    }

    @Synchronized
    fun recordSuccess() {
        failure = null
    }

    @Synchronized
    fun deactivate() {
        armed = false
    }

    companion object {
        private val TRANSIENT_FAILURES = setOf(
            NasFailure.UNREACHABLE, NasFailure.VPN_REQUIRED, NasFailure.TIMED_OUT, NasFailure.IO_ERROR
        )
    }
}
