@file:Suppress("MagicNumber", "LongMethod")

package org.fossify.gallery.nas

import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasCancelledException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun cancellationCases(): List<NasCoreCase> = buildList {
    add(NasCoreCase("cancel.check") {
        val cancellation = NasCancellation()
        cancellation.throwIfCancelled()
        expect(cancellation.cancel())
        expect(!cancellation.cancel())
        throws<NasCancelledException> { cancellation.throwIfCancelled() }
    })
    add(NasCoreCase("cancel.owner-and-cancel-close-once") {
        val closes = AtomicInteger()
        val cancellation = NasCancellation()
        val owner = cancellation.own(Closeable { closes.incrementAndGet() })
        owner.close()
        owner.close()
        cancellation.cancel()
        equal(1, closes.get())
    })
    add(NasCoreCase("cancel.cancel-and-owner-close-once") {
        val closes = AtomicInteger()
        val cancellation = NasCancellation()
        val owner = cancellation.own(Closeable { closes.incrementAndGet() })
        cancellation.cancel()
        owner.close()
        equal(1, closes.get())
    })
    add(NasCoreCase("cancel.late-resource-closed") {
        val closes = AtomicInteger()
        val cancellation = NasCancellation()
        cancellation.cancel()
        throws<NasCancelledException> { cancellation.own(Closeable { closes.incrementAndGet() }) }
        equal(1, closes.get())
    })
    add(NasCoreCase("cancel.cleanup-error-does-not-stop-other-closes") {
        val closes = AtomicInteger()
        val cancellation = NasCancellation()
        cancellation.own(Closeable { closes.incrementAndGet() })
        cancellation.own(Closeable { throw IOException("secret-bearing transport exception") })
        cancellation.own(Closeable { throw IllegalStateException("another transport failure") })
        cancellation.cancel()
        equal(1, closes.get())
        equal(2, cancellation.cleanupFailureCount)
    })
    add(NasCoreCase("cancel.reverse-ownership-order") {
        val order = arrayListOf<Int>()
        val cancellation = NasCancellation()
        repeat(3) { index -> cancellation.own(Closeable { order.add(index) }) }
        cancellation.cancel()
        equal(listOf(2, 1, 0), order)
    })
    add(NasCoreCase("cancel.register-race-100") {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val cancellation = NasCancellation()
                val start = CountDownLatch(1)
                val closes = AtomicInteger()
                val register = pool.submit {
                    expect(start.await(5, TimeUnit.SECONDS))
                    try {
                        cancellation.own(Closeable { closes.incrementAndGet() }).close()
                    } catch (ignored: NasCancelledException) {
                    }
                }
                val cancel = pool.submit {
                    expect(start.await(5, TimeUnit.SECONDS))
                    cancellation.cancel()
                }
                start.countDown()
                register.get(5, TimeUnit.SECONDS)
                cancel.get(5, TimeUnit.SECONDS)
                equal(1, closes.get())
            }
        } finally {
            pool.shutdownNow()
            expect(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    })
}
