package app.readly.tts.pocket

import app.readly.model.VoiceFamily
import app.readly.playback.ReaderService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketTuningPrefsTest {

    @Test
    fun emptyPrefsGiveDefaults() {
        val load = PocketTuningPrefs.load(emptyMap())
        assertEquals(PocketTuning(), load.tuning)
        assertNull(load.invalidReason)
    }

    @Test
    fun eachStoredValueIsUsed() {
        val stored = mapOf<String, Any?>(
            ReaderService.KEY_POCKET_MAX_TOKENS to 30,
            ReaderService.KEY_POCKET_MIN_TOKENS to 5,
            ReaderService.KEY_POCKET_G2P_WINDOW_CHARS to 200,
            ReaderService.KEY_POCKET_TEMPERATURE to 0.9f,
            ReaderService.KEY_POCKET_EOS_THRESHOLD to 1.5f,
            ReaderService.KEY_POCKET_FRAMES_AFTER_EOS to 3,
            ReaderService.KEY_POCKET_DECODE_STEPS to 2,
        )
        val load = PocketTuningPrefs.load(stored)
        assertNull(load.invalidReason)
        assertEquals(
            PocketTuning(
                maxTokens = 30, minTokens = 5, g2pWindowChars = 200,
                temperature = 0.9f, eosThreshold = 1.5f, framesAfterEos = 3,
                decodeSteps = 2,
            ),
            load.tuning,
        )
        // A single stored value is used; the rest stay at their defaults.
        val one = PocketTuningPrefs.load(
            mapOf(ReaderService.KEY_POCKET_MAX_TOKENS to 42),
        )
        assertEquals(42, one.tuning.maxTokens)
        assertEquals(PocketTuning().minTokens, one.tuning.minTokens)
    }

    @Test
    fun invalidStoredSetGivesDefaultsPlusValidateMessage() {
        val load = PocketTuningPrefs.load(
            mapOf(ReaderService.KEY_POCKET_MAX_TOKENS to 100),
        )
        assertEquals(PocketTuning(), load.tuning)
        assertTrue(load.invalidReason!!.contains("pocket.max_tokens"))

        val crossed = PocketTuningPrefs.load(
            mapOf(
                ReaderService.KEY_POCKET_MAX_TOKENS to 18,
                ReaderService.KEY_POCKET_MIN_TOKENS to 19,
            ),
        )
        assertEquals(PocketTuning(), crossed.tuning)
        assertTrue(crossed.invalidReason!!.contains("pocket.min_tokens"))
    }

    @Test
    fun valuesRoundTripThroughLoad() {
        val tuning = PocketTuning(
            maxTokens = 25, minTokens = 4, g2pWindowChars = 300,
            temperature = 0.5f, eosThreshold = -1.0f, framesAfterEos = 2,
            decodeSteps = 1,
        )
        val load = PocketTuningPrefs.load(PocketTuningPrefs.values(tuning))
        assertNull(load.invalidReason)
        assertEquals(tuning, load.tuning)
    }

    @Test
    fun onlyAPocketVoiceIsRebuiltForNewTuning() {
        val built = PocketTuning()
        val changed = PocketTuning(maxTokens = 30)
        assertTrue(PocketTuningPrefs.rebuildNeeded(VoiceFamily.POCKET, built, changed))
        assertFalse(PocketTuningPrefs.rebuildNeeded(VoiceFamily.POCKET, built, built.copy()))
        // Other voices ignore the tuning, so a change never reloads them.
        assertFalse(PocketTuningPrefs.rebuildNeeded(VoiceFamily.KOKORO, built, changed))
        assertFalse(PocketTuningPrefs.rebuildNeeded(VoiceFamily.PIPER, built, changed))
        assertFalse(PocketTuningPrefs.rebuildNeeded(null, built, changed))
    }
}
