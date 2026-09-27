package org.fossify.gallery.fragments

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.alexvasilkov.gestures.GestureController
import com.alexvasilkov.gestures.State
import org.fossify.gallery.R
import org.fossify.gallery.activities.NasViewerActivity
import org.fossify.gallery.databinding.FragmentNasPageBinding
import org.fossify.gallery.nas.ui.NasPageImage
import org.fossify.gallery.nas.ui.NasPageLoader
import org.fossify.gallery.nas.ui.NasUiRequest
import org.fossify.gallery.nas.ui.NasViewerIdentity
import org.fossify.gallery.nas.ui.nasFailureText

class NasPageFragment : Fragment(R.layout.fragment_nas_page) {
    private var binding: FragmentNasPageBinding? = null
    private val request = NasUiRequest()
    private var image: NasPageImage? = null
    private var attempted = false
    private var fitZoom = 1f
    private val identity get() = NasViewerIdentity.read(arguments)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = FragmentNasPageBinding.bind(view)
        binding = ui
        ui.nasPageRetry.setOnClickListener { load() }
        ui.nasPageImage.setOnClickListener { (activity as? NasViewerActivity)?.toggleFullscreen() }
        ui.nasPageImage.controller.settings.apply {
            isZoomEnabled = true
            isRotationEnabled = false
            maxZoom = MAX_ZOOM
            doubleTapZoom = DOUBLE_TAP_ZOOM
        }
        ui.nasPageImage.controller.setOnGesturesListener(object : GestureController.OnGestureListener {
            override fun onDown(event: MotionEvent) = Unit
            override fun onUpOrCancel(event: MotionEvent) = updateSwipe()
            override fun onSingleTapUp(event: MotionEvent) = false
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                ui.nasPageImage.performClick()
                return true
            }
            override fun onLongPress(event: MotionEvent) = Unit
            override fun onDoubleTap(event: MotionEvent) = false
        })
        ui.nasPageImage.controller.addOnStateChangeListener(object : GestureController.OnStateChangeListener {
            override fun onStateChanged(state: State) = updateSwipe()
        })

    }

    override fun onStart() {
        super.onStart()
        if (!attempted) load()
    }
    override fun onResume() {
        super.onResume()
        if (!attempted) load()
        updateSwipe()
    }

    private fun load() {
        val ui = binding ?: return
        val key = identity ?: return
        val entry = (activity as? NasViewerActivity)?.entry(key.selected) ?: return
        attempted = true
        ui.nasPageProgress.isVisible = true
        ui.nasPageError.isVisible = false
        val context = requireContext().applicationContext
        val metrics = resources.displayMetrics
        request.start(viewLifecycleOwner.lifecycleScope, work = {
            NasPageLoader.load(context, entry, it, metrics.widthPixels, metrics.heightPixels)
        }, discard = { it.image?.close() }) { result ->
            ui.nasPageProgress.isVisible = false
            val value = result.getOrNull()
            val loaded = value?.image
            if (loaded == null) {
                ui.nasPageMessage.setText(nasFailureText(value?.failure))
                ui.nasPageError.isVisible = true
            } else {
                clearImage()
                image = loaded
                ui.nasPageImage.setImageDrawable(loaded.drawable)
                ui.nasPageImage.controller.resetState()
                fitZoom = ui.nasPageImage.controller.state.zoom
                (loaded.drawable as? Animatable)?.start()
                ui.nasPageImage.contentDescription = entry.name
                updateSwipe()
            }
        }
    }

    private fun updateSwipe() {
        val ui = binding ?: return
        identity?.let {
            (activity as? NasViewerActivity)?.enableSwipe(it.selected,
                ui.nasPageImage.controller.state.zoom <= fitZoom * ZOOM_TOLERANCE)
        }
    }

    private fun clearImage() {
        val old = image
        (old?.drawable as? Animatable)?.stop()
        binding?.nasPageImage?.setImageDrawable(null)
        image = null
        old?.let { NasUiRequest.dispose { it.close() } }
    }

    override fun onStop() {
        request.cancel()
        clearImage()
        attempted = false
        super.onStop()
    }

    override fun onDestroyView() {
        request.cancel()
        clearImage()
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val MAX_ZOOM = 8f
        const val DOUBLE_TAP_ZOOM = 2f
        const val ZOOM_TOLERANCE = 1.05f
    }
}
