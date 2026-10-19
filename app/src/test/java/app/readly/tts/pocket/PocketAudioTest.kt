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
    fun classifyPieceEndReadsTheLastCharacter() {
        assertEquals(PocketAudio.PieceEnd.SENTENCE, PocketAudio.classifyPieceEnd("سلام دنیا."))
        assertEquals(PocketAudio.PieceEnd.SENTENCE, PocketAudio.classifyPieceEnd("really?"))
        assertEquals(PocketAudio.PieceEnd.SENTENCE, PocketAudio.classifyPieceEnd("خوبی؟"))
        assertEquals(PocketAudio.PieceEnd.SENTENCE, PocketAudio.classifyPieceEnd("wow!"))
        assertEquals(PocketAudio.PieceEnd.CLAUSE, PocketAudio.classifyPieceEnd("اول،"))
        assertEquals(PocketAudio.PieceEnd.CLAUSE, PocketAudio.classifyPieceEnd("دوم؛"))
        assertEquals(PocketAudio.PieceEnd.CLAUSE, PocketAudio.classifyPieceEnd("note:"))
        assertEquals(PocketAudio.PieceEnd.CLAUSE, PocketAudio.classifyPieceEnd("a,"))
        assertEquals(PocketAudio.PieceEnd.CLAUSE, PocketAudio.classifyPieceEnd("b;"))
        assertEquals(PocketAudio.PieceEnd.MID, PocketAudio.classifyPieceEnd("نیمه کاره"))
        assertEquals(PocketAudio.PieceEnd.MID, PocketAudio.classifyPieceEnd(""))
    }

    @Test
    fun trimSilenceKeepsAsymmetricEnds() {
        val sampleRate = 24000
        // 500 ms silence, 100 ms tone, 500 ms silence; constant tone, exact edges.
        val samples = FloatArray(24000) { i ->
            if (i in 12000 until 14400) 0.5f else 0f
        }
        val trimmed = PocketAudio.trimSilence(samples, sampleRate, keepStartMs = 25, keepEndMs = 60)
        assertEquals(600 + 2400 + 1440, trimmed.size)
        assertEquals(0.5f, trimmed[600], 0f)
        assertEquals(0.5f, trimmed[600 + 2399], 0f)
        assertEquals(0f, trimmed[599], 0f)
        assertEquals(0f, trimmed[600 + 2400], 0f)
        // All-silent input shortens to the two keeps.
        assertEquals(2040, PocketAudio.trimSilence(FloatArray(24000), sampleRate, 25, 60).size)
        // Defaults are the old 120/120 path.
        assertTrue(
            PocketAudio.trimSilence(samples, sampleRate)
                .contentEquals(PocketAudio.trimSilence(samples, sampleRate, 120, 120)),
        )
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

    @Test
    fun finishSpeechMidSentencePiecesLeaveAtMost60ms() {
        val sampleRate = 24000
        val emitted = mutableListOf<FloatArray>()
        var ticks = 0L
        val finished = PocketAudio.finishSpeech(
            sequenceOf(
                PocketAudio.RawPiece(squareBurst(sampleRate, toneMs = 80), PocketAudio.PieceEnd.MID),
                PocketAudio.RawPiece(squareBurst(sampleRate, toneMs = 160), PocketAudio.PieceEnd.SENTENCE),
            ),
            sampleRate,
            speed = 1.0f,
            isStopped = { false },
            onChunk = { emitted += it },
            clockMs = { ++ticks * 1000 },
        )
        val audio = finished.audio
        // (120 + 80 + 25) + (25 + 160 + 120) ms, no pause.
        assertEquals((225 + 305) * sampleRate / 1000, audio.size)
        val (bursts, gaps) = loudBursts(audio, sampleRate)
        assertEquals(1, gaps.size)
        assertTrue("mid-sentence gap is ${gaps[0]} samples", gaps[0] <= 60 * sampleRate / 1000)
        // Order: the 80 ms tone plays before the 160 ms one.
        assertEquals(2, bursts.size)
        assertTrue("bursts $bursts", bursts[0] < bursts[1])
        // Streaming hears exactly the returned audio (no pause emitted).
        assertEquals(2, emitted.size)
        assertTrue(audio.contentEquals(emitted[0] + emitted[1]))
        // Mastering was timed per piece: two pieces, 1000 ms each on the fake clock.
        assertEquals(2000L, finished.masterMs)
    }

    @Test
    fun finishSpeechClauseBoundaryPauses60ms() {
        val sampleRate = 24000
        val emitted = mutableListOf<FloatArray>()
        val finished = PocketAudio.finishSpeech(
            sequenceOf(
                PocketAudio.RawPiece(squareBurst(sampleRate, toneMs = 80), PocketAudio.PieceEnd.CLAUSE),
                PocketAudio.RawPiece(squareBurst(sampleRate, toneMs = 160), PocketAudio.PieceEnd.SENTENCE),
            ),
            sampleRate,
            speed = 1.0f,
            isStopped = { false },
            onChunk = { emitted += it },
        )
        val audio = finished.audio
        // (120 + 80 + 60) + 60 pause + (60 + 160 + 120) ms.
        assertEquals((260 + 60 + 340) * sampleRate / 1000, audio.size)
        val (bursts, gaps) = loudBursts(audio, sampleRate)
        assertEquals(1, gaps.size)
        assertTrue("clause gap is ${gaps[0]} samples", gaps[0] <= 200 * sampleRate / 1000)
        assertEquals(2, bursts.size)
        assertTrue("bursts $bursts", bursts[0] < bursts[1])
        assertEquals(3, emitted.size)
        assertTrue(audio.contentEquals(emitted[0] + emitted[1] + emitted[2]))
    }

    @Test
    fun finishSpeechSentenceEndMatchesLegacyAssembly() {
        val sampleRate = 24000
        val raw0 = squareBurst(sampleRate, toneMs = 80)
        val raw1 = squareBurst(sampleRate, toneMs = 160)
        val emitted = mutableListOf<FloatArray>()
        val finished = PocketAudio.finishSpeech(
            sequenceOf(
                PocketAudio.RawPiece(raw0, PocketAudio.PieceEnd.SENTENCE),
                PocketAudio.RawPiece(raw1, PocketAudio.PieceEnd.SENTENCE),
            ),
            sampleRate,
            speed = 1.0f,
            isStopped = { false },
            onChunk = { emitted += it },
        )
        // The pre-round-15 assembly: 120/120 trims, 150 ms pause, same order.
        fun legacy(raw: FloatArray): FloatArray {
            var piece = PocketAudio.trimSilence(raw, sampleRate)
            piece = PocketAudio.applySpeed(piece, 1.0f)
            return PocketAudio.masterChunk(piece, sampleRate)
        }
        val pause = FloatArray((150.0 / 1000.0 * sampleRate).toInt())
        val expected = legacy(raw0) + pause + legacy(raw1)
        assertTrue(finished.audio.contentEquals(expected))
        // Streaming hears the same bytes: piece, pause, piece.
        assertEquals(3, emitted.size)
        assertTrue(finished.audio.contentEquals(emitted[0] + emitted[1] + emitted[2]))
    }

    /**
     * Raw model-like output: a loud 440 Hz square tone between 300 ms silences.
     * Square edges are exactly loud, so trims land on exact samples; the tone
     * survives the mastering chain far above TRIM_THRESHOLD.
     */
    private fun squareBurst(sampleRate: Int, toneMs: Int): FloatArray {
        val lead = 300 * sampleRate / 1000
        val tone = toneMs * sampleRate / 1000
        val tail = 300 * sampleRate / 1000
        return FloatArray(lead + tone + tail) { i ->
            if (i in lead until lead + tone) {
                val half = (2.0 * 440.0 * (i - lead) / sampleRate).toInt()
                if (half % 2 == 0) 0.3f else -0.3f
            } else {
                0f
            }
        }
    }

    /**
     * Loud bursts (above TRIM_THRESHOLD) with the silence gaps between them, in
     * samples. Runs less than 10 ms apart merge, so ringing never splits a tone.
     */
    private fun loudBursts(audio: FloatArray, sampleRate: Int): Pair<List<Int>, List<Int>> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in audio.indices) {
            if (abs(audio[i]) > PocketAudio.TRIM_THRESHOLD) {
                if (start < 0) start = i
            } else if (start >= 0) {
                runs += start..i - 1
                start = -1
            }
        }
        if (start >= 0) runs += start..audio.size - 1
        assertTrue("expected loud bursts, found silence", runs.isNotEmpty())
        val mergeBelow = sampleRate * 10 / 1000
        val bursts = mutableListOf(runs[0])
        val gaps = mutableListOf<Int>()
        for (run in runs.drop(1)) {
            val gap = run.first - bursts.last().last - 1
            if (gap < mergeBelow) {
                bursts[bursts.size - 1] = bursts.last().first..run.last
            } else {
                gaps += gap
                bursts += run
            }
        }
        return Pair(bursts.map { it.last - it.first + 1 }, gaps)
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
