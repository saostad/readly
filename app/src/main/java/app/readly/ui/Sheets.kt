package app.readly.ui

import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import app.readly.R

/**
 * Gives [this] the bottom-sheet window chrome: no title, bottom gravity, full
 * width, 0.45 dim, slide-up animation and 26dp top corners. Returns the window,
 * or null when there is none (callers bail out then).
 */
fun Dialog.asBottomSheet(): Window? {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    val window = window ?: return null
    window.setBackgroundDrawableResource(R.drawable.sheet_background)
    window.setGravity(Gravity.BOTTOM)
    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    window.setDimAmount(0.45f)
    window.attributes = window.attributes.apply {
        windowAnimations = R.style.SheetAnimation
    }
    return window
}
