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

internal object NasExternalFiles {
    val tokens = NasOpenTokens()
    // Device probe only; Release never uses injected readers.
    internal var fixture: ((NasEntry, NasCancellation) -> NasSeekableHandle)? = null

    fun uri(context: Context, token: String): Uri = Uri.Builder().scheme("content")
        .authority(context.packageName + ".nas.external").appendPath("open").appendPath(token).build()

    fun entry(context: Context, uri: Uri): NasEntry {
        if (uri.scheme != "content" || uri.authority != context.packageName + ".nas.external" ||
            uri.pathSegments.size != 2 || uri.pathSegments[0] != "open" || uri.query != null || uri.fragment != null) {
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

    fun open(context: Context, entry: NasEntry, cancellation: NasCancellation): NasSeekableHandle {
        if (BuildConfig.DEBUG) fixture?.let { return it(entry, cancellation) }
        val app = context.applicationContext
        val settings = AndroidNasSettings.get(app)
        val source = settings.list().firstOrNull { it.source.key == entry.key.source }?.source
            ?: throw SmbFailureException(NasFailure.NOT_FOUND)
        val reader = SmbNasReader(AndroidSmbNetwork(app)) { settings.credentials(SavedNasSource(it, "")) }
        return when (val result = reader.openSeekable(source, entry, cancellation)) {
            is NasSeekableResult.Opened -> result.handle
            is NasSeekableResult.Failed -> throw SmbFailureException(result.reason)
            NasSeekableResult.Cancelled -> {
                cancellation.throwIfCancelled()
                throw FileNotFoundException("NAS cancelled")
            }
        }
    }
}
