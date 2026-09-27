package org.fossify.gallery.nas.smb

import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.transport.NasCancellation
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal const val SMB_TIMEOUT_MILLIS = 15_000

internal interface SmbNetworkRoute {
    val sockets: SocketFactory
    fun resolve(host: String): Array<InetAddress>
}

internal fun interface SmbNetworkProvider {
    fun select(mode: NasConnectionMode): SmbNetworkRoute
}

/** Native DNS cannot always be interrupted. Bound both wait time and outstanding resolver threads. */
internal object SmbDns {
    private const val RESOLVER_THREADS = 2
    private const val QUEUED_RESOLUTIONS = 2
    private val executor = ThreadPoolExecutor(
        RESOLVER_THREADS, RESOLVER_THREADS, 0, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(QUEUED_RESOLUTIONS),
        { task -> Thread(task, "nas-dns").apply { isDaemon = true } }
    )

    fun resolve(route: SmbNetworkRoute, host: String, context: SmbRequestContext): InetAddress {
        val future = executor.submit<Array<InetAddress>> { route.resolve(host) }
        context.own(AutoCloseable { future.cancel(true) })
        return try {
            future.get(SMB_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS).firstOrNull()
                ?: throw SmbFailureException(NasFailure.UNREACHABLE)
        } finally {
            future.cancel(true)
            executor.purge()
        }
    }
}

/** Every overload uses the selected route and address. No default factory or address retry. */
internal class SmbSocketFactory(
    private val route: SmbNetworkRoute,
    private val address: InetAddress,
    private val port: Int,
    private val context: SmbRequestContext,
    private val cancellation: NasCancellation
) : SocketFactory() {
    override fun createSocket(): Socket {
        cancellation.throwIfCancelled()
        return context.ownSocket(route.sockets.createSocket()).apply { soTimeout = SMB_TIMEOUT_MILLIS }
    }

    private fun connected(): Socket {
        val endpoint = InetSocketAddress(address, port)
        return createSocket().apply {
            cancellation.throwIfCancelled()
            connect(endpoint, SMB_TIMEOUT_MILLIS)
        }
    }

    override fun createSocket(host: String, port: Int): Socket = connected()
    override fun createSocket(host: InetAddress, port: Int): Socket = connected()
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = connected()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = connected()
}
