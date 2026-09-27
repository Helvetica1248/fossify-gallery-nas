package org.fossify.gallery.nas.external

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Non-exported provider. Android checks the exact temporary URI read grant before opening a file. */
class NasExternalProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = NasExternalTypes.mime(NasExternalFiles.entry(providerContext(), uri))

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val entry = NasExternalFiles.entry(providerContext(), uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray()).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) entry.name else entry.size })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri, mode, null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("NAS provider is read-only")
        signal?.throwIfCanceled()
        val app = providerContext().applicationContext
        val entry = NasExternalFiles.entry(app, uri)
        return NasProxyFile.open(app, entry, signal)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only NAS")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only NAS")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only NAS")

    private fun providerContext() = checkNotNull(context)
}
