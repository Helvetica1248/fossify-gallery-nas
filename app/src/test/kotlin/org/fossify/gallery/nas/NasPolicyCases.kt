package org.fossify.gallery.nas

import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.policy.NasConnectionPolicy
import org.fossify.gallery.nas.policy.NasNetworkState
import org.fossify.gallery.nas.policy.NasRequestReason
import org.fossify.gallery.nas.policy.NasRetryGate
import org.fossify.gallery.nas.transport.NasDeadline
import org.fossify.gallery.nas.transport.NasMonotonicClock
import org.fossify.gallery.nas.transport.NasReadLimits

internal fun policyCases(): List<NasCoreCase> = buildList {
    NasConnectionMode.values().forEach { mode ->
        listOf(false, true).forEach { network ->
            listOf(false, true).forEach { vpn ->
                add(NasCoreCase("connection.$mode.network-$network.vpn-$vpn") {
                    val expected = when {
                        mode == NasConnectionMode.VPN && !vpn -> NasFailure.VPN_REQUIRED
                        !network -> NasFailure.UNREACHABLE
                        else -> null
                    }
                    equal(expected, NasConnectionPolicy.blockedReason(mode, NasNetworkState(network, vpn)))
                })
            }
        }
    }
    add(NasCoreCase("retry.startup-never-connects") {
        expect(!NasRetryGate().tryAcquire(NasRequestReason.APP_START, 0))
    })
    add(NasCoreCase("retry.unopened-source-never-autoprobes") {
        expect(!NasRetryGate().tryAcquire(NasRequestReason.NETWORK_CHANGED, 1))
    })
    add(NasCoreCase("retry.unopened-source-never-prefetches") {
        expect(!NasRetryGate().tryAcquire(NasRequestReason.PREFETCH, 0))
    })
    add(NasCoreCase("retry.failure-stops-prefetch") {
        val gate = NasRetryGate()
        expect(gate.tryAcquire(NasRequestReason.USER_OPEN, 1))
        expect(gate.tryAcquire(NasRequestReason.PREFETCH, 1))
        gate.recordFailure(NasFailure.UNREACHABLE)
        expect(!gate.tryAcquire(NasRequestReason.PREFETCH, 1))
        gate.recordSuccess()
        expect(gate.tryAcquire(NasRequestReason.PREFETCH, 1))
    })
    add(NasCoreCase("retry.one-probe-per-new-network") {
        val gate = NasRetryGate()
        gate.tryAcquire(NasRequestReason.USER_OPEN, 1)
        gate.recordFailure(NasFailure.UNREACHABLE)
        expect(!gate.tryAcquire(NasRequestReason.NETWORK_CHANGED, 1))
        expect(gate.tryAcquire(NasRequestReason.NETWORK_CHANGED, 2))
        expect(!gate.tryAcquire(NasRequestReason.NETWORK_CHANGED, 2))
        expect(!gate.tryAcquire(NasRequestReason.NETWORK_CHANGED, 1))
        expect(gate.tryAcquire(NasRequestReason.NETWORK_CHANGED, 3))
    })
    NasFailure.values().forEach { reason ->
        add(NasCoreCase("retry.category.$reason") {
            val gate = NasRetryGate()
            gate.tryAcquire(NasRequestReason.USER_OPEN, 1)
            gate.recordFailure(reason)
            val transient = reason in setOf(
                NasFailure.UNREACHABLE,
                NasFailure.VPN_REQUIRED,
                NasFailure.TIMED_OUT,
                NasFailure.IO_ERROR
            )
            equal(transient, gate.tryAcquire(NasRequestReason.NETWORK_CHANGED, 2))
            expect(gate.tryAcquire(NasRequestReason.USER_RETRY, 2))
        })
    }
    add(NasCoreCase("retry.background-deactivation") {
        val gate = NasRetryGate()
        gate.tryAcquire(NasRequestReason.USER_OPEN, 1)
        gate.recordFailure(NasFailure.UNREACHABLE)
        gate.deactivate()
        expect(!gate.tryAcquire(NasRequestReason.NETWORK_CHANGED, 2))
        expect(!gate.tryAcquire(NasRequestReason.PREFETCH, 2))
    })
    add(NasCoreCase("limits.defaults-finite") {
        val limits = NasReadLimits()
        equal(15_000, limits.idleTimeoutMillis)
        equal(120_000L, limits.transferTimeoutMillis)
        equal(2, limits.maxConcurrentRequests)
    })
    listOf<() -> NasReadLimits>(
        { NasReadLimits(connectTimeoutMillis = 0) }, { NasReadLimits(idleTimeoutMillis = 0) },
        { NasReadLimits(transferTimeoutMillis = 0) }, { NasReadLimits(maxOriginalBytes = 0) },
        { NasReadLimits(maxConcurrentRequests = 3) }, { NasReadLimits(maxOriginalBytes = Long.MAX_VALUE) }
    ).forEachIndexed { i, build ->
        add(NasCoreCase("limits.reject.$i") { throws<IllegalArgumentException> { build() } })
    }
    add(NasCoreCase("deadline.boundary") {
        var now = 0L
        val deadline = NasDeadline(1, NasMonotonicClock { now })
        now = 999_999
        expect(!deadline.isExpired())
        now++
        expect(deadline.isExpired())
    })
    add(NasCoreCase("deadline.nanoTime-wrap") {
        var now = Long.MAX_VALUE - 500_000
        val deadline = NasDeadline(1, NasMonotonicClock { now })
        now += 1_000_000
        expect(deadline.isExpired())
    })
}
