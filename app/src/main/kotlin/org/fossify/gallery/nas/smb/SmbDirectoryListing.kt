package org.fossify.gallery.nas.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.FileInformationClass
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.msfscc.fileinformation.FileInformationFactory
import com.hierynomus.mssmb2.messages.SMB2QueryDirectoryRequest
import com.hierynomus.mssmb2.messages.SMB2QueryDirectoryResponse
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskShare
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.transport.NasCancellation
import java.util.concurrent.TimeUnit

internal class SmbDirectoryPage(val status: Long, val data: ByteArray)

/** SMBJ's iterator also treats repeated pages as EOF. Require the server's normal terminal status. */
internal object SmbDirectoryListing {
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_ENTRIES = 100_000

    fun list(share: DiskShare, directory: Directory, source: NasSource,
             folder: NasRelativePath, cancellation: NasCancellation): List<NasEntry> {
        val tree = share.treeConnect
        return collect(source, folder, cancellation) { first ->
            val flags = if (first) setOf(SMB2QueryDirectoryRequest.SMB2QueryDirectoryFlags.SMB2_RESTART_SCANS)
                else emptySet()
            val request = SMB2QueryDirectoryRequest(
                tree.negotiatedProtocol.dialect, tree.session.sessionId, tree.treeId, directory.fileId,
                FileInformationClass.FileIdBothDirectoryInformation, flags, 0, "*", BUFFER_SIZE
            )
            val response = tree.session.send<SMB2QueryDirectoryResponse>(request)
                .get(SMB_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            SmbDirectoryPage(response.header.statusCode, response.outputBuffer ?: byteArrayOf())
        }
    }

    fun collect(source: NasSource, folder: NasRelativePath, cancellation: NasCancellation,
                next: (Boolean) -> SmbDirectoryPage): List<NasEntry> {
        val entries = ArrayList<NasEntry>()
        val names = HashSet<String>()
        var first = true
        var previous: ByteArray? = null
        while (true) {
            cancellation.throwIfCancelled()
            val page = next(first)
            cancellation.throwIfCancelled()
            if (page.status == NtStatus.STATUS_NO_MORE_FILES.value ||
                first && page.status == NtStatus.STATUS_NO_SUCH_FILE.value) return entries
            if (page.status != NtStatus.STATUS_SUCCESS.value) {
                throw SmbFailureException(SmbSafety.statusFailure(page.status))
            }
            if (page.data.isEmpty() || page.data.contentEquals(previous)) {
                throw SmbFailureException(NasFailure.INVALID_RESPONSE)
            }
            appendPage(page.data, entries, names, source, folder, cancellation)
            first = false
            previous = page.data
        }
    }
    private fun appendPage(data: ByteArray, entries: MutableList<NasEntry>, names: MutableSet<String>,
                           source: NasSource, folder: NasRelativePath, cancellation: NasCancellation) {
        val decoder = FileInformationFactory.getDecoder(FileIdBothDirectoryInformation::class.java)
        val records = FileInformationFactory.createFileInformationIterator(data, decoder)
        while (records.hasNext()) {
            cancellation.throwIfCancelled()
            val record = records.next()
            if (record.fileName == "." || record.fileName == "..") continue
            if (!names.add(record.fileName) || names.size > MAX_ENTRIES) {
                throw SmbFailureException(NasFailure.INVALID_RESPONSE)
            }
            if (record.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value == 0L) {
                val kind = if (record.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L)
                    NasEntryKind.DIRECTORY else NasEntryKind.FILE
                entries.add(NasEntry(
                    NasRemoteKey(source.key, folder.child(record.fileName)), kind, record.endOfFile,
                    record.lastWriteTime.toEpochMillis().takeIf { it >= 0 }
                ))
            }
        }
    }
}
