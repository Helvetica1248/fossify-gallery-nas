package org.fossify.gallery.nas.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.policy.NasConnectionPolicy
import org.fossify.gallery.nas.policy.NasNetworkState
import java.net.InetAddress
import javax.net.SocketFactory

internal class AndroidSmbNetwork(context: Context) : SmbNetworkProvider {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override fun select(mode: NasConnectionMode): SmbNetworkRoute {
        val vpn = manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val state = NasNetworkState(manager.activeNetwork != null || vpn != null, vpn != null)
        NasConnectionPolicy.blockedReason(mode, state)?.let { throw SmbFailureException(it) }
        return if (mode == NasConnectionMode.VPN) {
            val network = checkNotNull(vpn)
            object : SmbNetworkRoute {
                override val sockets: SocketFactory = network.socketFactory
                override fun resolve(host: String): Array<InetAddress> = network.getAllByName(host)
            }
        } else {
            object : SmbNetworkRoute {
                override val sockets: SocketFactory = SocketFactory.getDefault()
                override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
            }
        }
    }
}
