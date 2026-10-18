package app.readly.tts.pocket

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Text and audio helpers for Pocket TTS Farsi v1, ported from the tested Rust engine for
 * the same model (`.agents/handoff/ref/pocket.rs`). Pure Kotlin: no Android or ORT imports.
 */
object PocketAudio {
    /** Silence kept at each end when trimming a chunk. */
    const val TRIM_KEEP_MS = 120L

    /** Samples at or below this magnitude count as silence. */
    const val TRIM_THRESHOLD = 0.01f

    /** Loudness target and limiter ceiling (−1 dBFS). */
    const val TARGET_LUFS = -16.0
    const val PEAK_CEILING = 0.8912515f

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
     * Lift quiet Pocket output (about −25 LUFS) to about −16 LUFS with a peak limiter.
     * Loudness is estimated from RMS with the usual −0.69 dB K-weighting offset for
     * speech-band audio; the limiter is a whole-file gain reduction to the ceiling, which
     * cannot pump. Returns the samples unchanged when they are silent.
     */
    fun normalizeLoudness(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) {
            return FloatArray(0)
        }
        var sumSquares = 0.0
        for (s in samples) {
            sumSquares += s.toDouble() * s.toDouble()
        }
        val meanSquare = sumSquares / samples.size
        if (meanSquare <= 0.0) {
            return samples.copyOf()
        }
        val estimatedLufs = 10.0 * log10(meanSquare) - 0.69
        val gainDb = (TARGET_LUFS - estimatedLufs).coerceIn(-12.0, 24.0)
        val gain = 10.0.pow(gainDb / 20.0).toFloat()
        val out = FloatArray(samples.size) { samples[it] * gain }
        var peak = 0f
        for (s in out) {
            peak = maxOf(peak, abs(s))
        }
        if (peak > PEAK_CEILING) {
            val reduce = PEAK_CEILING / peak
            for (i in out.indices) {
                out[i] = out[i] * reduce
            }
        }
        return out
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
