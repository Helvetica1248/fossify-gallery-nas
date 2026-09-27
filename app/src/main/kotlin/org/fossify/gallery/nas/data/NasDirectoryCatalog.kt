package org.fossify.gallery.nas.data

import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSourceKey
import java.util.Collections

/** One immutable successful generation. An absent timestamp differs from a confirmed empty folder. */
class NasDirectorySnapshot internal constructor(
    val source: NasSourceKey,
    val folder: NasRelativePath,
    val generation: Long,
    val lastSuccessfulRefreshMillis: Long?,
    entries: Collection<NasEntry>
) {
    val entries: List<NasEntry> = Collections.unmodifiableList(ArrayList(entries))
}

data class NasDirectoryState(
    val snapshot: NasDirectorySnapshot,
    val isRefreshing: Boolean,
    val lastFailure: NasFailure?,
    val wasCancelled: Boolean
)

/** Identity token, intentionally not a reusable numeric sequence or an externally supplied ID. */
class NasRefreshTicket internal constructor()

/**
 * Thread-safe in-memory reducer for P1. It performs NO network, Room, MediaStore, or File I/O.
 * P4 must persist a new generation and its entries in one Room transaction before publishing it.
 */
class NasDirectoryCatalog(
    private val source: NasSourceKey,
    private val folder: NasRelativePath,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private var active: NasRefreshTicket? = null
    private var state = NasDirectoryState(
        NasDirectorySnapshot(source, folder, 0, null, emptyList()), false, null, false
    )

    init {
        require(maxEntries > 0) { "Invalid directory entry limit" }
    }

    @Synchronized
    fun current(): NasDirectoryState = state

    @Synchronized
    fun beginRefresh(): NasRefreshTicket {
        val ticket = NasRefreshTicket()
        active = ticket
        state = state.copy(isRefreshing = true, lastFailure = null, wasCancelled = false)
        return ticket
    }

    /** Returns false for a superseded, foreign, or already-completed request. */
    @Synchronized
    fun finish(ticket: NasRefreshTicket, result: NasListingResult, completedAtMillis: Long): Boolean {
        require(completedAtMillis >= 0) { "Invalid refresh timestamp" }
        if (active !== ticket) return false
        active = null
        state = when (result) {
            is NasListingResult.Complete -> {
                if (isInvalid(result) || state.snapshot.generation == Long.MAX_VALUE) {
                    failed(NasFailure.INVALID_RESPONSE)
                } else {
                    NasDirectoryState(
                        NasDirectorySnapshot(
                            source, folder, state.snapshot.generation + 1, completedAtMillis, result.entries
                        ), false, null, false
                    )
                }
            }
            is NasListingResult.Failed -> failed(result.reason)
            NasListingResult.Incomplete -> failed(NasFailure.INVALID_RESPONSE)
            NasListingResult.Cancelled -> state.copy(isRefreshing = false, lastFailure = null, wasCancelled = true)
        }
        return true
    }

    private fun isInvalid(result: NasListingResult.Complete): Boolean {
        if (result.entries.size > maxEntries) return true
        if (result.entries.any { it.key.source != source || it.key.path.parent != folder }) return true
        return result.entries.map { it.key.path }.toSet().size != result.entries.size
    }

    private fun failed(reason: NasFailure) = state.copy(
        isRefreshing = false, lastFailure = reason, wasCancelled = false
    )

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000
    }
}
