package app.readly.text

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import java.util.Locale

/**
 * Detected language: 2-letter ISO 639-1 [lang], classifier [confidence]
 * (1.0 for the script heuristic) and [source] (`classifier` or `heuristic`).
 */
data class Detected(val lang: String, val confidence: Float, val source: String)

/**
 * Detects the dominant language of text for voice selection and chunking.
 *
 * [detect] uses the on-device `TextClassifier` on API 29+ and falls back to the
 * pure script heuristic [dominant] otherwise.
 */
object LanguageDetector {
    /** Letters that appear in Persian but not in Arabic. */
    private val PERSIAN_ONLY = setOf(0x067E, 0x0686, 0x0698, 0x06AF, 0x06A9, 0x06CC)

    /** Minimum classifier confidence to trust; below this the heuristic wins. */
    private const val MIN_CONFIDENCE = 0.6f

    /** Classifier input is capped so long articles don't stall detection. */
    private const val SAMPLE_CHARS = 2000

    /**
     * Detects the language of [text], normalized to 2-letter ISO 639-1.
     *
     * Must be called off the main thread (`TextClassifier` may block). Callers
     * on `readly-read` satisfy this.
     */
    fun detect(context: Context, text: String): Detected {
        if (Build.VERSION.SDK_INT >= 29 && text.isNotBlank()) {
            try {
                val manager = context.getSystemService(TextClassificationManager::class.java)
                val classifier = manager?.textClassifier
                if (classifier != null) {
                    val request = TextLanguage.Request.Builder(text.take(SAMPLE_CHARS)).build()
                    val result = classifier.detectLanguage(request)
                    if (result.localeHypothesisCount > 0) {
                        val top = result.getLocale(0)
                        val conf = result.getConfidenceScore(top)
                        if (conf >= MIN_CONFIDENCE) {
                            val lang = normalize(top.toLanguageTag(), top.language)
                            if (lang != null) {
                                Log.i("Readly", "Language detected: $lang (conf $conf, classifier)")
                                return Detected(lang, conf, "classifier")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("Readly", "TextClassifier language detection failed, using script heuristic", e)
            }
        }
        val lang = dominant(text)
        Log.i("Readly", "Language detected: $lang (conf 1.0, heuristic)")
        return Detected(lang, 1.0f, "heuristic")
    }

    /** Normalizes a BCP 47 tag to 2-letter ISO 639-1, or null when impossible. */
    private fun normalize(tag: String, fallback: String): String? {
        val lang = try {
            Locale.forLanguageTag(tag).language.lowercase(Locale.US)
        } catch (_: Exception) {
            ""
        }.ifEmpty { fallback.lowercase(Locale.US) }
        return if (lang.length == 2) lang else null
    }

    /**
     * Pure script heuristic: `"fa"` when Arabic-script letters dominate with a
     * Persian-only letter, `"ar"` when they dominate without one, `"ru"` when
     * Cyrillic letters dominate, else `"en"`.
     */
    fun dominant(text: String): String {
        var arabic = 0
        var latin = 0
        var cyrillic = 0
        var persian = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp in 0x0600..0x06FF || cp in 0x0750..0x077F ||
                    cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF -> {
                    arabic++
                    if (cp in PERSIAN_ONLY) persian = true
                }
                cp in 0x0400..0x04FF || cp in 0x0500..0x052F -> cyrillic++
                cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code ||
                    cp in 0x00C0..0x00D6 || cp in 0x00D8..0x00F6 ||
                    cp in 0x00F8..0x024F -> latin++
            }
            i += Character.charCount(cp)
        }
        val total = arabic + latin + cyrillic
        if (cyrillic * 2 > total) return "ru"
        if (arabic * 2 > total) return if (persian) "fa" else "ar"
        return "en"
    }
}
