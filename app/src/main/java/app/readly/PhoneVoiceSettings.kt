package app.readly

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

    /** The default engine package, whether it is us, and the status line to show. */
    data class PhoneStatus(val defaultEngine: String?, val isOurs: Boolean, val text: String)

    /**
     * Reads the default TTS engine setting and resolves its label (falling back
     * to the package name, with a warning, when the label can't be read).
     */
    fun status(activity: Activity): PhoneStatus {
        val ours = activity.packageName
        val defaultEngine: String? = try {
            Settings.Secure.getString(activity.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        } catch (e: Exception) {
            Log.w("Readly", "Couldn't read the default TTS engine setting", e)
            null
        }
        val label = if (defaultEngine != null && defaultEngine != ours) {
            try {
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    activity.packageManager.getApplicationInfo(
                        defaultEngine,
                        PackageManager.ApplicationInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getApplicationInfo(defaultEngine, 0)
                }
                activity.packageManager.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                Log.w("Readly", "Couldn't resolve the TTS engine label for $defaultEngine", e)
                defaultEngine
            }
        } else {
            ""
        }
        return PhoneStatus(defaultEngine, defaultEngine == ours, statusText(defaultEngine, ours, label))
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
