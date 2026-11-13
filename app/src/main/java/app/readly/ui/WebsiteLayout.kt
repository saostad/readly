package app.readly.ui

import android.content.Context
import app.readly.playback.ReaderService

/** One user preference controls website density on the phone and in the car. */
object WebsiteLayout {
    const val KEY_COMPACT = "compactWebsiteLists"
    const val DEFAULT_COMPACT = true

    fun compact(context: Context): Boolean = context
        .getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_COMPACT, DEFAULT_COMPACT)
}
