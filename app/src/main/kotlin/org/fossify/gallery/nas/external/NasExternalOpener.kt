package org.fossify.gallery.nas.external

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import org.fossify.gallery.R
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.smb.SmbSafety
import org.fossify.gallery.nas.ui.NasUiRequest
import org.fossify.gallery.nas.ui.nasFailureText
import java.util.concurrent.CancellationException

private data class PreparedExternal(val token: String? = null, val failure: NasFailure? = null)

/** Only a user tap can issue a URI. The chosen app receives read access to this one file, not NAS credentials. */
internal class NasExternalOpener(private val activity: Activity, private val owner: LifecycleOwner) {
    private val request = NasUiRequest()

    @Suppress("TooGenericExceptionCaught") // Map storage/SMBJ boundary failures without exposing their messages.
    fun open(entry: NasEntry, finished: () -> Unit = {}) {
        val mime = NasExternalTypes.mime(entry) ?: return
        request.start(owner.lifecycleScope, work = { cancellation ->
            try { PreparedExternal(NasExternalFiles.prepare(activity.applicationContext, entry, cancellation)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { PreparedExternal(failure = SmbSafety.failure(error)) }
        }, discard = { it.token?.let(NasExternalFiles.tokens::revoke) }) { result ->
            finished()
            val prepared = result.getOrNull()
            if (prepared?.token == null) {
                Toast.makeText(activity, nasFailureText(prepared?.failure), Toast.LENGTH_LONG).show()
            } else choose(entry, mime, prepared.token)
        }
    }

    private fun choose(entry: NasEntry, mime: String, token: String) {
        val uri = NasExternalFiles.uri(activity, token)
        val base = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(entry.name, uri) }
        val handlers = activity.packageManager.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.packageName != activity.packageName }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
        if (handlers.isEmpty()) {
            NasExternalFiles.tokens.revoke(token)
            Toast.makeText(activity, R.string.nas_external_no_app, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity).setTitle(R.string.nas_external_choose)
            .setItems(handlers.map { it.loadLabel(activity.packageManager).toString() }.toTypedArray()) { _, index ->
                val handler = handlers[index].activityInfo
                val intent = Intent(base).setComponent(ComponentName(handler.packageName, handler.name))
                try { activity.startActivity(intent) }
                catch (_: ActivityNotFoundException) { unavailable(token) }
                catch (_: SecurityException) { unavailable(token) }
            }.setOnCancelListener { NasExternalFiles.tokens.revoke(token) }.show()
    }

    private fun unavailable(token: String) {
        NasExternalFiles.tokens.revoke(token)
        Toast.makeText(activity, R.string.nas_external_no_app, Toast.LENGTH_LONG).show()
    }

    fun cancel() { request.cancel() }
}
