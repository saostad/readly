package app.readly.text

/**
 * Latin words inside Persian text, converted to Persian spelling so the
 * Persian voice reads them instead of dropping them.
 *
 * Pure Kotlin: no I/O beyond the embedded word list, no model. The word
 * list is `latin_fa.tsv` next to the English lexicon in main resources;
 * words it does not cover are spelled with letter names or with
 * approximate spelling rules. Port of `src/latin_fa.rs` in smart-voices.
 */

/** Result of [LatinFa.persianize]. */
data class Persianized(
    val text: String,
    /** Latin words that fell through to the approximate rules (original spelling). */
    val approximated: List<String>,
    /** True when letters that are neither ASCII nor Arabic-script remain. */
    val skipped: Boolean,
)

object LatinFa {
    /** ZWNJ (U+200C), joining Persian letter names and word parts. */
    private const val ZWNJ = '\u200C'

    private const val WORD_LIST_RESOURCE = "/app/readly/text/latin_fa.tsv"

    private fun loadRows(): List<List<String>> {
        val stream = LatinFa::class.java.getResourceAsStream(WORD_LIST_RESOURCE)
            ?: throw IllegalStateException("Missing Latin word list resource $WORD_LIST_RESOURCE")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split('\t') }
                .toList()
        }
    }

    private val wordListCache: Map<String, String> by lazy {
        val map = LinkedHashMap<String, String>()
        for (columns in loadRows()) {
            if (columns.size >= 2) map[columns[0]] = columns[1]
        }
        map
    }

    private val phonemeOverridesCache: Map<String, String> by lazy {
        val map = LinkedHashMap<String, String>()
        for (columns in loadRows()) {
            val phonemes = columns.getOrNull(2)
            if (columns.size >= 2 && !phonemes.isNullOrEmpty()) {
                map[columns[1]] = phonemes
            }
        }
        map
    }

    /** Written Latin word (lower-case) → its usual Persian spelling. */
    fun wordList(): Map<String, String> = wordListCache

    /**
     * Map from the Persian spelling (second TSV column) to its exact
     * phonemes (third column), in the v2 model's alphabet. A row whose
     * phonemes column is empty has no override (the pronunciation model
     * decides), so it is skipped here.
     */
    fun phonemeOverrides(): Map<String, String> = phonemeOverridesCache

    private fun letterName(c: Char): String? = when (c.uppercaseChar()) {
        'A' -> "ای"
        'B' -> "بی"
        'C' -> "سی"
        'D' -> "دی"
        'E' -> "ای"
        'F' -> "اف"
        'G' -> "جی"
        'H' -> "اچ"
        'I' -> "آی"
        'J' -> "جی"
        'K' -> "کی"
        'L' -> "ال"
        'M' -> "ام"
        'N' -> "ان"
        'O' -> "او"
        'P' -> "پی"
        'Q' -> "کیو"
        'R' -> "آر"
        'S' -> "اس"
        'T' -> "تی"
        'U' -> "یو"
        'V' -> "وی"
        'W' -> "دابلیو"
        'X' -> "ایکس"
        'Y' -> "وای"
        'Z' -> "زد"
        else -> null
    }

    /** True for a consonant letter: not one of the vowels `a e i o u y`. */
    private fun isConsonant(c: Char): Boolean =
        (c in 'a'..'z' || c in 'A'..'Z') && c !in "aeiouy"

    private fun isAsciiAlphabetic(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** True for chars in the Arabic-script blocks (Arabic, supplements, presentation forms). */
    internal fun isArabicScript(c: Char): Boolean = when (c) {
        in '\u0600'..'\u06FF', in '\u0750'..'\u077F', in '\u08A0'..'\u08FF',
        in '\uFB50'..'\uFDFF', in '\uFE70'..'\uFEFF' -> true
        else -> false
    }

    /**
     * Split a token into parts: a new part starts at a lower→upper change
     * and at the last capital of an upper-case run followed by a lower-case
     * letter (`ChatGPT` → `Chat`,`GPT`; `JSONParser` → `JSON`,`Parser`).
     */
    internal fun splitParts(token: String): List<String> {
        val chars = token.toList()
        val parts = mutableListOf<String>()
        var start = 0
        for (i in 1 until chars.size) {
            val prev = chars[i - 1]
            val cur = chars[i]
            val lowerToUpper = prev in 'a'..'z' && cur in 'A'..'Z'
            val runLastCapital = prev in 'A'..'Z' && cur in 'A'..'Z' &&
                i + 1 < chars.size && chars[i + 1] in 'a'..'z'
            if (lowerToUpper || runLastCapital) {
                parts += chars.subList(start, i).joinToString("")
                start = i
            }
        }
        parts += chars.subList(start, chars.size).joinToString("")
        return parts
    }

    /**
     * Letter rule: one letter, all upper-case with at most 5 letters, or no
     * `a e i o u y` in any case.
     */
    internal fun isLetterSpelled(part: String): Boolean {
        val chars = part.toList()
        return chars.size == 1 ||
            (chars.all { it in 'A'..'Z' } && chars.size <= 5) ||
            chars.none { it.lowercaseChar() in "aeiouy" }
    }

    private fun spellLetters(part: String): String =
        part.mapNotNull { letterName(it) }.joinToString(ZWNJ.toString())

    /** Convert one part. Returns the Persian text and whether the approximate rules were used. */
    private fun convertPart(part: String): Pair<String, Boolean> {
        wordList()[part.lowercase()]?.let { return it to false }
        if (isLetterSpelled(part)) return spellLetters(part) to false
        return spellByRules(part.lowercase()) to true
    }

    /** Convert one token, first match wins: the word list for the whole token, otherwise its parts joined with ZWNJ. */
    private fun convertToken(token: String, approximated: MutableList<String>): String {
        wordList()[token.lowercase()]?.let { return it }
        val parts = splitParts(token)
        var usedRules = false
        val converted = parts.map { part ->
            val (text, byRules) = convertPart(part)
            usedRules = usedRules || byRules
            text
        }
        if (usedRules && token !in approximated) approximated += token
        return converted.joinToString(ZWNJ.toString())
    }

    /** Convert Latin-letter words in [text] to Persian spelling. Everything else is copied as is. */
    fun persianize(text: String): Persianized {
        val result = StringBuilder(text.length)
        val approximated = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            if (isAsciiAlphabetic(text[index])) {
                val token = StringBuilder()
                while (index < text.length) {
                    val c = text[index]
                    if (isAsciiAlphabetic(c)) {
                        token.append(c)
                        index++
                    } else if ((c == '\'' || c == '\u2019') && token.isNotEmpty() &&
                        index + 1 < text.length && isAsciiAlphabetic(text[index + 1])
                    ) {
                        // An apostrophe between two letters belongs to the token and is dropped.
                        index++
                    } else {
                        break
                    }
                }
                result.append(convertToken(token.toString(), approximated))
                if (index < text.length && isArabicScript(text[index])) {
                    result.append(ZWNJ)
                }
            } else {
                result.append(text[index])
                index++
            }
        }
        val out = result.toString()
        val skipped = out.any { it.isLetter() && it.code >= 128 && !isArabicScript(it) }
        return Persianized(out, approximated, skipped)
    }

    private fun consonantText(word: List<Char>, at: Int): String {
        return when (word[at]) {
            'b' -> "ب"
            'd' -> "د"
            'f' -> "ف"
            'h' -> "ه"
            'j' -> "ج"
            'k' -> "ک"
            'l' -> "ل"
            'm' -> "م"
            'n' -> "ن"
            'p' -> "پ"
            'q' -> "ک"
            'r' -> "ر"
            's' -> "س"
            't' -> "ت"
            'v' -> "و"
            'w' -> "و"
            'x' -> "کس"
            'z' -> "ز"
            'c' -> if (word.getOrNull(at + 1) in setOf('e', 'i', 'y')) "س" else "ک"
            'g' -> if (word.getOrNull(at + 1) == 'e' && at + 2 == word.size) "ج" else "گ"
            'y' -> "ی"
            else -> ""
        }
    }

    private fun twoLetterText(pair: String): String? = when (pair) {
        "sh" -> "ش"
        "ch" -> "چ"
        "th" -> "ت"
        "ph" -> "ف"
        "ck" -> "ک"
        "qu" -> "کو"
        "wh" -> "و"
        "gh" -> "گ"
        "ee", "ea", "ie", "ey", "ai", "ay", "ei" -> "ی"
        "oo", "ou", "ow", "oa" -> "و"
        "oi", "oy" -> "وی"
        "au", "aw" -> "ا"
        else -> null
    }

    private fun isVowelPair(pair: String): Boolean = pair in setOf(
        "ee", "ea", "ie", "ey", "ai", "ay", "ei",
        "oo", "ou", "ow", "oa", "oi", "oy", "au", "aw",
    )

    /**
     * Approximate Persian spelling of one lower-cased part, left to right,
     * first match at each position. This is the rule function the tests
     * call directly, bypassing the word list.
     */
    internal fun spellByRules(word: String): String {
        val chars = word.toList()
        val n = chars.size
        val out = StringBuilder()
        var i = 0
        while (i < n) {
            val rest = chars.subList(i, n).joinToString("")
            // Start only: `s` followed by a consonant other than `h`.
            if (i == 0 && chars[i] == 's' && n > 1 && isConsonant(chars[1]) && chars[1] != 'h') {
                out.append("اس")
                i += 1
                continue
            }
            // Endings: the rest of the word is exactly this.
            if (rest == "tion") { out.append("شن"); break }
            if (rest == "sion") { out.append("ژن"); break }
            if (rest == "ture") { out.append("چر"); break }
            if (rest == "er") { out.append('ر'); break }
            if (rest == "or") { out.append("ور"); break }
            if (rest == "le" && i > 0 && isConsonant(chars[i - 1])) { out.append('ل'); break }
            if (rest == "e" && i > 0 && isConsonant(chars[i - 1]) && n > 2) break
            if (rest == "y" && i > 0) { out.append('ی'); break }
            // Two letters.
            if (i + 1 < n) {
                val pair = chars.subList(i, i + 2).joinToString("")
                val text = twoLetterText(pair)
                if (text != null) {
                    if (i == 0 && isVowelPair(pair) && !text.startsWith('ا')) out.append('ا')
                    out.append(text)
                    i += 2
                    continue
                }
            }
            // A doubled consonant is written once.
            if (i + 1 < n && chars[i] == chars[i + 1] && isConsonant(chars[i])) {
                out.append(consonantText(chars, i))
                i += 2
                continue
            }
            val c = chars[i]
            if (isConsonant(c) || (c == 'y' && i == 0)) {
                out.append(consonantText(chars, i))
                i += 1
                continue
            }
            // Vowels.
            when {
                i == 0 && c == 'a' -> out.append('ا')
                i == 0 && c == 'e' -> out.append('ا')
                i == 0 && c == 'i' -> out.append("ای")
                i == 0 && c == 'o' -> out.append('ا')
                i == 0 && c == 'u' -> out.append('ا')
                i + 1 == n && c == 'a' -> out.append('ا')
                i + 1 == n && c == 'i' -> out.append('ی')
                i + 1 == n && c == 'o' -> out.append('و')
                i + 1 == n && c == 'u' -> out.append('و')
                i + 1 == n && c == 'e' -> out.append('ی')
                c == 'a' -> {
                    if (chars.getOrNull(i + 1) == 'r') {
                        out.append('ا')
                    } else if (i + 2 < n && isConsonant(chars[i + 1]) && chars[i + 2] == 'e' && i + 3 == n) {
                        // Followed by one consonant then a word-final `e`.
                        out.append('ی')
                    } else if (i + 2 < n && isConsonant(chars[i + 1]) &&
                        chars[i + 2] in "aeiouy"
                    ) {
                        out.append('ا')
                    }
                }
                c == 'e' -> {}
                c == 'i' || c == 'y' -> out.append('ی')
                c == 'o' -> out.append('و')
                c == 'u' -> out.append('ا')
            }
            i += 1
        }
        return out.toString()
    }
}
