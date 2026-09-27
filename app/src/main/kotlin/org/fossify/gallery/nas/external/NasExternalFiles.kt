package org.fossify.gallery.nas.external

import android.content.Context
import android.net.Uri
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.repository.NasNetworkGate
import org.fossify.gallery.nas.settings.AndroidNasSettings
import org.fossify.gallery.nas.settings.SavedNasSource
import org.fossify.gallery.nas.smb.AndroidSmbNetwork
import org.fossify.gallery.nas.smb.SmbFailureException
import org.fossify.gallery.nas.smb.SmbNasReader
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasSeekableHandle
import org.fossify.gallery.nas.transport.NasSeekableResult
import java.io.FileNotFoundException

private const val EXTERNAL_SOCKET_IDLE_MILLIS = 11 * 60 * 1000

internal object NasExternalFiles {
    val tokens = NasOpenTokens()
    // Device probe only; Release never uses injected readers.
    internal var fixture: ((NasEntry, NasCancellation) -> NasSeekableHandle)? = null

    fun uri(context: Context, token: String): Uri = Uri.Builder().scheme("content")
        .authority(context.packageName + ".nas.external").appendPath("open").appendPath(token).build()

    fun entry(context: Context, uri: Uri): NasEntry {
        val endpoint = uri.scheme == "content" && uri.authority == context.packageName + ".nas.external"
        val path = uri.pathSegments.size == 2 && uri.pathSegments[0] == "open"
        val plain = uri.query == null && uri.fragment == null
        if (!endpoint || !path || !plain) {
            throw FileNotFoundException("NAS URI unavailable")
        }
        return tokens.get(uri.pathSegments[1]) ?: throw FileNotFoundException("NAS URI expired; reopen from Gallery")
    }

    /** Call on IO. The preflight gives Gallery a safe error before launching another app. */
    fun prepare(context: Context, entry: NasEntry, cancellation: NasCancellation): String {
        require(NasExternalTypes.mime(entry) != null)
        NasNetworkGate.run(cancellation) { open(context, entry, cancellation).use { } }
        cancellation.throwIfCancelled()
        return tokens.issue(entry)
    }

    private fun cancelled(cancellation: NasCancellation): Nothing {
        cancellation.throwIfCancelled()
        throw FileNotFoundException("NAS cancelled")
    }

    fun open(context: Context, entry: NasEntry, cancellation: NasCancellation): NasSeekableHandle {
        if (BuildConfig.DEBUG) fixture?.let { return it(entry, cancellation) }
        val app = context.applicationContext
        val settings = AndroidNasSettings.get(app)
        val source = settings.list().firstOrNull { it.source.key == entry.key.source }?.source
            ?: throw SmbFailureException(NasFailure.NOT_FOUND)
        // The packet reader must survive normal pauses between PDF pages or video buffers.
        // SMB commands remain bounded to 15 seconds; the proxy additionally aborts stalled reads.
        val reader = SmbNasReader(AndroidSmbNetwork(app), EXTERNAL_SOCKET_IDLE_MILLIS) {
            settings.credentials(SavedNasSource(it, ""))
        }
        return when (val result = reader.openSeekable(source, entry, cancellation)) {
            is NasSeekableResult.Opened -> result.handle
            is NasSeekableResult.Failed -> throw SmbFailureException(result.reason)
            NasSeekableResult.Cancelled -> cancelled(cancellation)
        }
    }
}
