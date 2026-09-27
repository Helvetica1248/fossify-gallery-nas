package org.fossify.gallery.nas.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.model.NasHost
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.policy.NasRequestReason
import org.fossify.gallery.nas.settings.NasCredentials
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasCancelledException
import org.fossify.gallery.nas.transport.NasReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class SmbTransportTest {
    private fun source(mode: NasConnectionMode = NasConnectionMode.LAN) = NasSource(
        NasSourceKey(UUID.randomUUID(), 1), NasHost.parse("127.0.0.1"), "media",
        NasRelativePath.parse("photos"), mode, UUID.randomUUID()
    )

    @Test fun credentialsAndReadOnlyConfiguration() {
        val auth = SmbSafety.authentication(NasCredentials("DOMAIN\\user", ""))
        assertEquals("DOMAIN", auth.domain)
        assertEquals("user", auth.username)
        assertEquals(0, auth.password.size)
        assertNull(SmbSafety.authentication(NasCredentials("user", "pass")).domain)
        assertEquals(setOf(AccessMask.GENERIC_READ), SmbSafety.access)
        assertEquals(SMB2CreateDisposition.FILE_OPEN, SmbSafety.disposition)
        assertTrue(SmbSafety.noFollow.contains(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT))
        val config = SmbSafety.config(SocketFactory.getDefault())
        assertTrue(config.isSigningRequired)
        assertTrue(config.isSigningEnabled)
        assertFalse(config.isDfsEnabled)
        assertFalse(config.isUseMultiProtocolNegotiate)
        assertFalse(config.isEncryptData)
        assertEquals(SMB_TIMEOUT_MILLIS, config.soTimeout)
        assertEquals(SMB_TIMEOUT_MILLIS.toLong(), config.transactTimeout)
        assertEquals(5, config.supportedDialects.size)
    }

    @Test fun listingRequiresNormalEndAndRejectsPartialOrRepeatedPages() {
        val item = source()
        val page = directoryRecord("test.jpg")
        fun collect(pages: List<SmbDirectoryPage>): List<NasEntry> {
            val iterator = pages.iterator()
            return SmbDirectoryListing.collect(item, NasRelativePath.ROOT, NasCancellation()) { iterator.next() }
        }
        val good = SmbDirectoryPage(NtStatus.STATUS_SUCCESS.value, page)
        val end = SmbDirectoryPage(NtStatus.STATUS_NO_MORE_FILES.value, byteArrayOf())
        assertEquals("test.jpg", collect(listOf(good, end)).single().name)
        assertTrue(collect(listOf(end)).isEmpty())
        assertThrows(SmbFailureException::class.java) { collect(listOf(good, good)) }
        assertThrows(SmbFailureException::class.java) {
            collect(listOf(good, SmbDirectoryPage(NtStatus.STATUS_ACCESS_DENIED.value, byteArrayOf())))
        }
        assertThrows(SocketTimeoutException::class.java) {
            var first = true
            SmbDirectoryListing.collect(item, NasRelativePath.ROOT, NasCancellation()) {
                if (first) { first = false; good } else throw SocketTimeoutException()
            }
        }
    }

    @Test fun safeFailureClassification() {
        assertEquals(NasFailure.AUTHENTICATION_FAILED, SmbSafety.statusFailure(NtStatus.STATUS_LOGON_FAILURE.value))
        assertEquals(NasFailure.ACCESS_DENIED, SmbSafety.statusFailure(NtStatus.STATUS_ACCESS_DENIED.value))
        assertEquals(NasFailure.NOT_FOUND, SmbSafety.statusFailure(NtStatus.STATUS_BAD_NETWORK_NAME.value))
        assertEquals(NasFailure.UNSUPPORTED_PROTOCOL, SmbSafety.statusFailure(NtStatus.STATUS_NOT_SUPPORTED.value))
        assertEquals(NasFailure.TIMED_OUT, SmbSafety.failure(IOException(SocketTimeoutException("secret"))))
        assertEquals(NasFailure.IO_ERROR, SmbSafety.failure(IOException("secret")))
    }

    @Test fun cancellationClosesRawSocketBeforeOtherResourcesAndUnblocksRead() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val cancellation = NasCancellation()
            val context = SmbRequestContext()
            cancellation.own(context)
            val address = InetAddress.getLoopbackAddress()
            val route = object : SmbNetworkRoute {
                override val sockets: SocketFactory = SocketFactory.getDefault()
                override fun resolve(host: String) = arrayOf(address)
            }
            val raw = SmbSocketFactory(route, address, server.localPort, context, cancellation)
                .createSocket(address.hostAddress, server.localPort)
            server.accept().use {
                val closed = mutableListOf<Int>()
                context.own(AutoCloseable { assertTrue(raw.isClosed); closed.add(1) })
                context.own(AutoCloseable { closed.add(2); throw IOException() })
                val started = CountDownLatch(1)
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val read = pool.submit<Boolean> {
                        started.countDown()
                        try { raw.getInputStream().read(); false } catch (_: IOException) { true }
                    }
                    assertTrue(started.await(1, TimeUnit.SECONDS))
                    cancellation.cancel()
                    assertTrue(read.get(2, TimeUnit.SECONDS))
                    context.close()
                    assertEquals(listOf(2, 1), closed)
                    assertThrows(NasCancelledException::class.java) { context.own(Socket()) }
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun vpnUsesSelectedFactoryWithoutFallbackAndMissingVpnStopsBeforeCredentials() {
        var sockets = 0
        val route = object : SmbNetworkRoute {
            override val sockets = object : SocketFactory() {
                override fun createSocket(): Socket { sockets++; throw IOException("bound route failed") }
                override fun createSocket(h: String, p: Int): Socket = error("unexpected")
                override fun createSocket(h: InetAddress, p: Int): Socket = error("unexpected")
                override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("unexpected")
                override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("unexpected")
            }
            override fun resolve(host: String) = arrayOf(InetAddress.getLoopbackAddress())
        }
        val reader = SmbNasReader(SmbNetworkProvider { mode -> assertEquals(NasConnectionMode.VPN, mode); route }) {
            NasCredentials("user", "")
        }
        assertTrue(reader.list(source(NasConnectionMode.VPN), NasRelativePath.ROOT, NasCancellation())
            is NasListingResult.Failed)
        assertEquals(1, sockets)
        val absent = SmbNasReader(SmbNetworkProvider { throw SmbFailureException(NasFailure.VPN_REQUIRED) }) {
            error("Credentials must not be loaded without VPN")
        }
        assertEquals(NasListingResult.Failed(NasFailure.VPN_REQUIRED),
            absent.list(source(NasConnectionMode.VPN), NasRelativePath.ROOT, NasCancellation()))
    }

    @Test fun connectionTestNeverRunsAtStartup() {
        var calls = 0
        val reader = object : NasReader {
            override fun list(source: NasSource, folder: NasRelativePath,
                              cancellation: NasCancellation): NasListingResult {
                calls++
                assertTrue(folder.isRoot)
                return NasListingResult.Complete(emptyList())
            }
            override fun open(source: NasSource, entry: NasEntry, cancellation: NasCancellation) = error("not used")
        }
        val test = NasConnectionTest(reader)
        assertSame(NasListingResult.Cancelled, test.run(source(), NasCancellation(), NasRequestReason.APP_START))
        assertEquals(0, calls)
        assertTrue(test.run(source(), NasCancellation()) is NasListingResult.Complete)
        assertEquals(1, calls)
    }

    @Test fun shortReadCannotBecomeSuccessfulEofAndCloseOwnsRequest() {
        var closed = false
        val input = SmbReadInput(10, NasCancellation(), Closeable { closed = true }) { _, _, _, _ -> -1 }
        assertThrows(IOException::class.java) { input.read() }
        input.close()
        assertTrue(closed)
        val empty = SmbReadInput(0, NasCancellation(), Closeable {}) { _, _, _, _ -> -1 }
        assertEquals(-1, empty.read())
    }

    @Test fun reparseEntryIsSkippedAndTraversalEntryFails() {
        fun collect(name: String, flags: Int): List<NasEntry> {
            var first = true
            return SmbDirectoryListing.collect(source(), NasRelativePath.ROOT, NasCancellation()) {
                if (first) {
                    first = false
                    SmbDirectoryPage(NtStatus.STATUS_SUCCESS.value, directoryRecord(name, flags))
                } else SmbDirectoryPage(NtStatus.STATUS_NO_MORE_FILES.value, byteArrayOf())
            }
        }
        assertTrue(collect("link", 0x400).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { collect("../escape", 0) }
    }

    private fun directoryRecord(name: String, flags: Int = 0): ByteArray {
        val text = name.toByteArray(Charsets.UTF_16LE)
        val buffer = ByteBuffer.allocate(104 + text.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(0)
        repeat(4) { buffer.putLong(0) }
        buffer.putLong(0).putLong(0).putInt(flags).putInt(text.size).putInt(0)
        buffer.position(104)
        buffer.put(text)
        return buffer.array()
    }
}
