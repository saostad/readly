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
    private val splitPunct = setOf(',', ';', ':', '—')

    fun chunk(input: String): List<Chunk> {
        if (input.isBlank()) return emptyList()
        val out = mutableListOf<Chunk>()
        var isFirst = true
        for ((paraStart, paraEnd) in paragraphs(input)) {
            var sentences = splitSentences(input, paraStart, paraEnd)
            if (sentences.isEmpty()) continue
            if (isFirst) {
                isFirst = false
                val (firstStart, firstEnd) = sentences[0]
                if (firstEnd - firstStart > FIRST_WHOLE_MAX) {
                    val (head, rest) = cutFirst(input, firstStart, firstEnd)
                    out.add(Chunk(input.substring(head.first, head.second), head.first, head.second))
                    sentences = listOf(rest) + sentences.drop(1)
                } else {
                    out.add(Chunk(input.substring(firstStart, firstEnd), firstStart, firstEnd))
                    sentences = sentences.drop(1)
                }
            }
            val pieces = sentences.flatMap { splitLong(input, it.first, it.second) }
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

    private fun splitSentences(input: String, paraStart: Int, paraEnd: Int): List<Pair<Int, Int>> {
        val raw = mutableListOf<Pair<Int, Int>>()
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(input.substring(paraStart, paraEnd))
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            trimAdd(input, paraStart + start, paraStart + end, raw)
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
                shouldGlue(input.substring(startOffset, endOffset), input[raw[i + 1].first])
            ) {
                endOffset = raw[i + 1].second
                i++
            }
            out.add(startOffset to endOffset)
            i++
        }
        return out
    }

    private fun shouldGlue(current: String, nextFirst: Char): Boolean =
        titleEnd.containsMatchIn(current) ||
            (abbreviationEnd.containsMatchIn(current) && (nextFirst.isLowerCase() || nextFirst.isDigit()))

    /**
     * Cuts an over-long first sentence: after the last `, ; : —` at offset 25..80
     * (the chunk keeps the punctuation), else at the last space before 60, else a
     * hard cut at 60. The rest becomes the start of chunk 2 under the normal rules.
     */
    private fun cutFirst(input: String, start: Int, end: Int): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val from = start + FIRST_PUNCT_MIN
        val to = minOf(start + FIRST_PUNCT_MAX, end - 1)
        var punct = -1
        var i = from
        while (i <= to) {
            if (input[i] in splitPunct) punct = i
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

    /** Splits a sentence over 300 chars at `, ; : —`, else the last space, else hard. */
    private fun splitLong(input: String, start: Int, end: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var current = start
        while (end - current > SPLIT_OVER) {
            val limit = current + SPLIT_OVER
            var punct = -1
            var i = current
            while (i < limit && i < end) {
                if (input[i] in splitPunct) punct = i
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
