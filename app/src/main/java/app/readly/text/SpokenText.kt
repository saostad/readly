package app.readly.text

/** [SpokenText.forVoice] output: what the voice reads and whether that is degraded. */
data class SpokenResult(
    val text: String,
    /** True when the normalizer skipped a rule or failed, so part of the text is read as written. */
    val degraded: Boolean,
)

/**
 * The text a voice reads for a chunk. English text goes through [EnglishNormalizer];
 * other languages have their own front ends (Pocket Persian, Piper's espeak) and are
 * read as they are. The chunk's display text never changes.
 */
object SpokenText {
    fun forVoice(chunkText: String, lang: String, options: NormalizeOptions): SpokenResult {
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
