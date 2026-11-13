package app.readly.tts.pocket

/**
 * v2 text front end: ports of `normalize_for_model`, the HARD_BREAK split,
 * `clean_sentence_for_g2p`, `translate_phonemes` and the ezafe-aware
 * `split_text`/`strip_ezafe` from the v2 reference
 * (`.agents/handoff/ref/v2/`: `normalize_fa_v2.py`, `synthesize.py`,
 * `pocket_helpers.py`).
 *
 * The v2 `normalize()` is identical to v1's, so normalization itself reuses
 * [NormalizeFa]; only the model wrapper and the phoneme helpers live here.
 * Pure Kotlin: no Android or ORT imports. Checked against the reference on
 * every golden row (`src/test/resources/pocket/v2-golden.jsonl`).
 */
object PocketTextV2 {
    /** Highest token budget per v2 chunk (reference `_synth_unit`). */
    const val MAX_TOKENS = 18

    /** Lowest token budget per v2 chunk (reference `_synth_unit`). */
    const val MIN_TOKENS = 8

    /** Homo-GE2PE marks the ezafe with a trailing "1" (reference `synthesize`). */
    const val EZAFE_MARK = '1'

    private val pythonBlank = Regex("${NormalizeFa.UNICODE_WS}*")
    private val hardBreak = Regex("(?<=[.!؟])${NormalizeFa.UNICODE_WS}+")
    private val softBreak = Regex("(?<=[،؛:])${NormalizeFa.UNICODE_WS}+")
    private val wordsSplit = Regex("${NormalizeFa.UNICODE_WS}+")
    private val stripLeading = Regex("^${NormalizeFa.UNICODE_WS}+")
    private val stripTrailing = Regex("${NormalizeFa.UNICODE_WS}+$")
    private val breakChars = setOf('.', '!', '؟', '،', '؛', ':')

    /**
     * Phoneme spellings (ezafe stripped, exact and case-sensitive) after
     * which a word cut is never allowed: conjunctions, prepositions,
     * determiners and similar words that bind to what follows.
     */
    private val NO_CUT_AFTER = setOf(
        "va", "?o", "ke", "dar", "be", "?az", "bA", "tA", "bar", "barAye", "yA", "?in", "?An", "yek",
        "har", "bAyad", "nabAyad", "SAyad", "xeyli", "besyAr", "qadr", "?inqadr", "?enqadr", "?Anqadr",
    )

    /**
     * Phoneme spellings (ezafe stripped, exact and case-sensitive) of
     * number words after which a word cut is never allowed: a number binds
     * to its unit or to the next part of a compound number that follows
     * (`haSt ?o si daqiqe`, `se momayyeze panj dahom`).
     */
    private val NO_CUT_AFTER_NUMBER = setOf(
        "sefr", "yek", "do", "se", "CahAr", "panj", "SeS", "haft", "haSt", "noh", "dah", "yAzdah",
        "davAzdah", "sizdah", "CahArdah", "pAnzdah", "SAnzdah", "hefdah", "hejdah", "nuzdah", "bist",
        "si", "Cehel", "panjAh", "Sast", "haftAd", "haStAd", "navad", "sad", "devist", "sisad",
        "CahArsad", "pAnsad", "SeSsad", "haftsad", "haStsad", "nohsad", "hezAr", "milyun", "milyArd",
        "momayyez",
    )

    /**
     * Phoneme spellings (ezafe stripped, exact and case-sensitive) before
     * which a word cut is never allowed: words that bind to what precedes.
     * (`?ast` is covered by [looksLikeFiniteVerb].)
     */
    private val NO_CUT_BEFORE = setOf("rA", "ro")

    /**
     * Phoneme spellings (ezafe stripped, exact and case-sensitive) before
     * which a word cut is preferred when a cut is needed: conjunctions and
     * clause openers. `va` and `yA` are not listed here: they join phrases
     * as often as clauses (e.g. `SomAli va qarbi`), so a cut before them is
     * preferred only after a finite verb (see [prefersCut]).
     */
    private val PREFER_CUT_BEFORE = setOf(
        "ke", "?ammA", "vali", "tA", "Cun", "?agar", "zirA", "pas", "sepas",
    )

    /** Phoneme spellings (ezafe stripped, exact and case-sensitive) that are finite verb forms on their own. */
    private val FINITE_VERB_FORMS = setOf(
        "?ast", "nist", "hast", "hastam", "hasti", "hastim", "hastid", "hastand",
        "nistam", "nistand", "bud", "budam", "budi", "budim", "budid", "budand",
        "Sod", "Sodam", "Sodi", "Sodim", "Sodid", "Sodand", "Sode",
        "Savad", "Savam", "Savim", "Savid", "Savand",
        "kon", "konam", "koni", "konad", "konim", "konid", "konand",
        "kard", "kardam", "kardi", "kardim", "kardid", "kardand", "karde",
        "dAd", "dAdam", "dAdand", "dAram", "dAri", "dArad", "dArim", "dArid", "dArand",
        "dASt", "dAStam", "dAStand", "zad", "zadam", "zadand",
        "gereft", "gereftam", "gereftand", "goft", "goftam", "goftand",
        "raft", "raftam", "raftand", "?Amad", "?Amadam", "?Amadand",
        "bASad", "bASam", "bASim", "bASid", "bASand",
    )

    /**
     * True when [word] (ezafe stripped) looks like a finite verb: it starts
     * with `mi` or `nemi` and has more than 3 chars, ends with `ad` or `and`
     * and has more than 3 chars, has at least 5 chars and starts with `be`,
     * `bo` or `na` while ending with `am`, `i`, `ad`, `im`, `id` or `and`,
     * or is one of [FINITE_VERB_FORMS].
     */
    internal fun looksLikeFiniteVerb(word: String): Boolean {
        val stripped = stripEzafe(word)
        val len = stripped.length
        return (len > 3 &&
            (stripped.startsWith("mi") || stripped.startsWith("nemi") ||
                stripped.endsWith("ad") || stripped.endsWith("and"))) ||
            (len >= 5 &&
                (stripped.startsWith("be") || stripped.startsWith("bo") || stripped.startsWith("na")) &&
                listOf("am", "i", "ad", "im", "id", "and").any { stripped.endsWith(it) }) ||
            stripped in FINITE_VERB_FORMS
    }

    /**
     * True when a cut between [prev] and [next] is allowed: [prev] carries
     * no ezafe mark, its stripped spelling is not in [NO_CUT_AFTER] or
     * [NO_CUT_AFTER_NUMBER], [next]'s stripped spelling is not in
     * [NO_CUT_BEFORE], and [next] does not look like a finite verb (Persian
     * puts the verb last, so a cut right before it would strand it). A cut
     * is also not allowed after a word whose stripped spelling ends in a
     * vowel followed by `ye` (`?ostAnhAye`, `hameye`, `sedAye`, `buye`):
     * the model often leaves the ezafe mark off such words, but the `ye`
     * still binds the word to what follows.
     */
    internal fun isAllowedCut(prev: String, next: String): Boolean {
        val stripped = stripEzafe(prev)
        val endsVowelYe = stripped.length >= 3 &&
            stripped.endsWith("ye") &&
            stripped[stripped.length - 3] in setOf('a', 'e', 'o', 'A', 'i', 'u')
        return !prev.endsWith(EZAFE_MARK) &&
            !endsVowelYe &&
            stripped !in NO_CUT_AFTER &&
            stripped !in NO_CUT_AFTER_NUMBER &&
            stripEzafe(next) !in NO_CUT_BEFORE &&
            !looksLikeFiniteVerb(next)
    }

    /** True when the cut before `words[at]` is allowed. */
    private fun cutAllowed(words: List<String>, at: Int): Boolean =
        at > 0 && at < words.size && isAllowedCut(words[at - 1], words[at])

    /**
     * True when the cut before `words[at]` lands before a preferred word.
     * A cut before `va` or `yA` counts as preferred only when the previous
     * word looks like a finite verb ([looksLikeFiniteVerb]), because
     * those conjunctions join phrases as often as clauses.
     */
    private fun prefersCut(words: List<String>, at: Int): Boolean {
        if (at == 0 || at >= words.size) return false
        val next = stripEzafe(words[at])
        if (next in PREFER_CUT_BEFORE) return true
        return (next == "va" || next == "yA") && looksLikeFiniteVerb(words[at - 1])
    }

    /**
     * Best word segmentation of [words] into pieces within [maxTokens]
     * using only allowed cuts, or null when no such segmentation exists.
     * Best is, in order: fewest segments, fewest segments under
     * [minTokens], most cuts before a [PREFER_CUT_BEFORE] word, smallest
     * sum of squared (segment tokens − total/segments), then earliest cuts.
     * Token counts grow with segment length, so a segment is not extended
     * once it passes [maxTokens].
     */
    private fun searchWordSegments(
        words: List<String>,
        countTokens: (String) -> Int,
        maxTokens: Int,
        minTokens: Int,
    ): List<String>? {
        val n = words.size
        if (n == 0) return emptyList()
        // seg[i][j] = tokens in words[i..<j], null once over the budget.
        val seg: Array<Array<Int?>> = Array(n) { arrayOfNulls(n + 1) }
        for (i in 0 until n) {
            for (j in i + 1..n) {
                val tokens = countTokens(words.subList(i, j).joinToString(" "))
                if (tokens > maxTokens) break
                seg[i][j] = tokens
            }
        }
        val inf = Int.MAX_VALUE / 4
        val dpMin = IntArray(n + 1) { inf }
        dpMin[n] = 0
        for (i in n - 1 downTo 0) {
            for (j in i + 1..n) {
                if (seg[i][j] == null || (j < n && !cutAllowed(words, j)) || dpMin[j] == inf) continue
                dpMin[i] = minOf(dpMin[i], 1 + dpMin[j])
            }
        }
        if (dpMin[0] == inf) return null
        val total = countTokens(words.joinToString(" ")).toDouble()
        val average = total / dpMin[0]
        data class Best(val under: Int, val prefer: Int, val squared: Double, val next: Int)
        val best = arrayOfNulls<Best>(n + 1)
        best[n] = Best(0, 0, 0.0, n)
        for (i in n - 1 downTo 0) {
            var chosen: Best? = null
            for (j in i + 1..n) {
                val tokens = seg[i][j] ?: continue
                if (j < n && !cutAllowed(words, j)) continue
                if (dpMin[j] == inf || 1 + dpMin[j] != dpMin[i]) continue
                val suffix = best[j] ?: continue
                val candidate = Best(
                    under = (if (tokens < minTokens) 1 else 0) + suffix.under,
                    prefer = (if (j < n && prefersCut(words, j)) 1 else 0) + suffix.prefer,
                    squared = (tokens - average) * (tokens - average) + suffix.squared,
                    next = j,
                )
                val cur = chosen
                val better = cur == null ||
                    candidate.under < cur.under ||
                    (candidate.under == cur.under && candidate.prefer > cur.prefer) ||
                    (candidate.under == cur.under && candidate.prefer == cur.prefer &&
                        candidate.squared < cur.squared - 1e-9)
                if (better) chosen = candidate
            }
            best[i] = chosen
        }
        val out = mutableListOf<String>()
        var i = 0
        while (i < n) {
            val next = best[i]?.next ?: return null
            out += words.subList(i, next).joinToString(" ")
            i = next
        }
        return out
    }

    /**
     * True when [text] is already phonemes rather than Persian script: no
     * Persian letter, but some ASCII letter (reference `is_phonemic`).
     */
    fun isPhonemic(text: String): Boolean =
        text.none { it in NormalizeFa.PERSIAN_LETTERS } &&
            text.any { it in 'A'..'Z' || it in 'a'..'z' }

    /**
     * Normalise Persian input, or pass phonemes through untouched (reference
     * `normalize_for_model`). Throws when Persian input normalizes to empty,
     * so a silent empty generation never hides a broken pipeline.
     */
    fun normalizeForModel(text: String): String {
        if (isPhonemic(text)) return text
        val out = NormalizeFa.normalize(text)
        if (!pythonBlank.matches(text) && out.isEmpty()) {
            throw IllegalArgumentException(
                "normalisation emptied the text: \"${text.take(60)}\". If this is phoneme " +
                    "input, it was not recognised as such; if it is Persian, it is outside " +
                    "the alphabet.",
            )
        }
        return out
    }

    /**
     * Split normalized text into sentences on HARD_BREAK, stripped, empties
     * dropped (reference `synth_one` v2 branch).
     */
    fun splitSentences(normalized: String): List<String> =
        hardBreak.split(normalized).map { strip(it) }.filter { it.isNotEmpty() }

    /**
     * Split one sentence into clauses on the soft breaks, keeping the mark
     * on the clause it ends.
     */
    fun splitClauses(sentence: String): List<String> =
        softBreak.split(sentence).map { strip(it) }.filter { it.isNotEmpty() }

    /** ZWNJ (U+200C), inside Persian words such as «این‌قدر». */
    private const val ZWNJ = '\u200C'

    /** Clause-opening words: a window cut right before one is strong (see [g2pWindows]). */
    private val G2P_STRONG_AFTER = setOf("که", "تا", "اما", "ولی", "چون", "اگر", "زیرا")

    /**
     * Split [sentence] into pronunciation windows of at most
     * [windowChars] chars. A sentence that fits is one window. Otherwise
     * its clauses are halved repeatedly (see [halveWindowPart]) until
     * each part fits — a part with no space stays whole — and neighbouring
     * parts are then joined greedily, in order, while the joined text
     * (with one space) still fits. Joining all windows with single spaces
     * gives back the sentence's words in order.
     */
    internal fun g2pWindows(sentence: String, windowChars: Int): List<String> {
        if (sentence.length <= windowChars) {
            return if (sentence.isEmpty()) emptyList() else listOf(sentence)
        }
        val parts = splitClauses(sentence).toMutableList()
        if (parts.isEmpty()) return emptyList()
        while (true) {
            val at = parts.indexOfFirst { it.length > windowChars && ' ' in it }
            if (at < 0) break
            val part = parts.removeAt(at)
            val (left, right) = halveWindowPart(part)
            parts.add(at, right)
            parts.add(at, left)
        }
        val windows = mutableListOf<String>()
        for (part in parts) {
            val current = windows.lastOrNull()
            if (current != null) {
                val trial = "$current $part"
                if (trial.length <= windowChars) {
                    windows[windows.size - 1] = trial
                    continue
                }
            }
            windows += part
        }
        return windows
    }

    /**
     * Halve one over-long part at its best space: candidates are the spaces
     * in the middle half of the part (a quarter to three quarters of its
     * chars), or every space when none lies there. A candidate is strong
     * when the word after it is in [G2P_STRONG_AFTER], or is `و` / `یا`
     * and the word before it (trailing punctuation ignored) starts with
     * `می` or `نمی` or ends with `د` or `ست`. A strong candidate wins over
     * any other; among the winners the one nearest the middle wins, with
     * ties going to the earlier one. The caller guarantees a space exists.
     */
    private fun halveWindowPart(part: String): Pair<String, String> {
        val chars = part.toList()
        val len = chars.size
        val spaces = chars.indices.filter { chars[it] == ' ' }
        val middle = spaces.filter { index -> index * 4 >= len && index * 4 <= len * 3 }
        val candidates = middle.ifEmpty { spaces }
        fun wordAfter(index: Int): String =
            splitWords(chars.subList(index + 1, chars.size).joinToString("")).firstOrNull() ?: ""
        fun wordBefore(index: Int): String {
            val word = splitWords(chars.subList(0, index).joinToString("")).lastOrNull() ?: ""
            return word.trimEnd { !it.isLetterOrDigit() }
        }
        fun isStrong(index: Int): Boolean {
            val after = wordAfter(index)
            if (after in G2P_STRONG_AFTER) return true
            if (after == "و" || after == "یا") {
                val before = wordBefore(index)
                return before.startsWith("می") || before.startsWith("نمی") ||
                    before.endsWith('د') || before.endsWith("ست")
            }
            return false
        }
        val strong = candidates.filter { isStrong(it) }
        val pool = strong.ifEmpty { candidates }
        val middleAt = len / 2.0
        var best: Int? = null
        for (index in pool) {
            val seen = best
            val better = seen == null ||
                kotlin.math.abs(index - middleAt) < kotlin.math.abs(seen - middleAt) - 1e-9
            if (better) best = index
        }
        val at = best ?: throw IllegalStateException("halveWindowPart called on a part with no space: \"$part\"")
        val left = chars.subList(0, at).joinToString("")
        val right = chars.subList(at + 1, chars.size).joinToString("")
        return left.trim() to right.trimStart(' ')
    }

    /** Whitespace tokens of [text] that contain at least one Persian letter. */
    internal fun faWords(text: String): List<String> =
        splitWords(text).filter { word -> word.any { it in NormalizeFa.PERSIAN_LETTERS } }

    private fun zwnjPartCount(word: String): Int = word.split(ZWNJ).size

    private fun persianLetterCount(word: String): Int =
        word.count { it in NormalizeFa.PERSIAN_LETTERS }

    /**
     * Drop repeated phoneme words the model emits for very short inputs.
     * [fa] is the sentence's Persian words. The phoneme list may keep at
     * most the total ZWNJ part count of [fa]; while it is longer and an
     * adjacent pair repeats, the second of the first such pair is dropped.
     * A lone phoneme word that is an exact doubling (`tamtam`) for a
     * Persian word of at most 3 letters is halved (`tam`).
     */
    internal fun dedupeRepeats(fa: List<String>, ph: List<String>): List<String> {
        val out = ph.toMutableList()
        val max = fa.sumOf { zwnjPartCount(it) }
        while (out.size > max) {
            val at = (1 until out.size).firstOrNull { out[it - 1] == out[it] } ?: break
            out.removeAt(at)
        }
        if (fa.size == 1 && persianLetterCount(fa[0]) <= 3 && out.size == 1) {
            val word = out[0]
            if (word.length % 2 == 0) {
                val half = word.substring(0, word.length / 2)
                val rest = word.substring(word.length / 2)
                if (half == rest && half.length >= 2) {
                    out[0] = half
                }
            }
        }
        return out
    }

    /**
     * For each Persian word in [fa], how many phoneme words it owns: 1, or
     * its ZWNJ part count when that is more than 1. The counts must sum to
     * `ph.size` exactly; among assignments that fit, the one minimising
     * the total |phoneme chars − 1.4 × Persian letters| wins, with ties
     * going to the earlier word taking 1. Null when nothing fits.
     */
    internal fun alignWords(fa: List<String>, ph: List<String>): List<Int>? {
        val n = fa.size
        val m = ph.size
        val choices: List<List<Int>> = fa.map { word ->
            val parts = zwnjPartCount(word)
            if (parts > 1) listOf(1, parts) else listOf(1)
        }
        // dp[i][j]: best cost for fa[i..] owning ph[j..], with its choice.
        val dp: Array<Array<Pair<Double, Int>?>> = Array(n + 1) { arrayOfNulls(m + 1) }
        dp[n][m] = 0.0 to 0
        for (i in n - 1 downTo 0) {
            for (j in m downTo 0) {
                var best: Pair<Double, Int>? = null
                for (count in choices[i]) {
                    val end = j + count
                    if (end > m) continue
                    val suffix = dp[i + 1][end] ?: continue
                    val joinedChars = ph.subList(j, end).joinToString(" ").length.toDouble()
                    val cost = kotlin.math.abs(joinedChars - 1.4 * persianLetterCount(fa[i])) + suffix.first
                    // Strict `<` keeps the first (smallest) count on a tie.
                    val seen = best
                    if (seen == null || cost < seen.first - 1e-9) {
                        best = cost to count
                    }
                }
                dp[i][j] = best
            }
        }
        if (dp[0][0] == null) return null
        val counts = mutableListOf<Int>()
        var j = 0
        for (i in 0 until n) {
            val count = dp[i][j]?.second ?: return null
            counts += count
            j += count
        }
        return counts
    }

    /**
     * Join phoneme words into one string per Persian clause, where
     * [counts] (one per Persian word, in clause order) says how many
     * phoneme words each Persian word owns. A clause with no Persian word
     * contributes nothing.
     */
    internal fun clausesFromCounts(
        clausesFa: List<String>,
        ph: List<String>,
        counts: List<Int>,
    ): List<String> {
        val clauses = mutableListOf<String>()
        var at = 0
        var wordAt = 0
        for (clause in clausesFa) {
            val words = faWords(clause)
            val owned = counts.subList(wordAt, wordAt + words.size).sum()
            wordAt += words.size
            if (words.isEmpty()) continue
            clauses += ph.subList(at, at + owned).joinToString(" ")
            at += owned
        }
        return clauses
    }

    /**
     * Split phoneme words [ph] into one phoneme clause per Persian clause
     * in [clausesFa], using [alignWords] over all their Persian words.
     * A clause with no Persian word contributes nothing. Null when the
     * words cannot be aligned.
     */
    fun phonemeClauses(clausesFa: List<String>, ph: List<String>): List<String>? {
        val all = clausesFa.flatMap { faWords(it) }
        val counts = alignWords(all, ph) ?: return null
        return clausesFromCounts(clausesFa, ph, counts)
    }

    /** Result of [applyOverrides]: the new phoneme words and the new per-word counts. */
    data class OverridesResult(val phonemeWords: List<String>, val counts: List<Int>)

    /**
     * Replace the phoneme group of each Persian word that [lookup] knows
     * (by its spelling stripped of leading/trailing non-letter, non-ZWNJ
     * characters) with the override as one phoneme word. When the group's
     * last word carries the ezafe mark, the ezafe is kept: `ye1` after a
     * vowel-final override, `e1` otherwise. Returns the new phoneme words
     * and the new per-word counts (1 for a replaced word, unchanged on a
     * miss), which the caller uses to keep clause shares right.
     */
    internal fun applyOverrides(
        fa: List<String>,
        ph: List<String>,
        counts: List<Int>,
        lookup: (String) -> String?,
    ): OverridesResult {
        val out = mutableListOf<String>()
        val newCounts = mutableListOf<Int>()
        var at = 0
        for ((word, count) in fa.zip(counts)) {
            val group = ph.subList(at, at + count)
            at += count
            val stripped = word.trim { it !in NormalizeFa.PERSIAN_LETTERS && it != ZWNJ }
            val replaced = if (stripped.isEmpty()) null else lookup(stripped)
            if (replaced != null) {
                val hasEzafe = group.lastOrNull()?.endsWith(EZAFE_MARK) == true
                val wordOut = if (hasEzafe) {
                    val vowelFinal = replaced.lastOrNull() in setOf('a', 'e', 'o', 'A', 'i', 'u')
                    if (vowelFinal) "${replaced}ye1" else "${replaced}e1"
                } else {
                    replaced
                }
                out += wordOut
                newCounts += 1
            } else {
                out.addAll(group)
                newCounts += count
            }
        }
        return OverridesResult(out, newCounts)
    }

    /**
     * Plan one sentence's phoneme clauses into pieces: whole clauses merge
     * while they fit [maxTokens]; a clause over the budget is split with
     * [splitChunks] and never merges with its neighbours. A boundary at a
     * clause edge ends [PocketAudio.PieceEnd.CLAUSE], a boundary inside a
     * split clause ends [PocketAudio.PieceEnd.MID], and the last piece ends
     * [sentenceEnd]. The returned text keeps the ezafe marks.
     */
    internal fun planPieces(
        clauses: List<String>,
        countTokens: (String) -> Int,
        maxTokens: Int,
        minTokens: Int,
        sentenceEnd: PocketAudio.PieceEnd,
    ): List<TextChunk> {
        val pieces = mutableListOf<TextChunk>()
        var current: String? = null
        for (clause in clauses) {
            if (clause.isBlank()) continue
            if (countTokens(clause) <= maxTokens) {
                val cur = current
                if (cur != null) {
                    val trial = "$cur $clause"
                    if (countTokens(trial) <= maxTokens) {
                        current = trial
                        continue
                    }
                    pieces += TextChunk(cur, PocketAudio.PieceEnd.CLAUSE)
                }
                current = clause
            } else {
                val cur = current
                if (cur != null) {
                    pieces += TextChunk(cur, PocketAudio.PieceEnd.CLAUSE)
                    current = null
                }
                val subs = splitChunks(clause, countTokens, maxTokens, minTokens)
                val lastSub = subs.size - 1
                for ((index, sub) in subs.withIndex()) {
                    val end = if (index == lastSub) PocketAudio.PieceEnd.CLAUSE else PocketAudio.PieceEnd.MID
                    pieces += TextChunk(sub, end)
                }
            }
        }
        val cur = current
        if (cur != null) pieces += TextChunk(cur, PocketAudio.PieceEnd.CLAUSE)
        if (pieces.isNotEmpty()) {
            pieces[pieces.size - 1] = pieces.last().copy(end = sentenceEnd)
        }
        return pieces
    }

    /** Strip question marks the G2P model was not trained on. */
    fun cleanSentenceForG2p(sentence: String): String =
        sentence.replace("؟", "").replace("?", "")

    /** Map Homo-GE2PE output symbols onto the pocket-tts v2 phone set. */
    fun translatePhonemes(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    '/' -> 'a'
                    'a' -> 'A'
                    '@' -> '?'
                    '$' -> 'S'
                    'c' -> 'C'
                    else -> c
                },
            )
        }
    }

    /** The text as the model must see it: no ezafe marks. */
    fun stripEzafe(text: String): String = text.replace(EZAFE_MARK.toString(), "")

    /**
     * Split phoneme [text] (ezafe marks kept) into chunks of at most
     * [maxTokens] tokens: sentence boundaries first, then clause punctuation,
     * then evenly packed words — a port of `split_text` in `synthesize.py`
     * with `keep_punct_boundaries=false` (the `_synth_unit` call). [countTokens]
     * sees the same text the model will (callers strip the ezafe marks);
     * a chunk boundary never lands between a word and the complement its
     * ezafe binds it to, and no chunk stays under [minTokens] while words
     * can move back from its neighbour.
     */
    fun splitChunks(
        text: String,
        countTokens: (String) -> Int,
        maxTokens: Int = MAX_TOKENS,
        minTokens: Int = MIN_TOKENS,
    ): List<String> {
        val clean = splitWords(text).joinToString(" ")
        if (clean.isEmpty()) return emptyList()
        val parts = splitRecurse(clean, countTokens, maxTokens, minTokens)
        val merged = mutableListOf<String>()
        for (chunk in parts) {
            val last = merged.lastOrNull()
            if (last != null && countTokens("$last $chunk") <= maxTokens) {
                merged[merged.size - 1] = "$last $chunk"
            } else {
                merged += chunk
            }
        }
        for (i in merged.size - 1 downTo 1) {
            if (countTokens(merged[i]) >= minTokens) continue
            if (endsWithBreak(merged[i - 1])) continue
            while (countTokens(merged[i]) < minTokens) {
                val words = splitWords(merged[i - 1])
                if (words.size < 2 || countTokens(words.dropLast(1).joinToString(" ")) < minTokens) break
                val moved = words.last()
                // The new boundary sits before `moved`; it must be allowed.
                if (!isAllowedCut(words[words.size - 2], moved)) break
                merged[i - 1] = words.dropLast(1).joinToString(" ")
                merged[i] = "$moved ${merged[i]}"
            }
        }
        return merged
    }

    private fun splitRecurse(
        piece: String,
        countTokens: (String) -> Int,
        maxTokens: Int,
        minTokens: Int,
    ): List<String> {
        fun fits(s: String): Boolean = countTokens(s) <= maxTokens
        if (fits(piece)) return listOf(piece)
        for (pattern in listOf(hardBreak, softBreak)) {
            val parts = pattern.split(piece).map { strip(it) }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                return parts.flatMap { splitRecurse(it, countTokens, maxTokens, minTokens) }
            }
        }
        val words = splitWords(piece)
        searchWordSegments(words, countTokens, maxTokens, minTokens)?.let { return it }
        val total = countTokens(piece)
        var chunks = maxOf(1, (total + maxTokens - 1) / maxTokens)
        var packed = emptyList<String>()
        repeat(4) {
            packed = pack(words, countTokens, ::fits, chunks, (total + chunks - 1) / chunks)
            if (packed.size <= chunks) return packed
            chunks = packed.size
        }
        return packed
    }

    private fun pack(
        words: List<String>,
        countTokens: (String) -> Int,
        fits: (String) -> Boolean,
        nChunks: Int,
        target: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val trial = if (current.isEmpty()) word else "$current $word"
            val owed = out.size < nChunks - 1
            val bound = current.isNotEmpty() && splitWords(current).last().endsWith(EZAFE_MARK)
            if (current.isNotEmpty() && !bound &&
                (!fits(trial) || (owed && countTokens(trial) > target))
            ) {
                out += current
                current = word
            } else {
                current = trial
            }
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun endsWithBreak(chunk: String): Boolean {
        val stripped = stripTrailing.replace(chunk, "")
        return stripped.isNotEmpty() && stripped.last() in breakChars
    }

    private fun splitWords(text: String): List<String> =
        wordsSplit.split(text).filter { it.isNotEmpty() }

    private fun strip(text: String): String =
        stripTrailing.replace(stripLeading.replace(text, ""), "")
}
