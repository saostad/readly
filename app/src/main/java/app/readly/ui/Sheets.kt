package app.readly.ui

import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import app.readly.R

/**
 * Gives [this] the bottom-sheet window chrome: no title, 0.45 dim, slide-up
 * animation and 26dp top corners. Returns the window, or null when there is
 * none (callers bail out then). Width and gravity are applied after [Dialog.show]
 * via [Sheets.applyFullWidth]: layout set before the content is attached is
 * overridden by the window manager (round 21: the sheet opened ~120 px wide).
 */
fun Dialog.asBottomSheet(): Window? {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    val window = window ?: return null
    window.setBackgroundDrawableResource(R.drawable.sheet_background)
    window.setDimAmount(0.45f)
    window.attributes = window.attributes.apply {
        windowAnimations = R.style.SheetAnimation
    }
    return window
}

/** Bottom-sheet helpers shared by every sheet ([SettingsSheet], [ListSheet]). */
object Sheets {
    /**
     * Pins [dialog] full-width at the bottom of the screen. Must run after
     * [Dialog.show]: the window manager only honors the layout then. Together
     * with the `SheetDialog` theme (`windowIsFloating=false`, min width 100%)
     * this keeps every sheet full-width (round 21 narrow-strip fix).
     */
    fun applyFullWidth(dialog: Dialog) {
        val window = dialog.window ?: return
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
    }
}
