package org.fossify.gallery.nas.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.Toolbar
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.viewpager2.widget.ViewPager2
import com.alexvasilkov.gestures.GestureImageView
import org.fossify.commons.views.MySearchMenu
import org.fossify.gallery.R
import org.fossify.gallery.activities.MainActivity
import org.fossify.gallery.activities.NasBrowserActivity
import org.fossify.gallery.activities.NasViewerActivity
import org.fossify.gallery.nas.cache.AndroidNasImages
import org.fossify.gallery.nas.cache.NasCacheResult
import org.fossify.gallery.nas.cache.NasDiskCache
import org.fossify.gallery.nas.catalog.NasCatalogDatabase
import org.fossify.gallery.nas.catalog.RoomNasCacheIndex
import org.fossify.gallery.nas.catalog.RoomNasCatalogStore
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasHost
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.repository.NasRepository
import org.fossify.gallery.nas.settings.SavedNasSource
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasOpenResult
import org.fossify.gallery.nas.transport.NasReadHandle
import org.fossify.gallery.nas.transport.NasReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real Activities + isolated Room/cache + synthetic images. No saved NAS settings or SMB transport. */
internal object NasUiProbe {
    var stage = "prepare"
    private val source = NasSource(NasSourceKey(UUID.randomUUID(), 1), NasHost.parse("fixture.invalid"), "photos",
        NasRelativePath.ROOT, NasConnectionMode.VPN, UUID.randomUUID())
    private val folder = NasRelativePath.ROOT.child("Sample album")

    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val dbFile = File(context.noBackupFilesDir, "nas-p5-probe.db")
        val cacheRoot = File(context.cacheDir, "nas-p5-probe")
        val favoritesFile = File(context.noBackupFilesDir, "nas-p5-favorites-probe.bin")
        favoritesFile.delete()
        context.deleteDatabase(dbFile.absolutePath)
        cacheRoot.deleteRecursively()
        val db = Room.databaseBuilder(context, NasCatalogDatabase::class.java, dbFile.absolutePath).build()
        val activities = mutableListOf<Activity>()
        try {
            val repository = prepare(db, cacheRoot)
            NasUiData.fixture = NasUiData(repository, NasFavoriteStore(favoritesFile)) { listOf(SavedNasSource(source, "NAS local fixture")) }
            exercise(instrumentation, activities)
            stage = "lease release"
            ui(instrumentation) { activities.reversed().forEach { it.finish() } }
            instrumentation.waitForIdleSync()
            repository.clearSourceCache(source.key.id)
            await { cacheRoot.walk().none { it.isFile } }
            return "PASS: favorites add/open/remove, folder cover, entry/source/folder/grid, JPEG EXIF+PNG+WebP+GIF, swipe/zoom/fullscreen/back, offline, lease cleanup"
        } finally {
            ui(instrumentation) { activities.reversed().forEach { it.finish() } }
            instrumentation.waitForIdleSync()
            NasUiData.fixture = null
            db.close()
            context.deleteDatabase(dbFile.absolutePath)
            cacheRoot.deleteRecursively()
            favoritesFile.delete()
        }
    }

    private fun prepare(db: NasCatalogDatabase, root: File): NasRepository {
        val files = linkedMapOf("01.jpg" to jpeg(root.parentFile!!), "02.png" to bitmap(Bitmap.CompressFormat.PNG),
            "03.webp" to bitmap(Bitmap.CompressFormat.WEBP), "04.gif" to Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAAQABAAACAkQBACH5BAAUAAAALAAAAAABAAEAAAICTAEAOw==", Base64.DEFAULT))
        val entries = files.map { (name, bytes) ->
            NasEntry(NasRemoteKey(source.key, folder.child(name)), NasEntryKind.FILE, bytes.size.toLong(), 1)
        }
        var offline = false
        val reader = object : NasReader {
            override fun list(source: NasSource, path: NasRelativePath, cancellation: NasCancellation): NasListingResult =
                if (offline) NasListingResult.Failed(NasFailure.VPN_REQUIRED) else NasListingResult.Complete(
                    if (path.isRoot) listOf(NasEntry(NasRemoteKey(source.key, folder), NasEntryKind.DIRECTORY, null, 1))
                    else entries)
            override fun open(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasOpenResult {
                check(!offline) { "Unexpected network request for cached image" }
                return NasOpenResult.Opened(object : NasReadHandle {
                    override val input = files.getValue(entry.name).inputStream()
                    override fun close() = input.close()
                })
            }
        }
        val repo = NasRepository(RoomNasCatalogStore(db), NasDiskCache(root, RoomNasCacheIndex(db.cache())),
            reader, AndroidNasImages())
        repo.refreshDirectory(source, NasRelativePath.ROOT, NasCancellation())
        repo.refreshDirectory(source, folder, NasCancellation())
        entries.forEach {
            (repo.fetchThumbnail(source, it, NasCancellation()) as NasCacheResult.Available).lease.close()
        }
        offline = true
        return repo
    }

    private fun exercise(i: Instrumentation, activities: MutableList<Activity>) {
        stage = "main entry"
        val main = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activities.add(main)
        val browserMonitor = i.addMonitor(NasBrowserActivity::class.java.name, null, false)
        ui(i) {
            val menu = main.findViewById<MySearchMenu>(R.id.main_menu).requireToolbar().menu
            check(menu.findItem(R.id.nas_albums) != null)
            check(menu.performIdentifierAction(R.id.nas_albums, 0))
        }
        val browser = checkNotNull(i.waitForMonitorWithTimeout(browserMonitor, 5000)) as NasBrowserActivity
        activities.add(browser)
        i.removeMonitor(browserMonitor)
        stage = "source list"
        clickRow(i, browser, 0, 1)
        stage = "folder cover"
        awaitUi(i) {
            val grid = browser.findViewById<RecyclerView>(R.id.nas_browser_grid)
            val drawable = grid.findViewHolderForAdapterPosition(0)?.itemView
                ?.findViewById<ImageView>(R.id.nas_item_image)?.drawable as? BitmapDrawable
            drawable?.bitmap?.width == 256
        }
        SystemClock.sleep(500)
        screenshot(i, "nas-p5-cover.png")
        stage = "folder navigation"
        clickRow(i, browser, 0, 1)
        stage = "grid thumbnails"
        awaitUi(i) {
            val grid = browser.findViewById<RecyclerView>(R.id.nas_browser_grid)
            grid.adapter?.itemCount == 4 && (0..3).all {
                val drawable = grid.findViewHolderForAdapterPosition(it)?.itemView
                    ?.findViewById<ImageView>(R.id.nas_item_image)?.drawable as? BitmapDrawable
                drawable?.bitmap?.width == if (it == 3) 1 else 256
            }
        }
        SystemClock.sleep(500) // Let the Activity transition finish before the diagnostic capture.
        screenshot(i, "nas-p5-grid.png")
        stage = "add favorite"
        favorite(i, browser, R.string.nas_favorite_add, R.string.nas_favorite_remove)
        val viewerMonitor = i.addMonitor(NasViewerActivity::class.java.name, null, false)
        clickRow(i, browser, 0, 4)
        val viewer = checkNotNull(i.waitForMonitorWithTimeout(viewerMonitor, 5000)) as NasViewerActivity
        activities.add(viewer)
        i.removeMonitor(viewerMonitor)
        val pager = viewer.findViewById<ViewPager2>(R.id.nas_viewer_pager)
        for (position in 0..3) {
            stage = "viewer page $position"
            if (position > 0) swipe(i, pager)
            awaitUi(i) { pager.currentItem == position }
            awaitUi(i) { pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                pager.currentItem == position && pageImage(pager, position)?.drawable != null }
            if (position == 0) ui(i) {
                val image = checkNotNull(pageImage(pager, position))
                check(image.drawable.intrinsicHeight > image.drawable.intrinsicWidth) // JPEG EXIF 90 degrees.
                check(image.controller.settings.isZoomEnabled)
                image.controller.state.zoomBy(2f, image.width / 2f, image.height / 2f)
                image.controller.updateState()
                check(image.controller.state.zoom > 1f)
                image.controller.resetState()
            }
            if (position == 3) ui(i) {
                stage = "GIF " + pageImage(pager, position)!!.drawable.javaClass.simpleName
                check(pageImage(pager, position)!!.drawable is Animatable)
            }
        }
        screenshot(i, "nas-p5-viewer.png")
        stage = "fullscreen"
        ui(i) {
            viewer.toggleFullscreen()
            check(viewer.findViewById<View>(R.id.nas_viewer_appbar).visibility == View.GONE)
            viewer.toggleFullscreen()
        }
        stage = "viewer back"
        ui(i) { viewer.onBackPressedDispatcher.onBackPressed() }
        awaitUi(i) { viewer.isDestroyed }
        stage = "browser back"
        ui(i) { browser.onBackPressedDispatcher.onBackPressed() }
        awaitUi(i) { browser.findViewById<RecyclerView>(R.id.nas_browser_grid).adapter?.itemCount == 1 }
        ui(i) { browser.onBackPressedDispatcher.onBackPressed() }
        stage = "favorite shortcut"
        awaitUi(i) { browser.findViewById<RecyclerView>(R.id.nas_browser_grid).adapter?.itemCount == 2 }
        SystemClock.sleep(500)
        screenshot(i, "nas-p5-favorites.png")
        clickRow(i, browser, 0, 2)
        awaitUi(i) { browser.findViewById<RecyclerView>(R.id.nas_browser_grid).adapter?.itemCount == 4 }
        stage = "remove favorite"
        favorite(i, browser, R.string.nas_favorite_remove, R.string.nas_favorite_add)
        check(NasUiData.fixture!!.favorites.list().isEmpty())
    }

    private fun favorite(i: Instrumentation, browser: NasBrowserActivity, before: Int, after: Int) {
        val menu = browser.findViewById<Toolbar>(R.id.nas_browser_toolbar).menu
        awaitUi(i) { menu.findItem(3).isEnabled && menu.findItem(3).title == browser.getString(before) }
        ui(i) { check(menu.performIdentifierAction(3, 0)) }
        awaitUi(i) { menu.findItem(3).isEnabled && menu.findItem(3).title == browser.getString(after) }
    }

    private fun clickRow(i: Instrumentation, activity: Activity, position: Int, count: Int) {
        awaitUi(i) {
            val grid = activity.findViewById<RecyclerView>(R.id.nas_browser_grid)
            grid.adapter?.itemCount == count && grid.findViewHolderForAdapterPosition(position) != null
        }
        ui(i) {
            activity.findViewById<RecyclerView>(R.id.nas_browser_grid)
                .findViewHolderForAdapterPosition(position)!!.itemView.performClick()
        }
        i.waitForIdleSync()
    }

    private fun swipe(i: Instrumentation, pager: ViewPager2) {
        val location = IntArray(2)
        var width = 0
        var height = 0
        ui(i) { pager.getLocationOnScreen(location); width = pager.width; height = pager.height }
        val down = SystemClock.uptimeMillis()
        val y = location[1] + height * 0.55f
        val x = location[0] + width * 0.85f
        fun send(action: Int, step: Int) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x - width * 0.7f * step / 12, y, 0)
            try { i.sendPointerSync(event) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, 0)
        for (step in 1..12) { SystemClock.sleep(20); send(android.view.MotionEvent.ACTION_MOVE, step) }
        send(android.view.MotionEvent.ACTION_UP, 12)
    }

    private fun pageImage(pager: ViewPager2, position: Int): GestureImageView? =
        (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(position)?.itemView
            ?.findViewById(R.id.nas_page_image)

    private fun ui(i: Instrumentation, action: () -> Unit) {
        var failure: Throwable? = null
        i.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun awaitUi(i: Instrumentation, condition: () -> Boolean) = await {
        var ready = false
        ui(i) { ready = condition() }
        ready
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Fixture UI timeout" }
            SystemClock.sleep(100)
        }
    }

    private fun screenshot(i: Instrumentation, name: String) {
        val image = checkNotNull(i.uiAutomation.takeScreenshot())
        File(i.targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun jpeg(root: File): ByteArray {
        val file = File(root, "nas-p5-exif-fixture.jpg")
        try {
            file.writeBytes(bitmap(Bitmap.CompressFormat.JPEG))
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally { file.delete() }
    }

    private fun bitmap(format: Bitmap.CompressFormat): ByteArray {
        val image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(40, 130, 190))
        try {
            return ByteArrayOutputStream().use { image.compress(format, 90, it); it.toByteArray() }
        } finally { image.recycle() }
    }
}
