package org.fossify.gallery.nas.external

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.gallery.R
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.smb.SmbSafety
import org.fossify.gallery.nas.ui.NasUiRequest
import org.fossify.gallery.nas.ui.nasFailureText
import java.util.concurrent.CancellationException
import java.io.File

private data class PreparedExternal(val token: String? = null, val failure: NasFailure? = null,
                                    val preferred: String? = null)

/** Only a user tap can issue a URI. The chosen app receives read access to this one file, not NAS credentials. */
internal class NasExternalOpener(private val activity: Activity, private val owner: LifecycleOwner) {
    private val request = NasUiRequest()
    private var chooser: AlertDialog? = null

    @Suppress("TooGenericExceptionCaught") // Map storage/SMBJ boundary failures without exposing their messages.
    fun open(entry: NasEntry, finished: () -> Unit = {}) {
        val mime = NasExternalTypes.mime(entry) ?: return
        chooser?.dismiss()
        request.start(owner.lifecycleScope, work = { cancellation ->
            try {
                val preferred = runCatching { preferences(activity).get(NasViewerKind.forMime(mime)) }.getOrNull()
                PreparedExternal(NasExternalFiles.prepare(activity.applicationContext, entry, cancellation),
                    preferred = preferred)
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { PreparedExternal(failure = SmbSafety.failure(error)) }
        }, discard = { it.token?.let(NasExternalFiles.tokens::revoke) }) { result ->
            finished()
            val prepared = result.getOrNull()
            if (prepared?.token == null) {
                Toast.makeText(activity, nasFailureText(prepared?.failure), Toast.LENGTH_LONG).show()
            } else choose(entry, mime, prepared.token, prepared.preferred)
        }
    }

    private fun choose(entry: NasEntry, mime: String, token: String, preferred: String?) {
        val uri = NasExternalFiles.uri(activity, token)
        val base = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(entry.name, uri) }
        val handlers = activity.packageManager.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.packageName != activity.packageName }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
        val kind = NasViewerKind.forMime(mime)
        val remembered = handlers.firstOrNull {
            ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString() == preferred
        }
        if (remembered != null && start(Intent(base).setComponent(
                ComponentName(remembered.activityInfo.packageName, remembered.activityInfo.name)))) return
        if (preferred != null) savePreference(kind, null, preferred)
        if (handlers.isEmpty()) {
            NasExternalFiles.tokens.revoke(token)
            Toast.makeText(activity, R.string.nas_external_no_app, Toast.LENGTH_LONG).show()
            return
        }
        val remember = CheckBox(activity).apply { setText(R.string.nas_external_remember) }
        var launched = false
        var shown: AlertDialog? = null
        chooser = AlertDialog.Builder(activity).setTitle(R.string.nas_external_choose)
            .setView(remember)
            .setItems(handlers.map { it.loadLabel(activity.packageManager).toString() }.toTypedArray()) { _, index ->
                val handler = handlers[index].activityInfo
                val component = ComponentName(handler.packageName, handler.name)
                launched = start(Intent(base).setComponent(component))
                if (launched && remember.isChecked) savePreference(kind, component.flattenToString())
                if (!launched) unavailable(token)
            }.setOnDismissListener {
                if (!launched) NasExternalFiles.tokens.revoke(token)
                if (chooser === shown) chooser = null
            }.show().also { shown = it }
    }

    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    private fun savePreference(kind: NasViewerKind, component: String?, stale: String? = null) {
        owner.lifecycleScope.launch {
            val result = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    val store = preferences(activity)
                    if (stale == null) store.set(kind, component) else store.clearIf(kind, stale)
                }
            }
            if (result.isFailure) {
                Toast.makeText(activity, R.string.nas_external_remember_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun unavailable(token: String) {
        NasExternalFiles.tokens.revoke(token)
        Toast.makeText(activity, R.string.nas_external_no_app, Toast.LENGTH_LONG).show()
    }

    fun cancel() { request.cancel(); chooser?.dismiss() }

    companion object {
        fun preferences(context: Context) = NasViewerPreferences(
            File(context.applicationContext.noBackupFilesDir, "nas-settings/viewers.bin"))
    }
}
