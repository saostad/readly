package app.readly.ui

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure formatting and mapping helpers for Readly's redesigned Settings Hub and
 * sub-screens. Free of Android dependencies so JVM unit tests run directly.
 */
object SettingsSummaries {

    val SPEED_PRESETS: List<Float> = listOf(1.0f, 1.2f, 1.5f, 1.8f)

    /** Maps SeekBar progress 0..15 to 0.5x..2.0x in 0.1x steps. */
    fun progressToSpeed(progress: Int): Float =
        ((0.5f + progress.coerceIn(0, 15) * 0.1f) * 10f).roundToInt() / 10f

    /** Maps playback speed 0.5x..2.0x back to SeekBar progress 0..15. */
    fun speedToProgress(speed: Float): Int {
        val safe = if (speed.isFinite()) speed else 1.0f
        return ((safe - 0.5f) * 10f + 0.5f).toInt().coerceIn(0, 15)
    }

    fun formatSpeed(speed: Float): String =
        String.format(Locale.US, "%.1f×", if (speed.isFinite()) speed else 1.0f)

    fun voicesSummary(
        mainVoice: String,
        persianVoice: String?,
        smartVoice: Boolean,
    ): String = buildString {
        append(mainVoice)
        if (!persianVoice.isNullOrBlank()) {
            append(" · ")
            append(persianVoice)
            append(" (FA)")
        }
        append(" · Smart voice ")
        append(if (smartVoice) "On" else "Off")
    }

    fun readingSummary(
        normalize: Boolean,
        dayFirst: Boolean,
        autoUpdates: Boolean,
    ): String {
        val norm = if (normalize) "Numbers On" else "Numbers Off"
        val date = if (dayFirst) "Day-first" else "Month-first"
        val eng = if (autoUpdates) "Engine Auto-update" else "Engine Manual"
        return "$norm · $date · $eng"
    }

    fun websitesSummary(
        siteCount: Int,
        compact: Boolean,
        timeZoneLabel: String,
        scheduleEnabled: Boolean,
    ): String {
        val siteWord = if (siteCount == 1) "site" else "sites"
        val layout = if (compact) "Compact list" else "Tiles"
        val sched = if (scheduleEnabled) "Schedule On" else "Schedule Off"
        return "$siteCount $siteWord · $layout · $timeZoneLabel · $sched"
    }

    fun androidAutoSummary(
        playNext: Boolean,
        prefetch: Boolean,
        pausePrompts: Boolean,
    ): String =
        "Play next ${onOff(playNext)} · Prefetch ${onOff(prefetch)} · Pause prompts ${onOff(pausePrompts)}"

    fun documentsSummary(
        ocrEnabled: Boolean,
        ocrLanguage: String,
        maxSide: Int,
    ): String {
        val ocr = if (ocrEnabled) "On-device OCR On" else "OCR Off"
        val lang = when (ocrLanguage) {
            "eng" -> "English"
            "fas" -> "Persian"
            "eng+fas" -> "EN+FA"
            else -> "Auto (EN/FA)"
        }
        return "$ocr · $lang · $maxSide px"
    }

    fun storageSummary(voiceMegabytes: Int, voicePackCount: Int): String {
        val packs = if (voicePackCount == 1) "1 pack" else "$voicePackCount packs"
        return "Voices $voiceMegabytes MB ($packs) · Clear readings & imports"
    }

    fun shortTimeZoneName(zoneId: String, deviceZoneId: String): String = when (zoneId) {
        "America/New_York" -> "New York"
        "device" -> "Device (${deviceZoneId.substringAfterLast('/').replace('_', ' ')})"
        else -> zoneId.substringAfterLast('/').replace('_', ' ')
    }

    fun formatCharCount(chars: Int): String = when {
        chars >= 1_000_000 && chars % 1_000_000 == 0 -> "${chars / 1_000_000}M chars"
        chars >= 1_000_000 -> String.format(Locale.US, "%.1fM chars", chars / 1_000_000.0)
        chars >= 1_000 && chars % 1_000 == 0 -> "${chars / 1_000}K chars"
        else -> "$chars chars"
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "On" else "Off"
}
