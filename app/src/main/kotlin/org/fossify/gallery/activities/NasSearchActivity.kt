package org.fossify.gallery.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityNasSearchBinding
import org.fossify.gallery.nas.external.NasExternalOpener
import org.fossify.gallery.nas.external.NasExternalTypes
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.search.NasSearch
import org.fossify.gallery.nas.search.NasSearchFolder
import org.fossify.gallery.nas.search.NasSearchLimits
import org.fossify.gallery.nas.search.NasSearchOptions
import org.fossify.gallery.nas.search.NasSearchResult
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.ui.NasBrowseModel
import org.fossify.gallery.nas.ui.NasSort
import org.fossify.gallery.nas.ui.NasUiData
import org.fossify.gallery.nas.ui.NasUiRequest
import org.fossify.gallery.nas.ui.NasViewerIdentity
import java.util.UUID

class NasSearchActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNasSearchBinding::inflate)
    private val external by lazy { NasExternalOpener(this, this) }
    private val opening = NasUiRequest()
    private var task: Job? = null
    private var cancellation: NasCancellation? = null
    private var source: NasSourceKey? = null
    private var root = NasRelativePath.ROOT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.nasSearchAppbar),
            padBottomSystem = listOf(binding.nasSearchResults))
        setupTopAppBar(binding.nasSearchAppbar, NavigationIcon.Arrow)
        binding.nasSearchToolbar.setNavigationOnClickListener { finish() }
        binding.root.setBackgroundColor(getProperBackgroundColor())
        listOf(binding.nasSearchText, binding.nasSearchRecursive, binding.nasSearchCached,
            binding.nasSearchStatus).forEach { it.setTextColor(getProperTextColor()) }
        runCatching {
            source = NasSourceKey(UUID.fromString(intent.getStringExtra("source")), intent.getLongExtra("revision", 0))
            root = NasRelativePath.parse(intent.getStringExtra("folder") ?: "")
        }.onFailure { finish() }
        binding.nasSearchStart.setOnClickListener { search() }
        binding.nasSearchCancel.setOnClickListener {
            cancellation?.let { token -> NasUiRequest.dispose { token.cancel() } }
        }
        binding.nasSearchText.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
    }

    private fun search() {
        val key = source ?: return
        if (task?.isActive == true) return
        val options = NasSearchOptions(binding.nasSearchText.text.toString().trim(),
            binding.nasSearchRecursive.isChecked, binding.nasSearchCached.isChecked)
        if (options.text.isBlank()) { binding.nasSearchText.error = getString(R.string.nas_search_hint); return }
        external.cancel()
        opening.cancel()
        val token = NasCancellation()
        cancellation = token
        busy(true)
        binding.nasSearchStatus.setText(R.string.nas_search_running)
        binding.nasSearchResults.adapter = null
        val expiry = lifecycleScope.launch {
            delay(NasSearchLimits().millis)
            NasUiRequest.dispose { token.cancel() }
        }
        task = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { executeSearch(key, options, token) } }
                busy(false)
                result.onSuccess(::show).onFailure { binding.nasSearchStatus.setText(R.string.nas_load_error) }
            } finally { expiry.cancel() }
        }
    }

    private fun executeSearch(key: NasSourceKey, options: NasSearchOptions, token: NasCancellation): NasSearchResult {
        val data = NasUiData.get(applicationContext)
        checkNotNull(data.source(key))
        return NasSearch({ path, live, cancel ->
            if (live) data.folder(key, path, true, cancel).let { NasSearchFolder(it.snapshot, it.failure) }
            else NasSearchFolder(data.repository.getDirectory(key, path))
        }).run(key, root, options, token)
    }

    private fun show(result: NasSearchResult) {
        val notes = listOfNotNull(
            getString(R.string.nas_search_count, result.entries.size, result.folders),
            if (result.usedCache) getString(R.string.nas_search_cached_note) else null,
            if (result.limited || result.incomplete || result.cancelled) {
                getString(R.string.nas_search_partial)
            } else null
        )
        binding.nasSearchStatus.text = notes.joinToString("\n")
        binding.nasSearchResults.adapter = object : ArrayAdapter<NasEntry>(this,
            android.R.layout.simple_list_item_2, android.R.id.text1, result.entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val entry = getItem(position) ?: return view
                view.findViewById<TextView>(android.R.id.text1).apply {
                    text = entry.name; setTextColor(getProperTextColor())
                }
                view.findViewById<TextView>(android.R.id.text2).apply {
                    text = entry.key.path.value; setTextColor(getProperTextColor())
                }
                return view
            }
        }
        binding.nasSearchResults.setOnItemClickListener { _, _, index, _ -> open(result.entries[index]) }
    }

    private fun open(entry: NasEntry) {
        when {
            entry.kind == NasEntryKind.DIRECTORY -> startActivity(Intent(this, NasBrowserActivity::class.java)
                .putExtras(location(entry.key.source, entry.key.path)))
            NasExternalTypes.mime(entry) != null -> external.open(entry)
            NasBrowseModel.isImage(entry) -> openImage(entry)
            else -> Toast.makeText(this, R.string.nas_search_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openImage(entry: NasEntry) {
        val parent = checkNotNull(entry.key.path.parent)
        opening.start(lifecycleScope, work = { token ->
            val view = NasUiData.get(applicationContext).folder(entry.key.source, parent, false, token)
            checkNotNull(NasBrowseModel.selected(view.snapshot, entry.key.source, parent, entry.key.path))
        }) { result ->
            if (result.isFailure) Toast.makeText(this, R.string.nas_load_error, Toast.LENGTH_LONG).show()
            else startActivity(Intent(this, NasViewerActivity::class.java).putExtras(
                NasViewerIdentity(entry.key.source, parent, entry.key.path, NasSort.NAME, false).bundle()))
        }
    }

    private fun busy(value: Boolean) {
        binding.nasSearchProgress.isVisible = value
        binding.nasSearchCancel.isVisible = value
        binding.nasSearchStart.isEnabled = !value
    }

    override fun onStop() {
        cancellation?.let { token -> NasUiRequest.dispose { token.cancel() } }
        task?.cancel()
        opening.cancel()
        external.cancel()
        busy(false)
        super.onStop()
    }

    companion object {
        internal fun intent(context: Context, source: NasSourceKey, folder: NasRelativePath): Intent =
            Intent(context, NasSearchActivity::class.java).putExtras(location(source, folder))

        private fun location(source: NasSourceKey, folder: NasRelativePath) = Bundle().apply {
            putString("source", source.id.toString()); putLong("revision", source.revision)
            putString("folder", folder.value)
        }
    }
}
