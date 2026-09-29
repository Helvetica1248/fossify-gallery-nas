package org.fossify.gallery

import com.github.ajalt.reprint.core.Reprint
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response
import org.fossify.commons.FossifyApp

class App : FossifyApp() {

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        // The isolated thumbnail decoder has no settings access and needs no UI/image-loader setup.
        if (android.os.Process.myUid() != applicationInfo.uid) return
        super.onCreate()
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }
}
