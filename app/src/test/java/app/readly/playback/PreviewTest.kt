package app.readly.playback

import app.readly.model.VoiceFamily
import app.readly.model.VoicePack
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTest {

    private fun pack(speakers: List<String>) = VoicePack(
        id = "test",
        displayName = "Test",
        variant = "v",
        voiceName = null,
        family = VoiceFamily.KOKORO,
        archive = "a.tar.bz2",
        sizeBytes = 1L,
        sha256 = "0".repeat(64),
        topDir = "t",
        modelFile = "m.onnx",
        speakers = speakers,
        rtfNote = "",
        sampleRes = 0,
    )

    @Test
    fun speakerShortName_stripsParenthetical() {
        val pack = pack(listOf("Default (American, female)", "Bella (American, female)"))
        assertEquals("Default", ReaderService.speakerShortName(pack, 0))
        assertEquals("Bella", ReaderService.speakerShortName(pack, 1))
    }

    @Test
    fun speakerShortName_keepsPlainNamesAndCoercesId() {
        val pack = pack(listOf("Voice 1", "Voice 2"))
        assertEquals("Voice 1", ReaderService.speakerShortName(pack, 0))
        assertEquals("Voice 2", ReaderService.speakerShortName(pack, 99))
        assertEquals("Voice 1", ReaderService.speakerShortName(pack, -1))
    }

    /** A controllable preview task: [onGet] runs when the waiter awaits it. */
    private class FakeTask<T>(private val onGet: (Long, TimeUnit) -> T) : Future<T> {
        override fun get(timeout: Long, unit: TimeUnit): T = onGet(timeout, unit)
        override fun get(): T = throw UnsupportedOperationException()
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            throw UnsupportedOperationException()

        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = false
    }

    @Test
    fun awaitPreviewDone_waitsForSlowFinally() {
        var timedOut = false
        val slow = FakeTask { _, _ ->
            Thread.sleep(150)
            true
        }
        val start = System.nanoTime()
        val done = ReaderService.awaitPreviewDone(slow, 5_000L) { timedOut = true }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(done)
        assertFalse(timedOut)
        assertTrue("returned after $elapsedMs ms, before the 150 ms finally", elapsedMs >= 150)
    }

    @Test
    fun awaitPreviewDone_timeoutStillProceeds() {
        var timedOut = false
        val stuck = FakeTask<Boolean> { _, _ -> throw TimeoutException() }
        val done = ReaderService.awaitPreviewDone(stuck, 50L) { timedOut = true }
        assertFalse(done)
        assertTrue(timedOut)
    }

    @Test
    fun awaitPreviewDone_nullTaskReturnsAtOnce() {
        var timedOut = false
        assertTrue(ReaderService.awaitPreviewDone(null, 50L) { timedOut = true })
        assertFalse(timedOut)
    }
}
