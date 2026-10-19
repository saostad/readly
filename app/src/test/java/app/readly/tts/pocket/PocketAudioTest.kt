package app.readly.tts.pocket

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports of the `pocket.rs` helper tests (`split_text_respects_token_budget_and_punctuation`,
 * `trim_silence_keeps_120ms_at_each_end`) plus the runaway cap, the 1.0-speed identity, and
 * the BS.1770 loudness chain (sine, gating, and the real v1 fixture clip).
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
    fun runawayLimitIs97PercentOfLengthCap() {
        val expected = ceil((18.0 / 3 + 2) * 12.5) / 12.5 * 0.97
        assertEquals(expected, PocketAudio.runawayLimitSeconds(18), 1e-12)
    }

    @Test
    fun applySpeedAt1ReturnsInputUnchanged() {
        val samples = FloatArray(100) { it.toFloat() }
        assertTrue(samples.contentEquals(PocketAudio.applySpeed(samples, 1.0f)))
    }

    @Test
    fun measureLufsOfSineIsMinus23AtBothSampleRates() {
        // 997 Hz sine at amplitude 0.1: RMS −23.0 dB, K-weighting ≈ 0 dB at 1 kHz.
        val at24k = PocketAudio.measureLufs(sine997(24000, 3), 24000)
        assertEquals(-23.0, at24k, 0.3)
        val at48k = PocketAudio.measureLufs(sine997(48000, 3), 48000)
        assertEquals(-23.0, at48k, 0.3)
        // Rate-correct coefficients: same acoustic level measures the same.
        assertTrue("24k=$at24k 48k=$at48k", abs(at24k - at48k) < 0.3)
    }

    @Test
    fun gatingIgnoresSilence() {
        val sine = sine997(24000, 2)
        val combo = sine + FloatArray(2 * 24000)
        val alone = PocketAudio.measureLufs(sine, 24000)
        val withSilence = PocketAudio.measureLufs(combo, 24000)
        assertTrue("alone=$alone combo=$withSilence", abs(withSilence - alone) < 0.5)
    }

    @Test
    fun rawClipMeasuresMinus218AndMastersToMinus10() {
        val (raw, sampleRate) = readMono16BitWav("/pocket/raw-v1-6s.wav")
        assertEquals(24000, sampleRate)
        // ffmpeg ebur128 says −21.8 LUFS for this clip.
        assertEquals(-21.8, PocketAudio.measureLufs(raw, sampleRate), 0.5)

        val mastered = PocketAudio.masterChunk(raw, sampleRate)
        assertEquals(raw.size, mastered.size)
        assertEquals(-10.0, PocketAudio.measureLufs(mastered, sampleRate), 1.0)
        val peak = mastered.maxOf { abs(it) }
        assertTrue("peak $peak exceeds ${PocketAudio.PEAK_CEILING}", peak <= PocketAudio.PEAK_CEILING)

        val outFile = File(findBuildDir(), "pocket-audio/processed-v1-6s.wav")
        try {
            outFile.parentFile!!.mkdirs()
            writeMono16BitWav(outFile, mastered, sampleRate)
        } catch (e: Exception) {
            throw AssertionError("couldn't write ${outFile.path}: ${e.javaClass.name}: ${e.message}")
        }
    }

    @Test
    fun allZeroInputPassesThroughUnchanged() {
        val silent = FloatArray(24000)
        assertTrue(silent.contentEquals(PocketAudio.masterChunk(silent, 24000)))
    }

    @Test
    fun masterChunkIsFastOnHost() {
        // The phone build does this per chunk; on the host 6 s of audio must master
        // well under a frame budget after JIT warm-up (round 14: was O(n·lookahead)).
        val (raw, sampleRate) = readMono16BitWav("/pocket/raw-v1-6s.wav")
        repeat(3) { PocketAudio.masterChunk(raw, sampleRate) }
        val t0 = System.nanoTime()
        val mastered = PocketAudio.masterChunk(raw, sampleRate)
        val ms = (System.nanoTime() - t0) / 1e6
        println("masterChunk 6 s fixture: %.1f ms".format(ms))
        assertEquals(raw.size, mastered.size)
        assertTrue("masterChunk took %.1f ms, expected < 60 ms".format(ms), ms < 60.0)
    }

    private fun sine997(sampleRate: Int, seconds: Int): FloatArray {
        val n = sampleRate * seconds
        return FloatArray(n) { i -> 0.1f * sin(2.0 * PI * 997.0 * i / sampleRate).toFloat() }
    }

    private fun readMono16BitWav(resource: String): Pair<FloatArray, Int> {
        val bytes = javaClass.getResourceAsStream(resource)!!.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(): String = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        require(tag() == "RIFF") { "$resource is not a WAV file" }
        buf.int // file size
        require(tag() == "WAVE") { "$resource is not a WAV file" }
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var data: FloatArray? = null
        while (buf.remaining() >= 8) {
            val id = tag()
            val size = buf.int
            val next = buf.position() + size
            when (id) {
                "fmt " -> {
                    val format = buf.short.toInt()
                    channels = buf.short.toInt()
                    sampleRate = buf.int
                    buf.int // byte rate
                    buf.short // block align
                    bits = buf.short.toInt()
                    require(format == 1) { "$resource is not PCM" }
                }
                "data" -> {
                    require(bits == 16 && channels == 1) { "$resource is not mono 16-bit" }
                    val count = size / 2
                    data = FloatArray(count) { buf.short / 32768.0f }
                }
            }
            buf.position(next)
        }
        return Pair(requireNotNull(data) { "$resource has no data chunk" }, sampleRate)
    }

    private fun writeMono16BitWav(file: File, samples: FloatArray, sampleRate: Int) {
        val out = ByteArrayOutputStream()
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun short(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        tag("RIFF")
        int(36 + samples.size * 2)
        tag("WAVE")
        tag("fmt ")
        int(16)
        short(1)
        short(1)
        int(sampleRate)
        int(sampleRate * 2)
        short(2)
        short(16)
        tag("data")
        int(samples.size * 2)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767.0f).toInt().coerceIn(-32768, 32767)
            short(v and 0xFFFF)
        }
        file.writeBytes(out.toByteArray())
    }

    private fun findBuildDir(): File {
        // Unit tests run with user.dir at the module (app/) or the repo root.
        val userDir = File(checkNotNull(System.getProperty("user.dir")) { "user.dir is not set" })
        if (File(userDir, "src/main/java").isDirectory) return File(userDir, "build")
        val app = File(userDir, "app")
        if (File(app, "src/main/java").isDirectory) return File(app, "build")
        return File(userDir, "build")
    }

    @Test
    fun prepareTextPrompt_honorsFrontEndFlags() {
        // v1 (defaults): capitalize + terminal period.
        assertEquals("Hello world.", PocketAudio.prepareTextPrompt("hello world"))
        // v2 (phonemes in): whitespace tidying only.
        assertEquals(
            "hello world",
            PocketAudio.prepareTextPrompt("hello world", capitalizeFirstLetter = false, appendTerminalPunctuation = false),
        )
        assertEquals(
            "a b",
            PocketAudio.prepareTextPrompt("  a\nb  ", capitalizeFirstLetter = false, appendTerminalPunctuation = false),
        )
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
