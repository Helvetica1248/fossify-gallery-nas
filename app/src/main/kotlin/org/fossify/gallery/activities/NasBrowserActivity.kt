package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityNasBrowserBinding
import org.fossify.gallery.nas.data.NasDirectorySnapshot
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.ui.NasFavoriteFolder
import org.fossify.gallery.nas.ui.NasBrowseModel
import org.fossify.gallery.nas.ui.NasBrowserAdapter
import org.fossify.gallery.nas.ui.NasBrowserRow
import org.fossify.gallery.nas.ui.NasSort
import org.fossify.gallery.nas.ui.NasUiData
import org.fossify.gallery.nas.ui.NasUiRequest
import org.fossify.gallery.nas.ui.NasViewerIdentity
import org.fossify.gallery.nas.ui.nasFailureText
import java.util.UUID

@Suppress("TooManyFunctions") // One screen owns source/folder navigation and Android lifecycle callbacks.
class NasBrowserActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNasBrowserBinding::inflate)
    private val request = NasUiRequest()
    private val favoriteRequest = NasUiRequest()
    private var favorite = false
    private var favoriteBusy = false
    private val adapter = NasBrowserAdapter(lifecycleScope, ::open)
    private var source: NasSourceKey? = null
    private var folder = NasRelativePath.ROOT
    private var snapshot: NasDirectorySnapshot? = null
    private var sourceName = ""
    private var sort = NasSort.NAME
    private var descending = false
    private var opened = false
    private var opening: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.nasBrowserAppbar),
            padBottomSystem = listOf(binding.nasBrowserGrid))
        setupTopAppBar(binding.nasBrowserAppbar, NavigationIcon.Arrow)
        binding.root.setBackgroundColor(getProperBackgroundColor())
        binding.nasBrowserStatus.setTextColor(getProperTextColor())
        val columns = maxOf(2, resources.configuration.screenWidthDp / CELL_WIDTH_DP)
        binding.nasBrowserGrid.layoutManager = GridLayoutManager(this, columns)
        binding.nasBrowserGrid.adapter = adapter
        binding.nasBrowserSettings.setOnClickListener { startActivity(Intent(this, NasSourcesActivity::class.java)) }
        binding.nasBrowserToolbar.apply {
            menu.add(0, REFRESH, 0, R.string.nas_refresh)
            menu.add(0, SORT, 1, R.string.nas_sort)
            menu.add(0, FAVORITE, 2, R.string.nas_favorite_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    REFRESH -> if (source == null) sources() else loadFolder(true)
                    SORT -> chooseSort()
                    FAVORITE -> toggleFavorite()
                }
                true
            }
            setNavigationOnClickListener { back() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        restore(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        adapter.setActive(true)
        if (source == null) sources() else {
            readFavorite()
            if (!opened) { opened = true; loadFolder(false) }
        }
    }

    override fun onStop() {
        opening?.cancel()
        request.cancel()
        favoriteRequest.cancel()
        adapter.setActive(false)
        binding.nasBrowserProgress.isVisible = false
        super.onStop()
    }

    private fun sources() {
        request.cancel()
        source = null
        snapshot = null
        readFavorite()
        binding.nasBrowserToolbar.setTitle(R.string.nas_albums)
        binding.nasBrowserSettings.isVisible = true
        binding.nasBrowserProgress.isVisible = true
        request.start(lifecycleScope, work = {
            val data = NasUiData.get(this)
            val sources = data.sources()
            val favorites = data.favorites.list().mapNotNull { bookmark ->
                sources.firstOrNull { it.source.key.id == bookmark.sourceId }?.let {
                    NasBrowserRow(bookmark.path.name.ifBlank {
                        it.displayName.ifBlank { getString(R.string.nas_sources) } },
                        source = it, favorite = bookmark.path)
                }
            }
            favorites + sources.map {
                NasBrowserRow(it.displayName.ifBlank { getString(R.string.nas_sources) }, it)
            }
        }) { result ->
            binding.nasBrowserProgress.isVisible = false
            val items = result.getOrNull()
            binding.nasBrowserStatus.setText(when {
                items == null -> R.string.nas_load_error
                items.isEmpty() -> R.string.nas_empty
                else -> R.string.nas_albums
            })
            adapter.submit(items.orEmpty())
        }
    }

    private fun open(row: NasBrowserRow) {
        if (opening?.isActive == true) return
        binding.nasBrowserProgress.isVisible = true
        opening = lifecycleScope.launch {
            // Folder covers can own the same original needed by the destination grid or viewer.
            adapter.finishRequests()
            binding.nasBrowserProgress.isVisible = false
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) openReady(row)
        }
    }

    private fun openReady(row: NasBrowserRow) {
        row.source?.let {
            source = it.source.key
            sourceName = it.displayName.ifBlank { getString(R.string.nas_sources) }
            folder = row.favorite ?: NasRelativePath.ROOT
            snapshot = null
            adapter.submit(emptyList())
            adapter.setActive(true)
            opened = true
            loadFolder(false)
            return
        }
        row.entry?.let { entry ->
            if (entry.kind == NasEntryKind.DIRECTORY) {
                folder = entry.key.path
                snapshot = null
                adapter.submit(emptyList())
                adapter.setActive(true)
                loadFolder(false)
            } else {
                val identity = NasViewerIdentity(entry.key.source, folder, entry.key.path, sort, descending)
                startActivity(Intent(this, NasViewerActivity::class.java).putExtras(identity.bundle()))
            }
        }
    }

    private fun loadFolder(refresh: Boolean) {
        val key = source ?: return
        val path = folder
        readFavorite()
        if (snapshot == null) binding.nasBrowserStatus.setText(R.string.nas_busy)
        binding.nasBrowserToolbar.title = if (path.isRoot) sourceName else path.name
        binding.nasBrowserSettings.isVisible = false
        binding.nasBrowserProgress.isVisible = true
        request.start(lifecycleScope, work = { NasUiData.get(this).folder(key, path, refresh, it) }) { result ->
            binding.nasBrowserProgress.isVisible = false
            val value = result.getOrNull()
            value?.snapshot?.let { snapshot = it }
            showSnapshot()
            if (result.isFailure || value?.failure != null) {
                binding.nasBrowserStatus.setText(nasFailureText(value?.failure))
            }
        }
    }

    private fun showSnapshot() {
        val entries = snapshot?.entries ?: return
        val rows = NasBrowseModel.sorted(entries, sort, descending).map { NasBrowserRow(it.name, entry = it) }
        adapter.submit(rows)
        binding.nasBrowserStatus.setText(when {
            entries.isEmpty() -> R.string.nas_folder_empty
            rows.isEmpty() -> R.string.nas_no_images
            else -> R.string.nas_cached
        })
    }

    private fun readFavorite() {
        favoriteRequest.cancel()
        val key = source
        val item = binding.nasBrowserToolbar.menu.findItem(FAVORITE)
        item.isVisible = key != null
        binding.nasBrowserToolbar.menu.findItem(SORT).isVisible = key != null
        if (key == null) return
        favoriteBusy = true
        item.isEnabled = false
        val bookmark = NasFavoriteFolder(key.id, folder)
        favoriteRequest.start(lifecycleScope, work = { bookmark in NasUiData.get(this).favorites.list() }) { result ->
            favoriteBusy = false
            favorite = result.getOrDefault(false)
            item.isEnabled = result.isSuccess
            showFavorite()
        }
    }

    private fun showFavorite() {
        binding.nasBrowserToolbar.menu.findItem(FAVORITE).apply {
            setTitle(if (favorite) R.string.nas_favorite_remove else R.string.nas_favorite_add)
            setIcon(if (favorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
        }
    }

    private fun toggleFavorite() {
        val key = source ?: return
        if (favoriteBusy) return
        val bookmark = NasFavoriteFolder(key.id, folder)
        val selected = !favorite
        favoriteBusy = true
        binding.nasBrowserToolbar.menu.findItem(FAVORITE).isEnabled = false
        favoriteRequest.start(lifecycleScope, work = {
            NasUiData.get(this).favorites.set(bookmark, selected)
            selected
        }) { result ->
            favoriteBusy = false
            binding.nasBrowserToolbar.menu.findItem(FAVORITE).isEnabled = true
            if (result.isSuccess) { favorite = selected; showFavorite() }
            else Toast.makeText(this, R.string.nas_favorite_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseSort() {
        AlertDialog.Builder(this).setTitle(R.string.nas_sort)
            .setItems(arrayOf(getString(R.string.nas_sort_name), getString(R.string.nas_sort_modified))) { _, index ->
                sort = if (index == 0) NasSort.NAME else NasSort.MODIFIED
                descending = index == 1
                showSnapshot()
            }.show()
    }

    private fun back() {
        if (source == null) { finish(); return }
        opening?.cancel()
        request.cancel()
        snapshot = null
        adapter.submit(emptyList())
        adapter.setActive(true)
        if (folder.isRoot) sources() else { folder = checkNotNull(folder.parent); loadFolder(false) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        source?.let { outState.putString("source", it.id.toString()); outState.putLong("revision", it.revision) }
        outState.putString("folder", folder.value)
        outState.putString("name", sourceName)
        outState.putString("sort", sort.name)
        outState.putBoolean("descending", descending)
    }

    private fun restore(saved: Bundle?) {
        if (saved == null) return
        runCatching {
            saved.getString("source")?.let { source = NasSourceKey(UUID.fromString(it), saved.getLong("revision")) }
            folder = NasRelativePath.parse(saved.getString("folder") ?: "")
            sourceName = saved.getString("name") ?: ""
            sort = NasSort.valueOf(saved.getString("sort") ?: NasSort.NAME.name)
            descending = saved.getBoolean("descending")
        }.onFailure { source = null; folder = NasRelativePath.ROOT }
    }

    private companion object {
        const val CELL_WIDTH_DP = 150
        const val REFRESH = 1
        const val SORT = 2
        const val FAVORITE = 3
    }
}
