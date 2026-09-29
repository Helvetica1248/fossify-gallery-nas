package org.fossify.gallery.nas.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ItemNasBrowserBinding
import org.fossify.gallery.nas.external.NasExternalTypes
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.settings.SavedNasSource
import org.fossify.gallery.nas.transport.NasCancellation

internal data class NasBrowserRow(val title: String, val source: SavedNasSource? = null,
                                  val entry: NasEntry? = null, val favorite: NasRelativePath? = null)
private data class NasThumbnail(val bitmap: Bitmap?)

/** Click-only adapter: no selection, action mode or local Gallery adapter inheritance. */
internal class NasBrowserAdapter(private val scope: CoroutineScope, private val click: (NasBrowserRow) -> Unit) :
    RecyclerView.Adapter<NasBrowserAdapter.Holder>() {
    private var rows = emptyList<NasBrowserRow>()
    private val attached = mutableSetOf<Holder>()
    private var active = false

    fun submit(items: List<NasBrowserRow>) {
        attached.forEach { it.unbind() }
        rows = items
        notifyDataSetChanged()
    }

    fun setActive(value: Boolean) {
        active = value
        attached.forEach { if (value) it.load() else it.stop() }
    }

    suspend fun finishRequests() {
        active = false
        attached.mapNotNull { it.stop() }.joinAll()
    }

    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemNasBrowserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])
    override fun onViewAttachedToWindow(holder: Holder) {
        attached.add(holder)
        holder.itemView.post { if (holder in attached) holder.load() }
    }
    override fun onViewDetachedFromWindow(holder: Holder) {
        attached.remove(holder)
        holder.stop()
    }
    override fun onViewRecycled(holder: Holder) { holder.unbind(); holder.binding.nasItemImage.setImageDrawable(null) }

    inner class Holder(val binding: ItemNasBrowserBinding) : RecyclerView.ViewHolder(binding.root) {
        private val request = NasUiRequest()
        private var row: NasBrowserRow? = null
        private var attempted = false
        private var complete = false

        fun bind(value: NasBrowserRow) {
            stop()
            row = value
            attempted = false
            complete = false
            val context = binding.root.context
            val textColor = context.getProperTextColor()
            binding.nasItemName.apply { text = value.title; setTextColor(textColor) }
            binding.nasItemDetail.setTextColor(textColor)
            binding.nasItemDetail.text = when {
                value.favorite != null -> context.getString(R.string.nas_favorite_detail,
                    value.source?.displayName?.ifBlank { context.getString(R.string.nas_sources) },
                    value.favorite.value)
                value.source != null -> context.getString(
                    if (value.source.source.mode == NasConnectionMode.VPN) R.string.nas_vpn else R.string.nas_lan)
                value.entry?.kind == NasEntryKind.DIRECTORY -> context.getString(R.string.nas_folder_label)
                else -> ""
            }
            val external = value.entry?.let(NasExternalTypes::mime)
            binding.nasItemImage.setImageResource(when {
                external == "application/pdf" -> android.R.drawable.ic_menu_view
                external != null -> android.R.drawable.ic_media_play
                value.entry?.kind == NasEntryKind.FILE -> android.R.drawable.ic_menu_gallery
                else -> R.drawable.ic_folders_vector
            })
            if (external != null) binding.nasItemDetail.setText(
                if (external == "application/pdf") R.string.nas_external_pdf else R.string.nas_external_video)
            binding.root.contentDescription = value.title
            binding.root.setOnClickListener { click(value) }
            if (itemView.isAttachedToWindow) itemView.post { load() }
        }

        fun load() {
            val value = row ?: return
            if (value.entry == null && value.favorite == null) return
            if (!active || attempted) return
            if (!itemView.getGlobalVisibleRect(Rect())) return
            attempted = true
            val context = itemView.context.applicationContext
            request.start(scope, work = { cancellation ->
                val data = NasUiData.get(context)
                val entry = thumbnailEntry(data, value, cancellation) ?: return@start NasThumbnail(null)
                decodeThumbnail(data, entry, cancellation)
            }, discard = { it.bitmap?.recycle() }) { result ->
                complete = true
                val bitmap = result.getOrNull()?.bitmap
                if (bitmap != null) binding.nasItemImage.setImageBitmap(bitmap)
                else if (value.entry?.kind == NasEntryKind.FILE && NasExternalTypes.mime(value.entry) == null) {
                    binding.nasItemDetail.setText(R.string.nas_thumbnail_error)
                }
            }
        }

        private fun decodeThumbnail(data: NasUiData, entry: NasEntry, cancellation: NasCancellation): NasThumbnail =
            when (val result = data.image(entry, Variant.THUMBNAIL, cancellation)) {
                is NasCacheResult.Available -> result.lease.use {
                    val bitmap = try { BitmapFactory.decodeFile(it.file.absolutePath) }
                    catch (ignored: OutOfMemoryError) { null }
                    if (bitmap == null) data.repository.invalidateCache(entry, Variant.THUMBNAIL)
                    NasThumbnail(bitmap)
                }
                else -> NasThumbnail(null)
            }

        private fun thumbnailEntry(data: NasUiData, value: NasBrowserRow, cancellation: NasCancellation): NasEntry? =
            when {
                value.favorite != null ->
                    data.cover(checkNotNull(value.source).source.key, value.favorite, cancellation)
                value.entry?.kind == NasEntryKind.DIRECTORY ->
                    data.cover(value.entry.key.source, value.entry.key.path, cancellation)
                else -> value.entry
            }

        fun unbind() {
            stop()
            row = null
        }

        fun stop(): kotlinx.coroutines.Job? {
            val pending = request.cancel()
            if (!complete) attempted = false
            return pending
        }
    }
}
