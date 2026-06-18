package com.piontech.changehaircolor.demo.util

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Base activity that:
 *  - hides the navigation bar (immersive, swipe-to-reveal transiently), and
 *  - lets content draw edge-to-edge while keeping it clear of the status bar and
 *    the display cutout ("tai thỏ"/notch) via [applyContentInsets].
 *
 * Subclasses call [applyContentInsets] on their root view after setContentView().
 */
open class ImmersiveActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideNavigationBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide after the bar is revealed by a swipe or after returning to the screen.
        if (hasFocus) hideNavigationBar()
    }

    private fun hideNavigationBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * Pads [root] so its content stays below the status bar and outside the
     * display cutout. The navigation bar is hidden, so the bottom edge is left
     * to the layout (use [padBottom] = true to also inset the bottom cutout).
     */
    protected fun applyContentInsets(root: View, padBottom: Boolean = false) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                baseLeft + i.left,
                baseTop + i.top,
                baseRight + i.right,
                baseBottom + if (padBottom) i.bottom else 0,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
