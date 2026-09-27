package org.fossify.gallery.activities

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityNasSourcesBinding
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.settings.AndroidNasSettings
import org.fossify.gallery.nas.settings.NasCredentials
import org.fossify.gallery.nas.settings.NasSourceForm
import org.fossify.gallery.nas.settings.SavedNasSource
import org.fossify.commons.R as CommonsR

class NasSourcesActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNasSourcesBinding::inflate)
    private val store by lazy { AndroidNasSettings.get(this) }
    private var busy = false
    private var editor: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.nasAppbar),
            padBottomSystem = listOf(binding.nasScroll)
        )
        setupMaterialScrollListener(binding.nasScroll, binding.nasAppbar)
        setupTopAppBar(binding.nasAppbar, NavigationIcon.Arrow)
        binding.nasAdd.setOnClickListener { if (!busy) edit(null, null) }
        perform({ store.list() }) { showSources(it) }
    }

    private fun showSources(items: List<SavedNasSource>) {
        binding.nasItems.removeAllViews()
        binding.nasStatus.setText(if (items.isEmpty()) R.string.nas_empty else R.string.nas_settings_only)
        items.forEach { item ->
            binding.nasItems.addView(Button(this).apply {
                val mode = if (item.source.mode == NasConnectionMode.VPN) R.string.nas_vpn else R.string.nas_lan
                text = "${item.displayName.ifBlank { item.source.host.value }}\n${getString(mode)}"
                setOnClickListener { perform({ store.credentials(item) }) { edit(item, it) } }
            })
        }
        updateTextColors(binding.nasHolder)
    }

    private fun edit(item: SavedNasSource?, credentials: NasCredentials?) {
        if (editor != null) return
        val form = NasSourceForm(this, item, credentials)
        val builder = getAlertDialogBuilder()
            .setPositiveButton(CommonsR.string.save, null)
            .setNegativeButton(CommonsR.string.cancel, null)
        if (item != null) builder.setNeutralButton(CommonsR.string.delete, null)
        val title = if (item == null) R.string.nas_add_source else R.string.nas_edit_source
        setupDialogStuff(form.view, builder, title) { dialog ->
            editor = dialog
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setOnDismissListener {
                form.clearCredentials()
                editor = null
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                save(form, dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                if (!busy && item != null) {
                    dialog.dismiss()
                    confirmDelete(item)
                }
            }
        }
    }

    private fun save(form: NasSourceForm, dialog: AlertDialog) {
        if (busy) return
        val draft = try {
            form.draft()
        } catch (_: IllegalArgumentException) {
            message(R.string.nas_invalid_source)
            return
        }
        val credentials = form.credentials()
        if (credentials.username.isBlank()) {
            message(R.string.nas_reenter_required)
            return
        }
        perform({
            store.save(draft, credentials)
            store.list()
        }) {
            dialog.dismiss()
            showSources(it)
            message(R.string.nas_saved)
        }
    }

    private fun confirmDelete(item: SavedNasSource) {
        getAlertDialogBuilder()
            .setTitle(R.string.nas_delete_source)
            .setMessage(R.string.nas_delete_explanation)
            .setNegativeButton(CommonsR.string.cancel, null)
            .setPositiveButton(CommonsR.string.delete) { _, _ ->
                perform({
                    store.delete(item.source.key)
                    store.list()
                }) {
                    showSources(it)
                    message(R.string.nas_deleted)
                }
            }.show()
    }

    private fun <T> perform(action: () -> T, completed: (T) -> Unit) {
        if (busy) return
        busy = true
        binding.nasAdd.isEnabled = false
        binding.nasStatus.setText(R.string.nas_busy)
        editor?.setCancelable(false)
        editor?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                binding.nasStatus.setText(R.string.nas_settings_only)
                completed(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never expose exception messages: a provider may include sensitive values.
                binding.nasStatus.setText(R.string.nas_storage_error)
                message(R.string.nas_storage_error)
            } finally {
                busy = false
                binding.nasAdd.isEnabled = true
                editor?.setCancelable(true)
                editor?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
            }
        }
    }

    private fun message(text: Int) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        editor?.dismiss()
        super.onDestroy()
    }
}
