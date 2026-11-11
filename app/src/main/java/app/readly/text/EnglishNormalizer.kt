package app.readly.text

import java.math.BigInteger
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/** How to read a date whose day and month could be swapped, like 09/10/2026. */
enum class DateOrder(val key: String) {
    MONTH_FIRST("month_first"),
    DAY_FIRST("day_first");

    companion object {
        /** The stored setting value, or [MONTH_FIRST] for an unknown one. */
        fun fromKey(key: String?): DateOrder = entries.firstOrNull { it.key == key } ?: MONTH_FIRST
    }
}

/** Which normalization passes run (the Pronunciation settings). */
data class NormalizeOptions(
    /** Rewrite numbers, dates, money, codes, units and symbols into words. */
    val normalizeText: Boolean = true,
    /** Respell hard names and foreign words from the built-in lexicon. Off by default: it can hurt names a voice already says well. */
    val respellNames: Boolean = false,
    /** How ambiguous slashed dates are read. */
    val dateOrder: DateOrder = DateOrder.MONTH_FIRST,
)

/** [EnglishNormalizer.normalizeReport] output: the spoken text plus how many rules had to be skipped. */
data class NormalizeResult(val text: String, val skippedRules: Int)

/**
 * English text front end: written text to the words a person would say.
 *
 * Numbers, dates, times, money, codes, units, symbols and abbreviations are rewritten
 * right before synthesis (chunks keep their display text), so every English voice reads
 * "Order #4471 ships 14/03" the way a person would.
 *
 * Kotlin port of smart-voices' `src/normalize.rs`, itself a port of
 * `underdog_woof/normalize.py` and `respell.py` from Underdog Woof
 * (<https://huggingface.co/ConwayResearch/Underdog-Woof>), Underdog Non-Commercial
 * License 1.0, Copyright 2026 Conway. The rules stay in the original order, because each
 * pass depends on what earlier passes left behind: `EnglishNormalizerGoldenTest` checks
 * this port against the same fixture smart-voices uses.
 *
 * Patterns are written in the Rust regex dialect and translated by [translate] so that
 * `\d \w \s \b` mean the same Unicode classes on the JVM, on Android's ICU engine and in
 * Rust (no inline unicode flag: ICU rejects it), `$` means end of text and `.` stops only
 * at `\n`.
 */
object EnglishNormalizer {

    /** The text an English voice should read for [text]. */
    fun normalize(text: String, options: NormalizeOptions): String = normalizeReport(text, options).text

    /**
     * Like [normalize], also counting rules skipped because the regex engine gave up
     * (backtracking budget or stack). A skipped rule leaves its part of the text as
     * written, so the caller shows that the reading is degraded.
     */
    fun normalizeReport(text: String, options: NormalizeOptions): NormalizeResult {
        val skipped = intArrayOf(0)
        val previous = skipCounter.get()
        skipCounter.set(skipped)
        try {
            return NormalizeResult(normalizeText(text, options), skipped[0])
        } finally {
            skipCounter.set(previous)
        }
    }

    /**
     * Compiles the patterns (and loads the lexicon when [options] respell names) off the
     * playback path, so the first chunk of the first reading doesn't pay for them.
     */
    fun warmUp(options: NormalizeOptions) {
        normalize("Order #4471 ships 14/03 at 9:30am for \$5.", options.copy(normalizeText = true))
    }

    private fun normalizeText(text: String, options: NormalizeOptions): String {
        if (!options.normalizeText && !options.respellNames) return text
        var t = text
        if (options.normalizeText) {
            val order = resolveDateOrder(t, options.dateOrder)
            t = normPre(t, order)
            if (search("""[0-9#%$€£@&/_+=<>±≥≤×°]""", t)) {
                t = fallback(t, order)
            }
            t = tpl("""\s+([.,!?;:])""", t, """\1""")
            t = tpl("""\s{2,}""", t, " ").trim()
        }
        if (options.respellNames) {
            t = respell(t)
        }
        return if (firstIsUpper(text)) capitalizeFirst(t) else t
    }

    // MARK: - Regex plumbing

    /** Rust `\w`: Alphabetic, marks, decimal digits, connector punctuation, join controls. */
    private const val WORD_BODY = """\p{IsAlphabetic}\p{M}\p{Nd}\p{Pc}\u200C\u200D"""
    private const val WORD = "[$WORD_BODY]"
    private const val NOT_WORD = "[^$WORD_BODY]"
    private const val SPACE_BODY = """\p{IsWhite_Space}"""
    private const val BOUNDARY = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"
    private const val NOT_BOUNDARY = "(?:(?<=$WORD)(?=$WORD)|(?<!$WORD)(?!$WORD))"

    /**
     * Character budget per rule run, the counterpart of fancy-regex's backtracking
     * limit in smart-voices: a pathological input skips the rule instead of stalling
     * playback. A safety bound, not a behavior setting.
     */
    private const val CHAR_BUDGET = 20_000_000L

    private val patterns = ConcurrentHashMap<String, Pattern>()
    private val skipCounter = ThreadLocal<IntArray?>()

    private fun re(rust: String): Pattern = patterns.getOrPut(rust) { Pattern.compile(translate(rust)) }

    /** Rewrites a Rust-dialect pattern into one that means the same on JVM and ICU. */
    internal fun translate(rust: String): String {
        val out = StringBuilder(rust.length * 2)
        var depth = 0
        var i = 0
        while (i < rust.length) {
            val c = rust[i]
            if (c == '\\' && i + 1 < rust.length) {
                val n = rust[i + 1]
                if (depth == 0) {
                    out.append(
                        when (n) {
                            'w' -> WORD
                            'W' -> NOT_WORD
                            'd' -> """\p{Nd}"""
                            'D' -> """\P{Nd}"""
                            's' -> "[$SPACE_BODY]"
                            'S' -> "[^$SPACE_BODY]"
                            'b' -> BOUNDARY
                            'B' -> NOT_BOUNDARY
                            else -> "\\" + n
                        },
                    )
                } else {
                    out.append(
                        when (n) {
                            'w' -> WORD_BODY
                            'd' -> """\p{Nd}"""
                            's' -> SPACE_BODY
                            'W', 'D', 'S', 'b', 'B' ->
                                throw IllegalArgumentException("negated class \\$n inside [] in: $rust")
                            else -> "\\" + n
                        },
                    )
                }
                i += 2
                continue
            }
            when {
                c == '[' -> {
                    depth++
                    out.append(c)
                    // A leading ] (or ^]) is literal in Rust; Java needs it escaped.
                    if (i + 1 < rust.length && rust[i + 1] == '^') {
                        out.append('^')
                        i++
                    }
                    if (i + 1 < rust.length && rust[i + 1] == ']') {
                        out.append("\\]")
                        i++
                    }
                }
                c == ']' && depth > 0 -> {
                    depth--
                    out.append(c)
                }
                depth == 0 && c == '.' -> out.append("[^\\n]")
                depth == 0 && c == '$' -> out.append("\\z")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Rust `regex::escape`. */
    private fun escape(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            if (c in """\.+*?()|[]{}^$#&-~""") out.append('\\')
            out.append(c)
        }
        return out.toString()
    }

    private class BudgetExceeded : RuntimeException(null, null, false, false)

    /** The text a rule runs on, failing once the rule has read [CHAR_BUDGET] characters. */
    private class Budgeted(private val s: String) : CharSequence {
        private var reads = 0L
        override val length: Int get() = s.length
        override fun get(index: Int): Char {
            if (++reads > CHAR_BUDGET) throw BudgetExceeded()
            return s[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = s.substring(startIndex, endIndex)
        override fun toString(): String = s
    }

    /** One regex match plus the string it was found in. */
    private class M(private val m: Matcher, private val text: String) {
        /** Group [index], or "" when it didn't take part. */
        fun g(index: Int): String = if (index > m.groupCount()) "" else m.group(index) ?: ""
        fun whole(): String = g(0)
        fun before(): String = text.substring(0, m.start())
        fun after(): String = text.substring(m.end())
    }

    /** A rule the regex engine gave up on is skipped: the voice reads that text as written. */
    private fun ruleSkipped(reason: String) {
        skipCounter.get()?.let { it[0]++ }
        NormalizerLog.warn(
            "fallback: true, attempted=text_normalization_rule, reason=$reason, taken=rule_skipped",
        )
    }

    /** `re.sub` with a function. */
    private fun sub(pattern: String, text: String, replace: (M) -> String): String {
        val matcher = re(pattern).matcher(Budgeted(text))
        val result = StringBuilder(text.length)
        var last = 0
        try {
            while (matcher.find()) {
                result.append(text, last, matcher.start())
                result.append(replace(M(matcher, text)))
                last = matcher.end()
            }
        } catch (_: BudgetExceeded) {
            ruleSkipped("character budget exceeded")
            return text
        } catch (_: StackOverflowError) {
            ruleSkipped("regex stack overflow")
            return text
        }
        result.append(text, last, text.length)
        return result.toString()
    }

    /** `re.sub` with a template: `\1`-style group references, the rest literal. */
    private fun tpl(pattern: String, text: String, template: String): String = sub(pattern, text) { m ->
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            val next = template.getOrNull(i + 1)
            if (c == '\\' && next != null && next in '0'..'9') {
                out.append(m.g(next - '0'))
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        out.toString()
    }

    /** `re.search`: true when [pattern] matches anywhere in [text]. */
    private fun search(pattern: String, text: String): Boolean = try {
        re(pattern).matcher(Budgeted(text)).find()
    } catch (_: BudgetExceeded) {
        ruleSkipped("character budget exceeded")
        false
    } catch (_: StackOverflowError) {
        ruleSkipped("regex stack overflow")
        false
    }

    /** Every match of [pattern] in [text], for loops that read groups. */
    private fun forEachMatch(pattern: String, text: String, block: (M) -> Unit) {
        val matcher = re(pattern).matcher(Budgeted(text))
        try {
            while (matcher.find()) block(M(matcher, text))
        } catch (_: BudgetExceeded) {
            ruleSkipped("character budget exceeded")
        } catch (_: StackOverflowError) {
            ruleSkipped("regex stack overflow")
        }
    }

    // MARK: - String helpers

    private fun count(s: String): Int = s.codePointCount(0, s.length)

    /** Python's `s[-n:]` in code points. */
    private fun lastChars(s: String, n: Int): String {
        if (n <= 0) return ""
        val total = count(s)
        return if (total <= n) s else s.substring(s.offsetByCodePoints(0, total - n))
    }

    /** Python's `s[:n]` in code points. */
    private fun firstChars(s: String, n: Int): String {
        val total = count(s)
        return if (total <= n) s else s.substring(0, s.offsetByCodePoints(0, n))
    }

    private fun codePoints(s: String): List<Int> = s.codePoints().toArray().toList()

    private fun cpString(cp: Int): String = String(Character.toChars(cp))

    private fun firstIsUpper(s: String): Boolean = s.isNotEmpty() && Character.isUpperCase(s.codePointAt(0))

    private fun firstIsLower(s: String): Boolean = s.isNotEmpty() && Character.isLowerCase(s.codePointAt(0))

    private fun upper(s: String): String = s.uppercase()

    private fun lower(s: String): String = s.lowercase()

    private fun capitalizeFirst(s: String): String {
        if (s.isEmpty()) return s
        val first = s.codePointAt(0)
        return upper(cpString(first)) + s.substring(Character.charCount(first))
    }

    /** Python's `str.capitalize`. */
    private fun capitalize(s: String): String {
        if (s.isEmpty()) return s
        val first = s.codePointAt(0)
        return upper(cpString(first)) + lower(s.substring(Character.charCount(first)))
    }

    private fun isNumeric(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private fun isDigits(s: String): Boolean = s.isNotEmpty() && codePoints(s).all(::isNumeric)

    private fun isAlpha(s: String): Boolean = s.isNotEmpty() && codePoints(s).all(Character::isAlphabetic)

    /** Python's `str.isupper`: has a cased char and every cased char is upper. */
    private fun isUpper(s: String): Boolean =
        codePoints(s).any(Character::isUpperCase) && codePoints(s).none(Character::isLowerCase)

    private fun isLower(s: String): Boolean =
        codePoints(s).any(Character::isLowerCase) && codePoints(s).none(Character::isUpperCase)

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    /** Rust `split_whitespace`. */
    private fun words(s: String): List<String> = s.split(re("""\s+""").toRegex()).filter { it.isNotEmpty() }

    private val U128_MAX: BigInteger = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
    private val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)

    /** Parse ASCII digits, saturating at u128 like the Rust, so no input can fail. */
    private fun int(s: String): BigInteger {
        var n = BigInteger.ZERO
        for (c in s) {
            if (!isAsciiDigit(c)) continue
            n = n.multiply(BigInteger.TEN).add(BigInteger.valueOf((c - '0').toLong()))
            if (n > U128_MAX) return U128_MAX
        }
        return n
    }

    /** [int] clamped to a Long, for comparisons with small numbers. */
    private fun sm(s: String): Long = int(s).min(LONG_MAX).toLong()

    private fun big(n: Long): BigInteger = BigInteger.valueOf(n)

    // MARK: - Numbers as words

    private val DIG = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen",
    )
    private val TENS = listOf("_", "_", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September",
        "October", "November", "December",
    )
    private val SCALES = listOf(
        BigInteger("1000000000000") to "trillion",
        BigInteger("1000000000") to "billion",
        BigInteger("1000000") to "million",
        BigInteger("1000") to "thousand",
    )

    private fun cardinal(n: BigInteger): String {
        if (n < big(20)) return ONES[n.toInt()]
        if (n < big(100)) {
            val tens = TENS[n.toInt() / 10]
            return if (n.toInt() % 10 == 0) tens else "$tens ${ONES[n.toInt() % 10]}"
        }
        if (n < big(1000)) {
            val hundreds = ONES[n.toInt() / 100]
            return if (n.toInt() % 100 == 0) {
                "$hundreds hundred"
            } else {
                "$hundreds hundred and ${cardinal(big(n.toLong() % 100))}"
            }
        }
        for ((value, name) in SCALES) {
            if (n >= value) {
                val rest = n.mod(value)
                val tail = when {
                    rest.signum() == 0 -> ""
                    rest < big(100) -> " and ${cardinal(rest)}"
                    else -> " ${cardinal(rest)}"
                }
                return "${cardinal(n.divide(value))} $name$tail"
            }
        }
        return n.toString()
    }

    private fun cardinal(n: Long): String = cardinal(big(n))

    private fun ordinal(n: BigInteger): String {
        val words = cardinal(n)
        val space = words.lastIndexOf(' ')
        val head = if (space >= 0) words.substring(0, space + 1) else ""
        val last = if (space >= 0) words.substring(space + 1) else words
        val tail = when (last) {
            "one" -> "first"
            "two" -> "second"
            "three" -> "third"
            "five" -> "fifth"
            "eight" -> "eighth"
            "nine" -> "ninth"
            "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return head + tail
    }

    private fun ordinal(n: Long): String = ordinal(big(n))

    private fun hundredPairs(n: BigInteger): String {
        val hi = n.divide(big(100))
        val lo = n.mod(big(100)).toInt()
        return cardinal(hi) + when {
            lo == 0 -> " hundred"
            lo < 10 -> " oh ${ONES[lo]}"
            else -> " ${cardinal(lo.toLong())}"
        }
    }

    private fun year(n: BigInteger): String =
        if (n >= big(2000) && n <= big(2009)) cardinal(n) else hundredPairs(n)

    private fun number(raw: String): String {
        val s = raw.replace(",", "")
        val dot = s.indexOf('.')
        if (dot < 0) return cardinal(int(s))
        val a = s.substring(0, dot)
        val b = s.substring(dot + 1)
        val whole = if (a.isEmpty()) "zero" else cardinal(int(a))
        val decimals = b.map { c -> ONES[if (isAsciiDigit(c)) c - '0' else 0] }
        return "$whole point ${decimals.joinToString(" ")}"
    }

    private fun digits(s: String): String = s.filter(::isAsciiDigit).map { DIG[it - '0'] }.joinToString(" ")

    private fun spell(s: String): String {
        val out = mutableListOf<String>()
        for (cp in codePoints(s)) {
            if (cp in '0'.code..'9'.code) {
                out += DIG[cp - '0'.code]
            } else if (Character.isAlphabetic(cp)) {
                out += upper(cpString(cp))
            }
        }
        return out.joinToString(" ")
    }

    /** All-caps chunk that reads as a word (SAVE, WINTER, PROMO) rather than letters (ORD, XT, CS). */
    private fun pronounceable(word: String): Boolean {
        val w = lower(word)
        if (count(w) < 4 || !search("[aeiouy]", w)) return false
        if (search("[^aeiouy]{3,}", w) && !search("(str|sch|thr|chr|spr|scr|ght|nds|nts)", w)) return false
        return search("^(?:[^aeiouy]{0,3}[aeiouy]{1,2})+[^aeiouy]{0,3}$", w)
    }

    private fun codeRead(s: String): String {
        val out = mutableListOf<String>()
        for (p in s.split('-', '/')) {
            if (p.isEmpty()) continue
            out += when {
                isDigits(p) -> if (count(p) <= 2 && !p.startsWith("0")) p else digits(p)
                isAlpha(p) && isLower(p) && count(p) > 3 && pronounceable(p) -> p
                isAlpha(p) && pronounceable(p) -> capitalize(p)
                else -> spell(p)
            }
        }
        return out.joinToString(" ")
    }

    /**
     * Digits of an identifier read the way people say room, flight and house numbers:
     * 402 four oh two, 229 two twenty nine, 1204 twelve oh four, 1600 sixteen hundred;
     * 5+ digits or a leading 0 go digit by digit.
     */
    private fun nread(s: String): String {
        val n = int(s)
        val len = count(s)
        if (s.startsWith("0") && len > 1 || len > 4) return digits(s)
        if (n < big(100) || n.mod(big(100)).signum() == 0 && len == 3 || n.mod(big(1000)).signum() == 0) {
            return cardinal(n)
        }
        return hundredPairs(n)
    }

    /** getUserName to get user name, user_id to user id, OpenAI to Open A I. */
    private fun splitIdent(s: String): String {
        val spaced = tpl("""(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""", s.replace('_', ' '), " ")
        return words(spaced).joinToString(" ") { w -> if (isUpper(w) && count(w) <= 4) spell(w) else w }
    }

    /** @openai to open A I, @Jane_Doe to Jane underscore Doe. */
    private fun handle(h: String): String = h.split('_')
        .map { p ->
            val m = re("^([a-z]{3,}[nlyex])(ai|hq)$").matcher(p)
            if (m.find()) "${m.group(1)} ${spell(m.group(2) ?: "")}" else splitIdent(p)
        }
        .filter { it.isNotEmpty() }
        .joinToString(" underscore ")

    // MARK: - Roman numerals

    private const val ROMAN_RE = """(?=[MDCLXVI]+\b)M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})"""

    /** Words after which a Roman numeral is a plain count (Chapter XIV, World War II). */
    private val ROMAN_CARD_WORDS = listOf(
        "chapter", "chap", "ch", "part", "pt", "book", "volume", "vol", "act", "scene", "section",
        "sec", "phase", "stage", "level", "episode", "season", "article", "title", "class", "type",
        "grade", "war", "bowl", "apollo", "mark", "mk", "vatican", "rocky", "fantasy", "wars",
        "trek", "civilization", "gemini", "saturn", "soyuz", "annex", "appendix", "figure", "fig",
        "table", "plate", "tier", "round", "schedule", "unit", "division", "category", "series",
        "olympiad", "psalm", "canto", "lesson", "module", "form",
    )
    private val ROMAN_LABEL = setOf(
        "appendix", "annex", "table", "figure", "fig", "plate", "schedule", "unit", "category",
        "class", "type", "grade", "tier", "section", "form", "division", "series",
    )
    private val ROMAN_STOP = setOf(
        "the", "a", "an", "in", "on", "of", "and", "for", "to", "at", "by", "from", "with", "my",
        "our", "his", "her", "their", "this", "that", "its",
    )

    /** Names that take a regnal ordinal (Henry VIII, Henry the eighth). */
    private val REGNAL = setOf(
        "Henry", "Louis", "Elizabeth", "George", "Edward", "Richard", "Charles", "James", "William",
        "John", "Paul", "Benedict", "Pius", "Leo", "Gregory", "Innocent", "Clement", "Boniface",
        "Urban", "Alexander", "Peter", "Ivan", "Nicholas", "Frederick", "Wilhelm", "Ludwig",
        "Philip", "Felipe", "Carlos", "Gustav", "Gustaf", "Christian", "Olaf", "Haakon", "Harald",
        "Ramesses", "Ramses", "Thutmose", "Amenhotep", "Ptolemy", "Darius", "Xerxes", "Cyrus",
        "Constantine", "Justinian", "Mehmed", "Suleiman", "Selim", "Murad", "Napoleon", "Umberto",
        "Mary", "Anne", "Victoria", "Catherine", "Isabella", "Margaret", "Rama", "Abdullah",
        "Robert", "David", "Kenneth", "Duncan", "Alfonso", "Ferdinand", "Sancho", "Manuel",
        "Sebastian", "Stephen", "Matthias", "Sigismund", "Casimir", "Otto", "Conrad", "Rudolf",
        "Maximilian", "Leopold", "Joseph", "Francis", "Franz", "Karl", "Hugh", "Baldwin", "Basil",
        "Michael", "Theodore", "Edmund", "Harold", "Magnus", "Eric", "Erik", "Valdemar", "Frederik",
        "Albert", "Baudouin", "Philippe", "Rainier", "Hassan", "Faisal", "Fuad", "Pedro", "Afonso",
        "Jaime", "Sixtus", "Julius", "Adrian", "Hadrian", "Celestine", "Honorius", "Lothair",
        "Pepin", "Antiochus", "Seleucus", "Herod", "Cleopatra", "Wenceslaus", "Kamehameha",
        "Tewodros", "Menelik", "Nebuchadnezzar", "Shalmaneser", "Sargon", "Tiglath", "Artaxerxes",
        "Andronikos", "Romanos", "Leon", "Mohammed", "Muhammad", "Charlemagne",
    )
    private val TITLES = setOf(
        "King", "Queen", "Pope", "Emperor", "Empress", "Tsar", "Czar", "Tsarina", "Kaiser", "Pharaoh",
        "Sultan", "Shah", "Prince", "Princess", "Duke", "Duchess", "Count", "Countess", "Lord", "Lady",
        "Saint", "St", "Archduke", "Elector", "Emir", "Caliph", "Khan", "King's", "Queen's",
    )
    private val ROMAN_RANGE_BEFORE: String = run {
        val all = ROMAN_CARD_WORDS + ROMAN_CARD_WORDS.map { it + "s" } + listOf("through", "thru")
        """(?i)\b(?:${all.joinToString("|")})\s+\S+\s+$"""
    }

    private fun roman(s: String): Long {
        fun value(c: Char): Long = when (c) {
            'I' -> 1
            'V' -> 5
            'X' -> 10
            'L' -> 50
            'C' -> 100
            'D' -> 500
            'M' -> 1000
            else -> 0
        }
        var total = 0L
        for (i in s.indices) {
            val v = value(s[i])
            if (i + 1 < s.length && value(s[i + 1]) > v) total -= v else total += v
        }
        return total.coerceAtLeast(0)
    }

    private fun isTitleWord(w: String): Boolean = search("^[A-Z][a-z]+$", w)

    private fun isRomanCard(key: String): Boolean =
        key == "through" || key == "thru" || key in ROMAN_CARD_WORDS ||
            (key.endsWith("s") && key.dropLast(1) in ROMAN_CARD_WORDS)

    private fun romanSub(m: M): String {
        val prev = m.g(1)
        val gap = m.g(2)
        val num = m.g(3)
        if (num.isEmpty()) return m.whole()
        val lastBefore = words(m.before()).lastOrNull()
        val prev2 = if (lastBefore != null && !search("""[.,;:!?)"]$""", lastBefore)) lastBefore else ""
        val key = lower(prev).trimEnd('.')
        val n = roman(num)
        if (key in setOf("through", "thru", "to", "and", "or") && !search(ROMAN_RANGE_BEFORE, m.before())) {
            return m.whole()
        }
        if (isRomanCard(key) &&
            (count(num) > 1 || num == "I" && firstIsUpper(prev) || (num == "V" || num == "X") && key !in ROMAN_LABEL)
        ) {
            return "$prev$gap${cardinal(n)}"
        }
        if (!search("^[IVXL]+$", num)) return m.whole()
        if (prev in REGNAL || prev2 in TITLES && isTitleWord(prev) && num != "I") {
            return "$prev${gap}the ${ordinal(n)}"
        }
        if (count(num) > 1 && isTitleWord(prev) && !search("^[IVXL]*L[IVXL]*$", num)) {
            // John Smith III is the third; Frozen II, Rocky IV are two, four.
            val full = isTitleWord(prev2) && lower(prev2) !in ROMAN_STOP
            return if (full) "$prev${gap}the ${ordinal(n)}" else "$prev$gap${cardinal(n)}"
        }
        return m.whole()
    }

    // MARK: - Word lists

    private val CURCODE = listOf(
        "USD" to "$", "US$" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "yen", "¥" to "yen",
        "CNY" to "yuan", "RMB" to "yuan", "INR" to "rupees", "₹" to "rupees", "Rs." to "rupees",
        "Rs" to "rupees", "CAD" to "Canadian dollars", "C$" to "Canadian dollars",
        "AUD" to "Australian dollars", "A$" to "Australian dollars", "NZD" to "New Zealand dollars",
        "CHF" to "Swiss francs", "HKD" to "Hong Kong dollars", "HK$" to "Hong Kong dollars",
        "SGD" to "Singapore dollars", "KRW" to "won", "₩" to "won", "MXN" to "pesos", "BRL" to "reais",
        "R$" to "reais", "RUB" to "rubles", "₽" to "rubles", "SEK" to "Swedish kronor",
        "NOK" to "Norwegian kroner", "DKK" to "Danish kroner", "ZAR" to "rand", "₦" to "naira",
        "NGN" to "naira", "₺" to "lira", "₱" to "pesos", "₫" to "dong", "BTC" to "bitcoin",
        "ETH" to "ether", "₿" to "bitcoin",
    )

    private fun lookup(table: List<Pair<String, String>>, key: String): String? =
        table.firstOrNull { it.first == key }?.second

    private fun scale(key: String): String = when (key) {
        "k", "K", "thousand" -> "thousand"
        "m", "M", "mn", "mm", "million" -> "million"
        "bn", "b", "B", "billion" -> "billion"
        "tn", "T", "trn", "trillion" -> "trillion"
        else -> ""
    }

    private val FRAC_DEN = setOf(2L, 3L, 4L, 5L, 6L, 8L, 10L, 16L, 32L, 64L, 100L)
    private const val FRAC_AFTER = """^\s*(?:of\b|an?\b|cups?|c\.|teaspoons?|tsp|tablespoons?|tbsp|inch|inches|in\b|in\.|"|miles?|mi\b|pounds?|lbs?|ounces?|oz|hours?|hrs?|minutes?|mins?|seconds?|pints?|quarts?|gallons?|liters?|litres?|kilos?|kg|feet|foot|ft|yards?|acres?|sticks?|pieces?|slices?|loaf|tons?|percent|points?|shares?|the\b|times\b|marathon|price|full|empty|done|finished|complete)"""
    private const val DATE_BEFORE = """(?i)(?:\b(?:on|by|until|till|from|since|before|after|due|dated?|deadline|through|thru|starting|effective|expires?|expiry|born|died|week of|shipped|delivered|arrives?|arriving|ordered|paid|placed|scheduled|filed|signed|posted|submitted|opens?|closes?|ends?|starts?|begins?|[Mm]onday|[Tt]uesday|[Ww]ednesday|[Tt]hursday|[Ff]riday|[Ss]aturday|[Ss]unday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)\.?[,:]?\s*)$"""

    /** 3/4 three quarters; 1/2 one half; with [lead] (the 1 1/2 case): a half. */
    private fun fracWords(a: Long, b: Long, lead: Boolean): String {
        val name = if (b == 2L) {
            if (a == 1L) "half" else "halves"
        } else {
            val base = if (b == 4L) "quarter" else ordinal(b)
            if (a == 1L) base else base + "s"
        }
        val head = if (lead && a == 1L) "a" else cardinal(a)
        return "$head $name"
    }

    private val ABBR = listOf(
        """\bDr\.(?=\s+[A-Z])""" to "Doctor",
        """\bMr\.""" to "Mister",
        """\bMrs\.""" to "Missus",
        """\bMs\.""" to "Miz",
        """\bProf\.(?=\s+[A-Z])""" to "Professor",
        """\bSt\.(?=\s+[A-Z])""" to "Saint",
        """(?<=[a-z] )St\.""" to "Street",
        """\bRd\.""" to "Road",
        """\bAve\.""" to "Avenue",
        """\bBlvd\.""" to "Boulevard",
        """\bApt\.""" to "Apartment",
        """\b[Aa]pprox\.""" to "approximately",
        """\betc\.""" to "et cetera",
        """\be\.g\.""" to "for example",
        """\bi\.e\.""" to "that is",
        """\bvs\.?(?=\s)""" to "versus",
        """\b[Nn]o\.(?=\s*\d)""" to "number",
        """\bdept\.""" to "department",
        """\bext\.""" to "extension",
        """(?<=\d)\s?min\.""" to " minutes",
        """\bmin\.(?=\s+[a-z])""" to "minimum",
        """\bmin\.""" to "minutes",
        """\bhr?s?\.(?=\s)""" to "hours",
        """(?<![\d\s])\s*\b[Ss]ec\.(?=\s*\d)""" to " section",
        """\b([Ee])st\.(?=\s*\d{4})""" to """\1stablished""",
        """\bSgt\.""" to "Sergeant",
        """\bCapt\.""" to "Captain",
        """\bLt\.""" to "Lieutenant",
        """\bCol\.""" to "Colonel",
        """\bGen\.(?=\s+[A-Z])""" to "General",
        """\bGov\.""" to "Governor",
        """\bSen\.""" to "Senator",
        """\bRep\.(?=\s+[A-Z])""" to "Representative",
        """\bRev\.""" to "Reverend",
        """\bHon\.""" to "Honorable",
        """\bJr\.""" to "Junior",
        """\bSr\.""" to "Senior",
        """\bLn\.""" to "Lane",
        """\bDr\.(?=[\s,.]|$)""" to "Drive",
        """\bCt\.""" to "Court",
        """\bPl\.""" to "Place",
        """\bSq\.""" to "Square",
        """\bHwy\.""" to "Highway",
        """\bPkwy\.""" to "Parkway",
        """\bMt\.""" to "Mount",
        """\bFt\.(?=\s+[A-Z])""" to "Fort",
        """\bSte\.""" to "Suite",
        """\bBldg\.""" to "Building",
        """\bfl\.""" to "floor",
        """\bFl\.""" to "Floor",
        """\bRm\.""" to "Room",
        """\bEst\.""" to "Estimated",
        """\best\.""" to "estimated",
        """\bInc\.""" to "Incorporated",
        """\bLtd\.""" to "Limited",
        """\bCo\.(?=\s)""" to "Company",
        """\bCorp\.""" to "Corporation",
        """\bDept\.""" to "Department",
        """\bAssn\.""" to "Association",
        """\bUniv\.""" to "University",
        """\bqty\.?""" to "quantity",
        """\bref\.""" to "reference",
        """\btel\.""" to "telephone",
        """\bTel\.""" to "Telephone",
        """\bfig\.""" to "figure",
        """\bvol\.""" to "volume",
        """\bpp\.""" to "pages",
        """\bp\.(?=\s*\d)""" to "page",
        """\bwk\.""" to "week",
        """\bmo\.""" to "month",
        """\byr\.""" to "year",
        """\bavg\.""" to "average",
        """\bmax\.""" to "maximum",
    )
    private val DAYS = listOf(
        "Mon" to "Monday", "Tue" to "Tuesday", "Tues" to "Tuesday", "Wed" to "Wednesday",
        "Thu" to "Thursday", "Thur" to "Thursday", "Thurs" to "Thursday", "Fri" to "Friday",
        "Sat" to "Saturday", "Sun" to "Sunday",
    )
    private val MON_ABBR = listOf(
        "Jan" to "January", "Feb" to "February", "Mar" to "March", "Apr" to "April", "Jun" to "June",
        "Jul" to "July", "Aug" to "August", "Sep" to "September", "Sept" to "September",
        "Oct" to "October", "Nov" to "November", "Dec" to "December",
    )
    private val SYMBOLS = listOf(
        """\s*±\s*""" to " plus or minus ",
        """\s*≥\s*""" to " greater than or equal to ",
        """\s*≤\s*""" to " less than or equal to ",
        """\s*≠\s*""" to " not equal to ",
        """\s*≈\s*""" to " approximately ",
        """\s*÷\s*""" to " divided by ",
        """\s*→\s*""" to " to ",
        """\s*√\s*""" to " square root of ",
        """(?<=\d)\s*%""" to " percent",
        """\s*‰""" to " per mille",
        """\s+=\s+""" to " equals ",
        """(?<=\d)\s*<\s*(?=\d)""" to " less than ",
        """(?<=\d)\s*>\s*(?=\d)""" to " greater than ",
        """\s*&\s*""" to " and ",
    )
    private val UNITS = listOf(
        "km" to "kilometers", "m" to "meters", "cm" to "centimeters", "mm" to "millimeters",
        "kg" to "kilograms", "g" to "grams", "mg" to "milligrams", "l" to "liters", "L" to "liters",
        "ml" to "milliliters", "mL" to "milliliters", "km/h" to "kilometers per hour",
        "mph" to "miles per hour", "GB" to "gigabytes", "MB" to "megabytes", "TB" to "terabytes",
        "kB" to "kilobytes", "GHz" to "gigahertz", "MHz" to "megahertz", "kW" to "kilowatts",
        "kWh" to "kilowatt hours", "W" to "watts", "V" to "volts", "mAh" to "milliamp hours",
        "ft" to "feet", "in" to "inches", "lb" to "pounds", "lbs" to "pounds", "oz" to "ounces",
        "h" to "hours", "min" to "minutes", "s" to "seconds", "ms" to "milliseconds", "mi" to "miles",
        "yd" to "yards", "yds" to "yards", "Hz" to "hertz", "kHz" to "kilohertz", "dB" to "decibels",
        "mpg" to "miles per gallon", "kph" to "kilometers per hour", "tsp" to "teaspoons",
        "tbsp" to "tablespoons", "gal" to "gallons", "qt" to "quarts", "mcg" to "micrograms",
        "µg" to "micrograms", "μg" to "micrograms", "nm" to "nanometers", "µm" to "micrometers",
        "μm" to "micrometers", "ns" to "nanoseconds", "µs" to "microseconds", "μs" to "microseconds",
        "sec" to "seconds", "secs" to "seconds", "Mbps" to "megabits per second",
        "Gbps" to "gigabits per second", "kbps" to "kilobits per second", "kcal" to "kilocalories",
        "mmHg" to "millimeters of mercury", "dL" to "deciliters", "dl" to "deciliters",
        "PB" to "petabytes", "MW" to "megawatts", "GW" to "gigawatts", "GWh" to "gigawatt hours",
        "MWh" to "megawatt hours", "kPa" to "kilopascals", "hPa" to "hectopascals",
        "mmol" to "millimoles", "fl oz" to "fluid ounces", "sq ft" to "square feet",
        "sq m" to "square meters", "km2" to "square kilometers", "m2" to "square meters",
        "cc" to "C C", "mA" to "milliamps", "ng" to "nanograms", "pg" to "picograms", "IU" to "I U",
        "mEq" to "milliequivalents", "kN" to "kilonewtons", "Nm" to "newton meters",
        "TFLOPS" to "teraflops", "rpm" to "R P M", "psi" to "P S I",
    )

    private fun unitName(key: String): String = lookup(UNITS, key) ?: ""

    private fun sing(w: String): String {
        val per = w.indexOf(" per ")
        val head = if (per >= 0) w.substring(0, per) else w
        val sep = if (per >= 0) " per " else ""
        val rest = if (per >= 0) w.substring(per + " per ".length) else ""
        if (head.contains(" of ")) return w
        val parts = head.split(' ').toMutableList()
        val last = parts.removeAt(parts.lastIndex)
        val single = when (last) {
            "feet" -> "foot"
            "inches" -> "inch"
            "hertz" -> "hertz"
            "kilohertz" -> "kilohertz"
            "teaspoons" -> "teaspoon"
            "C C", "R P M", "P S I", "I U" -> last
            "teraflops" -> "teraflop"
            else -> last.removeSuffix("s")
        }
        parts += single
        return parts.joinToString(" ") + sep + rest
    }

    private fun unit(key: String, n: String): String = if (n == "1") sing(unitName(key)) else unitName(key)

    private const val MON_RE = "(January|February|March|April|May|June|July|August|September|October|November|December)"
    private val STREET = listOf(
        "St" to "Street", "Rd" to "Road", "Ave" to "Avenue", "Av" to "Avenue", "Blvd" to "Boulevard",
        "Ln" to "Lane", "Dr" to "Drive", "Ct" to "Court", "Pl" to "Place", "Sq" to "Square",
        "Hwy" to "Highway", "Pkwy" to "Parkway", "Cres" to "Crescent", "Ter" to "Terrace",
        "Terr" to "Terrace", "Cir" to "Circle", "Trl" to "Trail", "Pk" to "Park",
    )

    /** Words after which a 2-4 digit number is an identifier read in pairs. */
    private const val ID_WORDS = """[Rr]oom|[Rr]m\.?|[Oo]ffice|[Ss]uite|[Ss]te\.?|[Aa]partment|[Aa]pt\.?|[Uu]nit|[Gg]ate|[Pp]latform|[Ff]light|[Bb]us|[Rr]oute|[Hh]ighway|[Hh]wy\.?|[Ii]nterstate|[Tt]erminal|[Bb]ay|[Dd]ock|[Pp]ier|[Ss]eat|[Cc]abin|[Ww]ard|[Ll]ocker|[Bb]ooth|[Ss]tudio|[Bb]uilding|[Bb]ldg\.?|[Ee]xit|[Pp]ort|[Ee]xt\.?|[Ee]xtension|[Mm]odel|[Bb]oeing|[Aa]irbus|[Cc]heckpoint|[Hh]angar|[Tt]rain|[Cc]oach|[Ff]erry|[Cc]ar"""
    private val ELEMENTS = setOf(
        "H", "He", "Li", "Be", "B", "C", "N", "O", "F", "Ne", "Na", "Mg", "Al", "Si", "P", "S", "Cl",
        "Ar", "K", "Ca", "Sc", "Ti", "V", "Cr", "Mn", "Fe", "Co", "Ni", "Cu", "Zn", "Ga", "Ge", "As",
        "Se", "Br", "Kr", "Rb", "Sr", "Y", "Zr", "Nb", "Mo", "Tc", "Ru", "Rh", "Pd", "Ag", "Cd", "In",
        "Sn", "Sb", "Te", "I", "Xe", "Cs", "Ba", "La", "Ce", "Pr", "Nd", "Pm", "Sm", "Eu", "Gd", "Tb",
        "Dy", "Ho", "Er", "Tm", "Yb", "Lu", "Hf", "Ta", "W", "Re", "Os", "Ir", "Pt", "Au", "Hg", "Tl",
        "Pb", "Bi", "Po", "At", "Rn", "Fr", "Ra", "Ac", "Th", "Pa", "U", "Np", "Pu", "Am", "Cm", "Bk",
        "Cf", "Es", "Fm", "Md", "No", "Lr",
    )
    private const val CODE_CTX = """(?i:\b(?:codes?|ref(?:erence)?|serial|PIN|password|passcode|quote|plate|registration|booking|confirmation|tracking|order|voucher|coupon|promo|ticket|ID|key|token|account|policy|claim|case|invoice|SKU|part)\b)[^.;!?]*$"""
    private const val UNIT_PRE = """(?<![\w.])(?<!\w-)(\d(?:[\d,]*\d)?(?:\.\d+)?)"""
    private const val DOMAIN_TLD = "com|org|net|io|ai|co|uk|dev|app|gov|edu|ly|me|tv|us|eu|de|fr|info|biz"

    private fun schemeSay(s: String): String {
        val lowered = lower(s)
        return when {
            lowered == "http" -> "H T T P"
            lowered == "https" -> "H T T P S"
            lowered in setOf("file", "git", "data", "blob", "chrome", "spotify", "slack", "zoom", "steam") -> lowered
            pronounceable(s) -> lowered
            else -> spell(s)
        }
    }

    private fun keys(table: List<Pair<String, String>>): String = table.joinToString("|") { it.first }

    /** Unit keys, longest first, without the two that read as ordinary words. */
    private val UKEYS: String = UNITS.map { it.first }
        .filter { it != "in" && it != "s" }
        .sortedByDescending(::count)
        .joinToString("|", transform = ::escape)

    /** Currency codes and symbols, longest first. */
    private val CURRENCY_CODES: String = CURCODE.map { it.first }
        .sortedByDescending(::count)
        .joinToString("|", transform = ::escape)

    private fun plural(n: String, word: String): String = "$n $word${if (n == "1") "" else "s"}"

    private fun pluralInt(n: String, word: String): String {
        val value = int(n)
        return "$value $word${if (value == BigInteger.ONE) "" else "s"}"
    }

    private fun hourWord(n: String): String = plural(n, "hour")

    // MARK: - First pass: tokens

    private const val NUM = """(\d[\d,]*(?:\.\d+)?)"""
    private const val SC = """(\s?(?:thousand|million|billion|trillion)\b|(?:k|K|mn|m|M|bn|b|B|tn|T)\b)?"""

    private fun currency(code: String, num: String, sc: String): String {
        val name = lookup(CURCODE, code) ?: return "$code$num$sc"
        if (count(name) == 1) return "$name$num$sc"
        val dot = num.indexOf('.')
        val whole = if (dot >= 0) num.substring(0, dot) else num
        val cents = if (dot >= 0) num.substring(dot + 1) else ""
        if (sc.isEmpty() && count(cents) == 2 && name.endsWith("dollars")) {
            val tail = if (int(cents).signum() == 0) "" else " and ${int(cents)} cents"
            return "$whole $name$tail"
        }
        val scaled = if (sc.isEmpty()) "" else " ${scale(sc.trim())}"
        return "$num$scaled $name"
    }

    private fun moneyWord(symbol: String): String = when (symbol) {
        "$" -> "dollars"
        "€" -> "euros"
        else -> "pounds"
    }

    private fun decade(m: M): String {
        val s = m.g(2)
        val n = int(s)
        if (n.mod(big(10)).signum() != 0) return m.whole()
        val word = when {
            count(s) == 4 -> year(n)
            n.signum() == 0 && m.g(1).isNotEmpty() -> "two thousand"
            n.signum() != 0 -> cardinal(n)
            else -> ""
        }
        if (word.isEmpty()) return m.whole()
        return if (word.endsWith("y")) word.dropLast(1) + "ies" else word + "s"
    }

    private fun power(m: M): String {
        val rawBase = m.g(1)
        val rawExp = m.g(2)
        val neg = rawExp.startsWith("-")
        val exp = rawExp.trimStart('+', '-')
        val base = if (isAlpha(rawBase) && count(rawBase) > 1) {
            codePoints(rawBase).joinToString(" ") { cpString(it) }
        } else {
            rawBase
        }
        if (!isDigits(exp)) return "$base to the $exp"
        val n = int(exp)
        return when {
            n == big(2) && !neg -> "$base squared"
            n == big(3) && !neg -> "$base cubed"
            else -> "$base to the ${if (neg) "minus " else ""}${ordinal(n)}"
        }
    }

    private val CURRENCY_LEAD = """(?<![\w$])($CURRENCY_CODES)\s?$NUM$SC"""
    private val CURRENCY_TRAIL = """$NUM$SC\s?(USD|EUR|GBP|JPY|CNY|INR|CAD|AUD|CHF|NZD|HKD|SGD|KRW|MXN|BRL|RUB|SEK|NOK|DKK|ZAR|BTC|ETH)\b"""
    private val MONEY_RANGE = """([$€£])$NUM$SC\s?[-–]\s?[$€£]?$NUM$SC"""
    private val MONEY_SCALED = """([$€£])$NUM(\s?(?:thousand|million|billion|trillion)\b|(?:k|K|mn|m|M|bn|b|B|tn|T)\b)"""
    private val ROMAN_WORD = """\b([A-Za-z][A-Za-z']*\.?)(\s+)($ROMAN_RE)\b(?!'\w|-\d)"""
    private val SQUARE_UNIT = """$UNIT_PRE\s?(km|m|cm|mm|ft|mi|yd|in)\^([23])\b"""

    /**
     * Rules that must see the raw string before the general passes: symbols outside the
     * voice's alphabet, money with scale words and ranges, decades, Roman numerals,
     * powers, durations, code identifiers, hashtags and handles.
     */
    private fun normTokens(input: String): String {
        var t = input.replace('−', '-').replace('’', '\'').replace('‘', '\'')
            .replace('“', '"').replace('”', '"')
        t = tpl("""(?<=\d)([½¼¾⅓⅔⅛])""", t, """ \1""")
        t = sub("[½¼¾⅓⅔⅛]", t) { m ->
            when (m.g(0)) {
                "½" -> "1/2"
                "¼" -> "1/4"
                "¾" -> "3/4"
                "⅓" -> "1/3"
                "⅔" -> "2/3"
                else -> "1/8"
            }
        }
        t = sub("[⁰¹²³⁴⁵⁶⁷⁸⁹⁻]+", t) { m ->
            val mapped = m.g(0).map { c ->
                when (c) {
                    '⁰' -> '0'
                    '¹' -> '1'
                    '²' -> '2'
                    '³' -> '3'
                    '⁴' -> '4'
                    '⁵' -> '5'
                    '⁶' -> '6'
                    '⁷' -> '7'
                    '⁸' -> '8'
                    '⁹' -> '9'
                    else -> '-'
                }
            }.joinToString("")
            "^$mapped"
        }
        // Tech tokens: .NET, C#, C++, getUserName().
        t = sub("""(?<![\w.])([A-Za-z]*)\.NET\b""", t) { m ->
            if (m.g(1).isEmpty()) "dot net" else "${spell(m.g(1))} dot net"
        }
        t = tpl("""\b([A-Za-z])#(?!\w)""", t, """\1 sharp""")
        t = tpl("""\b([A-Za-z])\+\+(?!\w)""", t, """\1 plus plus""")
        t = sub("""\b([A-Za-z_][A-Za-z0-9_]*)\(\)""", t) { m -> splitIdent(m.g(1)) }
        t = sub("""\b[a-z]{2,}(?:[A-Z][a-z]+){2,}\b""", t) { m -> splitIdent(m.g(0)) }
        t = sub("""\bv(\d+(?:\.\d+)+)\b""", t) { m -> "version ${m.g(1).split('.').joinToString(" point ")}" }
        // Hashtags and handles.
        t = sub("""(?<![\w&#])#([A-Za-z][A-Za-z_]*)\b(?![\d-])""", t) { m -> "hashtag ${splitIdent(m.g(1))}" }
        t = sub("""(?<![\w.@])@([A-Za-z_][A-Za-z0-9_]{1,30})\b""", t) { m -> "at ${handle(m.g(1))}" }
        // UK and Canadian postcodes: SW1A 1AA, M14 5TG, K1A 0B6.
        t = sub("""\b([A-Z]{1,2}\d[A-Z\d]?|[A-Z]\d[A-Z])\s(\d[A-Z]{2}|\d[A-Z]\d)\b""", t) { m ->
            "${spell(m.g(1))}, ${spell(m.g(2))}"
        }
        // Digits read one by one: card ending in 4459, PIN 0420.
        t = sub(
            """\b((?:ending|ends) in|last (?:four|4)(?: digits)?(?: of)?|PIN|[Pp]in|passcode|OTP|[Cc]ode)(\s+)(\d{3,8}(?:\s\d{2,4})*)\b(?![.,]\d|-\w)""",
            t,
        ) { m -> m.g(1) + m.g(2) + words(m.g(3)).joinToString(", ", transform = ::digits) }
        // Paths and code: C:\Users, SELECT *, .env, API_KEY.
        t = tpl("""\b([A-Z]):\\""", t, """\1 colon backslash """)
        t = t.replace("\\", " backslash ")
        t = tpl("""(?<=\s)\*(?=\s)(?!\s*\d)""", t, "star")
        t = tpl("""(?<![\w.])\.(env|gitignore|bashrc|zshrc|npmrc|htaccess|dockerignore)\b""", t, """dot \1""")
        t = sub("""\b(?=\w*_)(?![\w]*\.\w)[A-Za-z0-9]+(?:_[A-Za-z0-9]+)+\b""", t) { m ->
            m.g(0).split('_').joinToString(" underscore ") { p ->
                val hasDigit = p.any(::isAsciiDigit)
                val hasLetter = p.any { it in 'a'..'z' || it in 'A'..'Z' }
                if (hasDigit && hasLetter) spell(p) else p
            }
        }
        // US toll-free and long-distance: 1-800-829-1040 is one eight hundred, ...
        t = sub("""(?<![\w+])1[-. ](\d{3})[-. ](?:(\d{3})[-. ](\d{4})|([A-Z]{3,}(?:-[A-Z]+)*))\b""", t) { m ->
            val area = if (m.g(1) == "800" || m.g(1) == "900") cardinal(int(m.g(1))) else digits(m.g(1))
            val rest = if (m.g(2).isEmpty()) {
                m.g(4).split('-').joinToString(" ", transform = ::capitalize)
            } else {
                "${digits(m.g(2))}, ${digits(m.g(3))}"
            }
            "one $area, $rest"
        }
        // Money: currency codes and symbols, ranges, scale words.
        t = sub(CURRENCY_LEAD, t) { m -> currency(m.g(1), m.g(2), m.g(3)) }
        t = sub(CURRENCY_TRAIL, t) { m -> currency(m.g(3), m.g(1), m.g(2)) }
        t = tpl("""(?<!\w)(\d+)\s?¢""", t, """\1 cents""")
        t = sub(MONEY_RANGE, t) { m ->
            val c = m.g(1)
            val a = m.g(2)
            val s1 = m.g(3)
            val b = m.g(4)
            val s2 = m.g(5)
            if (s1.isEmpty() && s2.isEmpty() && search("""\.\d\d\b""", "$a $b")) {
                return@sub "$c$a to $c$b"
            }
            fun word(s: String) = if (s.isEmpty()) "" else " ${scale(s.trim())}"
            val w1 = word(s1)
            val w2 = word(s2)
            val lead = if (w1 == w2) "" else w1
            "$a$lead to $b$w2 ${moneyWord(c)}"
        }
        t = sub(MONEY_SCALED, t) { m -> "${m.g(2)} ${scale(m.g(3).trim())} ${moneyWord(m.g(1))}" }
        // Durations: 2h 15m, 1h30, 6m 30s; a bare 45m after for/last/takes is minutes.
        t = sub("""\b(\d+)\s?h(?:rs?)?\s?(\d{1,2})(?:\s?m(?:ins?)?)?\b""", t) { m ->
            "${plural(m.g(1), "hour")} ${plural(m.g(2), "minute")}"
        }
        t = sub("""\b(\d+)\s?m(?:ins?)?\s?(\d{1,2})\s?s(?:ecs?)?\b""", t) { m ->
            "${plural(m.g(1), "minute")} ${plural(m.g(2), "second")}"
        }
        t = sub(
            """(\b(?:for|last|lasts|lasted|lasting|takes?|took|taking|every|within|wait|waited|remaining|runtime|ETA|blocked)\s+(?:\w+\s+){0,2}?)(\d{1,3})m\b(?!\s*(?:dash|sprint|race|swim|hurdles|pool|freestyle|relay))""",
            t,
        ) { m -> m.g(1) + plural(m.g(2), "minute") }
        // Decades: '80s, 1990s, the 2000s; '98 is ninety eight.
        t = sub("""(?<![\w$€£.,'])(')?(\d{2}|1\d{3}|20\d{2})'?s\b""", t, ::decade)
        t = sub("""(?:(?<=\s)|^)'(\d{2})\b(?!['\w])""", t) { m ->
            if (m.g(1).startsWith("0")) "oh ${ONES[sm(m.g(1)).toInt()]}" else m.g(1)
        }
        // Roman numerals by context: Henry VIII, Chapter XIV.
        t = sub("""\bWW\s?(II|I|2|1)\b""", t) { m ->
            "World War ${if (m.g(1) == "II" || m.g(1) == "2") "Two" else "One"}"
        }
        t = sub(ROMAN_WORD, t, ::romanSub)
        t = sub("""(?<=[a-z])\((I|II|III|IV|V|VI)\)""", t) { m -> " ${cardinal(roman(m.g(1)))}" }
        // Powers: x^2 is x squared, 6.02 x 10^23 is 6.02 times 10 to the ...
        t = tpl("""(\d)\s?[x×*]\s?(?=10\s?\^)""", t, """\1 times """)
        t = sub(SQUARE_UNIT, t) { m ->
            "${m.g(1)} ${if (m.g(3) == "2") "square" else "cubic"} ${unitName(m.g(2))}"
        }
        t = sub("""(\b[A-Za-z]{1,3}|\b\d+(?:\.\d+)?|\))\s?\^\s?\(?([+-]?(?:\d+|[a-z]))\)?""", t, ::power)
        if (search("""[=^+]|\bsolve\b|\bequation""", t)) {
            // 3x - 2 = 11 is 3 x minus 2.
            t = tpl("""(?<=[\w)])\s+[-–]\s+(?=[\w(])""", t, " minus ")
            t = tpl("""\b(\d+)([a-fi-jn-rt-z])\b""", t, """\1 \2""")
        }
        // Multipliers: 3x faster, about 3x what it cost, grew 3x. are three times.
        t = tpl(
            """\b(\d+(?:\.\d+)?)[xX](?=\s+(?:faster|slower|more|less|larger|smaller|bigger|higher|lower|cheaper|better|worse|longer|shorter|the|as|over|speed|zoom|what|that|this|these|those|its?|their|his|her|our|your|my|an?|in|on|per|than|from|since|normal|usual|before|compared)\b|[.,;:!?)]+(?:\s|$)|$)""",
            t,
            """\1 times""",
        )
        // Sizes: 4x4, 1920x1080 are four by four (0x1F stays hex).
        t = tpl("""\b([1-9]\d{0,4})[xX](\d{1,5})\b""", t, """\1 by \2""")
        // Any other 10x before a word is said "ten x" (a 10x engineer).
        t = tpl("""\b(\d+(?:\.\d+)?)[xX](?=\s+[A-Za-z])""", t, """\1 x""")
        t = tpl("""(\d)\s+[x×*]\s+(\d+(?:\.\d+)?)(?=\s*=)""", t, """\1 times \2""")
        // Bare scales: 4.5M visitors is 4.5 million visitors (5K run stays).
        return sub(
            """(?<![\w$€£.])(\d+\.\d+|\d+(?=\s?\w+\s+[a-z]{3,}s\b))\s?(M|bn|mn|tn)\b(?=\s+[a-z]{3,})""",
            t,
        ) { m -> "${m.g(1)} ${scale(m.g(2))}" }
    }

    // MARK: - Second pass: structured text

    private fun dotted(s: String): String = s.replace(".", " dot ").replace("-", " dash ")

    private fun streetRule(m: M): String {
        val num = m.g(1)
        val letter = m.g(2)
        val name = m.g(3)
        val kind = m.g(4)
        val dot = m.g(5)
        val tail = if (dot.isNotEmpty() && search("""^\s*(?:$|[A-Z\n])""", m.after())) "." else ""
        val spacedLetter = if (letter.isEmpty()) "" else " ${upper(letter)}"
        return "${nread(num)}$spacedLetter $name ${lookup(STREET, kind) ?: kind}$tail"
    }

    private fun phone(m: M): String {
        val s = m.g(0)
        if (s.count(::isAsciiDigit) < 7) return m.whole()
        val lead = if (s.trim().startsWith("+")) "plus " else ""
        val groups = mutableListOf<String>()
        val current = StringBuilder()
        for (cp in codePoints(s)) {
            if (isNumeric(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                groups += digits(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) groups += digits(current.toString())
        return " $lead${groups.joinToString(", ")} "
    }

    private fun ordSuffix(n: Long): String {
        val suffix = if (n % 100 in 10..20) {
            "th"
        } else {
            when (n % 10) {
                1L -> "st"
                2L -> "nd"
                3L -> "rd"
                else -> "th"
            }
        }
        return "$n$suffix"
    }

    /** Day/month (or month/day) without a year: 14/03 the 14th of March, 03/14 March 14th. */
    private fun dayMonth(m: M, order: DateOrder): String {
        val a = sm(m.g(1))
        val b = sm(m.g(2))
        val after = lower(firstChars(m.after(), 12))
        val before = lastChars(m.before(), 16)
        if (search("""^\s*(stars?|points?|marks?|votes?)\b""", after) ||
            search("""(rated|scored?|rating|got)\s*$""", lower(before))
        ) {
            return "$a out of $b"
        }
        if (0 < a && a < b && b in FRAC_DEN && !m.g(1).startsWith("0") && !m.g(2).startsWith("0") &&
            (!search(DATE_BEFORE, before) || search(FRAC_AFTER, after))
        ) {
            return fracWords(a, b, false)
        }
        val the = if (search("""(?i)\bthe\s+$""", before)) "" else "the "
        val dayFirst = b in 1..12 && a in 1..31
        val monthFirst = a in 1..12 && b in 1..31
        if (monthFirst && (order == DateOrder.MONTH_FIRST || !dayFirst)) {
            return "${MONTHS[(a - 1).toInt()]} ${ordSuffix(b)}"
        }
        if (dayFirst) return "$the${ordSuffix(a)} of ${MONTHS[(b - 1).toInt()]}"
        return m.whole()
    }

    /** Not preceded by a code word (codes stay letter by letter, digit by digit). */
    private fun notCodeContext(m: M): Boolean = !search(CODE_CTX, lastChars(m.before(), 40))

    private val DAY_RANGE = """\b(${keys(DAYS)})\.?\s*[-–]\s*(${keys(DAYS)})\.?"""
    private val DAY_ABBR_DOT = """(?<!\bthe )(?<!\bThe )\b(${keys(DAYS)})\.(?=[\s,]|$)"""
    private val DAY_AFTER_WORD = """\b(on|by|until|till|every|from|next|this|last) (${keys(DAYS)})\b(?!')"""
    private val MON_ABBR_DOT = """\b(${keys(MON_ABBR)})\.(?=\s*\d)"""
    private val MON_ABBR_NUM = """\b(${keys(MON_ABBR)})\b(?=\s+\d{1,4}\b(?!:))"""
    private val MON_ABBR_AFTER = """(?<=\d )(${keys(MON_ABBR)})\b\.?"""
    private val STREET_ADDRESS = """\b(\d{1,4})([A-Za-z]?)\s+((?:(?:North|South|East|West|N\.?|S\.?|E\.?|W\.?)\s+)?(?:[A-Z][\w']*|\d+(?:st|nd|rd|th))(?:\s+[A-Z][\w']*){0,2})\s+(Street|Road|Avenue|Boulevard|Lane|Drive|Court|Place|Square|Highway|Parkway|Crescent|Terrace|Circle|Way|Close|Row|Walk|Alley|Plaza|Trail|Pike|Loop|${keys(STREET)})\b(\.?)"""
    private val DAY_RANGE_MONTH = """\b(\d{1,2})(?:st|nd|rd|th)?\s*[-–]\s*(\d{1,2})(?:st|nd|rd|th)?\s+$MON_RE\b"""
    private val DAY_OF_MONTH = """\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$MON_RE\b"""
    private val PER_UNIT = """$UNIT_PRE\s?($UKEYS)/($UKEYS|s|in)(?![\w/])"""
    private val NUM_UNIT = """$UNIT_PRE\s?($UKEYS)(?![\w/'])"""
    private val POWER_UNIT = """(\b(?:squared|cubed|to the (?:minus )?(?:\w+ )?\w+(?:th|st|nd|rd))\s)($UKEYS)(?:/($UKEYS|s))?(?![\w/'])"""
    private val ID_NUMBER = """\b($ID_WORDS)(\s+(?:No\.?\s*|#\s*)?)([A-Z]{1,3}\s?)?(\d{2,4})([A-Z]{0,2})\b(?![.,]\d|\s?[-–]\s?\d)"""
    private val HOST_TLD = """\.(?:$DOMAIN_TLD)$"""
    private val DOMAIN = """\b((?:[\w-]+\.)+)($DOMAIN_TLD)\b"""
    private val MONTH_DAY = """\b(${MONTHS.joinToString("|")})\.? (\d{1,2})\b(?!:)"""

    private fun normPre(text: String, order: DateOrder): String {
        var t = normTokens(text)
        // Map scales and large ratios: 1:25,000 and 1:100000 can't be clock times.
        t = sub("""(?<![\d:.,])\b(\d{1,3}):(\d{1,3}(?:,\d{3})+|\d{4,})\b(?![:.,]\d)""", t) { m ->
            "${cardinal(int(m.g(1)))} to ${cardinal(int(m.g(2).replace(",", "")))}"
        }
        // Host ports: localhost:8080 is localhost colon eighty eighty.
        t = sub("""\b(localhost|(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}):(\d{2,5})\b""", t) { m ->
            "${m.g(1)} colon ${nread(m.g(2))}"
        }
        // URL schemes: a bare one is read out, http(s) before an address is dropped as
        // people do reading aloud, other schemes are read.
        t = sub("""(?<![\w.+-])([A-Za-z][A-Za-z0-9+.-]{1,9})://(?=[\s,.;:!?)"']|$)""", t) { m ->
            "${schemeSay(m.g(1))} colon slash slash"
        }
        t = sub(
            """(?<![\w.+-])(?!https?://)([A-Za-z][A-Za-z0-9+.-]{1,9})://(?=[\w/@\[])((?![\w.-]*@)[A-Za-z][\w-]*(?:\.[\w-]+)+\b)?""",
            t,
        ) { m -> "${schemeSay(m.g(1))} colon slash slash ${dotted(m.g(2))}" }
        t = tpl("""\bmailto:(?=[\w.+-]+@)""", t, "mail to ")
        // E-mail and web addresses.
        t = sub("""\b([\w.+-]+)@([\w-]+(?:\.[\w-]+)+)\b""", t) { m ->
            val local = m.g(1).replace(".", " dot ").replace("_", " underscore ").replace("-", " dash ")
            "$local at ${dotted(m.g(2))}"
        }
        t = sub("""\bhttps?://(?![\w.-]*[:@])([A-Za-z][\w-]*(?:\.[\w-]+)+\b)?""", t) { m ->
            val host = m.g(1)
            if (host.isNotEmpty() && !search(HOST_TLD, host)) {
                val read = dotted(host)
                if (read.startsWith("www ")) "w w w ${read.removePrefix("www ")}" else read
            } else {
                host
            }
        }
        t = tpl("""\bwww\.""", t, "w w w dot ")
        t = sub(DOMAIN, t) { m -> dotted(m.g(1)) + m.g(2) }
        // File names: name_with_parts.ext.
        t = sub(
            """\b([\w-]+)\.(pdf|png|jpe?g|gif|docx?|xlsx?|csv|txt|zip|mp3|mp4|wav|json|py|js|html?|gradle|ipynb|rtf|pptx?|webm|bin|dat|bat|ps1|vue|jsx|kts|cs|dart|lua|r)\b""",
            t,
        ) { m -> "${m.g(1).replace("_", " underscore ")} dot ${m.g(2)}" }
        t = sub(
            """(?<![\w@])((?:/?[\w-]+/)*[\w-]*[a-z_][\w-]*)\.(conf|log|cfg|ini|yaml|yml|toml|md|sh|ts|tsx|css|xml|sql|db|bak|tmp|exe|dmg|apk|iso|tar|gz|rar|7z|svg|webp|heic|mov|avi|mkv|flac|ogg|m4a|key|pem|crt|env|lock|rs|go|java|kt|swift|rb|php|c|cpp|h|git)\b""",
            t,
        ) { m ->
            val base = m.g(1).replace("_", " underscore ").replace("/", " slash ")
                .replace("-", " dash ").replace(".", " dot ")
            "$base dot ${m.g(2)}"
        }
        t = sub("""(?<![\w])/((?:[\w.-]+/)*[\w.-]+)""", t) { m -> " slash ${m.g(1).replace("/", " slash ")}" }
        // 12-25-1999 and 14.03.2027 are dates; slashed form for the date rules.
        t = tpl("""\b(\d{1,2})-(\d{1,2})-((?:19|20)\d\d)\b""", t, """\1/\2/\3""")
        t = sub("""\b(\d{1,2})\.(\d{1,2})\.((?:19|20)\d\d)\b""", t) { m ->
            val d = sm(m.g(1))
            val mo = sm(m.g(2))
            if (d in 1..31 && mo in 1..12) "the ${ordinal(d)} of ${MONTHS[(mo - 1).toInt()]} ${m.g(3)}" else m.whole()
        }
        // Day and month ranges / abbreviations.
        t = sub(DAY_RANGE, t) { m -> "${lookup(DAYS, m.g(1)) ?: ""} to ${lookup(DAYS, m.g(2)) ?: ""}" }
        t = sub(DAY_ABBR_DOT, t) { m ->
            val dot = if (search("""^\s*(?:$|[A-Z])""", m.after())) "." else ""
            (lookup(DAYS, m.g(1)) ?: "") + dot
        }
        t = sub(DAY_AFTER_WORD, t) { m -> "${m.g(1)} ${lookup(DAYS, m.g(2)) ?: ""}" }
        t = sub(MON_ABBR_DOT, t) { m -> lookup(MON_ABBR, m.g(1)) ?: "" }
        t = sub(MON_ABBR_NUM, t) { m -> lookup(MON_ABBR, m.g(1)) ?: "" }
        t = sub(MON_ABBR_AFTER, t) { m -> lookup(MON_ABBR, m.g(1)) ?: "" }
        // House numbers and street types: 742 Evergreen Terrace, 4 Elm Rd.
        t = sub(STREET_ADDRESS, t, ::streetRule)
        t = sub(
            """(?<!Mount )(?<!Port )(?<!Fort )(?<!Lake )(?<!Cape )\b([A-Z][a-z]+|\d+(?:st|nd|rd|th))\s+(St|Rd|Ave|Blvd|Ln|Hwy|Pkwy|Cres|Terr?)\b(\.?)""",
            t,
        ) { m ->
            val lowered = lower(m.g(1))
            if (lowered in ROMAN_STOP || lowered in setOf("on", "at", "visit", "see") ||
                m.g(2) == "St" && search("""^\s+[A-Z]""", m.after())
            ) {
                return@sub m.whole()
            }
            val dot = if (m.g(3).isNotEmpty() && search("""^\s*(?:$|\n)""", m.after())) "." else ""
            "${m.g(1)} ${lookup(STREET, m.g(2)) ?: ""}$dot"
        }
        t = sub("""\b((?:I|US|SR|Route|Highway|Hwy|Interstate)[- ]\d+)\s([NSEW])\b""", t) { m ->
            val way = when (m.g(2)) {
                "N" -> "North"
                "S" -> "South"
                "E" -> "East"
                else -> "West"
            }
            "${m.g(1)} $way"
        }
        // "Bus no. 42" is said "bus forty two". Phonon also misreads a sentence that
        // starts "Bus number" as "Buzz number".
        t = tpl("""\b([Bb]us|[Rr]oute)\s+[Nn]o\.\s*(?=\d)""", t, """\1 """)
        for ((pattern, replacement) in ABBR) {
            t = tpl(pattern, t, replacement)
        }
        // ISO dates: 2026-10-21 is October 21, 2026.
        t = sub("""\b(\d{4})-(\d{2})-(\d{2})\b""", t) { m ->
            val mo = sm(m.g(2))
            if (mo in 1..12) "${MONTHS[(mo - 1).toInt()]} ${int(m.g(3))}, ${m.g(1)}" else m.whole()
        }
        // 3-17 November is the third to the seventeenth of November.
        t = sub(DAY_RANGE_MONTH, t) { m ->
            val a = sm(m.g(1))
            val b = sm(m.g(2))
            if (a < b && b <= 31) "the ${ordinal(a)} to the ${ordinal(b)} of ${m.g(3)}" else m.whole()
        }
        // 14 March 2027 is the fourteenth of March 2027.
        t = sub(DAY_OF_MONTH, t) { m ->
            val d = sm(m.g(1))
            if (d !in 1..31) return@sub m.whole()
            val the = if (search("""(?i)\bthe\s+$""", m.before())) "" else "the "
            "$the${ordinal(d)} of ${m.g(2)}"
        }
        // Year ranges 1968-2020, 1990-95 (before the phone rule can take them).
        t = sub("""\b(1[5-9]\d\d|20\d\d)\s*[-–]\s*(1[5-9]\d\d|20\d\d|\d\d)\b(?![-/.]\d)""", t) { m ->
            val tail = sm(lastChars(m.g(1), 2))
            if (count(m.g(2)) == 4 || sm(m.g(2)) > tail) "${m.g(1)} to ${m.g(2)}" else m.whole()
        }
        // Month/year 3/2027 is March 2027; set phrases 24/7, 50/50, 9/11.
        t = sub("""(?<![\d/])\b(\d{1,2})/((?:19|20)\d\d)\b""", t) { m ->
            val mo = sm(m.g(1))
            if (mo in 1..12) "${MONTHS[(mo - 1).toInt()]} ${m.g(2)}" else m.whole()
        }
        t = sub("""(?<![\d/])\b(24/7|50/50|9/11)\b(?!/)""", t) { m ->
            when (m.g(1)) {
                "24/7" -> "24 7"
                "50/50" -> "fifty fifty"
                else -> "nine eleven"
            }
        }
        // Fractions: 1 1/2 is 1 and a half (dates need a date word before them).
        t = sub("""(?<![\d/.,])\b(\d+)\s+(\d{1,2})/(\d{1,2})\b(?!/\d)""", t) { m ->
            val a = sm(m.g(2))
            val b = sm(m.g(3))
            if (0 < a && a < b && b in FRAC_DEN) "${m.g(1)} and ${fracWords(a, b, true)}" else m.whole()
        }
        t = sub("""(?<![\d/])\b(\d{1,2})/(\d{1,2})\b(?!/\d)""", t) { m -> dayMonth(m, order) }
        // Multi-dot versions 4.0.1(-beta).
        t = sub("""\b(\d+(?:\.\d+){2,})(?:-(alpha|beta|rc\d*|preview))?\b""", t) { m ->
            val read = m.g(1).split('.').joinToString(" point ")
            if (m.g(2).isEmpty()) read else "$read ${m.g(2)}"
        }
        // 2:03:45 is two hours three minutes ... (a clock time after "at" keeps its
        // seconds at the end).
        t = sub("""\b(\d{1,2}):(\d{2}):(\d{2})\b(?!:)""", t) { m ->
            if (search("""\b(at|by|until|from)\s+$""", m.before())) {
                return@sub "${m.g(1)}:${m.g(2)} and ${pluralInt(m.g(3), "second")}"
            }
            val parts = mutableListOf<String>()
            for ((value, word) in listOf(m.g(1) to "hour", m.g(2) to "minute", m.g(3) to "second")) {
                if (int(value).signum() != 0 || word == "second") parts += pluralInt(value, word)
            }
            parts.joinToString(", ")
        }
        // Time ranges: 10:00-16:30, 8 AM-6 PM, 9am-5pm.
        t = tpl(
            """\b(\d{1,2}(?::\d{2})?\s?(?:[AaPp]\.?[Mm]\.?)?)\s?[-–]\s?(\d{1,2}(?::\d{2})?\s?[AaPp]\.?[Mm]\b\.?)""",
            t,
            """\1 to \2""",
        )
        t = tpl("""\b(\d{1,2}:\d{2})\s?[-–]\s?(\d{1,2}:\d{2})\b""", t, """\1 to \2""")
        // Ratios and scores with a one-digit right side: 3:2; 16:9 after "ratio".
        t = tpl("""\b(\d{1,2}):(\d)\b(?!\d)""", t, """\1 to \2""")
        t = tpl("""(?<=[Rr]atio of |[Rr]atio is )(\d{1,2}):(\d{1,2})\b""", t, """\1 to \2""")
        // Letter-led codes with digit groups, before the phone rule takes them.
        t = sub("""\b[A-Z]{1,4}(?:-[A-Z0-9]{1,6})*-\d[A-Za-z0-9]*(?:-[A-Za-z0-9]+)*\b""", t) { m -> codeRead(m.g(0)) }
        // Phone numbers: optional +CC, digit groups, 7+ digits in total.
        t = sub("""(?<![\w.,])\+?\(?\d{2,4}\)?(?:[\s.-]\(?\d{2,4}\)?){1,4}(?![\w.,]*\d)""", t, ::phone)
        // Short service numbers after call/dial/text.
        t = sub("""\b((?:[Cc]all|[Dd]ial|[Tt]ext|[Rr]ing)\s+)(\d{3,6})\b""", t) { m -> m.g(1) + digits(m.g(2)) }
        // Units after numbers (singular after exactly 1; 10 mg/kg is per kilogram).
        t = sub("""\b(\d+)\s?ft\.?\s?(\d{1,2})\s?in\b\.?""", t) { m ->
            "${m.g(1)} ${if (m.g(1) == "1") "foot" else "feet"} ${m.g(2)} ${if (m.g(2) == "1") "inch" else "inches"}"
        }
        t = tpl("""\b(\d)'\s?(\d{1,2})(?:"|'')""", t, """\1 foot \2""")
        t = sub(PER_UNIT, t) { m ->
            if (m.g(3) == "in") {
                m.whole()
            } else {
                val per = if (m.g(3) == "s") sing("seconds") else sing(unitName(m.g(3)))
                "${m.g(1)} ${unit(m.g(2), m.g(1))} per $per"
            }
        }
        t = sub(NUM_UNIT, t) { m -> "${m.g(1)} ${unit(m.g(2), m.g(1))}" }
        t = sub(POWER_UNIT, t) { m ->
            val per = when {
                m.g(3).isEmpty() -> ""
                m.g(3) == "s" -> " per ${sing("seconds")}"
                else -> " per ${sing(unitName(m.g(3)))}"
            }
            m.g(1) + unitName(m.g(2)) + per
        }
        // Identifiers read in pairs: room 402, gate 47B, flight UA 482, B12, A380.
        t = sub(ID_NUMBER, t) { m ->
            val letters = if (m.g(3).isEmpty()) "" else "${spell(m.g(3))} "
            val tail = if (m.g(5).isEmpty()) "" else " ${spell(m.g(5))}"
            "${m.g(1)}${m.g(2)}$letters${nread(m.g(4))}$tail"
        }
        t = sub("""\b([A-Z]{2,3})\s(\d{3,4})\b(?![.,:]\d|\s\d)""", t) { m -> "${m.g(1)} ${nread(m.g(2))}" }
        t = sub("""(?<![-/.\w])([A-Z]{1,3})(\d{1,4})([A-Z]{0,2})(?![-/\w])""", t) { m ->
            if (notCodeContext(m) && (count(m.g(2)) <= 2 || count(m.g(1)) == 2 && m.g(3).isEmpty())) {
                listOf(spell(m.g(1)), nread(m.g(2)), spell(m.g(3))).filter { it.isNotEmpty() }.joinToString(" ")
            } else {
                m.whole()
            }
        }
        t = sub("""(?<![-/.\w])(\d{1,4})([A-Z]{1,2})(?![-/\w])""", t) { m ->
            if (notCodeContext(m)) "${nread(m.g(1))} ${spell(m.g(2))}" else m.whole()
        }
        // Chemical formulas: C6H12O6 is C six H twelve O six.
        t = sub("""(?<![-/.\w])(?=[A-Za-z]*\d)(?:[A-Z][a-z]?\d{0,3}){2,}(?![-/\w])""", t) { m ->
            val parts = mutableListOf<String>()
            var allElements = true
            forEachMatch("""([A-Z][a-z]?)(\d*)""", m.g(0)) { e ->
                val symbol = e.g(1)
                val amount = e.g(2)
                allElements = allElements && symbol in ELEMENTS
                parts += if (amount.isEmpty()) spell(symbol) else "${spell(symbol)} ${cardinal(int(amount))}"
            }
            if (allElements && notCodeContext(m)) parts.joinToString(" ") else m.whole()
        }
        // Hash numbers: #1 is number one, #4471 is number four four seven one.
        t = sub("""#(\d+(?:-\d+)*)\b""", t) { m ->
            "number " + m.g(1).split('-').joinToString(", ") { g -> if (count(g) <= 3) g else digits(g) }
        }
        // Codes: letter/digit mixes with or without dashes.
        t = sub("""\b(?=[A-Za-z0-9-]*\d)(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+\b""", t) { m ->
            codeRead(m.g(0))
        }
        t = sub("""\b(?=\w*\d)(?=\w*[A-Z])[A-Z0-9]{3,}\b""", t) { m -> spell(m.g(0)) }
        // Random mixed IDs (4hQx8Z, a9F2k), not ordinals, times or units.
        t = sub("""\b(?=[A-Za-z0-9]*\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{4,}\b""", t) { m ->
            if (search(
                    """(?i)^\d+(?:st|nd|rd|th|am|pm|s|kg|km|mm|cm|ml|mg|gb|mb|tb|kb|ghz|mhz|kw|kwh|mah|hrs?|mins?|h|m|k|x|g|l|w|v)$""",
                    m.g(0),
                )
            ) {
                m.whole()
            } else {
                spell(m.g(0))
            }
        }
        // Long bare digit strings (order / account numbers): 5+ digits.
        t = sub("""(?<![\d$€£.,])\b(\d{5,})\b(?![.,]\d)""", t) { m -> digits(m.g(1)) }
        // Ranges between small numbers: 9-5, 3-5 days, 0-2.
        t = tpl("""(?<![\d/:.-])\b(\d{1,4})\s*[-–]\s*(\d{1,4})\b(?![/:.-]\d)""", t, """\1 to \2""")
        // Durations: 2h 15, 1h30, 45min, 3hrs.
        t = sub("""\b(\d+)\s*h\s*(\d{1,2})\b""", t) { m -> "${hourWord(m.g(1))} ${m.g(2)}" }
        t = sub("""\b(\d+)\s*hrs?\b""", t) { m -> hourWord(m.g(1)) }
        t = sub("""\b(\d+)\s*mins?\b""", t) { m -> plural(m.g(1), "minute") }
        t = sub(NUM_UNIT, t) { m -> "${m.g(1)} ${unit(m.g(2), m.g(1))}" }
        // Dimensions with ×.
        t = tpl("""\s*×\s*""", t, " by ")
        t = tpl("""(?<=\d)\s+x\s+(?=\d)""", t, " by ")
        for ((pattern, replacement) in SYMBOLS) {
            t = tpl(pattern, t, replacement)
        }
        // Pounds and pence: £7.50 is 7 pounds 50.
        t = sub("""£(\d[\d,]*)\.(\d{2})\b""", t) { m ->
            if (m.g(2) == "00") "${m.g(1)} pounds" else "${m.g(1)} pounds ${int(m.g(2))}"
        }
        // Leftover en dashes aren't in the voice's alphabet.
        t = tpl("""\s+–\s+""", t, " — ").replace('–', '-')
        return tpl("""\s{2,}""", t, " ").trim()
    }

    // MARK: - Last pass: remaining digits and symbols

    /** Light verbalizer for numbers, money, times and ordinals. */
    private fun fallback(input: String, order: DateOrder): String {
        var t = sub("""([$€£])(\d[\d,]*)(?:\.(\d+))?\b""", input) { m ->
            val (one, many, c1, cn) = when (m.g(1)) {
                "$" -> listOf("dollar", "dollars", "cent", "cents")
                "€" -> listOf("euro", "euros", "cent", "cents")
                else -> listOf("pound", "pounds", "penny", "pence")
            }
            val amount = m.g(2)
            val frac = m.g(3)
            if (frac.isNotEmpty() && count(frac) != 2) return@sub "${number("$amount.$frac")} $many"
            val whole = int(amount)
            val cents = if (frac.isEmpty()) BigInteger.ZERO else int(frac)
            val centWord = if (cents == BigInteger.ONE) c1 else cn
            if (whole.signum() == 0 && cents.signum() != 0) return@sub "${cardinal(cents)} $centWord"
            val head = "${cardinal(whole)} ${if (whole == BigInteger.ONE) one else many}"
            if (cents.signum() == 0) head else "$head and ${cardinal(cents)} $centWord"
        }
        t = sub("""\b(\d{1,2}):(\d{2})\s*([aApP]\.?[mM])?(?:\.(?=\s+[a-z]))?(?=\W|$)""", t) { m ->
            val h = sm(m.g(1))
            val mi = sm(m.g(2)).toInt()
            val ampm = m.g(3)
            if (mi == 0 && ampm.isEmpty() && search("""^\s*(noon|midnight)""", m.after())) {
                return@sub cardinal(h)
            }
            if (mi == 0 && ampm.isEmpty() && (h > 12 || h == 0L)) {
                val hour = if (h == 0L) "zero" else cardinal(h)
                return@sub "$hour hundred"
            }
            val minutes = when {
                mi == 0 -> if (ampm.isEmpty()) " o'clock" else ""
                mi < 10 -> " oh ${ONES[mi]}"
                else -> " ${cardinal(mi.toLong())}"
            }
            val suffix = if (ampm.isEmpty()) "" else " ${upper(ampm.replace(".", ""))}"
            "${cardinal(h)}$minutes$suffix"
        }
        t = sub("""\b(\d{1,2})\s*([aApP])\.?[mM](?:\.(?=\s+[a-z]))?(?=\W|$)""", t) { m ->
            "${cardinal(int(m.g(1)))} ${upper(m.g(2))}M"
        }
        t = sub("""\b(\d{1,2})/(\d{1,2})/(\d{4}|\d{2})\b""", t) { m ->
            val mo = sm(m.g(1))
            val d = sm(m.g(2))
            val y = int(m.g(3)).add(if (count(m.g(3)) == 2) big(2000) else BigInteger.ZERO)
            if ((mo > 12 || order == DateOrder.DAY_FIRST) && mo in 1..31 && d in 1..12) {
                return@sub "the ${ordinal(mo)} of ${MONTHS[(d - 1).toInt()]} ${year(y)}"
            }
            if (!(mo in 1..12 && d in 1..31)) return@sub m.whole()
            "${MONTHS[(mo - 1).toInt()]} ${ordinal(d)} ${year(y)}"
        }
        t = sub(MONTH_DAY, t) { m -> "${m.g(1)} ${ordinal(int(m.g(2)))}" }
        t = tpl("""(\d)\s?°\s?C\b""", t, """\1 degrees Celsius""")
        t = tpl("""(\d)\s?°\s?F\b""", t, """\1 degrees Fahrenheit""")
        t = tpl("""(\d)\s?°""", t, """\1 degrees""")
        t = tpl("""(?<=\w)/(?=\w)""", t, " slash ")
        t = sub("""\b(\d+)(st|nd|rd|th)\b""", t) { m -> ordinal(int(m.g(1))) }
        t = sub("""\b(1[1-9]\d\d|20\d\d)\b(?![.,]\d)""", t) { m -> year(int(m.g(1))) }
        t = tpl("""(?<![\w-])-(?=\d)""", t, "minus ")
        t = tpl("""(?<=[A-Za-z])(?=\d)""", t, " ")
        t = sub("""(\d+(?:\.\d+)?)\s?%""", t) { m -> "${number(m.g(1))} percent" }
        t = sub("""\b\d{1,3}(?:,\d{3})+(?:\.\d+)?\b|\b\d+(?:\.\d+)?\b""", t) { m -> number(m.g(0)) }
        return t.replace("&", " and ").replace("+", " plus ").replace("=", " equals ")
            .replace("@", " at ").replace("#", " number ")
    }

    /**
     * The order every slashed date in [text] should use, so one text never reads
     * 09/10/2026 month first and 12/10 day first. A date that only works one way
     * (14/03, 12-25-1999) settles it; otherwise the setting decides.
     */
    private fun resolveDateOrder(text: String, preferred: DateOrder): DateOrder {
        var found: DateOrder? = null
        forEachMatch(
            """(?<![\d/.,])\b(\d{1,2})/(\d{1,2})(?:/\d{2,4})?\b(?!/\d)|\b(\d{1,2})-(\d{1,2})-(?:19|20)\d\d\b""",
            text,
        ) { m ->
            if (found != null) return@forEachMatch
            val first = sm(m.g(1).ifEmpty { m.g(3) })
            val second = sm(m.g(2).ifEmpty { m.g(4) })
            if (first in 13..31 && second in 1..12) {
                found = DateOrder.DAY_FIRST
            } else if (second in 13..31 && first in 1..12) {
                found = DateOrder.MONTH_FIRST
            }
        }
        return found ?: preferred
    }

    // MARK: - Respelling

    /** `written<TAB>respelling<TAB>flags` (`c` = only when capitalized as written). */
    private const val LEXICON_RESOURCE = "/app/readly/text/respell.tsv"

    private class Lexicon(val entries: Map<String, Pair<String, Boolean>>, val maxWords: Int)

    private val lexicon: Lexicon by lazy {
        val stream = EnglishNormalizer::class.java.getResourceAsStream(LEXICON_RESOURCE)
            ?: throw IllegalStateException("Missing name lexicon resource $LEXICON_RESOURCE")
        val entries = HashMap<String, Pair<String, Boolean>>()
        var maxWords = 1
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < 2) continue
                val key = fold(fields[0])
                maxWords = maxOf(maxWords, key.count { it == ' ' } + 1)
                val caps = fields.getOrNull(2)?.contains('c') == true
                entries[key] = joinSyllables(fields[1]) to caps
            }
        }
        Lexicon(entries, maxWords)
    }

    /**
     * The lexicon spells names in syllables (Bah-gahn), but voices pause at each hyphen
     * and read the parts as separate words, so they get one word (Bahgahn).
     */
    internal fun joinSyllables(spoken: String): String = words(spoken).joinToString(" ") { word ->
        val bare = word.replace("-", "")
        if (bare.isEmpty()) {
            ""
        } else {
            val first = bare.codePointAt(0)
            cpString(first) + lower(bare.substring(Character.charCount(first)))
        }
    }

    private fun isCombiningMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** Lookup key: no accents, lower case, straight apostrophes, hyphens as spaces. */
    private fun fold(s: String): String {
        val straight = s.replace('’', '\'').replace('‘', '\'')
        val bare = StringBuilder()
        Normalizer.normalize(straight, Normalizer.Form.NFKD).codePoints().forEach { cp ->
            if (!isCombiningMark(cp)) bare.appendCodePoint(cp)
        }
        val lowered = lower(bare.toString()).replace("ł", "l").replace("ø", "o").replace("æ", "ae")
            .replace("œ", "oe").replace("ß", "ss").replace("đ", "d")
        return words(lowered.replace('-', ' ').replace('‐', ' ').replace('–', ' ')).joinToString(" ")
    }

    /**
     * A word joined to another by a hyphen matches alone only when it is a capitalized
     * name and the other part is lower case (Worcestershire-glazed).
     */
    private fun hyphenOk(t: String, a: Int, b: Int, src: String): Boolean {
        val nameLike = firstIsUpper(src)
        val joinedBefore = a >= 2 && t[a - 1] == '-' && Character.isAlphabetic(t.codePointBefore(a - 1))
        if (joinedBefore) {
            val other = words(t.substring(0, a - 1)).lastOrNull() ?: ""
            if (!(nameLike && firstIsLower(other))) return false
        }
        val joinedAfter = b + 1 < t.length && t[b] == '-' && Character.isAlphabetic(t.codePointAt(b + 1))
        if (joinedAfter) {
            val other = words(t.substring(b + 1)).firstOrNull() ?: ""
            if (!(nameLike && firstIsLower(other))) return false
        }
        return true
    }

    /** Rust `[^\W\d_]`: word characters that are not digits or the underscore. */
    private const val LETTER = """[\p{IsAlphabetic}\p{M}\u203F\u2040\u2054\uFE33\uFE34\uFE4D-\uFE4F\uFF3F\u200C\u200D]"""

    /**
     * Respell hard names and foreign words: longest entry wins, whole words only, and a
     * possessive 's survives (Eoin's is Owen's).
     */
    private fun respell(text: String): String {
        val lex = lexicon
        if (lex.entries.isEmpty()) return text
        val t = text.replace('’', '\'')
        val spans = mutableListOf<IntArray>()
        val matcher = Pattern.compile("$LETTER+(?:'$LETTER+)*").matcher(t)
        while (matcher.find()) spans += intArrayOf(matcher.start(), matcher.end())
        val out = StringBuilder(t.length)
        var pos = 0
        var i = 0
        while (i < spans.size) {
            var matched = false
            for (n in minOf(lex.maxWords, spans.size - i) downTo 1) {
                val span = spans.subList(i, i + n)
                if ((0 until n - 1).any { k -> t.substring(span[k][1], span[k + 1][0]) !in setOf(" ", "-") }) continue
                val a = span[0][0]
                val b = span[n - 1][1]
                val src = t.substring(a, b)
                if (!hyphenOk(t, a, b, src)) continue
                val key = fold(src)
                val direct = lex.entries[key]
                val hit: Pair<String, Boolean>
                val tail: String
                if (direct != null) {
                    hit = direct
                    tail = ""
                } else {
                    val stem = if (key.endsWith("'s")) lex.entries[key.dropLast(2)] else null
                    if (stem == null) continue
                    hit = stem
                    tail = src.substring(src.length - 2)
                }
                val (spoken, caps) = hit
                if (caps && !span.all { s -> Character.isUpperCase(t.codePointAt(s[0])) }) continue
                out.append(t, pos, a)
                out.append(if (firstIsUpper(src) && firstIsLower(spoken)) capitalizeFirst(spoken) else spoken)
                out.append(tail)
                pos = b
                i += n
                matched = true
                break
            }
            if (!matched) i++
        }
        out.append(t, pos, t.length)
        return out.toString()
    }
}

/**
 * Where [EnglishNormalizer] reports a skipped rule. Pure Kotlin so JVM tests run without
 * Android; the app points it at `Log.w` (see `ReadlyApp`).
 */
object NormalizerLog {
    @Volatile
    var warnSink: (String) -> Unit = { message -> System.err.println("warn: $message") }

    @Volatile
    var errorSink: (String, Throwable) -> Unit = { message, e -> System.err.println("error: $message ($e)") }

    fun warn(message: String) {
        try {
            warnSink(message)
        } catch (e: Exception) {
            System.err.println("warn: $message (log sink failed: ${e.javaClass.name})")
        }
    }

    fun error(message: String, cause: Throwable) {
        try {
            errorSink(message, cause)
        } catch (e: Exception) {
            System.err.println("error: $message (log sink failed: ${e.javaClass.name})")
        }
    }
}
