package app.readly.tts.pocket

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports of the `pocket.rs` helper tests (`split_text_respects_token_budget_and_punctuation`,
 * `trim_silence_keeps_120ms_at_each_end`, `loudness_lifts_quiet_audio_and_limits_peaks`) plus
 * the runaway cap and the 1.0-speed identity.
 */
class PocketAudioTest {
    @Test
    fun splitTextRespectsTokenBudgetAndPunctuation() {
        // One token per word in this toy counter.
        val counter = { s: String -> s.split(WHITESPACE).count { it.isNotEmpty() } }
        val text = "یک دو سه چهار. پنج شش هفت هشت نه ده یازده دوازده سیزده چهارده پانزده شانزده هفده هجده نوزده بیست."
        val chunks = PocketAudio.splitText(text, counter, 8)
        assertTrue("expected at least 3 chunks, got $chunks", chunks.size >= 3)
        for (chunk in chunks) {
            assertTrue("chunk over budget: $chunk", counter(chunk) <= 8)
        }
        // Short text stays whole.
        assertEquals(listOf("سلام دنیا."), PocketAudio.splitText("سلام دنیا.", counter, 18))
        assertTrue(PocketAudio.splitText("", counter, 18).isEmpty())
    }

    @Test
    fun trimSilenceKeeps120msAtEachEnd() {
        val sampleRate = 24000
        val keep = 2880
        val samples = FloatArray(24000)
        samples[12000] = 0.5f
        val trimmed = PocketAudio.trimSilence(samples, sampleRate)
        assertEquals(keep * 2 + 1, trimmed.size)
        assertEquals(0.5f, trimmed[keep], 0f)
        // Loud edges are untouched.
        val loud = FloatArray(100) { 0.5f }
        assertTrue(loud.contentEquals(PocketAudio.trimSilence(loud, sampleRate)))
    }

    @Test
    fun loudnessLiftsQuietAudioAndLimitsPeaks() {
        // Quiet sine (~-23 dB RMS) gains up toward the target.
        val quiet = FloatArray(24000) { i -> 0.1f * sin(2.0 * PI * 440.0 * i / 24000.0).toFloat() }
        val lifted = PocketAudio.normalizeLoudness(quiet)
        assertTrue("quiet audio must get louder", rms(lifted) > rms(quiet) * 1.5)
        // Loud audio never exceeds the ceiling.
        val loud = FloatArray(24000) { i -> 0.99f * sin(2.0 * PI * 440.0 * i / 24000.0).toFloat() }
        val limited = PocketAudio.normalizeLoudness(loud)
        val peak = limited.maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak", peak <= PocketAudio.PEAK_CEILING + 1e-6f)
        // Silence passes through.
        assertTrue(FloatArray(16).contentEquals(PocketAudio.normalizeLoudness(FloatArray(16))))
    }

    @Test
    fun runawayLimitIs97PercentOfLengthCap() {
        val expected = ceil((18.0 / 3 + 2) * 12.5) / 12.5 * 0.97
        assertEquals(expected, PocketAudio.runawayLimitSeconds(18), 1e-12)
    }

    @Test
    fun applySpeedAt1ReturnsInputUnchanged() {
        val samples = FloatArray(100) { it.toFloat() }
        assertTrue(samples.contentEquals(PocketAudio.applySpeed(samples, 1.0f)))
    }

    private fun rms(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return kotlin.math.sqrt(sum / samples.size)
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
