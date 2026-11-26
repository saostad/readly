package app.readly.tts.tinyava

import app.readly.tts.pocket.NormalizeFa
import app.readly.tts.pocket.PocketAudio
import app.readly.tts.pocket.PocketTextV2
import kotlin.math.ceil

/**
 * Pure-Kotlin phoneme, token, and speed helpers for Tiny Ava (`tiny-ava-parisa.onnx`).
 * No Android or ONNX Runtime imports so JVM unit tests run directly.
 */
object TinyAvaPhonemes {
    const val SAMPLE_RATE = 24_000
    const val MAX_TOKENS = 512
    const val LONG_SENTENCE_CHARS = 350
    const val G2P_WINDOW_CHARS = 120
    const val MIN_NEURAL_SPEED = 0.75f
    const val MAX_NEURAL_SPEED = 1.30f
    const val PITCH_SHIFT = 0.0f
    const val PITCH_RANGE = 1.05f
    const val PEAK_LIMIT = 0.99f

    private const val PAD_ID = 0L
    private const val BOS_ID = 1L
    private const val EOS_ID = 2L
    private const val SP_ID = 3L
    private const val UNK_ID = 4L
    private const val FIRST_PHONE_ID = 17L

    /** Exact 46-symbol vocabulary from `tiny-ava-parisa.json`. */
    val SYMBOLS: List<String> = listOf(
        "<pad>", "<bos>", "<eos>", "<sp>", "<unk>",
        "،", ".", "؟", "!", "؛", ":", "«", "»", "(", ")", "—", "…",
        "p", "b", "t", "d", "k", "ɡ", "q", "ʔ",
        "f", "v", "s", "z", "ʃ", "ʒ", "x", "h",
        "tʃ", "dʒ", "m", "n", "l", "r", "j",
        "a", "e", "o", "i", "u", "ɑ",
    )

    internal sealed interface TokenItem {
        data class Punct(val id: Long) : TokenItem
        data class Word(val phones: List<Pair<Long, Long>>) : TokenItem
    }

    internal data class TokenSequence(
        val tokenIds: LongArray,
        val stressIds: LongArray,
    )

    internal data class SentencePiece(
        val text: String,
        val end: PocketAudio.PieceEnd,
    )

    internal class StressState(
        var canDeaccentLightVerb: Boolean = false,
    ) {
        fun onPunct() {
            canDeaccentLightVerb = false
        }
    }

    private enum class StressMode {
        NONE,
        FIRST_VOWEL,
        PENULT_VOWEL,
        LAST_VOWEL,
    }

    /** Unstressed Persian conjunctions, prepositions, postpositions, enclitics, and copulas. */
    private val UNSTRESSED_CLITICS = setOf(
        "va", "o", "ke", "dar", "be", "az", "bA", "tA", "yA", "bar",
        "rA", "ro", "ast", "am", "im", "id", "and", "ham", "niz",
    )

    private val FUTURE_AUX = setOf(
        "xAham", "xAhi", "xAhad", "xAhim", "xAhid", "xAhand",
    )

    private val LIGHT_PAST_STEMS = listOf(
        "kard", "Sod", "dAd", "gereft", "dASt", "zad", "Avard", "Avord",
        "bord", "yAft", "nemud", "sAxt", "gozASt", "xord", "bud", "gaSt",
        "gardid", "Amad",
    )
    private val LIGHT_PAST_STEM_SET = LIGHT_PAST_STEMS.toHashSet()

    private val LIGHT_PRES_STEMS = listOf(
        "kon", "Sav", "dah", "deh", "gir", "dAr", "zan", "Avar", "bar",
        "yAb", "namA", "namAy", "sAz", "gozAr", "xor", "bAS", "gard", "Ay",
    )

    private val VERB_PAST_STEMS = LIGHT_PAST_STEMS + listOf(
        "goft", "raft", "tavAnest", "dAnest", "xAst", "did", "xAnd", "mAnd",
        "gozaSt", "neveSt", "ferestAd", "pardAxt", "andAxt", "afzud", "kAst",
        "neSast", "istAd", "oftAd", "koSt", "bast", "Sekast", "foruxt",
        "Amuxt", "suxt", "SenAxt", "paziroft", "paziraft", "peymud", "namud",
        "zist", "resid", "kaSid", "fahmid", "porsid", "xarid", "Sanid",
        "puSid", "jangid", "koSid", "tarsid", "baxSid", "talabid", "xAbid",
        "yAmad", "yAvard", "yAvord", "bardASt", "barAmad", "darAmad", "bAzgaSt",
        "dargozaSt", "barxAst", "farAgereft", "vAdASt",
    )
    private val VERB_PAST_STEM_SET = VERB_PAST_STEMS.toHashSet()

    private val VERB_PRES_STEMS = LIGHT_PRES_STEMS + listOf(
        "gu", "guy", "rav", "ro", "tavAn", "dAn", "xAh", "bin", "xAn", "mAn",
        "gozar", "nevis", "ferest", "pardAz", "andAz", "afzA", "afzAy", "kAh",
        "neSin", "ist", "oft", "koS", "band", "Sekan", "foruS", "Amuz", "suz",
        "SenAs", "pazir", "peymA", "peymAy", "res", "keS", "kaS", "fahm",
        "pors", "xar", "Senav", "puS", "jang", "kuS", "tars", "baxS",
        "talab", "xAb", "yAy", "yAvar", "yandAz",
    )

    private val FINITE_ENDS = listOf("ad", "and", "am", "im", "id")
    private val PERS_ENDS = listOf("and", "am", "im", "id")
    private val PERFECT_ENDS = listOf(
        "e?and", "eand", "e?im", "eim", "e?id", "eid", "e?am", "eam", "e?i",
    )
    private val PAST_PART_OR_PERS_ENDS = setOf(
        "", "e", "am", "im", "id", "and",
        "e?am", "eam", "e?i", "ei", "e?im", "eim", "e?id", "eid", "e?and", "eand",
    )
    private val PRES_FINITE_TAILS = setOf("am", "i", "ad", "im", "id", "and")
    private val BARE_PRES_FINITE_TAILS = setOf("am", "ad", "im", "id", "and")
    private val OPT_PREFIXED_PRES_TAILS = setOf("", "am", "i", "ad", "im", "id", "and")

    private fun punctId(ch: Char): Long? = when (ch) {
        '،' -> 5L
        '.' -> 6L
        '؟' -> 7L
        '!' -> 8L
        '؛' -> 9L
        ':' -> 10L
        '«' -> 11L
        '»' -> 12L
        '(' -> 13L
        ')' -> 14L
        '—' -> 15L
        '…' -> 16L
        else -> null
    }

    private fun repr1CharToId(ch: Char): Long? = when (ch) {
        'p' -> 17L
        'b' -> 18L
        't' -> 19L
        'd' -> 20L
        'k' -> 21L
        'g' -> 22L
        'q' -> 23L
        '?' -> 24L
        'f' -> 25L
        'v' -> 26L
        's' -> 27L
        'z' -> 28L
        'S' -> 29L
        'Z', ';' -> 30L
        'x' -> 31L
        'h' -> 32L
        'C' -> 33L
        'j' -> 34L
        'm' -> 35L
        'n' -> 36L
        'l' -> 37L
        'r' -> 38L
        'y' -> 39L
        'a' -> 40L
        'e' -> 41L
        'o' -> 42L
        'i' -> 43L
        'u' -> 44L
        'A' -> 45L
        else -> null
    }

    private fun isVowelId(id: Long): Boolean = id in 40L..45L

    /**
     * Split clamped playback [speed] into pitch-preserving ONNX duration scaling
     * (`0.75..1.30`) and residual waveform resampling (`1.0` inside the sweet spot).
     */
    fun decomposeSpeed(speed: Float): Pair<Float, Float> {
        val clamped = if (speed.isFinite()) speed.coerceIn(0.5f, 2.0f) else 1.0f
        val neural = clamped.coerceIn(MIN_NEURAL_SPEED, MAX_NEURAL_SPEED)
        return neural to (clamped / neural)
    }

    private fun isFutureAux(canon: String): Boolean = canon in FUTURE_AUX

    private fun isNegativeFutureAux(canon: String): Boolean =
        canon.startsWith("na") && isFutureAux(canon.substring(2))

    private fun isLightPastParticiple(canon: String): Boolean =
        canon.endsWith('e') && canon.dropLast(1) in LIGHT_PAST_STEM_SET

    private fun isAffirmativeLightVerb(canon: String): Boolean {
        if (isFutureAux(canon)) return true
        val hasMi = canon.startsWith("mi")
        val pastRest = if (hasMi) canon.substring(2) else canon
        for (stem in LIGHT_PAST_STEMS) {
            if (pastRest.startsWith(stem)) {
                val tail = pastRest.substring(stem.length)
                if (tail in PAST_PART_OR_PERS_ENDS || (hasMi && tail == "i")) return true
            }
        }
        val presRest = when {
            canon.startsWith("mi") || canon.startsWith("be") || canon.startsWith("bo") || canon.startsWith("biy") ->
                canon.substring(2)
            else -> null
        }
        if (presRest != null) {
            for (stem in LIGHT_PRES_STEMS) {
                if (presRest.startsWith(stem) && presRest.substring(stem.length) in PRES_FINITE_TAILS) {
                    return true
                }
            }
        }
        // Bare present subjunctive light verb (never allow "-i" here so compound
        // action nouns like "tasmim giri" or "pAk sAzi" stay accented).
        for (stem in LIGHT_PRES_STEMS) {
            if (canon.startsWith(stem) && canon.substring(stem.length) in BARE_PRES_FINITE_TAILS) {
                return true
            }
        }
        return false
    }

    private fun isPrefixedFiniteVerb(canon: String): Boolean {
        if (canon.length >= 6 && canon.startsWith("nemi")) {
            val rest = canon.substring(4)
            return FINITE_ENDS.any { rest.endsWith(it) } ||
                VERB_PAST_STEMS.any { rest == it || (rest.startsWith(it) && rest.substring(it.length) == "i") } ||
                VERB_PRES_STEMS.any { rest.startsWith(it) && rest.substring(it.length) == "i" }
        }
        if (canon.length >= 5 && canon.startsWith("mi")) {
            val rest = canon.substring(2)
            // Exclude bare "-i" except explicit verb stems so news adjectives like
            // "milAdi", "milyArdi", "milyuni", "mihani", "mirAsi" keep final stress.
            return FINITE_ENDS.any { rest.endsWith(it) } ||
                VERB_PAST_STEMS.any { rest == it || (rest.startsWith(it) && rest.substring(it.length) == "i") } ||
                VERB_PRES_STEMS.any { rest.startsWith(it) && rest.substring(it.length) == "i" }
        }
        if (canon.length >= 4 && (canon.startsWith("na") || canon.startsWith("ne"))) {
            val rest = canon.substring(2)
            return VERB_PAST_STEMS.any { stem ->
                if (!rest.startsWith(stem)) {
                    false
                } else {
                    val t = rest.substring(stem.length)
                    t in PAST_PART_OR_PERS_ENDS || t == "i"
                }
            } || VERB_PRES_STEMS.any { stem ->
                rest.startsWith(stem) && rest.substring(stem.length) in OPT_PREFIXED_PRES_TAILS
            }
        }
        if (canon.length >= 4 && (canon.startsWith("be") || canon.startsWith("bo"))) {
            val rest = canon.substring(2)
            return VERB_PRES_STEMS.any { stem ->
                rest.startsWith(stem) && rest.substring(stem.length) in OPT_PREFIXED_PRES_TAILS
            }
        }
        return false
    }

    private fun hasUnstressedVerbalEnding(canon: String): Boolean {
        if (canon.length >= 5 && PERFECT_ENDS.any { canon.endsWith(it) }) return true
        for (end in PERS_ENDS) {
            if (canon.endsWith(end)) {
                val base = canon.dropLast(end.length)
                if (base in VERB_PAST_STEM_SET || (base.length >= 4 && base.endsWith("id"))) {
                    return true
                }
            }
        }
        return false
    }

    private fun classifyStress(canon: String, hasEzafe: Boolean, state: StressState): StressMode {
        if (hasEzafe) {
            state.canDeaccentLightVerb = false
            return StressMode.LAST_VOWEL
        }
        if (canon in UNSTRESSED_CLITICS) {
            state.canDeaccentLightVerb = false
            return StressMode.NONE
        }
        if (state.canDeaccentLightVerb && isAffirmativeLightVerb(canon)) {
            state.canDeaccentLightVerb = isFutureAux(canon) || isLightPastParticiple(canon)
            return StressMode.NONE
        }
        if (isFutureAux(canon)) {
            state.canDeaccentLightVerb = true
            return StressMode.PENULT_VOWEL
        }
        if (isPrefixedFiniteVerb(canon)) {
            state.canDeaccentLightVerb = isNegativeFutureAux(canon) ||
                ((canon.startsWith("na") || canon.startsWith("ne")) && canon.endsWith('e'))
            return StressMode.FIRST_VOWEL
        }
        if (hasUnstressedVerbalEnding(canon)) {
            state.canDeaccentLightVerb = false
            return StressMode.PENULT_VOWEL
        }
        state.canDeaccentLightVerb = true
        return StressMode.LAST_VOWEL
    }

    /**
     * Convert one G2P phoneme word (`Homo-GE2PE` `1`, `-hAye` plural ezafe, `barAye`,
     * or Repr. 1 `-e`/`-ye`) into `(id, stress)` pairs using Persian syntactic stress
     * rules in [state].
     */
    internal fun ge2peWordToIpaInContext(raw: String, state: StressState): List<Pair<Long, Long>> {
        val (rawStem, ez) = when {
            raw.length > 3 && raw.endsWith("ye1") -> raw.dropLast(3) to "ye"
            raw.length > 2 && raw.endsWith("e1") -> raw.dropLast(2) to "e"
            raw.length > 3 && raw.endsWith("-ye") -> raw.dropLast(3) to "ye"
            raw.length > 2 && raw.endsWith("-e") -> raw.dropLast(2) to "e"
            raw.length > 4 && raw.endsWith("hAye") -> raw.dropLast(2) to "ye"
            raw == "barAye" -> "barA" to "ye"
            else -> raw.replace("1", "") to ""
        }
        var stem = rawStem.replace("-", "")
        if (stem.length >= 2 && stem[0] == '?' && stem[1] in "aAeoiu") {
            stem = stem.substring(1)
        }
        val canon = stem.replace("sh", "S").replace("zh", "Z")
        val mode = classifyStress(canon, ez.isNotEmpty(), state)

        val phones = ArrayList<Pair<Long, Long>>(stem.length + ez.length)
        var i = 0
        while (i < stem.length) {
            if (i + 1 < stem.length && stem[i] == 'z' && stem[i + 1] == 'h') {
                phones.add(30L to 0L)
                i += 2
                continue
            }
            if (i + 1 < stem.length && stem[i] == 's' && stem[i + 1] == 'h') {
                phones.add(29L to 0L)
                i += 2
                continue
            }
            repr1CharToId(stem[i])?.let { id -> phones.add(id to 0L) }
            i++
        }
        val vowelIdxs = phones.indices.filter { isVowelId(phones[it].first) }
        val targetVowel = when (mode) {
            StressMode.NONE -> null
            StressMode.FIRST_VOWEL -> vowelIdxs.firstOrNull()
            StressMode.PENULT_VOWEL -> if (vowelIdxs.size >= 2) vowelIdxs[vowelIdxs.size - 2] else vowelIdxs.lastOrNull()
            StressMode.LAST_VOWEL -> vowelIdxs.lastOrNull()
        }
        if (targetVowel != null) {
            phones[targetVowel] = phones[targetVowel].first to 1L
        }
        for (ch in ez) {
            repr1CharToId(ch)?.let { id ->
                phones.add(id to if (isVowelId(id)) 2L else 0L)
            }
        }
        return phones
    }

    /** Convert one isolated G2P phoneme word with fresh sentence state. */
    fun ge2peWordToIpa(raw: String): List<Pair<Long, Long>> = ge2peWordToIpaInContext(raw, StressState())

    fun ensureTerminalMark(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val last = trimmed.last()
        return if (last in ".!؟?…،,;؛:") trimmed else "$trimmed."
    }

    fun splitPunctChunks(text: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        var start = 0
        for (i in text.indices) {
            if (punctId(text[i]) == null) continue
            val seg = text.substring(start, i).trim()
            if (seg.isNotEmpty()) out.add(false to seg)
            out.add(true to text[i].toString())
            start = i + 1
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) out.add(false to tail)
        return out
    }

    private fun halveToBudget(part: String, out: MutableList<String>) {
        if (part.length <= LONG_SENTENCE_CHARS) {
            out += part
            return
        }
        val words = part.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) {
            out += part
            return
        }
        val half = ceil(words.size / 2.0).toInt()
        halveToBudget(words.subList(0, half).joinToString(" "), out)
        halveToBudget(words.subList(half, words.size).joinToString(" "), out)
    }

    internal fun splitLongSentence(
        sentence: String,
        finalEnd: PocketAudio.PieceEnd,
    ): List<SentencePiece> {
        if (sentence.length <= LONG_SENTENCE_CHARS) {
            return listOf(SentencePiece(sentence, finalEnd))
        }
        val clauses = PocketTextV2.splitClauses(sentence)
        val packed = ArrayList<String>()
        for (clause in clauses) {
            val cur = packed.lastOrNull()
            if (cur != null && cur.length + 1 + clause.length <= LONG_SENTENCE_CHARS) {
                packed[packed.lastIndex] = "$cur $clause"
            } else {
                packed += clause
            }
        }
        val parts = ArrayList<String>(packed.size)
        for (part in packed) {
            halveToBudget(part, parts)
        }
        if (parts.isEmpty()) {
            return listOf(SentencePiece(sentence, finalEnd))
        }
        val last = parts.lastIndex
        return parts.mapIndexed { idx, part ->
            val end = if (idx == last) finalEnd else PocketAudio.classifyPieceEnd(part)
            SentencePiece(part, end)
        }
    }

    /**
     * Map aligned phoneme words ([finalPh], [counts]) back onto the original
     * normalized [window], keeping commas, periods, and quotes in exact word order
     * while threading sentence-level [stressState].
     */
    internal fun attachWindowItems(
        window: String,
        finalPh: List<String>,
        counts: List<Int>,
        stressState: StressState = StressState(),
    ): List<TokenItem> {
        val items = ArrayList<TokenItem>()
        var faIdx = 0
        var phAt = 0
        for (token in window.split(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            val first = token.indexOfFirst { it in NormalizeFa.PERSIAN_LETTERS }
            if (first >= 0) {
                val last = token.indexOfLast { it in NormalizeFa.PERSIAN_LETTERS }
                for (i in 0 until first) {
                    punctId(token[i])?.let {
                        stressState.onPunct()
                        items.add(TokenItem.Punct(it))
                    }
                }
                val count = counts.getOrNull(faIdx)
                if (count != null) {
                    val end = minOf(phAt + count, finalPh.size)
                    for (idx in phAt until end) {
                        val phones = ge2peWordToIpaInContext(finalPh[idx], stressState)
                        if (phones.isNotEmpty()) items.add(TokenItem.Word(phones))
                    }
                    phAt = end
                    faIdx++
                }
                for (i in (last + 1) until token.length) {
                    punctId(token[i])?.let {
                        stressState.onPunct()
                        items.add(TokenItem.Punct(it))
                    }
                }
            } else {
                for (ch in token) {
                    punctId(ch)?.let {
                        stressState.onPunct()
                        items.add(TokenItem.Punct(it))
                    }
                }
            }
        }
        return items
    }

    internal fun buildTokenSequence(items: List<TokenItem>): TokenSequence? {
        val tokens = ArrayList<Long>(items.size * 5 + 2)
        val stress = ArrayList<Long>(items.size * 5 + 2)
        tokens.add(BOS_ID)
        stress.add(0L)
        var hasPhone = false

        for (item in items) {
            when (item) {
                is TokenItem.Punct -> {
                    if (tokens.last() != item.id) {
                        tokens.add(item.id)
                        stress.add(0L)
                    }
                }
                is TokenItem.Word -> {
                    if (item.phones.isEmpty()) continue
                    val prev = tokens.last()
                    if (prev >= FIRST_PHONE_ID || prev == UNK_ID) {
                        tokens.add(SP_ID)
                        stress.add(0L)
                    }
                    for ((phId, lvl) in item.phones) {
                        tokens.add(phId)
                        stress.add(if (phId == UNK_ID || phId == PAD_ID) 0L else lvl)
                        if (phId >= FIRST_PHONE_ID) hasPhone = true
                    }
                }
            }
        }

        tokens.add(EOS_ID)
        stress.add(0L)
        if (!hasPhone) return null
        return TokenSequence(tokens.toLongArray(), stress.toLongArray())
    }

    internal fun splitItemsToBudget(
        items: List<TokenItem>,
        end: PocketAudio.PieceEnd,
    ): List<Pair<TokenSequence, PocketAudio.PieceEnd>> {
        val seq = buildTokenSequence(items) ?: return emptyList()
        if (seq.tokenIds.size <= MAX_TOKENS || items.size <= 1) {
            return listOf(seq to end)
        }
        val mid = items.size / 2
        val out = ArrayList<Pair<TokenSequence, PocketAudio.PieceEnd>>()
        out.addAll(splitItemsToBudget(items.subList(0, mid), PocketAudio.PieceEnd.MID))
        out.addAll(splitItemsToBudget(items.subList(mid, items.size), end))
        return out
    }
}
