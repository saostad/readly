package app.readly.text

/**
 * Persian text expansions for numbers with punctuation, done before the
 * Persian normalizer turns digits into words. Pure: no I/O, no model.
 *
 * A "digit" is ASCII `0-9`, Persian `۰-۹` or Arabic `٠-٩`. Numbers this
 * object rewrites are written back as ASCII digits without leading zeros.
 * A pattern only matches when it is not glued to another digit on either
 * side. Text that matches no rule is returned unchanged.
 *
 * Port of `src/fa_text.rs` in smart-voices.
 */
object FaText {

    private val JALALI_MONTHS = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    private val GREGORIAN_MONTHS = listOf(
        "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
        "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر",
    )

    private fun digitValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in '۰'..'۹' -> c.code - '۰'.code
        in '٠'..'٩' -> c.code - '٠'.code
        else -> null
    }

    private fun isDigit(c: Char): Boolean = digitValue(c) != null

    /** A maximal digit run: its end index, value, and leading-zero flag. */
    private class DigitRun(val end: Int, val value: Long, val leadingZero: Boolean)

    private fun digitRun(chars: List<Char>, start: Int): DigitRun? {
        if (start >= chars.size || !isDigit(chars[start])) return null
        var end = start
        var value = 0L
        while (end < chars.size) {
            val digit = digitValue(chars[end]) ?: break
            value = if (value > (Long.MAX_VALUE - 9) / 10) {
                Long.MAX_VALUE
            } else {
                value * 10 + digit
            }
            end++
        }
        return DigitRun(end, value, end - start > 1 && digitValue(chars[start]) == 0)
    }

    /** The run's digits as ASCII, without leading zeros (`"0"` for zero). */
    private fun normalizedDigits(chars: List<Char>, start: Int, end: Int): String {
        val ascii = StringBuilder()
        for (i in start until end) ascii.append('0' + (digitValue(chars[i]) ?: 0))
        val trimmed = ascii.toString().trimStart('0')
        return trimmed.ifEmpty { "0" }
    }

    private fun gluedBefore(chars: List<Char>, start: Int): Boolean =
        start > 0 && isDigit(chars[start - 1])

    private fun currencyWord(c: Char): String? = when (c) {
        '$' -> "دلار"
        '€' -> "یورو"
        '£' -> "پوند"
        '¥' -> "ین"
        '₹' -> "روپیه"
        '\uFDFC' -> "ریال"
        else -> null
    }

    private val MAGNITUDES = listOf("هزار", "میلیون", "میلیارد", "تریلیون")

    /** A magnitude word at [start], followed by a non-letter boundary. */
    private fun magnitudeAt(chars: List<Char>, start: Int): Pair<String, Int>? {
        for (word in MAGNITUDES) {
            val wordChars = word.toList()
            if (start + wordChars.size <= chars.size &&
                chars.subList(start, start + wordChars.size) == wordChars
            ) {
                val end = start + wordChars.size
                if (end >= chars.size || !chars[end].isLetter()) return word to end
            }
        }
        return null
    }

    private fun skipSpaces(chars: List<Char>, from: Int): Int {
        var index = from
        while (index < chars.size && chars[index].isWhitespace()) index++
        return index
    }

    /** Spoken degree suffix at [start] (`°C`, `℃`, `°F` or a bare `°`). */
    private fun degreeAt(chars: List<Char>, start: Int): Pair<String, Int>? =
        when (chars.getOrNull(start)) {
            '℃' -> " درجه سانتی\u200Cگراد" to start + 1
            '°' -> when (chars.getOrNull(start + 1)) {
                'C' -> " درجه سانتی\u200Cگراد" to start + 2
                'F' -> " درجه فارنهایت" to start + 2
                else -> " درجه" to start + 1
            }
            else -> null
        }

    /**
     * Try an abbreviation at [start] (whole token; optional spaces after
     * the inner dot; optional final dot).
     */
    private fun tryAbbreviation(chars: List<Char>, start: Int): Pair<String, Int>? {
        if (start > 0 && (chars[start - 1].isLetterOrDigit() || isDigit(chars[start - 1]))) {
            return null
        }
        if (chars.getOrNull(start + 1) != '.') return null
        var second = start + 2
        while (second < chars.size && chars[second].isWhitespace()) second++
        val expansion = when {
            chars[start] == 'ه' && chars.getOrNull(second) == 'ش' -> "هجری شمسی"
            chars[start] == 'ه' && chars.getOrNull(second) == 'ق' -> "هجری قمری"
            chars[start] == 'ق' && chars.getOrNull(second) == 'م' -> "قبل از میلاد"
            else -> return null
        }
        var end = second + 1
        if (chars.getOrNull(end) == '.') end++
        val after = chars.getOrNull(end)
        if (after != null && (after.isLetterOrDigit() || isDigit(after))) return null
        return expansion to end
    }

    private fun collapseDoubleSpaces(text: String): String {
        val result = StringBuilder(text.length)
        var previousSpace = false
        for (c in text) {
            if (c == ' ') {
                if (!previousSpace) result.append(c)
                previousSpace = true
            } else {
                result.append(c)
                previousSpace = false
            }
        }
        return result.toString()
    }

    /**
     * Try a date at [start] (`Y/M/D` or `Y-M-D`, same separator twice).
     * Returns the replacement (the original text when the shape matches
     * but the date is out of range) and the index just past the pattern.
     */
    private fun tryDate(chars: List<Char>, start: Int): Pair<String, Int>? {
        val year = digitRun(chars, start) ?: return null
        if (year.end - start != 4) return null
        val separator = chars.getOrNull(year.end) ?: return null
        if (separator != '/' && separator != '-') return null
        val month = digitRun(chars, year.end + 1) ?: return null
        if (month.end - (year.end + 1) > 2 || chars.getOrNull(month.end) != separator) {
            return null
        }
        val day = digitRun(chars, month.end + 1) ?: return null
        if (day.end - (month.end + 1) > 2) return null
        val original = chars.subList(start, day.end).joinToString("")
        if (month.value !in 1..12 || day.value !in 1..31) return original to day.end
        val monthName = when (year.value) {
            in 1200..1499 -> JALALI_MONTHS[(month.value - 1).toInt()]
            in 1900..2100 -> GREGORIAN_MONTHS[(month.value - 1).toInt()]
            else -> return original to day.end
        }
        return "${day.value} $monthName ${year.value}" to day.end
    }

    /** Try a time at [start] (`H:MM` or `H:MM:SS`). Same shape as [tryDate]. */
    private fun tryTime(chars: List<Char>, start: Int): Pair<String, Int>? {
        val hour = digitRun(chars, start) ?: return null
        if (hour.end - start > 2 || chars.getOrNull(hour.end) != ':') return null
        val minute = digitRun(chars, hour.end + 1) ?: return null
        if (minute.end - (hour.end + 1) != 2) return null
        var end = minute.end
        var second: DigitRun? = null
        if (chars.getOrNull(end) == ':' &&
            chars.getOrNull(end + 1)?.let { isDigit(it) } == true
        ) {
            val seconds = digitRun(chars, end + 1) ?: return null
            if (seconds.end - (end + 1) != 2) return null
            end = seconds.end
            second = seconds
        }
        val original = chars.subList(start, end).joinToString("")
        if (hour.value > 23 || minute.value > 59 || (second != null && second.value > 59)) {
            return original to end
        }
        if (minute.value == 0L && (second == null || second.value == 0L)) {
            return "${hour.value}" to end
        }
        val spoken = StringBuilder("${hour.value} و ${minute.value} دقیقه")
        if (second != null && second.value != 0L) {
            spoken.append(" و ${second.value} ثانیه")
        }
        return spoken.toString() to end
    }

    /**
     * Try a range at [start] (`A-B`, `-`/`–`/`—`, optional spaces around
     * it, both sides 1–4 digits with no leading zero). A dashed number
     * such as `0912-345-6789` or `978-3-16` is not a range.
     */
    private fun tryRange(chars: List<Char>, start: Int): Pair<String, Int>? {
        if (start > 0 && chars[start - 1] in listOf('-', '–', '—', '/')) return null
        val first = digitRun(chars, start) ?: return null
        if (first.end - start > 4 || first.leadingZero) return null
        var dash = first.end
        while (dash < chars.size && chars[dash].isWhitespace()) dash++
        if (chars.getOrNull(dash) !in listOf('-', '–', '—')) return null
        var secondStart = dash + 1
        while (secondStart < chars.size && chars[secondStart].isWhitespace()) secondStart++
        val second = digitRun(chars, secondStart) ?: return null
        if (second.end - secondStart > 4 || second.leadingZero) return null
        if (chars.getOrNull(second.end) in listOf('-', '–', '—', '/') &&
            chars.getOrNull(second.end + 1)?.let { isDigit(it) } == true
        ) {
            return null
        }
        return "${first.value} تا ${second.value}" to second.end
    }

    /** Try a Persian decimal at [start] (`A/B`, not followed by another `/`). */
    private fun tryDecimal(chars: List<Char>, start: Int): Pair<String, Int>? {
        val first = digitRun(chars, start) ?: return null
        if (chars.getOrNull(first.end) != '/') return null
        val second = digitRun(chars, first.end + 1) ?: return null
        if (chars.getOrNull(second.end) == '/') return null
        // The fraction digits keep their leading zeros: `3/05` is 3.05.
        val fraction = StringBuilder()
        for (i in first.end + 1 until second.end) {
            fraction.append('0' + (digitValue(chars[i]) ?: 0))
        }
        return "${normalizedDigits(chars, start, first.end)}.$fraction" to second.end
    }

    private fun isAsciiAlphanumeric(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'

    private fun isAsciiAlphabetic(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /**
     * Expand dates, times, ranges, signs, Persian decimals, currency,
     * symbols and abbreviations in Persian text. Text that matches no
     * rule is returned unchanged.
     */
    fun expand(text: String): String {
        val chars = text.toList()
        val result = StringBuilder(text.length)
        var symbolChanged = false
        var index = 0
        while (index < chars.size) {
            val c = chars[index]
            val abbreviation = tryAbbreviation(chars, index)
            if (abbreviation != null) {
                result.append(abbreviation.first)
                index = abbreviation.second
                continue
            }
            val currency = currencyWord(c)
            if (currency != null) {
                symbolChanged = true
                val numberStart = skipSpaces(chars, index + 1)
                val run = digitRun(chars, numberStart)
                if (run != null) {
                    val number = normalizedDigits(chars, numberStart, run.end)
                    // `$۵ میلیون` → `5 میلیون دلار`: the currency word
                    // goes after a following magnitude word.
                    val magnitude = if (chars.getOrNull(run.end) == ' ') {
                        magnitudeAt(chars, run.end + 1)
                    } else {
                        null
                    }
                    if (magnitude != null) {
                        result.append("$number ${magnitude.first} $currency")
                        index = magnitude.second
                    } else {
                        result.append("$number $currency")
                        index = run.end
                    }
                } else {
                    result.append(currency)
                    index += 1
                }
                continue
            }
            if (isDigit(c) && !gluedBefore(chars, index)) {
                val pattern = tryDate(chars, index)
                    ?: tryTime(chars, index)
                    ?: tryRange(chars, index)
                    ?: tryDecimal(chars, index)
                if (pattern != null) {
                    result.append(pattern.first)
                    index = pattern.second
                    continue
                }
                val run = digitRun(chars, index)!!
                val number = normalizedDigits(chars, index, run.end)
                // A currency symbol right after the number (optional space).
                val symbolAt = skipSpaces(chars, run.end)
                val afterSymbol = chars.getOrNull(symbolAt)?.let { currencyWord(it) }
                if (afterSymbol != null) {
                    symbolChanged = true
                    val after = skipSpaces(chars, symbolAt + 1)
                    val magnitude = if (symbolAt + 1 < after) {
                        magnitudeAt(chars, after)
                    } else {
                        null
                    }
                    if (magnitude != null) {
                        result.append("$number ${magnitude.first} $afterSymbol")
                        index = magnitude.second
                    } else {
                        result.append("$number $afterSymbol")
                        index = symbolAt + 1
                    }
                    continue
                }
                // A degree sign right after the number (optional space).
                val degree = degreeAt(chars, symbolAt)
                if (degree != null) {
                    symbolChanged = true
                    result.append(number)
                    result.append(degree.first)
                    index = degree.second
                    continue
                }
                // A number taking part in an operator expression is rewritten.
                if (chars.getOrNull(run.end) in listOf('+', '=', '×', '÷')) {
                    result.append(number)
                } else {
                    result.append(text.substring(index, run.end))
                }
                index = run.end
                continue
            }
            if ((c == '-' || c == '−') &&
                (index == 0 || chars[index - 1].isWhitespace() || chars[index - 1] == '(') &&
                chars.getOrNull(index + 1)?.let { isDigit(it) } == true
            ) {
                val run = digitRun(chars, index + 1)!!
                result.append("منفی ")
                result.append(normalizedDigits(chars, index + 1, run.end))
                index = run.end
                continue
            }
            val degree = degreeAt(chars, index)
            if (degree != null) {
                symbolChanged = true
                result.append(degree.first)
                index = degree.second
                continue
            }
            var handled = true
            when (c) {
                '×' -> result.append(" در ")
                '÷' -> result.append(" تقسیم بر ")
                '=' -> {
                    val next = chars.getOrNull(index + 1)
                    if (next != null && isDigit(next)) {
                        val run = digitRun(chars, index + 1)!!
                        result.append(" مساوی ")
                        result.append(normalizedDigits(chars, index + 1, run.end))
                        symbolChanged = true
                        index = run.end
                        continue
                    }
                    result.append(" مساوی ")
                }
                '+' -> {
                    val next = chars.getOrNull(index + 1)
                    if (next != null && isDigit(next)) {
                        val run = digitRun(chars, index + 1)!!
                        result.append(" به\u200Cعلاوه ")
                        result.append(normalizedDigits(chars, index + 1, run.end))
                        symbolChanged = true
                        index = run.end
                        continue
                    }
                    handled = false
                }
                '#' -> {
                    val next = chars.getOrNull(index + 1)
                    if (next != null && isDigit(next)) {
                        val run = digitRun(chars, index + 1)!!
                        result.append("شماره ")
                        result.append(normalizedDigits(chars, index + 1, run.end))
                        symbolChanged = true
                        index = run.end
                        continue
                    }
                    handled = false
                }
                '@' -> {
                    val prevOk = index > 0 && isAsciiAlphanumeric(chars[index - 1])
                    val next = chars.getOrNull(index + 1)
                    if (prevOk && next != null && isAsciiAlphanumeric(next)) {
                        result.append(" ات ")
                    } else {
                        handled = false
                    }
                }
                '.' -> {
                    val prev = if (index > 0) chars[index - 1] else null
                    val next = chars.getOrNull(index + 1)
                    if (prev != null && next != null &&
                        isAsciiAlphanumeric(prev) && isAsciiAlphanumeric(next) &&
                        (isAsciiAlphabetic(prev) || isAsciiAlphabetic(next))
                    ) {
                        result.append(" دات ")
                    } else {
                        handled = false
                    }
                }
                else -> handled = false
            }
            if (handled) {
                symbolChanged = true
                index += 1
                continue
            }
            result.append(c)
            index += 1
        }
        return if (symbolChanged) collapseDoubleSpaces(result.toString()) else result.toString()
    }
}
