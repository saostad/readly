package app.readly.tts.pocket

import java.text.Normalizer

/**
 * Persian text normalization for Pocket TTS Farsi v1: a line-by-line port of
 * `normalize()` in the model's `normalize_fa.py` (mehdi-hf/pocket-tts-farsi), which is
 * exactly what the training transcripts went through. The model only knows Persian
 * letters, ZWNJ, spaces and `.،؛؟!:`, so everything else is folded or dropped:
 * Arabic letter forms become Persian ones, diacritics go, digits become words
 * ("۱۴۰۲" → "هزار و چهارصد و دو"), and Latin text is dropped.
 *
 * Checked against the Python original on every test sentence
 * (`src/test/resources/pocket/fixtures.json`).
 */
object NormalizeFa {
    private const val ZWNJ = '‌'
    private const val PERSIAN_LETTERS =
        "آابپتثجچحخدذرز" +
            "ژسشصضطظعغفقکگ" +
            "لمنوهیئ"
    private const val KEPT_PUNCT = ".،؛؟!:"

    private val allowed: Set<Char> = (PERSIAN_LETTERS + ZWNJ + KEPT_PUNCT + " ").toSet()

    private val charMap: Map<Char, String> = mapOf(
        'ي' to "ی", 'ى' to "ی", 'ۍ' to "ی", 'ې' to "ی",
        'ك' to "ک", 'ڪ' to "ک",
        'ة' to "ه", 'ۀ' to "ه", 'ۂ' to "ه", 'ۃ' to "ه",
        'أ' to "ا", 'إ' to "ا", 'ٱ' to "ا", 'ٲ' to "ا",
        'ٳ' to "ا",
        'ؤ' to "و",
        'ء' to "", // bare hamza: not spoken on its own
        'ـ' to "", // tatweel
    )

    private val punctMap: Map<Char, String> = buildMap {
        put(',', "،"); put('?', "؟"); put(';', "؛")
        put('٬', ""); put('٫', "."); put('…', ".")
        for (c in "»«“”‘’\"'`()[]{}–—−-‐/\\*_|×=+") {
            put(c, " ")
        }
        put('،', "،"); put('؛', "؛"); put('؟', "؟")
    }

    private val diacritics = Regex("[ً-ٰٕۖ-ۭٖ-ٟ]")
    private val invisibles = Regex("[​‍‎‏‪-‮⁦-⁩﻿­]")
    private val thousandsComma = Regex("(?U)(?<=\\d),(?=\\d\\d\\d\\b)")
    private val number = Regex("([0-9]+)(?:[.]([0-9]+))?")
    private val percent = Regex("[٪%]")
    private val zwnjRun = Regex("$ZWNJ+")
    private val spacedZwnj = Regex("(?U)\\s*$ZWNJ\\s*")
    private val strayZwnj = Regex("(?<![$PERSIAN_LETTERS])$ZWNJ|$ZWNJ(?![$PERSIAN_LETTERS])")
    private val spaceBeforePunct = Regex("(?U)\\s+([$KEPT_PUNCT])")
    private val repeatedPunct = Regex("([.،؛؟!:])\\1+")
    private val whitespace = Regex("(?U)\\s+")

    private val ones = listOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
    private val teens = listOf(
        "ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده",
    )
    private val tens = listOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val hundreds = listOf(
        "", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد",
    )
    private val scales = listOf(
        1_000_000_000_000L to "تریلیون",
        1_000_000_000L to "میلیارد",
        1_000_000L to "میلیون",
        1_000L to "هزار",
    )
    private val fractionUnits = mapOf(1 to "دهم", 2 to "صدم", 3 to "هزارم")
    private const val JOIN = " و "
    private const val MAX_NUMBER_DIGITS = 12

    /** Folds [text] onto the spelling the model was trained on. */
    fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFC)
        t = invisibles.replace(t, "")
        t = diacritics.replace(t, "")
        t = mapChars(t, charMap)
        t = foldDigits(t)
        t = t.replace("٬", "").replace("٫", ".")
        t = percent.replace(t, " درصد ")
        t = numbersToWords(t)
        t = mapChars(t, punctMap)
        t = buildString(t.length) { for (c in t) append(if (c in allowed) c else ' ') }
        t = zwnjRun.replace(t, ZWNJ.toString())
        t = spacedZwnj.replace(t) { if (' ' in it.value) " " else ZWNJ.toString() }
        t = strayZwnj.replace(t, "")
        t = spaceBeforePunct.replace(t, "$1")
        t = repeatedPunct.replace(t, "$1")
        t = whitespace.replace(t, " ").trim()
        return t.trimStart { it in KEPT_PUNCT || it == ' ' }
    }

    /** A non-negative integer as Persian words ("1402" → "هزار و چهارصد و دو"). */
    fun numberToWords(value: Long): String {
        if (value == 0L) return "صفر"
        var n = value
        val parts = mutableListOf<String>()
        for ((scale, name) in scales) {
            if (n >= scale) {
                val count = n / scale
                n %= scale
                val head = if (count == 1L && scale == 1_000L) "" else underThousand(count.toInt()) + " "
                parts += "$head$name".trim()
            }
        }
        if (n > 0) parts += underThousand(n.toInt())
        return parts.joinToString(JOIN)
    }

    private fun underThousand(value: Int): String {
        var n = value
        val parts = mutableListOf<String>()
        if (n >= 100) {
            parts += hundreds[n / 100]
            n %= 100
        }
        if (n >= 20) {
            parts += tens[n / 10]
            n %= 10
        }
        if (n in 10..19) {
            parts += teens[n - 10]
            n = 0
        }
        if (n > 0) parts += ones[n]
        return parts.joinToString(JOIN)
    }

    private fun digitsOneByOne(digits: String): String =
        digits.map { if (it == '0') "صفر" else ones[it - '0'] }.joinToString(" ")

    private fun numberTokenToWords(wholeIn: String, fracIn: String?): String {
        if (wholeIn.length > MAX_NUMBER_DIGITS || (wholeIn.startsWith("0") && wholeIn.length > 1)) {
            val fracPart = if (!fracIn.isNullOrEmpty()) " " + digitsOneByOne(fracIn) else ""
            return " " + digitsOneByOne(wholeIn) + fracPart + " "
        }
        val whole = wholeIn.trimStart('0').ifEmpty { "0" }
        var words = numberToWords(whole.toLong())
        if (!fracIn.isNullOrEmpty()) {
            val frac = fracIn.trimEnd('0')
            if (frac.isEmpty()) return " $words "
            val unit = fractionUnits[frac.length]
            words += if (unit != null) {
                " ممیز " + numberToWords(frac.toLong()) + " " + unit
            } else {
                " ممیز " + digitsOneByOne(frac)
            }
        }
        return " $words "
    }

    private fun numbersToWords(text: String): String {
        val t = thousandsComma.replace(text, "")
        return number.replace(t) { numberTokenToWords(it.groupValues[1], it.groups[2]?.value) }
    }

    private fun foldDigits(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                },
            )
        }
    }

    private fun mapChars(text: String, map: Map<Char, String>): String = buildString(text.length) {
        for (c in text) {
            val mapped = map[c]
            if (mapped != null) append(mapped) else append(c)
        }
    }
}
