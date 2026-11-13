package app.readly.text

import java.text.BreakIterator
import java.util.Locale

/** One speakable piece of [SentenceChunker] input, with offsets into that input. */
data class Chunk(val text: String, val start: Int, val end: Int)

/**
 * Splits sanitized text into chunks for sentence-by-sentence synthesis.
 *
 * Pure Kotlin. Chunk text is always the trimmed slice `input.substring(start, end)`, so
 * offsets round-trip exactly. Chunks never cross a paragraph break.
 */
object SentenceChunker {

    /** First sentence this long or shorter is spoken whole, so audio starts fast. */
    private const val FIRST_WHOLE_MAX = 80
    /** A first sentence shorter than this is merged forward so its audio isn't tiny. */
    private const val FIRST_MERGE_MIN = 20
    /** Over-long first sentence: cut after the last `, ; : —` in this offset window. */
    private const val FIRST_PUNCT_MIN = 25
    private const val FIRST_PUNCT_MAX = 80
    /** …else at the last space before this offset, else a hard cut here (~2 s of audio). */
    private const val FIRST_FALLBACK_MAX = 60
    private const val MERGE_UNDER = 40
    private const val MERGE_MAX = 250
    private const val SPLIT_OVER = 300
    /** Titles: always glue, whatever case follows (never speak "Mr." alone). */
    private val titleEnd = Regex("""\b(Mr|Mrs|Ms|Dr|Prof|St|Sr|Jr|Mt|No)\.$""")
    private val abbreviationEnd = Regex("""(?i)\b(e\.g|i\.e|vs|etc)\.$""")
    private val paragraphBreak = Regex("""\r?\n[ \t]*\r?\n""")
    private val splitPunctEn = setOf(',', ';', ':', '—')

    /**
     * @param lang BCP 47 language (`en`, `fa`, `ar`): picks the sentence-break
     *   locale, adds `،` and `؛` as split points for fa/ar, and disables the
     *   English abbreviation glue for other languages.
     */
    fun chunk(input: String, lang: String = "en"): List<Chunk> {
        if (input.isBlank()) return emptyList()
        val clauseMarksOnly = lang == "fa" || lang == "ar"
        val splitPunct = if (clauseMarksOnly) {
            splitPunctEn + '،' + '؛'
        } else {
            splitPunctEn
        }
        val out = mutableListOf<Chunk>()
        var isFirst = true
        for ((paraStart, paraEnd) in paragraphs(input)) {
            var sentences = splitSentences(input, paraStart, paraEnd, lang)
            if (sentences.isEmpty()) continue
            if (isFirst) {
                isFirst = false
                val (firstStart, firstEnd) = sentences[0]
                if (firstEnd - firstStart < FIRST_MERGE_MIN && sentences.size > 1) {
                    // Tiny first sentence (e.g. "سلام!"): merge following sentences
                    // from the same paragraph while the total stays <= 80 chars, so
                    // the first audio isn't shorter than the second synth (underrun).
                    val start = firstStart
                    var included = 0
                    var cut: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null
                    var i = 1
                    while (i < sentences.size) {
                        if (sentences[i].second - start <= FIRST_WHOLE_MAX) {
                            included = i
                            i++
                        } else {
                            cut = cutFirst(input, start, sentences[i].second, splitPunct, clauseMarksOnly)
                            break
                        }
                    }
                    if (cut != null) {
                        val (head, rest) = cut
                        out.add(Chunk(input.substring(head.first, head.second), head.first, head.second))
                        sentences = listOf(rest) + sentences.drop(i + 1)
                    } else {
                        val end = sentences[included].second
                        out.add(Chunk(input.substring(start, end), start, end))
                        sentences = sentences.drop(included + 1)
                    }
                } else if (firstEnd - firstStart > FIRST_WHOLE_MAX) {
                    val cut = cutFirst(input, firstStart, firstEnd, splitPunct, clauseMarksOnly)
                    if (cut != null) {
                        val (head, rest) = cut
                        out.add(Chunk(input.substring(head.first, head.second), head.first, head.second))
                        sentences = listOf(rest) + sentences.drop(1)
                    }
                    // With no clause mark to cut at (fa/ar), the sentence is
                    // left whole and goes through splitLong below like every
                    // later sentence.
                } else {
                    out.add(Chunk(input.substring(firstStart, firstEnd), firstStart, firstEnd))
                    sentences = sentences.drop(1)
                }
            }
            val pieces = sentences.flatMap { splitLong(input, it.first, it.second, splitPunct) }
            for ((start, end) in merge(pieces)) {
                out.add(Chunk(input.substring(start, end), start, end))
            }
        }
        return out
    }

    private fun paragraphs(input: String): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (match in paragraphBreak.findAll(input)) {
            trimAdd(input, start, match.range.first, out)
            start = match.range.last + 1
        }
        trimAdd(input, start, input.length, out)
        return out
    }

    private fun splitSentences(
        input: String,
        paraStart: Int,
        paraEnd: Int,
        lang: String,
    ): List<Pair<Int, Int>> {
        val raw = mutableListOf<Pair<Int, Int>>()
        val locale = if (lang == "en") Locale.US else Locale.forLanguageTag(lang)
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(input.substring(paraStart, paraEnd))
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (lang == "fa" || lang == "ar") {
                // ICU breaks at `.` but not at `؟`/`؛` (probed on the host JVM),
                // so split those explicitly, keeping the mark with its sentence.
                var cut = paraStart + start
                val segEnd = paraStart + end
                var i = cut
                while (i < segEnd) {
                    if (input[i] == '؟' || input[i] == '؛') {
                        trimAdd(input, cut, i + 1, raw)
                        cut = i + 1
                    }
                    i++
                }
                trimAdd(input, cut, segEnd, raw)
            } else {
                trimAdd(input, paraStart + start, paraStart + end, raw)
            }
            start = end
            end = iterator.next()
        }
        // Undo false breaks after abbreviations ("e.g. red", "etc. 12") and titles
        // ("Mr. Smith" is one sentence no matter what follows "Mr.").
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < raw.size) {
            var (startOffset, endOffset) = raw[i]
            while (i + 1 < raw.size &&
                shouldGlue(input.substring(startOffset, endOffset), input[raw[i + 1].first], lang)
            ) {
                endOffset = raw[i + 1].second
                i++
            }
            out.add(startOffset to endOffset)
            i++
        }
        return out
    }

    private fun shouldGlue(current: String, nextFirst: Char, lang: String): Boolean =
        lang == "en" && (
            titleEnd.containsMatchIn(current) ||
                (abbreviationEnd.containsMatchIn(current) && (nextFirst.isLowerCase() || nextFirst.isDigit()))
            )

    /** True when the clause mark at [i] ends a clause: whitespace or [end] follows it. */
    private fun isClauseCut(input: String, i: Int, end: Int): Boolean =
        i + 1 >= end || input[i + 1].isWhitespace()

    /**
     * Cuts an over-long first sentence: after the last `, ; : —` (plus `،`
     * and `؛` for fa/ar) in the search window (the chunk keeps the
     * punctuation), else at the last space before 60, else a hard cut at
     * 60. The rest becomes the start of chunk 2 under the normal rules.
     * A clause mark counts as a cut point only when the next character is
     * whitespace or the end of the sentence, so the `:` in `8:30` and the
     * `,` in `1,250` never cut.
     *
     * For fa/ar ([clauseMarksOnly]) a cut is made only at a clause mark
     * from offset [FIRST_MERGE_MIN] - 1 to [FIRST_PUNCT_MAX], so the head
     * is at least [FIRST_MERGE_MIN] chars long and never a tiny fragment.
     * With no clause mark there this returns null and the caller leaves
     * the sentence whole for [splitLong] instead of cutting it mid-phrase
     * at a space. Other languages search from [FIRST_PUNCT_MIN].
     */
    private fun cutFirst(
        input: String,
        start: Int,
        end: Int,
        splitPunct: Set<Char>,
        clauseMarksOnly: Boolean,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        if (clauseMarksOnly) {
            val from = start + FIRST_MERGE_MIN - 1
            val to = minOf(start + FIRST_PUNCT_MAX, end - 1)
            var punct = -1
            var i = from
            while (i <= to) {
                if (input[i] in splitPunct && isClauseCut(input, i, end)) punct = i
                i++
            }
            if (punct < from) return null
            return (start to trimEnd(input, start, punct + 1)) to
                (trimStart(input, punct + 1, end) to end)
        }
        val from = start + FIRST_PUNCT_MIN
        val to = minOf(start + FIRST_PUNCT_MAX, end - 1)
        var punct = -1
        var i = from
        while (i <= to) {
            if (input[i] in splitPunct && isClauseCut(input, i, end)) punct = i
            i++
        }
        if (punct >= from) {
            return (start to trimEnd(input, start, punct + 1)) to
                (trimStart(input, punct + 1, end) to end)
        }
        val space = input.lastIndexOf(' ', start + FIRST_FALLBACK_MAX - 1)
        if (space > start) {
            return (start to trimEnd(input, start, space)) to
                (trimStart(input, space + 1, end) to end)
        }
        val cut = start + FIRST_FALLBACK_MAX
        return (start to trimEnd(input, start, cut)) to (trimStart(input, cut, end) to end)
    }

    /** Splits a sentence over 300 chars at `, ; : —` (plus `،` for fa/ar), else the last space, else hard. */
    private fun splitLong(
        input: String,
        start: Int,
        end: Int,
        splitPunct: Set<Char>,
    ): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var current = start
        while (end - current > SPLIT_OVER) {
            val limit = current + SPLIT_OVER
            var punct = -1
            var i = current
            while (i < limit && i < end) {
                if (input[i] in splitPunct && isClauseCut(input, i, end)) punct = i
                i++
            }
            if (punct >= current) {
                out.add(current to trimEnd(input, current, punct + 1))
                current = trimStart(input, punct + 1, end)
            } else {
                val space = input.lastIndexOf(' ', limit - 1)
                if (space <= current) {
                    out.add(current to trimEnd(input, current, limit))
                    current = trimStart(input, limit, end)
                } else {
                    out.add(current to trimEnd(input, current, space))
                    current = trimStart(input, space + 1, end)
                }
            }
        }
        out.add(current to end)
        return out
    }

    private fun merge(pieces: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < pieces.size) {
            var (start, end) = pieces[i]
            while (end - start < MERGE_UNDER && i + 1 < pieces.size &&
                pieces[i + 1].second - start <= MERGE_MAX
            ) {
                i++
                end = pieces[i].second
            }
            out.add(start to end)
            i++
        }
        return out
    }

    private fun trimAdd(input: String, start: Int, end: Int, out: MutableList<Pair<Int, Int>>) {
        val trimmedStart = trimStart(input, start, end)
        val trimmedEnd = trimEnd(input, trimmedStart, end)
        if (trimmedStart < trimmedEnd) out.add(trimmedStart to trimmedEnd)
    }

    private fun trimStart(input: String, start: Int, end: Int): Int {
        var result = start
        while (result < end && input[result].isWhitespace()) result++
        return result
    }

    private fun trimEnd(input: String, start: Int, end: Int): Int {
        var result = end
        while (result > start && input[result - 1].isWhitespace()) result--
        return result
    }
}
