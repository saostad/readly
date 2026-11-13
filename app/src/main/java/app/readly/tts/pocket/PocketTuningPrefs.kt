package app.readly.tts.pocket

import app.readly.playback.ReaderService

/**
 * The stored Pocket tuning as a pure prefs-map conversion, so JVM tests
 * can drive it with a fake map: a value that is missing (or stored under
 * the wrong type) falls back to that field's default; a stored set that
 * fails [PocketTuning.validate] is not used at all.
 */
data class PocketTuningLoad(
    /** The tuning to use: the stored set when valid, else the defaults. */
    val tuning: PocketTuning,
    /** The `validate()` message when the stored set was invalid, else null. */
    val invalidReason: String?,
)

object PocketTuningPrefs {

    /** Builds the tuning from stored [values] (prefs key → stored value). */
    fun load(values: Map<String, Any?>): PocketTuningLoad {
        val defaults = PocketTuning()
        val candidate = PocketTuning(
            maxTokens = intValue(values, ReaderService.KEY_POCKET_MAX_TOKENS, defaults.maxTokens),
            minTokens = intValue(values, ReaderService.KEY_POCKET_MIN_TOKENS, defaults.minTokens),
            g2pWindowChars = intValue(values, ReaderService.KEY_POCKET_G2P_WINDOW_CHARS, defaults.g2pWindowChars),
            temperature = floatValue(values, ReaderService.KEY_POCKET_TEMPERATURE, defaults.temperature),
            eosThreshold = floatValue(values, ReaderService.KEY_POCKET_EOS_THRESHOLD, defaults.eosThreshold),
            framesAfterEos = intValue(values, ReaderService.KEY_POCKET_FRAMES_AFTER_EOS, defaults.framesAfterEos),
            decodeSteps = intValue(values, ReaderService.KEY_POCKET_DECODE_STEPS, defaults.decodeSteps),
        )
        return try {
            candidate.validate()
            PocketTuningLoad(candidate, invalidReason = null)
        } catch (e: IllegalArgumentException) {
            PocketTuningLoad(PocketTuning(), invalidReason = e.message)
        }
    }

    /** Every stored value for [tuning], keyed for `SharedPreferences`. */
    fun values(tuning: PocketTuning): Map<String, Any> = mapOf(
        ReaderService.KEY_POCKET_MAX_TOKENS to tuning.maxTokens,
        ReaderService.KEY_POCKET_MIN_TOKENS to tuning.minTokens,
        ReaderService.KEY_POCKET_G2P_WINDOW_CHARS to tuning.g2pWindowChars,
        ReaderService.KEY_POCKET_TEMPERATURE to tuning.temperature,
        ReaderService.KEY_POCKET_EOS_THRESHOLD to tuning.eosThreshold,
        ReaderService.KEY_POCKET_FRAMES_AFTER_EOS to tuning.framesAfterEos,
        ReaderService.KEY_POCKET_DECODE_STEPS to tuning.decodeSteps,
    )

    private fun intValue(values: Map<String, Any?>, key: String, default: Int): Int =
        (values[key] as? Number)?.toInt() ?: default

    private fun floatValue(values: Map<String, Any?>, key: String, default: Float): Float =
        (values[key] as? Number)?.toFloat() ?: default
}
