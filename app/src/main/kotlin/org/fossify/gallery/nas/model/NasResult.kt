package org.fossify.gallery.nas.model

import java.util.Collections

/** Safe UI categories only: never embed a transport exception, host, path, or credential here. */
enum class NasFailure {
    UNREACHABLE,
    VPN_REQUIRED,
    AUTHENTICATION_FAILED,
    ACCESS_DENIED,
    NOT_FOUND,
    UNSUPPORTED_PROTOCOL,
    INVALID_RESPONSE,
    LOW_STORAGE,
    TRANSFER_TOO_LARGE,
    CONTENT_CHANGED,
    TIMED_OUT,
    IO_ERROR
}

sealed class NasListingResult {
    /** Only a successfully exhausted directory enumeration may construct Complete, including empty. */
    class Complete(entries: Collection<NasEntry>) : NasListingResult() {
        val entries: List<NasEntry> = Collections.unmodifiableList(ArrayList(entries))
    }

    data class Failed(val reason: NasFailure) : NasListingResult()
    object Cancelled : NasListingResult()
    /** Progress is not a replacement snapshot, including a transport's truncated first page. */
    object Incomplete : NasListingResult()
}

sealed class NasTransferResult {
    data class Complete(val bytesCopied: Long) : NasTransferResult()
    data class Failed(val reason: NasFailure) : NasTransferResult()
    object Cancelled : NasTransferResult()
}
