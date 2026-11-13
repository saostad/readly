package app.readly.tts.pocket

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun pocketTuningDefaultsValidate() {
        val tuning = PocketTuning()
        assertEquals(50, tuning.maxTokens)
        assertEquals(8, tuning.minTokens)
        assertEquals(120, tuning.g2pWindowChars)
        assertEquals(0.3f, tuning.temperature, 0.0f)
        assertEquals(-2.0f, tuning.eosThreshold, 0.0f)
        assertEquals(0, tuning.framesAfterEos)
        assertEquals(0, tuning.decodeSteps)
        tuning.validate()
    }

    @Test
    fun pocketTuningOutOfRangeFieldsFailWithTheirKey() {
        val cases = listOf(
            PocketTuning(maxTokens = 7) to "pocket.max_tokens",
            PocketTuning(maxTokens = 51) to "pocket.max_tokens",
            PocketTuning(minTokens = 0) to "pocket.min_tokens",
            PocketTuning(maxTokens = 18, minTokens = 19) to "pocket.min_tokens",
            PocketTuning(temperature = 1.6f) to "pocket.temperature",
            PocketTuning(temperature = Float.NaN) to "pocket.temperature",
            PocketTuning(decodeSteps = 5) to "pocket.decode_steps",
            PocketTuning(eosThreshold = -10.1f) to "pocket.eos_threshold",
            PocketTuning(eosThreshold = Float.POSITIVE_INFINITY) to "pocket.eos_threshold",
            PocketTuning(framesAfterEos = 11) to "pocket.frames_after_eos",
            PocketTuning(g2pWindowChars = 39) to "pocket.g2p_window_chars",
            PocketTuning(g2pWindowChars = 1001) to "pocket.g2p_window_chars",
        )
        for ((tuning, key) in cases) {
            try {
                tuning.validate()
                fail("expected IllegalArgumentException for $key")
            } catch (e: IllegalArgumentException) {
                assertTrue("message '${e.message}' must name $key", e.message!!.contains(key))
            }
        }
    }

    @Test
    fun prefetchedResultEqualsInlineResult() {
        val compute: (String) -> V2PrefetchData = { text ->
            V2PrefetchData(
                listOf(TextChunk("phonemes for $text", PocketAudio.PieceEnd.SENTENCE)),
                listOf(),
            )
        }
        V2Prefetcher(compute).use { prefetcher ->
            prefetcher.prefetch("hello")
            val take = awaitTake(prefetcher, "hello")
            assertTrue("expected Ready, was $take", take is V2PrefetchTake.Ready)
            assertEquals(compute("hello"), (take as V2PrefetchTake.Ready).data)
        }
    }

    @Test
    fun staleTextIgnored() {
        V2Prefetcher { text ->
            V2PrefetchData(
                listOf(TextChunk(text, PocketAudio.PieceEnd.SENTENCE)),
                emptyList(),
            )
        }.use { prefetcher ->
            prefetcher.prefetch("aaa")
            assertTrue(prefetcher.take("bbb") is V2PrefetchTake.Miss)
            val take = awaitTake(prefetcher, "aaa")
            assertTrue("expected Ready, was $take", take is V2PrefetchTake.Ready)
            assertEquals("aaa", (take as V2PrefetchTake.Ready).data.chunks!!.single().text)
        }
    }

    @Test
    fun prefetchErrorSurfacesFromInlinePath() {
        val message = "Pronunciation step failed for \"bad\": test boom"
        val compute: (String) -> V2PrefetchData = { throw IOException(message) }
        V2Prefetcher(compute).use { prefetcher ->
            prefetcher.prefetch("bad")
            val take = awaitTake(prefetcher, "bad")
            assertTrue("expected Failed, was $take", take is V2PrefetchTake.Failed)
            try {
                compute("bad")
                fail("inline compute must throw")
            } catch (e: IOException) {
                assertEquals(message, e.message)
            }
        }
    }

    @Test
    fun pendingSameTextPrefetchIsAwaited() {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        V2Prefetcher { text ->
            calls.incrementAndGet()
            started.countDown()
            Thread.sleep(300)
            V2PrefetchData(
                listOf(TextChunk(text, PocketAudio.PieceEnd.SENTENCE)),
                emptyList(),
            )
        }.use { prefetcher ->
            prefetcher.prefetch("hello")
            assertTrue("compute never started", started.await(10, TimeUnit.SECONDS))
            assertTrue(prefetcher.take("hello") is V2PrefetchTake.Pending)
            // Awaiting consumes the running compute instead of recomputing it.
            val take = prefetcher.await("hello")
            assertTrue("expected Ready, was $take", take is V2PrefetchTake.Ready)
            assertEquals("hello", (take as V2PrefetchTake.Ready).data.chunks!!.single().text)
            assertEquals(1, calls.get())
            assertTrue(prefetcher.take("hello") is V2PrefetchTake.Miss)
            // A different text still misses, so the caller computes inline.
            assertTrue(prefetcher.await("other") is V2PrefetchTake.Miss)
        }
    }

    @Test
    fun pocketThreadCountIsClampedTo2To4() {
        assertEquals(2, PocketEngine.MIN_THREADS)
        assertEquals(4, PocketEngine.MAX_THREADS)
        assertEquals(2, PocketEngine.threadCount(1))
        assertEquals(2, PocketEngine.threadCount(2))
        assertEquals(2, PocketEngine.threadCount(3))
        assertEquals(3, PocketEngine.threadCount(4))
        assertEquals(4, PocketEngine.threadCount(5))
        assertEquals(4, PocketEngine.threadCount(8))
    }

    @Test
    fun g2pSessionsAreSingleThreaded() {
        assertEquals(1, G2p.INTRA_OP_THREADS)
        assertEquals(1, G2p.INTER_OP_THREADS)
    }

    private fun awaitTake(prefetcher: V2Prefetcher, text: String): V2PrefetchTake {
        val end = System.nanoTime() + 10_000_000_000L
        while (true) {
            val take = prefetcher.take(text)
            // Ready/Failed/Miss consume the entry; Pending leaves it for the retry.
            if (take !is V2PrefetchTake.Pending) return take
            if (System.nanoTime() > end) return take
            Thread.sleep(10)
        }
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
