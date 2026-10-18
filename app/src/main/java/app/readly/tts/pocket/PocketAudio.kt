package app.readly.tts.pocket

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Text and audio helpers for Pocket TTS Farsi v1, ported from the tested Rust engine for
 * the same model (`.agents/handoff/ref/pocket.rs`). Pure Kotlin: no Android or ORT imports.
 */
object PocketAudio {
    /** Silence kept at each end when trimming a chunk. */
    const val TRIM_KEEP_MS = 120L

    /** Samples at or below this magnitude count as silence. */
    const val TRIM_THRESHOLD = 0.01f

    /** Mastered chunk target: about −12 LUFS after compression and limiting. */
    const val TARGET_LUFS = -12.0

    /** Pre-compressor level: each chunk is gained here first (gain clamped below). */
    const val PRE_TARGET_LUFS = -20.0

    /** Pre-gain clamp in dB, so near-silent chunks can't explode. */
    const val PRE_GAIN_MIN_DB = -12.0
    const val PRE_GAIN_MAX_DB = 24.0

    /** Compressor: threshold, ratio, RMS detector ballistics (ffmpeg-approved chain). */
    const val COMP_THRESHOLD_DB = -24.0
    const val COMP_RATIO = 4.0
    const val COMP_ATTACK_MS = 5.0
    const val COMP_RELEASE_MS = 80.0

    /** Look-ahead limiter ceiling and ballistics; output never exceeds the ceiling. */
    const val PEAK_CEILING = 0.95f
    const val LIMITER_LOOKAHEAD_MS = 5.0
    const val LIMITER_RELEASE_MS = 50.0

    /** Second-order Butterworth high-pass in front of the chain. */
    const val HIGHPASS_FREQ_HZ = 70.0

    /** A generation longer than this share of its length cap never said EOS. */
    const val RUNAWAY_RATIO = 0.97

    private val HARD_BREAKS = setOf('.'.code, '!'.code, '؟'.code)
    private val SOFT_BREAKS = setOf('،'.code, '؛'.code, ':'.code)

    /**
     * Prepare one chunk the way the reference runtime does before tokenizing: flatten line
     * breaks, uppercase the first character, and close a trailing word character with a
     * period. (The Farsi config disables space padding and semicolon removal, and Persian
     * has no case to change.)
     */
    fun prepareTextPrompt(chunk: String): String {
        var text = trimWhitespace(chunk).replace('\n', ' ').replace('\r', ' ')
        while ("  " in text) {
            text = text.replace("  ", " ")
        }
        if (text.isEmpty()) {
            return text
        }
        val first = text.codePointAt(0)
        text = String(Character.toChars(first)).uppercase(Locale.ROOT) +
            text.substring(Character.charCount(first))
        if (Character.isLetterOrDigit(text.codePointBefore(text.length))) {
            text += "."
        }
        return text
    }

    /**
     * Split normalized text into chunks of at most [maxTokens], breaking at sentence
     * punctuation first, then clause punctuation, then words — a port of `split_text` in
     * the model's `farsi_tts.py` (with the default `keep_punct_boundaries=false`, so
     * neighbours merge back while they fit).
     */
    fun splitText(text: String, countTokens: (String) -> Int, maxTokens: Int): List<String> {
        val clean = splitWhitespace(text).joinToString(" ")
        if (clean.isEmpty()) {
            return emptyList()
        }
        val parts = splitRecurse(clean, countTokens, maxTokens)
        val merged = mutableListOf<String>()
        for (chunk in parts) {
            val last = merged.lastOrNull()
            if (last != null) {
                val trial = "$last $chunk"
                if (countTokens(trial) <= maxTokens) {
                    merged[merged.size - 1] = trial
                    continue
                }
            }
            merged += chunk
        }
        return merged
    }

    private fun splitRecurse(
        piece: String,
        countTokens: (String) -> Int,
        maxTokens: Int,
    ): List<String> {
        if (countTokens(piece) <= maxTokens) {
            return listOf(piece)
        }
        for (breaks in listOf(HARD_BREAKS, SOFT_BREAKS)) {
            val parts = splitAfter(piece, breaks)
            if (parts.isNotEmpty()) {
                return parts.flatMap { splitRecurse(it, countTokens, maxTokens) }
            }
        }
        val out = mutableListOf<String>()
        var current = ""
        for (word in splitWhitespace(piece)) {
            val trial = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && countTokens(trial) > maxTokens) {
                out += current
                current = word
            } else {
                current = trial
            }
        }
        if (current.isNotEmpty()) {
            out += current
        }
        return out
    }

    private fun splitAfter(text: String, breaks: Set<Int>): List<String> {
        val chars = text.codePoints().toArray()
        val parts = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < chars.size) {
            if (chars[i] in breaks && i + 1 < chars.size && isWhitespaceCp(chars[i + 1])) {
                val piece = trimWhitespace(String(chars, start, i + 1 - start))
                if (piece.isNotEmpty()) {
                    parts += piece
                }
                i += 1
                while (i < chars.size && isWhitespaceCp(chars[i])) {
                    i += 1
                }
                start = i
            } else {
                i += 1
            }
        }
        if (start < chars.size) {
            val piece = trimWhitespace(String(chars, start, chars.size - start))
            if (piece.isNotEmpty()) {
                parts += piece
            }
        }
        return if (parts.size > 1) parts else emptyList()
    }

    /**
     * Trim leading/trailing silence (samples at or below the threshold) while keeping
     * [TRIM_KEEP_MS] of it at each end. An all-silent input stays as-is shortened to the
     * kept ends, so a silent chunk still has its shape.
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        val keep = (TRIM_KEEP_MS / 1000.0 * sampleRate).roundToInt()
        val first = samples.indexOfFirst { abs(it) > TRIM_THRESHOLD }
        val last = samples.indexOfLast { abs(it) > TRIM_THRESHOLD }
        if (first < 0 || last < 0) {
            return samples.copyOfRange(0, minOf(keep * 2, samples.size))
        }
        val start = maxOf(0, first - keep)
        val end = minOf(last + 1 + keep, samples.size)
        return samples.copyOfRange(start, end)
    }

    /** Resample by linear interpolation so playback lasts `1/speed` as long. */
    fun applySpeed(samples: FloatArray, speed: Float): FloatArray {
        if (!speed.isFinite() || abs(speed - 1f) < Math.ulp(1f) || samples.size < 2) {
            return samples.copyOf()
        }
        val outLen = maxOf(1, (samples.size / speed).roundToInt())
        return FloatArray(outLen) { i ->
            val pos = i * speed
            val index = maxOf(0, floor(pos).toInt())
            val frac = pos - index
            val a = samples[index.coerceAtMost(samples.size - 1)]
            val b = samples[(index + 1).coerceAtMost(samples.size - 1)]
            a + (b - a) * frac
        }
    }

    /**
     * ITU-R BS.1770-4 integrated loudness in LUFS: K-weighting (high-shelf pre-filter +
     * RLB high-pass, coefficients computed for [sampleRate] with the libebur128/pyloudnorm
     * formulas), 400 ms blocks with 75 % overlap, −70 LUFS absolute gate and −10 LU
     * relative gate. Shorter than one 400 ms block: one ungated block over the whole
     * input. All-silent input returns [Double.NEGATIVE_INFINITY].
     */
    fun measureLufs(samples: FloatArray, sampleRate: Int): Double {
        if (samples.isEmpty() || sampleRate <= 0) {
            return Double.NEGATIVE_INFINITY
        }
        val input = DoubleArray(samples.size) { samples[it].toDouble() }
        var weighted = applyBiquad(input, kShelfCoeffs(sampleRate))
        weighted = applyBiquad(weighted, kHighPassCoeffs(sampleRate))
        val blockSize = sampleRate * 400 / 1000
        val hopSize = sampleRate * 100 / 1000
        if (blockSize <= 0 || hopSize <= 0) {
            return Double.NEGATIVE_INFINITY
        }
        if (weighted.size < blockSize) {
            val meanSquare = meanSquare(weighted, 0, weighted.size)
            if (meanSquare <= 0.0) return Double.NEGATIVE_INFINITY
            return -0.691 + 10.0 * log10(meanSquare)
        }
        val blocks = ArrayList<Double>()
        var start = 0
        while (start + blockSize <= weighted.size) {
            blocks += meanSquare(weighted, start, start + blockSize)
            start += hopSize
        }
        if (blocks.isEmpty()) return Double.NEGATIVE_INFINITY
        val absGate = 10.0.pow((-70.0 + 0.691) / 10.0)
        val gated = blocks.filter { it > absGate }
        if (gated.isEmpty()) return Double.NEGATIVE_INFINITY
        val relativeLoudness = -0.691 + 10.0 * log10(gated.average())
        val relGate = 10.0.pow((relativeLoudness - 10.0 + 0.691) / 10.0)
        val kept = gated.filter { it > relGate }
        if (kept.isEmpty()) return Double.NEGATIVE_INFINITY
        return -0.691 + 10.0 * log10(kept.average())
    }

    /**
     * Master one chunk to about [TARGET_LUFS]: 70 Hz high-pass, pre-gain to
     * [PRE_TARGET_LUFS] (clamped), 4:1 compression, make-up gain to [TARGET_LUFS], and a
     * look-ahead peak limiter at [PEAK_CEILING]. Output length equals input length.
     * All-zero input passes through unchanged.
     */
    fun masterChunk(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) {
            return FloatArray(0)
        }
        var allZero = true
        for (s in samples) {
            if (s != 0f) {
                allZero = false
                break
            }
        }
        if (allZero) {
            return samples.copyOf()
        }
        var out = applyHighPass(samples, sampleRate)
        val preLoudness = measureLufs(out, sampleRate)
        if (preLoudness.isInfinite() && preLoudness < 0.0) {
            return samples.copyOf()
        }
        out = applyGainDb(out, (PRE_TARGET_LUFS - preLoudness).coerceIn(PRE_GAIN_MIN_DB, PRE_GAIN_MAX_DB))
        out = applyCompressor(out, sampleRate)
        val postLoudness = measureLufs(out, sampleRate)
        if (postLoudness.isInfinite() && postLoudness < 0.0) {
            return samples.copyOf()
        }
        out = applyGainDb(out, TARGET_LUFS - postLoudness)
        return applyLimiter(out, sampleRate)
    }

    /**
     * Length cap for one chunk's audio, in seconds: the token count at [tokensPerSecond]
     * plus [paddingSeconds], rounded up to whole frames at [frameRate], times [ratio]. A
     * generation longer than this share of its cap never said EOS (see `generate_chunk`
     * in the reference runtime).
     */
    fun runawayLimitSeconds(
        tokenCount: Int,
        tokensPerSecond: Double = 3.0,
        paddingSeconds: Double = 2.0,
        frameRate: Double = 12.5,
        ratio: Double = RUNAWAY_RATIO,
    ): Double = ceil((tokenCount / tokensPerSecond + paddingSeconds) * frameRate) / frameRate * ratio

    /** Second-order direct-form biquad: y = b0·x + b1·x1 + b2·x2 − a1·y1 − a2·y2. */
    private data class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    /** K-weighting high-shelf pre-filter (libebur128/pyloudnorm formula for [sampleRate]). */
    private fun kShelfCoeffs(sampleRate: Int): Biquad {
        val f0 = 1681.974450955533
        val gain = 3.737636852614458
        val q = 0.7071752369554196
        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(gain / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            (vh + vb * k / q + k * k) / a0,
            2.0 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / q + k * k) / a0,
        )
    }

    /** K-weighting RLB high-pass (libebur128/pyloudnorm formula for [sampleRate]). */
    private fun kHighPassCoeffs(sampleRate: Int): Biquad =
        highPassCoeffs(38.13547087602444, 0.5003270373238773, sampleRate)

    /** Second-order high-pass with cutoff [f0] Hz and Q [q] at [sampleRate]. */
    private fun highPassCoeffs(f0: Double, q: Double, sampleRate: Int): Biquad {
        val k = tan(PI * f0 / sampleRate)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            1.0 / a0,
            -2.0 / a0,
            1.0 / a0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / q + k * k) / a0,
        )
    }

    private fun applyBiquad(input: DoubleArray, c: Biquad): DoubleArray {
        val out = DoubleArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (n in input.indices) {
            val x0 = input[n]
            val y0 = c.b0 * x0 + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            out[n] = y0
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return out
    }

    private fun applyHighPass(samples: FloatArray, sampleRate: Int): FloatArray {
        // Second-order Butterworth: Q = 1/sqrt(2).
        val c = highPassCoeffs(HIGHPASS_FREQ_HZ, 0.7071067811865475, sampleRate)
        val out = FloatArray(samples.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (n in samples.indices) {
            val x0 = samples[n].toDouble()
            val y0 = c.b0 * x0 + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            out[n] = y0.toFloat()
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return out
    }

    private fun applyGainDb(samples: FloatArray, gainDb: Double): FloatArray {
        if (gainDb == 0.0) return samples.copyOf()
        val gain = 10.0.pow(gainDb / 20.0).toFloat()
        return FloatArray(samples.size) { samples[it] * gain }
    }

    /**
     * Downward compressor with an RMS detector: the squared signal is smoothed with the
     * attack/release ballistics, then the static 4:1 curve above the threshold is applied.
     */
    private fun applyCompressor(samples: FloatArray, sampleRate: Int): FloatArray {
        val attack = 1.0 - exp(-1.0 / (COMP_ATTACK_MS / 1000.0 * sampleRate))
        val release = 1.0 - exp(-1.0 / (COMP_RELEASE_MS / 1000.0 * sampleRate))
        val out = FloatArray(samples.size)
        var envelope = 0.0
        for (n in samples.indices) {
            val x = samples[n].toDouble()
            val squared = x * x
            envelope += (if (squared > envelope) attack else release) * (squared - envelope)
            val detector = sqrt(envelope)
            if (detector < 1e-10) {
                out[n] = samples[n]
                continue
            }
            val detectorDb = 20.0 * log10(detector)
            if (detectorDb > COMP_THRESHOLD_DB) {
                val gainDb =
                    (COMP_THRESHOLD_DB + (detectorDb - COMP_THRESHOLD_DB) / COMP_RATIO) - detectorDb
                out[n] = (x * 10.0.pow(gainDb / 20.0)).toFloat()
            } else {
                out[n] = samples[n]
            }
        }
        return out
    }

    /**
     * Look-ahead peak limiter: each sample's gain sees the peak [LIMITER_LOOKAHEAD_MS]
     * ahead, attacks instantly and releases over [LIMITER_RELEASE_MS]. Offline, so the
     * look-ahead delay is compensated (no extra samples); the final clamp guarantees the
     * ceiling against float error.
     */
    private fun applyLimiter(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val lookAhead = maxOf(1, (LIMITER_LOOKAHEAD_MS / 1000.0 * sampleRate).roundToInt())
        val release = 1.0 - exp(-1.0 / (LIMITER_RELEASE_MS / 1000.0 * sampleRate))
        val targets = FloatArray(samples.size)
        for (n in samples.indices) {
            var peak = 0f
            val end = minOf(samples.size, n + lookAhead + 1)
            for (k in n until end) {
                peak = maxOf(peak, abs(samples[k]))
            }
            targets[n] = if (peak < 1e-10f) 1f else minOf(1f, PEAK_CEILING / peak)
        }
        val out = FloatArray(samples.size)
        var smooth = 1.0
        for (n in samples.indices) {
            val target = targets[n].toDouble()
            smooth = if (target < smooth) target else smooth + (target - smooth) * release
            var y = (samples[n] * smooth).toFloat()
            if (y > PEAK_CEILING) y = PEAK_CEILING else if (y < -PEAK_CEILING) y = -PEAK_CEILING
            out[n] = y
        }
        return out
    }

    private fun meanSquare(samples: DoubleArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) {
            sum += samples[i] * samples[i]
        }
        return sum / (to - from)
    }

    /** Rust `char::is_whitespace` (Unicode White_Space) for a code point. */
    private fun isWhitespaceCp(cp: Int): Boolean = when (cp) {
        0x09, 0x0A, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
        -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    private fun splitWhitespace(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = -1
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isWhitespaceCp(cp)) {
                if (start >= 0) {
                    out += text.substring(start, i)
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
            i += Character.charCount(cp)
        }
        if (start >= 0) {
            out += text.substring(start)
        }
        return out
    }

    private fun trimWhitespace(text: String): String {
        var start = 0
        while (start < text.length) {
            val cp = text.codePointAt(start)
            if (!isWhitespaceCp(cp)) break
            start += Character.charCount(cp)
        }
        var end = text.length
        while (end > start) {
            val cp = text.codePointBefore(end)
            if (!isWhitespaceCp(cp)) break
            end -= Character.charCount(cp)
        }
        return text.substring(start, end)
    }
}
