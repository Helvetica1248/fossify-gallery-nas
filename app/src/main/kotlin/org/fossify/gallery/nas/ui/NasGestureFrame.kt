package org.fossify.gallery.nas.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/** Keep the pager from intercepting the second finger before the image zoom controller sees it. */
class NasGestureFrame(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }
}
