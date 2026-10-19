package app.readly.tts.pocket

import java.io.File
import java.io.IOException
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the pure parts of [PocketEngine] (no ONNX Runtime): the f16 voice
 * decoding, the step cache, the sampling RNG, and the missing-file error.
 */
class PocketEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun f16DecodingCoversZeroOneAndSubnormals() {
        assertEquals(0.0f, f16ToF32(0x0000))
        assertEquals(1.0f, f16ToF32(0x3c00))
        assertEquals(-1.0f, f16ToF32(0xbc00))
        assertEquals(5.9604645e-8f, f16ToF32(0x0001), 1e-12f)
        assertTrue(f16ToF32(0x7e00).isNaN())
    }

    @Test
    fun cacheWindowAndWindowSized() {
        val cache = Cache(stride = 2, capacity = 2)
        cache.append(floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(2, cache.length)
        // The full past.
        assertEquals(listOf(1f, 2f, 3f, 4f), cache.window().toList())
        // Still short: zero-padded at the front.
        assertEquals(listOf(0f, 0f, 1f, 2f, 3f, 4f), cache.windowSized(3).toList())
        // Full: the last `size` entries.
        assertEquals(listOf(3f, 4f), cache.windowSized(1).toList())
        // Growth keeps the past.
        cache.append(floatArrayOf(5f, 6f, 7f, 8f, 9f, 10f))
        assertEquals(5, cache.length)
        assertEquals(
            listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f),
            cache.window().toList(),
        )
    }

    @Test
    fun rngNoiseHasPlausibleSpread() {
        val noise = Rng().noise(4_000, 0.3f)
        val mean = noise.average().toFloat()
        val variance = noise.map { (it - mean) * (it - mean) }.average().toFloat()
        assertTrue("mean $mean", abs(mean) < 0.05f)
        assertTrue("variance $variance", abs(variance - 0.3f) < 0.05f)
    }

    @Test
    fun v2SentenceFinalChunksEndSentences() {
        // Each sentence's phonemes end a sentence; ezafe/word cuts inside don't.
        val chunks = v2TextChunks(
            listOf(
                listOf("ketAbe1 jadide1", "duste1 man"),
                listOf("miz ?ast"),
            ),
        )
        assertEquals(
            listOf(
                TextChunk("ketAbe1 jadide1", PocketAudio.PieceEnd.MID),
                TextChunk("duste1 man", PocketAudio.PieceEnd.SENTENCE),
                TextChunk("miz ?ast", PocketAudio.PieceEnd.SENTENCE),
            ),
            chunks,
        )
    }

    @Test
    fun missingFileGivesThePathInTheMessage() {
        val missing = File(temp.root, "pocket-farsi-v1/pocket-farsi-v1.onnx")
        try {
            PocketEngine(missing)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Pocket voice file is missing: ${missing.path}", e.message)
        }
    }
}
