@file:Suppress("MagicNumber", "LongMethod")

package org.fossify.gallery.nas

import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasTransferResult
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasDeadline
import org.fossify.gallery.nas.transport.NasMonotonicClock
import org.fossify.gallery.nas.transport.NasStreamCopier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private open class TrackedInput(data: ByteArray) : ByteArrayInputStream(data) {
    val closes = AtomicInteger()
    override fun close() { closes.incrementAndGet(); super.close() }
}

private open class TrackedOutput : ByteArrayOutputStream() {
    val closes = AtomicInteger()
    override fun close() { closes.incrementAndGet(); super.close() }
}

private fun foreverDeadline() = NasDeadline(120_000, NasMonotonicClock { 0 })

internal fun transferCases(): List<NasCoreCase> = buildList {
    listOf(0, 1, 4, 65_535, 65_536, 131_079).forEach { size ->
        add(NasCoreCase("transfer.complete.$size") {
            val data = ByteArray(size) { (it % 251).toByte() }
            val input = TrackedInput(data)
            val output = TrackedOutput()
            val result = NasStreamCopier.copy(
                input,
                output,
                NasCancellation(),
                foreverDeadline(),
                maxOf(1, size).toLong(),
                size.toLong()
            )
            equal(NasTransferResult.Complete(size.toLong()), result)
            expect(data.contentEquals(output.toByteArray()))
            equal(1, input.closes.get())
            equal(1, output.closes.get())
        })
    }
    add(NasCoreCase("transfer.unknown-length") {
        val output = TrackedOutput()
        equal(
            NasTransferResult.Complete(3),
            NasStreamCopier.copy(TrackedInput(byteArrayOf(1, 2, 3)), output, NasCancellation(), foreverDeadline(), 10)
        )
    })
    add(NasCoreCase("transfer.limit-not-exceeded-on-disk") {
        val output = TrackedOutput()
        equal(
            NasTransferResult.Failed(NasFailure.TRANSFER_TOO_LARGE),
            NasStreamCopier.copy(TrackedInput(ByteArray(5)), output, NasCancellation(), foreverDeadline(), 4)
        )
        expect(output.size() <= 4)
    })
    listOf(2L, 4L).forEach { expected ->
        add(NasCoreCase("transfer.changed-length.$expected") {
            val result = NasStreamCopier.copy(
                TrackedInput(ByteArray(3)),
                TrackedOutput(),
                NasCancellation(),
                foreverDeadline(),
                8,
                expected
            )
            equal(NasTransferResult.Failed(NasFailure.CONTENT_CHANGED), result)
        })
    }
    add(NasCoreCase("transfer.advertised-oversize-not-read") {
        val input = object : TrackedInput(ByteArray(1)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw AssertionError("Should not read")
        }
        val output = TrackedOutput()
        equal(
            NasTransferResult.Failed(NasFailure.TRANSFER_TOO_LARGE),
            NasStreamCopier.copy(input, output, NasCancellation(), foreverDeadline(), 4, 5)
        )
        equal(1, input.closes.get())
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.read-error-closes-both") {
        val input = object : TrackedInput(ByteArray(1)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw IOException("read failed")
        }
        val output = TrackedOutput()
        equal(
            NasTransferResult.Failed(NasFailure.IO_ERROR),
            NasStreamCopier.copy(input, output, NasCancellation(), foreverDeadline(), 4)
        )
        equal(1, input.closes.get())
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.write-error-closes-both") {
        val input = TrackedInput(ByteArray(1))
        val output = object : TrackedOutput() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { throw IOException("write failed") }
        }
        equal(
            NasTransferResult.Failed(NasFailure.IO_ERROR),
            NasStreamCopier.copy(input, output, NasCancellation(), foreverDeadline(), 4)
        )
        equal(1, input.closes.get())
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.flush-error-is-not-complete") {
        val output = object : TrackedOutput() { override fun flush() { throw IOException("flush failed") } }
        equal(
            NasTransferResult.Failed(NasFailure.IO_ERROR),
            NasStreamCopier.copy(TrackedInput(ByteArray(1)), output, NasCancellation(), foreverDeadline(), 4)
        )
    })
    add(NasCoreCase("transfer.close-error-is-not-complete") {
        val output = object : TrackedOutput() {
            override fun close() {
                super.close()
                throw IOException("close failed")
            }
        }
        equal(
            NasTransferResult.Failed(NasFailure.IO_ERROR),
            NasStreamCopier.copy(TrackedInput(ByteArray(1)), output, NasCancellation(), foreverDeadline(), 4)
        )
    })
    add(NasCoreCase("transfer.input-close-error-is-not-complete") {
        val input = object : TrackedInput(ByteArray(1)) {
            override fun close() {
                super.close()
                throw IOException("close failed")
            }
        }
        equal(
            NasTransferResult.Failed(NasFailure.IO_ERROR),
            NasStreamCopier.copy(input, TrackedOutput(), NasCancellation(), foreverDeadline(), 4)
        )
    })
    add(NasCoreCase("transfer.zero-read-cannot-spin-forever") {
        var reads = 0
        val input = object : TrackedInput(ByteArray(1)) { override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int
        ): Int { reads++; return 0 } }
        equal(
            NasTransferResult.Failed(NasFailure.IO_ERROR),
            NasStreamCopier.copy(input, TrackedOutput(), NasCancellation(), foreverDeadline(), 4)
        )
        equal(8, reads)
    })
    add(NasCoreCase("transfer.invalid-read-count") {
        val input = object : TrackedInput(ByteArray(1)) { override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int
        ) = length + 1 }
        equal(
            NasTransferResult.Failed(NasFailure.INVALID_RESPONSE),
            NasStreamCopier.copy(input, TrackedOutput(), NasCancellation(), foreverDeadline(), 4)
        )
    })
    add(NasCoreCase("transfer.expired-before-read") {
        var time = 0L
        val deadline = NasDeadline(1, NasMonotonicClock { time })
        time = 1_000_000
        val input = object : TrackedInput(ByteArray(1)) { override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int
        ): Int = throw AssertionError("expired read") }
        equal(
            NasTransferResult.Failed(NasFailure.TIMED_OUT),
            NasStreamCopier.copy(input, TrackedOutput(), NasCancellation(), deadline, 4)
        )
    })
    add(NasCoreCase("transfer.expired-during-read-not-written") {
        var time = 0L
        val deadline = NasDeadline(1, NasMonotonicClock { time })
        val input = object : TrackedInput(ByteArray(1)) {
            override fun read(
                bytes: ByteArray,
                offset: Int,
                length: Int
            ): Int { time = 1_000_000; return super.read(bytes, offset, length) }
        }
        val output = TrackedOutput()
        equal(
            NasTransferResult.Failed(NasFailure.TIMED_OUT),
            NasStreamCopier.copy(input, output, NasCancellation(), deadline, 4)
        )
        equal(0, output.size())
    })
    add(NasCoreCase("transfer.expired-during-flush") {
        var time = 0L
        val deadline = NasDeadline(1, NasMonotonicClock { time })
        val output = object : TrackedOutput() { override fun flush() { time = 1_000_000 } }
        equal(
            NasTransferResult.Failed(NasFailure.TIMED_OUT),
            NasStreamCopier.copy(TrackedInput(ByteArray(1)), output, NasCancellation(), deadline, 4)
        )
    })
    add(NasCoreCase("transfer.expired-during-close") {
        var time = 0L
        val deadline = NasDeadline(1, NasMonotonicClock { time })
        val output = object : TrackedOutput() { override fun close() { super.close(); time = 1_000_000 } }
        equal(
            NasTransferResult.Failed(NasFailure.TIMED_OUT),
            NasStreamCopier.copy(TrackedInput(ByteArray(1)), output, NasCancellation(), deadline, 4)
        )
    })
    add(NasCoreCase("transfer.cancelled-before-ownership") {
        val input = TrackedInput(ByteArray(1))
        val output = TrackedOutput()
        val cancellation = NasCancellation().apply { cancel() }
        equal(NasTransferResult.Cancelled, NasStreamCopier.copy(input, output, cancellation, foreverDeadline(), 4))
        equal(1, input.closes.get())
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.cancelled-during-read") {
        val cancellation = NasCancellation()
        val input = object : TrackedInput(ByteArray(1)) {
            override fun read(
                bytes: ByteArray,
                offset: Int,
                length: Int
            ): Int { cancellation.cancel(); return super.read(bytes, offset, length) }
        }
        val output = TrackedOutput()
        equal(NasTransferResult.Cancelled, NasStreamCopier.copy(input, output, cancellation, foreverDeadline(), 4))
        equal(0, output.size())
        equal(1, input.closes.get())
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.cancelled-during-write") {
        val cancellation = NasCancellation()
        val output = object : TrackedOutput() {
            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int
            ) { super.write(bytes, offset, length); cancellation.cancel() }
        }
        equal(
            NasTransferResult.Cancelled,
            NasStreamCopier.copy(TrackedInput(ByteArray(1)), output, cancellation, foreverDeadline(), 4)
        )
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.unchecked-read-closes-both") {
        val input = object : TrackedInput(ByteArray(1)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                error("unchecked read")
            }
        }
        val output = TrackedOutput()
        val error = throws<IllegalStateException> {
            NasStreamCopier.copy(input, output, NasCancellation(), foreverDeadline(), 4)
        }
        equal("unchecked read", error.message)
        equal(1, input.closes.get())
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.general-cancellation-closes-both") {
        val input = object : TrackedInput(ByteArray(1)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                throw CancellationException("upstream cancellation")
            }
        }
        val output = TrackedOutput()
        equal(
            NasTransferResult.Cancelled,
            NasStreamCopier.copy(input, output, NasCancellation(), foreverDeadline(), 4)
        )
        equal(1, input.closes.get())
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.unchecked-output-close-still-closes-input") {
        val input = TrackedInput(ByteArray(1))
        val output = object : TrackedOutput() {
            override fun close() {
                super.close()
                error("unchecked close")
            }
        }
        val error = throws<IllegalStateException> {
            NasStreamCopier.copy(input, output, NasCancellation(), foreverDeadline(), 4)
        }
        equal("unchecked close", error.message)
        equal(1, input.closes.get())
        equal(1, output.closes.get())
    })
    add(NasCoreCase("transfer.fake-blocking-stream-reclaimed-20-cycles") {
        // This is a close-unblocked fake stream, NOT evidence of SMBJ or Tailscale behaviour.
        val pool = Executors.newSingleThreadExecutor()
        try {
            repeat(20) {
                val started = CountDownLatch(1)
                val closed = CountDownLatch(1)
                val closes = AtomicInteger()
                val cancellation = NasCancellation()
                val input = object : InputStream() {
                    override fun read(): Int {
                        started.countDown()
                        if (!closed.await(5, TimeUnit.SECONDS)) throw AssertionError("Fake stream was not closed")
                        throw IOException("request closed")
                    }
                    override fun close() { closes.incrementAndGet(); closed.countDown() }
                }
                val output = TrackedOutput()
                val request = pool.submit<NasTransferResult> { NasStreamCopier.copy(
                    input,
                    output,
                    cancellation,
                    foreverDeadline(),
                    4
                ) }
                expect(started.await(5, TimeUnit.SECONDS))
                cancellation.cancel()
                equal(NasTransferResult.Cancelled, request.get(5, TimeUnit.SECONDS))
                equal(1, closes.get())
                equal(1, output.closes.get())
            }
        } finally {
            pool.shutdownNow()
            expect(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    })
}
