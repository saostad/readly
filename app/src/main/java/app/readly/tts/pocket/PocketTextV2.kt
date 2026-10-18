package app.readly.tts.pocket

/**
 * v2 text front end: ports of `normalize_for_model`, the HARD_BREAK split,
 * `clean_sentence_for_g2p`, `translate_phonemes` and the ezafe-aware
 * `split_text`/`strip_ezafe` from the v2 reference
 * (`.agents/handoff/ref/v2/`: `normalize_fa_v2.py`, `synthesize.py`,
 * `pocket_helpers.py`).
 *
 * The v2 `normalize()` is identical to v1's, so normalization itself reuses
 * [NormalizeFa]; only the model wrapper and the phoneme helpers live here.
 * Pure Kotlin: no Android or ORT imports. Checked against the reference on
 * every golden row (`src/test/resources/pocket/v2-golden.jsonl`).
 */
object PocketTextV2 {
    /** Highest token budget per v2 chunk (reference `_synth_unit`). */
    const val MAX_TOKENS = 18

    /** Lowest token budget per v2 chunk (reference `_synth_unit`). */
    const val MIN_TOKENS = 8

    /** Homo-GE2PE marks the ezafe with a trailing "1" (reference `synthesize`). */
    const val EZAFE_MARK = '1'

    private val pythonBlank = Regex("${NormalizeFa.UNICODE_WS}*")
    private val hardBreak = Regex("(?<=[.!؟])${NormalizeFa.UNICODE_WS}+")
    private val softBreak = Regex("(?<=[،؛:])${NormalizeFa.UNICODE_WS}+")
    private val wordsSplit = Regex("${NormalizeFa.UNICODE_WS}+")
    private val stripLeading = Regex("^${NormalizeFa.UNICODE_WS}+")
    private val stripTrailing = Regex("${NormalizeFa.UNICODE_WS}+$")
    private val breakChars = setOf('.', '!', '؟', '،', '؛', ':')

    /**
     * True when [text] is already phonemes rather than Persian script: no
     * Persian letter, but some ASCII letter (reference `is_phonemic`).
     */
    fun isPhonemic(text: String): Boolean =
        text.none { it in NormalizeFa.PERSIAN_LETTERS } &&
            text.any { it in 'A'..'Z' || it in 'a'..'z' }

    /**
     * Normalise Persian input, or pass phonemes through untouched (reference
     * `normalize_for_model`). Throws when Persian input normalizes to empty,
     * so a silent empty generation never hides a broken pipeline.
     */
    fun normalizeForModel(text: String): String {
        if (isPhonemic(text)) return text
        val out = NormalizeFa.normalize(text)
        if (!pythonBlank.matches(text) && out.isEmpty()) {
            throw IllegalArgumentException(
                "normalisation emptied the text: \"${text.take(60)}\". If this is phoneme " +
                    "input, it was not recognised as such; if it is Persian, it is outside " +
                    "the alphabet.",
            )
        }
        return out
    }

    /**
     * Split normalized text into sentences on HARD_BREAK, stripped, empties
     * dropped (reference `synth_one` v2 branch).
     */
    fun splitSentences(normalized: String): List<String> =
        hardBreak.split(normalized).map { strip(it) }.filter { it.isNotEmpty() }

    /** Strip question marks the G2P model was not trained on. */
    fun cleanSentenceForG2p(sentence: String): String =
        sentence.replace("؟", "").replace("?", "")

    /** Map Homo-GE2PE output symbols onto the pocket-tts v2 phone set. */
    fun translatePhonemes(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    '/' -> 'a'
                    'a' -> 'A'
                    '@' -> '?'
                    '$' -> 'S'
                    'c' -> 'C'
                    else -> c
                },
            )
        }
    }

    /** The text as the model must see it: no ezafe marks. */
    fun stripEzafe(text: String): String = text.replace(EZAFE_MARK.toString(), "")

    /**
     * Split phoneme [text] (ezafe marks kept) into chunks of at most
     * [maxTokens] tokens: sentence boundaries first, then clause punctuation,
     * then evenly packed words — a port of `split_text` in `synthesize.py`
     * with `keep_punct_boundaries=false` (the `_synth_unit` call). [countTokens]
     * sees the same text the model will (callers strip the ezafe marks);
     * a chunk boundary never lands between a word and the complement its
     * ezafe binds it to, and no chunk stays under [minTokens] while words
     * can move back from its neighbour.
     */
    fun splitChunks(
        text: String,
        countTokens: (String) -> Int,
        maxTokens: Int = MAX_TOKENS,
        minTokens: Int = MIN_TOKENS,
    ): List<String> {
        val clean = splitWords(text).joinToString(" ")
        if (clean.isEmpty()) return emptyList()
        val parts = splitRecurse(clean, countTokens, maxTokens)
        val merged = mutableListOf<String>()
        for (chunk in parts) {
            val last = merged.lastOrNull()
            if (last != null && countTokens("$last $chunk") <= maxTokens) {
                merged[merged.size - 1] = "$last $chunk"
            } else {
                merged += chunk
            }
        }
        for (i in merged.size - 1 downTo 1) {
            if (countTokens(merged[i]) >= minTokens) continue
            if (endsWithBreak(merged[i - 1])) continue
            while (countTokens(merged[i]) < minTokens) {
                val words = splitWords(merged[i - 1])
                if (words.size < 2 || countTokens(words.dropLast(1).joinToString(" ")) < minTokens) break
                merged[i - 1] = words.dropLast(1).joinToString(" ")
                merged[i] = "${words.last()} ${merged[i]}"
            }
        }
        return merged
    }

    private fun splitRecurse(
        piece: String,
        countTokens: (String) -> Int,
        maxTokens: Int,
    ): List<String> {
        fun fits(s: String): Boolean = countTokens(s) <= maxTokens
        if (fits(piece)) return listOf(piece)
        for (pattern in listOf(hardBreak, softBreak)) {
            val parts = pattern.split(piece).map { strip(it) }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                return parts.flatMap { splitRecurse(it, countTokens, maxTokens) }
            }
        }
        val words = splitWords(piece)
        val total = countTokens(piece)
        var chunks = maxOf(1, (total + maxTokens - 1) / maxTokens)
        var packed = emptyList<String>()
        repeat(4) {
            packed = pack(words, countTokens, ::fits, chunks, (total + chunks - 1) / chunks)
            if (packed.size <= chunks) return packed
            chunks = packed.size
        }
        return packed
    }

    private fun pack(
        words: List<String>,
        countTokens: (String) -> Int,
        fits: (String) -> Boolean,
        nChunks: Int,
        target: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val trial = if (current.isEmpty()) word else "$current $word"
            val owed = out.size < nChunks - 1
            val bound = current.isNotEmpty() && splitWords(current).last().endsWith(EZAFE_MARK)
            if (current.isNotEmpty() && !bound &&
                (!fits(trial) || (owed && countTokens(trial) > target))
            ) {
                out += current
                current = word
            } else {
                current = trial
            }
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun endsWithBreak(chunk: String): Boolean {
        val stripped = stripTrailing.replace(chunk, "")
        return stripped.isNotEmpty() && stripped.last() in breakChars
    }

    private fun splitWords(text: String): List<String> =
        wordsSplit.split(text).filter { it.isNotEmpty() }

    private fun strip(text: String): String =
        stripTrailing.replace(stripLeading.replace(text, ""), "")
}
