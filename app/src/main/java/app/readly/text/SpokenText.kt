package app.readly.text

/** Which user notice a degraded [SpokenResult] should show, when it is not the generic one. */
enum class SpokenNotice {
    /** English words inside Persian text were read with approximate Persian spelling. */
    APPROXIMATE_PERSIAN,

    /** Text the Persian voice cannot read (not Persian or English letters) was skipped. */
    SKIPPED_TEXT,
}

/** [SpokenText.forVoice] output: what the voice reads and whether that is degraded. */
data class SpokenResult(
    val text: String,
    /** True when the normalizer skipped a rule or failed, so part of the text is read as written. */
    val degraded: Boolean,
    /** The specific notice for this degradation, or null for the generic degraded notice. */
    val notice: SpokenNotice? = null,
)

/**
 * The text a voice reads for a chunk. English text goes through [EnglishNormalizer];
 * Persian text is expanded by [FaText] (times, dates, currency, symbols), then its
 * Latin words are converted to Persian spelling by [LatinFa] so the
 * Persian voice reads them; other languages have their own front ends (Piper's espeak)
 * and are read as they are. The chunk's display text never changes.
 */
object SpokenText {
    fun forVoice(chunkText: String, lang: String, options: NormalizeOptions): SpokenResult {
        if (lang == "fa") {
            return try {
                val result = LatinFa.persianize(FaText.expand(chunkText))
                if (result.approximated.isNotEmpty()) {
                    NormalizerLog.warn(
                        "fallback: true, attempted=english_word_list, " +
                            "reason=word_not_listed, taken=approximate_persian_spelling, " +
                            "count=${result.approximated.size}",
                    )
                }
                if (result.skipped) {
                    NormalizerLog.warn(
                        "fallback: true, attempted=persian_voice, " +
                            "reason=unsupported_script, taken=skipped_text",
                    )
                }
                val notice = when {
                    result.skipped -> SpokenNotice.SKIPPED_TEXT
                    result.approximated.isNotEmpty() -> SpokenNotice.APPROXIMATE_PERSIAN
                    else -> null
                }
                SpokenResult(
                    result.text,
                    degraded = result.approximated.isNotEmpty() || result.skipped,
                    notice = notice,
                )
            } catch (e: Exception) {
                NormalizerLog.error(
                    "fallback: true, attempted=latin_to_persian_spelling, " +
                        "reason=${e.javaClass.name}: ${e.message}, taken=original_text",
                    e,
                )
                SpokenResult(chunkText, degraded = true)
            }
        }
        if (lang != "en") return SpokenResult(chunkText, degraded = false)
        return try {
            val result = EnglishNormalizer.normalizeReport(chunkText, options)
            SpokenResult(result.text, degraded = result.skippedRules > 0)
        } catch (e: Exception) {
            NormalizerLog.error(
                "fallback: true, attempted=english_text_normalization, " +
                    "reason=${e.javaClass.name}: ${e.message}, taken=original_text",
                e,
            )
            SpokenResult(chunkText, degraded = true)
        }
    }
}
