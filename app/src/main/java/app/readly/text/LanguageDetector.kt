package app.readly.text

/**
 * Detects the dominant language of text for voice selection and chunking.
 *
 * Pure Kotlin. Only three answers: `"fa"` (Persian), `"ar"` (Arabic) or `"en"`
 * (everything else, including empty text).
 */
object LanguageDetector {
    /** Letters that appear in Persian but not in Arabic. */
    private val PERSIAN_ONLY = setOf(0x067E, 0x0686, 0x0698, 0x06AF, 0x06A9, 0x06CC)

    /**
     * Returns `"fa"` when Arabic-script letters are more than half of all letters
     * and a Persian-only letter appears, `"ar"` when they are more than half
     * without one, else `"en"`.
     */
    fun dominant(text: String): String {
        var arabic = 0
        var latin = 0
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
                cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code ||
                    cp in 0x00C0..0x00D6 || cp in 0x00D8..0x00F6 ||
                    cp in 0x00F8..0x024F -> latin++
            }
            i += Character.charCount(cp)
        }
        if (arabic * 2 > arabic + latin) return if (persian) "fa" else "ar"
        return "en"
    }
}
