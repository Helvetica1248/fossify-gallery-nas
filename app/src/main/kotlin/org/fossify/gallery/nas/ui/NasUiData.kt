package org.fossify.gallery.nas.ui

import android.content.Context
import org.fossify.gallery.R
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.data.NasDirectorySnapshot
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.repository.AndroidNasRepository
import org.fossify.gallery.nas.repository.NasRepository
import org.fossify.gallery.nas.settings.AndroidNasSettings
import org.fossify.gallery.nas.settings.SavedNasSource
import org.fossify.gallery.nas.transport.NasCancellation

internal data class NasFolderView(val snapshot: NasDirectorySnapshot?, val failure: NasFailure? = null)

/** Blocking UI boundary. Source revisions are resolved against current settings before any request. */
internal class NasUiData(val repository: NasRepository, private val sourceProvider: () -> List<SavedNasSource>) {
    fun sources() = sourceProvider()

    fun source(key: NasSourceKey) = sources().firstOrNull { it.source.key == key }?.source

    fun folder(key: NasSourceKey, path: NasRelativePath, refresh: Boolean,
               cancellation: NasCancellation): NasFolderView {
        val source = source(key) ?: return NasFolderView(null, NasFailure.NOT_FOUND)
        val previous = repository.getDirectory(key, path)
        if (!refresh && previous != null) return NasFolderView(previous)
        return when (val result = repository.refreshDirectory(source, path, cancellation)) {
            is NasListingResult.Complete -> NasFolderView(repository.getDirectory(key, path))
            is NasListingResult.Failed -> NasFolderView(previous, result.reason)
            else -> NasFolderView(previous, NasFailure.IO_ERROR)
        }
    }

    fun image(entry: NasEntry, variant: Variant, cancellation: NasCancellation): NasCacheResult {
        val source = source(entry.key.source) ?: return NasCacheResult.Failed(NasFailure.NOT_FOUND)
        return if (variant == Variant.ORIGINAL) repository.fetchOriginal(source, entry, cancellation)
        else repository.fetchThumbnail(source, entry, cancellation)
    }

    companion object {
        // Internal instrumentation seam; release builds always use the real P2/P4 stores.
        internal var fixture: NasUiData? = null
        fun get(context: Context): NasUiData = (if (BuildConfig.DEBUG) fixture else null) ?: real(context)
        private fun real(context: Context): NasUiData = NasUiData(AndroidNasRepository.get(context)) {
            AndroidNasSettings.get(context).list()
        }
    }
}

internal fun nasFailureText(reason: NasFailure?): Int = when (reason) {
    NasFailure.VPN_REQUIRED -> R.string.nas_test_vpn_required
    NasFailure.AUTHENTICATION_FAILED -> R.string.nas_test_auth_failed
    NasFailure.ACCESS_DENIED -> R.string.nas_test_access_denied
    NasFailure.NOT_FOUND -> R.string.nas_source_changed
    NasFailure.TIMED_OUT -> R.string.nas_test_timeout
    NasFailure.LOW_STORAGE -> R.string.nas_cache_space
    NasFailure.TRANSFER_TOO_LARGE -> R.string.nas_image_large
    NasFailure.INVALID_RESPONSE, NasFailure.CONTENT_CHANGED -> R.string.nas_image_error
    else -> R.string.nas_load_error
}
