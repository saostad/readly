package app.readly

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * The phone's default text-to-speech engine: describing it and opening the
 * system screen that changes it. Shared by the main screen's Phone voice
 * section and the Voices screen's System voice button so the intent and its
 * fallback live in one place.
 */
object PhoneVoiceSettings {

    /** Opens the Text-to-speech output screen directly (verified on a Pixel). */
    const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

    /**
     * Status line for the Phone voice section, chosen from the stored default
     * engine package ([defaultEngine]), our own package name and the resolved
     * engine label. Pure so it is JVM-testable; reading the setting and
     * resolving the label need a Context, so the caller does those.
     */
    fun statusText(defaultEngine: String?, ourPackage: String, engineLabel: String): String =
        when {
            defaultEngine == null ->
                "Your phone's voice engine couldn't be read."
            defaultEngine == ourPackage ->
                "✓ Readly is your phone's voice."
            else ->
                "Your phone speaks with $engineLabel."
        }

    /**
     * Opens the phone's text-to-speech settings. Shows [hintToast] after a
     * successful open (null shows nothing). If the TTS screen doesn't exist,
     * opens generic Settings with [fallbackToast] instead and logs the miss.
     */
    fun open(activity: Activity, hintToast: String?, fallbackToast: String) {
        try {
            activity.startActivity(Intent(TTS_SETTINGS_ACTION))
            if (hintToast != null) {
                Toast.makeText(activity, hintToast, Toast.LENGTH_LONG).show()
            }
        } catch (_: ActivityNotFoundException) {
            Log.w("Readly", "TTS settings screen not found; opening system settings")
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            Toast.makeText(activity, fallbackToast, Toast.LENGTH_LONG).show()
        }
    }
}
