package com.example.itellytv.ui

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Applies system-bar (status / nav) insets as padding on a root view
 * so the content doesn't sit behind the OS chrome.
 *
 * Android 15 (API 35) made edge-to-edge the default — the system
 * bars are transparent and the app is responsible for offsetting its
 * own content. TVs don't usually have a software nav bar, but they
 * do render the status bar (the timestamp / signal row) at the top
 * of the screen, and we want our channel-list header to sit *below*
 * that row, not under it.
 *
 * Usage:
 *   applySystemBarInsets(rootView, applyTop = true, applyBottom = true)
 *
 * The listener installs once; calling applySystemBarInsets multiple
 * times on the same view is a no-op (we guard with a tag).
 */
object EdgeToEdgeInsets {

    private val APPLIED_TAG = -0x77E1D   // sentinel to avoid double-install

    fun apply(
        root: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true,
        applyLeft: Boolean = true,
        applyRight: Boolean = true
    ) {
        if (root.getTag(APPLIED_TAG) == true) return
        root.setTag(APPLIED_TAG, true)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val bars: Insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )
            v.updatePadding(
                left   = if (applyLeft)   bars.left   else v.paddingLeft,
                top    = if (applyTop)    bars.top    else v.paddingTop,
                right  = if (applyRight)  bars.right  else v.paddingRight,
                bottom = if (applyBottom) bars.bottom else v.paddingBottom
            )
            // Return the insets unconsumed so children that want to
            // react to IME / display-cutout can still see them.
            windowInsets
        }
    }
}
