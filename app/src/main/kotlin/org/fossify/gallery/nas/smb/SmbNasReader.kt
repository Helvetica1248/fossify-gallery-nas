package org.fossify.gallery.nas.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.paths.PathResolver
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.settings.NasCredentials
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasOpenResult
import org.fossify.gallery.nas.transport.NasReadHandle
import org.fossify.gallery.nas.transport.NasReader
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.CancellationException

/** One client/session/socket per call. Only read capabilities cross the NasReader boundary. */
// The transport boundary maps checked and unchecked SMBJ/parser failures without exposing their messages.
@Suppress("TooGenericExceptionCaught")
internal class SmbNasReader(
    private val network: SmbNetworkProvider,
    private val credentials: (NasSource) -> NasCredentials?
) : NasReader {
    override fun list(source: NasSource, folder: NasRelativePath, cancellation: NasCancellation): NasListingResult {
        var owner: Closeable? = null
        return try {
            val context = SmbRequestContext()
            owner = cancellation.own(context)
            val share = connect(source, cancellation, context)
            val directory = directory(share, source.pathWithinShare(folder), context, cancellation)
            val entries = SmbDirectoryListing.list(share, directory, source, folder, cancellation)
            cancellation.throwIfCancelled()
            NasListingResult.Complete(entries)
        } catch (_: CancellationException) {
            NasListingResult.Cancelled
        } catch (error: Exception) {
            if (cancellation.isCancelled) NasListingResult.Cancelled
            else NasListingResult.Failed(SmbSafety.failure(error))
        } finally {
            owner?.close()
        }
    }

    override fun open(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasOpenResult {
        var owner: Closeable? = null
        var transferred = false
        return try {
            if (entry.kind != NasEntryKind.FILE || entry.key.source != source.key) {
                throw SmbFailureException(NasFailure.INVALID_RESPONSE)
            }
            val context = SmbRequestContext()
            owner = cancellation.own(context)
            val share = connect(source, cancellation, context)
            val path = source.pathWithinShare(entry.key.path)
            directory(share, checkNotNull(path.parent), context, cancellation)
            val file = context.own(share.open(
                wirePath(path), SmbSafety.access, emptySet(), SmbSafety.sharing, SmbSafety.disposition,
                SmbSafety.noFollow + SMB2CreateOptions.FILE_NON_DIRECTORY_FILE
            ))
            rejectReparse(file)
            if (file !is File) throw SmbFailureException(NasFailure.INVALID_RESPONSE)
            val info = file.fileInformation
            val size = info.standardInformation.endOfFile
            if (size < 0 || entry.size?.let { it != size } == true ||
                entry.modifiedEpochMillis?.let { it != info.basicInformation.lastWriteTime.toEpochMillis() } == true) {
                throw SmbFailureException(NasFailure.CONTENT_CHANGED)
            }
            val input = SmbReadInput(size, cancellation, owner, file::read)
            cancellation.throwIfCancelled()
            transferred = true
            NasOpenResult.Opened(object : NasReadHandle {
                override val input: InputStream = input
                override fun close() = input.close()
            })
        } catch (_: CancellationException) {
            NasOpenResult.Cancelled
        } catch (error: Exception) {
            if (cancellation.isCancelled) NasOpenResult.Cancelled else NasOpenResult.Failed(SmbSafety.failure(error))
        } finally {
            if (!transferred) owner?.close()
        }
    }

    private fun connect(source: NasSource, cancellation: NasCancellation, context: SmbRequestContext): DiskShare {
        cancellation.throwIfCancelled()
        val route = network.select(source.mode)
        val secret = credentials(source) ?: throw SmbFailureException(NasFailure.AUTHENTICATION_FAILED)
        cancellation.throwIfCancelled()
        val address = SmbDns.resolve(route, source.host.value, context)
        val sockets = SmbSocketFactory(route, address, source.port, context, cancellation)
        val client = context.own(SMBClient(SmbSafety.config(sockets)))
        // Pass a numeric address so SMBJ's InetSocketAddress cannot resolve outside the selected VPN.
        val connection = client.connect(address.hostAddress, source.port)
        context.own(AutoCloseable { connection.close(true) })
        val auth = SmbSafety.authentication(secret)
        val session = try {
            context.own(connection.authenticate(auth))
        } finally {
            auth.password.fill('\u0000')
        }
        requireAuthenticated(session.isGuest, session.isAnonymous, session.isSigningRequired)
        val original = context.own(session.connectShare(source.share))
        if (original !is DiskShare || original.treeConnect.isDfsShare) {
            throw SmbFailureException(NasFailure.UNSUPPORTED_PROTOCOL)
        }
        // Reuse the owned tree, replacing SMBJ's implicit SymlinkPathResolver with strict local resolution.
        return DiskShare(original.smbPath, original.treeConnect, PathResolver.LOCAL)
    }

    private fun requireAuthenticated(guest: Boolean, anonymous: Boolean, signing: Boolean) {
        if (guest || anonymous || !signing) throw SmbFailureException(NasFailure.AUTHENTICATION_FAILED)
    }

    private fun directory(share: DiskShare, path: NasRelativePath, context: SmbRequestContext,
                          cancellation: NasCancellation): Directory {
        var prefix = NasRelativePath.ROOT
        var result = checkedDirectory(share, prefix, context, cancellation)
        if (!path.isRoot) path.value.split('/').forEach { segment ->
            prefix = prefix.child(segment)
            result = checkedDirectory(share, prefix, context, cancellation)
        }
        return result
    }

    private fun checkedDirectory(share: DiskShare, path: NasRelativePath, context: SmbRequestContext,
                                 cancellation: NasCancellation): Directory {
        cancellation.throwIfCancelled()
        // Keep ancestor handles open, denying write/delete sharing, to prevent replacement after validation.
        val entry = context.own(share.open(
            wirePath(path), SmbSafety.access, emptySet(), SmbSafety.sharing, SmbSafety.disposition,
            SmbSafety.noFollow + SMB2CreateOptions.FILE_DIRECTORY_FILE
        ))
        rejectReparse(entry)
        if (entry !is Directory) throw SmbFailureException(NasFailure.NOT_FOUND)
        return entry
    }

    private fun rejectReparse(entry: DiskEntry) {
        val attributes = entry.fileInformation.basicInformation.fileAttributes
        if (attributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L) {
            throw SmbFailureException(NasFailure.UNSUPPORTED_PROTOCOL)
        }
    }

    private fun wirePath(path: NasRelativePath) = path.value.replace('/', '\\')
}
