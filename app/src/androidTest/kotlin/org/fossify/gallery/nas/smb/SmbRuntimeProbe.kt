package org.fossify.gallery.nas.smb

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.hierynomus.smbj.SMBClient
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.transport.NasCancellation
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Local-only device smoke test: no saved sources, NAS credentials or remote server are accessed. */
class SmbRuntimeProbe : Instrumentation() {
    private var stage = "start"
    @Volatile private var workerFailure = "none"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            cryptoAndAbort()
            // Exercise Android's network adapter without connecting to an endpoint.
            stage = "Android network"
            AndroidSmbNetwork(targetContext).select(NasConnectionMode.LAN)
            result.putString("result", "PASS: SMBJ crypto, Android network adapter, raw socket cancellation")
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("result", "FAIL: $stage / ${error.javaClass.simpleName} / $workerFailure")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun cryptoAndAbort() {
        val address = InetAddress.getLoopbackAddress()
        val route = object : SmbNetworkRoute {
            override val sockets: SocketFactory = SocketFactory.getDefault()
            override fun resolve(host: String) = arrayOf(address)
        }
        ServerSocket(0, 1, address).use { server ->
            server.soTimeout = SMB_TIMEOUT_MILLIS
            val cancellation = NasCancellation()
            val context = SmbRequestContext()
            val owner = cancellation.own(context)
            val config = SmbSafety.config(SmbSocketFactory(route, address, server.localPort, context, cancellation))
            stage = "crypto"
            val provider = config.securityProvider
            check(provider.getDigest("MD4").digest().size == HASH_BYTES)
            listOf("HMACT64", "HmacSHA256", "AESCMAC").forEach { algorithm ->
                stage = "crypto $algorithm"
                val mac = provider.getMac(algorithm)
                mac.init(ByteArray(HASH_BYTES))
                mac.update(byteArrayOf(1))
                check(mac.doFinal().isNotEmpty())
            }
            val client = context.own(SMBClient(config))
            val pool = Executors.newSingleThreadExecutor()
            try {
                val pending = pool.submit<Boolean> {
                    try {
                        client.connect(address.hostAddress, server.localPort)
                        false
                    } catch (error: Throwable) {
                        workerFailure = generateSequence(error) { it.cause }.take(4)
                            .joinToString { it.javaClass.simpleName }
                        true
                    }
                }
                stage = "accept"
                server.accept().use { peer ->
                    peer.soTimeout = SMB_TIMEOUT_MILLIS
                    stage = "negotiate"
                    check(peer.getInputStream().read() >= 0) // SMB negotiation is now waiting for a response.
                    stage = "cancel"
                    cancellation.cancel()
                    check(pending.get(ABORT_SECONDS, TimeUnit.SECONDS))
                }
            } finally {
                owner.close()
                pool.shutdownNow()
            }
        }
    }

    private companion object {
        const val HASH_BYTES = 16
        const val ABORT_SECONDS = 3L
    }
}
