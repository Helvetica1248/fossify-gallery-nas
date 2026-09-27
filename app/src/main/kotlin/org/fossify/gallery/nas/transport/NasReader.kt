package org.fossify.gallery.nas.transport

import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSource
import java.io.Closeable
import java.io.InputStream

/**
 * P1 transport boundary; not an SMB implementation. All calls belong on a bounded I/O executor.
 * Implementations must enforce the configured root, reject links/DFS redirection, require read-only
 * FILE_OPEN access, and install request-local cancellation before DNS/connect/auth/read can block.
 * list() returns Complete only after the enumeration ends successfully; never turn exceptions into [].
 */
interface NasReader {
    fun list(source: NasSource, folder: NasRelativePath, cancellation: NasCancellation): NasListingResult
    fun open(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasOpenResult
}

/** Owns the entire request (stream, SMB file, session, connection). It has no write capability. */
interface NasReadHandle : Closeable {
    val input: InputStream
}

sealed class NasOpenResult {
    class Opened(val handle: NasReadHandle) : NasOpenResult()
    data class Failed(val reason: NasFailure) : NasOpenResult()
    object Cancelled : NasOpenResult()
}
