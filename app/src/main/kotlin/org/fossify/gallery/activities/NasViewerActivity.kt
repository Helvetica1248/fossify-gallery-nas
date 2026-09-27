package org.fossify.gallery.activities

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityNasViewerBinding
import org.fossify.gallery.fragments.NasPageFragment
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.ui.NasBrowseModel
import org.fossify.gallery.nas.ui.NasUiData
import org.fossify.gallery.nas.ui.NasUiRequest
import org.fossify.gallery.nas.ui.NasViewerIdentity

class NasViewerActivity : BaseViewerActivity() {
    private val binding by viewBinding(ActivityNasViewerBinding::inflate)
    override val contentHolder get() = binding.nasViewerHolder
    override val appBarLayout get() = binding.nasViewerAppbar
    private val request = NasUiRequest()
    private var identity: NasViewerIdentity? = null
    private var pages = emptyList<NasEntry>()
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        identity = NasViewerIdentity.read(savedInstanceState ?: intent.extras)
        setupTopAppBar(binding.nasViewerAppbar, NavigationIcon.Arrow)
        binding.nasViewerToolbar.setNavigationOnClickListener { finish() }
        binding.nasViewerPager.offscreenPageLimit = 1
        binding.nasViewerPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pages.getOrNull(position)?.let {
                    identity = identity?.copy(selected = it.key.path)
                    binding.nasViewerToolbar.title = it.name
                }
                binding.nasViewerPager.isUserInputEnabled = true
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (binding.nasViewerPager.adapter == null) loadCatalog()
    }

    private fun loadCatalog() {
        val key = identity
        if (key == null) { failed(); return }
        binding.nasViewerProgress.isVisible = true
        request.start(lifecycleScope, work = {
            val data = NasUiData.get(this)
            checkNotNull(data.source(key.source))
            val snapshot = checkNotNull(data.repository.getDirectory(key.source, key.folder))
            checkNotNull(NasBrowseModel.selected(snapshot, key.source, key.folder, key.selected))
            NasBrowseModel.pages(snapshot, key.sort, key.descending)
        }) { result ->
            binding.nasViewerProgress.isVisible = false
            val loaded = result.getOrNull()
            if (loaded == null) { failed() } else {
                pages = loaded
                binding.nasViewerPager.adapter = object : FragmentStateAdapter(this) {
                    override fun getItemCount() = pages.size
                    override fun createFragment(position: Int): Fragment = NasPageFragment().apply {
                        arguments = key.copy(selected = pages[position].key.path).bundle()
                    }
                }
                val position = pages.indexOfFirst { it.key.path == key.selected }
                binding.nasViewerPager.setCurrentItem(position.coerceAtLeast(0), false)
                binding.nasViewerToolbar.title = pages[position.coerceAtLeast(0)].name
            }
        }
    }

    internal fun entry(path: NasRelativePath) = pages.firstOrNull { it.key.path == path }

    internal fun enableSwipe(path: NasRelativePath, enabled: Boolean) {
        if (identity?.selected == path) binding.nasViewerPager.isUserInputEnabled = enabled
    }

    internal fun toggleFullscreen() {
        fullscreen = !fullscreen
        binding.nasViewerAppbar.isVisible = !fullscreen
        val bars = WindowCompat.getInsetsController(window, binding.root)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars())
        else bars.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun failed() {
        binding.nasViewerProgress.isVisible = false
        binding.nasViewerStatus.setText(R.string.nas_source_changed)
    }

    override fun onStop() { request.cancel(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        identity?.bundle()?.let { outState.putAll(it) }
    }
}
