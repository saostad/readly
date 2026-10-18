package app.readly.model

import android.content.Context
import androidx.annotation.RawRes
import app.readly.R
import java.io.InputStream
import java.util.Locale

/** TTS model family. Decides the required files and the sherpa-onnx config. */
enum class VoiceFamily {
    KOKORO,
    PIPER,
    SUPERTONIC,
    KITTEN,
    POCKET,
}

/** Card order, titles and taglines for the family-grouped Voices screen. */
data class VoiceFamilyInfo(
    val family: VoiceFamily,
    val title: String,
    val tagline: String,
)

/**
 * One downloadable voice pack.
 *
 * @param variant short chip label within its family card (e.g. `v2`, `Full`, `HD`).
 * @param voiceName Piper voice only (e.g. `Lessac (English)`); null for other families.
 * @param modelFile the family's main `.onnx` (PIPER/KITTEN/KOKORO). SUPERTONIC has four
 *   model files instead, so this is `""` and unused for that family.
 * @param speakers display names; the speaker id passed to synthesis is the index.
 * @param rtfNote honest speed note, measured on a Pixel 11 Pro.
 * @param sampleRes raw resource id of the spoken sample clip (all clips say the same
 *   sentence). The file is `res/raw/<sampleName>.ogg`. `0` when [sampleUrl] is used.
 * @param lang Piper lang code from the JSON (e.g. `en_US`, `fa_IR`), or Pocket's
 *   2-letter code (`fa`); null otherwise.
 * @param sampleUrl remote sample clip for generated Piper packs (the official
 *   piper-samples mp3); null means the bundled [sampleRes] clip.
 */
data class VoicePack(
    val id: String,
    val displayName: String,
    val variant: String,
    val voiceName: String?,
    val family: VoiceFamily,
    val archive: String,
    val sizeBytes: Long,
    val sha256: String,
    val topDir: String,
    val modelFile: String,
    val speakers: List<String>,
    val rtfNote: String,
    @RawRes val sampleRes: Int,
    val lang: String? = null,
    val sampleUrl: String? = null,
) {
    /** Download URL: every pack lives in the sherpa-onnx `tts-models` release. */
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archive"

    /** [sizeBytes] in decimal MB, rounded; the progress label shows this total. */
    val sizeMb: Int
        get() = ((sizeBytes + 500_000) / 1_000_000).toInt()

    /**
     * False while the pack has no download host (Pocket v1): the file is copied
     * to the phone by hand, so nothing may offer or start a download for it.
     */
    val downloadable: Boolean
        get() = archive.isNotEmpty()

    /** Base name of the sample clip in `res/raw` (`.ogg` suffix not included). */
    val sampleName: String
        get() = "sample_" + id.replace('-', '_')

    /** 2-letter ISO languages this pack can read, for Smart voice ranking. */
    fun languages(): Set<String> {
        if (family == VoiceFamily.PIPER) {
            val code = lang ?: return emptySet()
            if (code == "fa_en") return setOf("fa", "en")
            return setOf(code.substringBefore("_"))
        }
        if (family == VoiceFamily.POCKET) return setOf("fa")
        if (id == "supertonic-3") return VoiceCatalog.SUPERTONIC3_LANGS
        return setOf("en")
    }
}

/**
 * Voice packs: 6 hand-written packs plus the generated Piper list from
 * `assets/piper_voices.json` (see `scripts/gen_piper_catalog.py`).
 *
 * The Piper list needs a [Context] to load, so call [ensureLoaded] from
 * `Application.onCreate` before anything touches [ALL]; tests use [loadForTests].
 */
object VoiceCatalog {
    /** Fresh installs download this pack. */
    const val DEFAULT_ID = "supertonic-2"

    /** The only pack before pack switching; kept as the default when already installed. */
    const val KOKORO_ID = "kokoro-en"

    /** The 31 language codes Supertonic 3 covers (sherpa's example list). */
    val SUPERTONIC3_LANGS: Set<String> = setOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et",
        "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl",
        "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
    )

    private fun numberedVoices(n: Int): List<String> = (1..n).map { "Voice $it" }

    private val KOKORO_SPEAKERS = listOf(
        "Default (American, female)",
        "Bella (American, female)",
        "Nicole (American, female)",
        "Sarah (American, female)",
        "Sky (American, female)",
        "Adam (American, male)",
        "Michael (American, male)",
        "Emma (British, female)",
        "Isabella (British, female)",
        "George (British, male)",
        "Lewis (British, male)",
    )

    /** Hand-written packs in catalog order, before the generated Piper block. */
    private val CORE_BEFORE_PIPER: List<VoicePack> = listOf(
        VoicePack(
            id = "supertonic-2",
            displayName = "Supertonic 2",
            variant = "v2",
            voiceName = null,
            family = VoiceFamily.SUPERTONIC,
            archive = "sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2",
            sizeBytes = 84692981L,
            sha256 = "8c74359f63edd5045d47747f65331f0f6dbcbc91d7e898dd756d631295fe3259",
            topDir = "sherpa-onnx-supertonic-tts-int8-2026-03-06",
            modelFile = "",
            speakers = numberedVoices(10),
            rtfNote = "About 12× faster than real time",
            sampleRes = R.raw.sample_supertonic_2,
        ),
        VoicePack(
            id = "supertonic-3",
            displayName = "Supertonic 3",
            variant = "v3",
            voiceName = null,
            family = VoiceFamily.SUPERTONIC,
            archive = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            sizeBytes = 128774318L,
            sha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
            topDir = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
            modelFile = "",
            speakers = numberedVoices(10),
            rtfNote = "About 3× faster",
            sampleRes = R.raw.sample_supertonic_3,
        ),
        VoicePack(
            id = "kokoro-en",
            displayName = "Kokoro",
            variant = "Full",
            voiceName = null,
            family = VoiceFamily.KOKORO,
            archive = "kokoro-en-v0_19.tar.bz2",
            sizeBytes = 319625534L,
            sha256 = "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7",
            topDir = "kokoro-en-v0_19",
            modelFile = "model.onnx",
            speakers = KOKORO_SPEAKERS,
            rtfNote = "About 1.5–2× faster",
            sampleRes = R.raw.sample_kokoro_en,
        ),
        VoicePack(
            id = "kokoro-en-compact",
            displayName = "Kokoro Compact",
            variant = "Compact",
            voiceName = null,
            family = VoiceFamily.KOKORO,
            archive = "kokoro-int8-en-v0_19.tar.bz2",
            sizeBytes = 103248205L,
            sha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
            topDir = "kokoro-int8-en-v0_19",
            modelFile = "model.int8.onnx",
            speakers = KOKORO_SPEAKERS,
            rtfNote = "About real time on a Pixel 11 Pro; may pause between sentences on slower phones",
            sampleRes = R.raw.sample_kokoro_en_compact,
        ),
    )

    /** Hand-written packs after the Piper block. */
    private val CORE_AFTER_PIPER: List<VoicePack> = listOf(
        VoicePack(
            id = "kitten-mini",
            displayName = "Kitten Mini",
            variant = "Mini",
            voiceName = null,
            family = VoiceFamily.KITTEN,
            archive = "kitten-mini-en-v0_8.tar.bz2",
            sizeBytes = 67547594L,
            sha256 = "518f9b130320f690d5b5476df77bde4215fca67773cda16710318e5081234b9d",
            topDir = "kitten-mini-en-v0_8",
            modelFile = "model.onnx",
            speakers = numberedVoices(8),
            rtfNote = "About 2× faster",
            sampleRes = R.raw.sample_kitten_mini,
        ),
        VoicePack(
            id = "pocket-farsi-v1",
            displayName = "Pocket Farsi",
            variant = "v1",
            voiceName = null,
            family = VoiceFamily.POCKET,
            archive = "",
            sizeBytes = 226396445L,
            sha256 = "",
            topDir = "pocket-farsi-v1",
            modelFile = "pocket-farsi-v1.onnx",
            speakers = listOf("Voice A"),
            rtfNote = "Speed on phones not measured yet",
            sampleRes = R.raw.sample_pocket_farsi_v1,
            lang = "fa",
        ),
    )

    @Volatile
    private var piperPacks: List<VoicePack>? = null

    /** Loads the generated Piper list from assets once; call from `Application.onCreate`. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (piperPacks == null) {
            context.assets.open(PiperCatalogJson.ASSET_NAME).use { stream ->
                piperPacks = streamToPacks(stream)
            }
        }
    }

    /** Test hook: loads the Piper list from any stream. */
    @Synchronized
    fun loadForTests(stream: InputStream) {
        stream.use { piperPacks = streamToPacks(it) }
    }

    private fun streamToPacks(stream: InputStream): List<VoicePack> =
        PiperCatalogJson.parse(stream.readBytes().toString(Charsets.UTF_8)).map(::packFromEntry)

    /** All packs (hand-written + generated Piper); fails fast before [ensureLoaded]. */
    val ALL: List<VoicePack>
        get() = CORE_BEFORE_PIPER + requirePiper() + CORE_AFTER_PIPER

    private fun requirePiper(): List<VoicePack> =
        piperPacks ?: error("VoiceCatalog is not loaded: call ensureLoaded(context) first")

    /**
     * Old hand-written Piper ids → generated ids (round 15). The archives, sizes,
     * hashes and install dirs are identical, so installed voices keep working.
     */
    val PIPER_ALIASES: Map<String, String> = mapOf(
        "piper-lessac" to "piper-en_US-lessac-medium",
        "piper-ryan" to "piper-en_US-ryan-medium",
        "piper-amy" to "piper-en_US-amy-medium",
        "piper-alba" to "piper-en_GB-alba-medium",
        "piper-lessac-high" to "piper-en_US-lessac-high",
        "piper-lessac-compact" to "piper-en_US-lessac-medium-compact",
    )

    /**
     * Canonical id for [id]: follows [PIPER_ALIASES] when the target exists in the
     * catalog, otherwise [id] unchanged.
     */
    fun resolvePackId(id: String): String {
        val target = PIPER_ALIASES[id] ?: return id
        return if (ALL.any { it.id == target }) target else id
    }

    /** The pack with [id], following [PIPER_ALIASES]; null when unknown. */
    fun byId(id: String): VoicePack? =
        ALL.find { it.id == id }
            ?: PIPER_ALIASES[id]?.let { target -> ALL.find { it.id == target } }

    /** Maps one generated entry to a [VoicePack]. Pure; also covered by tests. */
    fun packFromEntry(e: PiperVoiceEntry): VoicePack {
        val variant = when {
            !e.compact -> when (e.quality) {
                "x_low" -> "Tiny"
                "low" -> "Low"
                "medium" -> "Standard"
                else -> "HD" // "high"; the generator only emits the four qualities
            }
            e.quality == "medium" -> "Compact"
            // A voice can ship int8 builds of several qualities (e.g. Thorsten
            // low/medium/high): qualify those so chips stay unambiguous.
            e.quality == "low" -> "Low Compact"
            e.quality == "high" -> "HD Compact"
            else -> "Tiny Compact" // x_low
        }
        val voiceName = "${voiceTitle(e.voice)} (${langShort(e.lang)})"
        return VoicePack(
            id = e.id,
            displayName = "Piper $voiceName",
            variant = variant,
            voiceName = voiceName,
            family = VoiceFamily.PIPER,
            archive = e.archive,
            sizeBytes = e.sizeBytes,
            sha256 = e.sha256,
            topDir = e.topDir,
            modelFile = e.modelFile,
            speakers = listOf("Default"),
            rtfNote = when {
                e.compact -> "About 5× faster"
                e.quality == "high" -> "About 2.5× faster"
                else -> "About 11× faster"
            },
            sampleRes = 0,
            lang = e.lang,
            sampleUrl = piperSampleUrl(e.lang, e.voice, e.quality),
        )
    }

    /** `ganji_adabi` → `Ganji Adabi`; hyphenated parts keep their hyphen. */
    fun voiceTitle(voice: String): String =
        voice.split("_").joinToString(" ") { part ->
            part.split("-").joinToString("-") { word ->
                word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            }
        }

    /** Short language name for voice names, e.g. `Persian`, `English`. */
    fun langShort(lang: String): String = when (lang) {
        "fa_en" -> "Persian + English"
        "fa" -> "Persian"
        else -> Locale(lang.substringBefore("_")).getDisplayLanguage(Locale.US)
            .replaceFirstChar { it.titlecase(Locale.US) }.ifEmpty { lang }
    }

    /** Full language name for the Language spinner, e.g. `Persian (Iran)`. */
    fun langDisplay(lang: String): String = when (lang) {
        "fa_en" -> "Persian + English"
        "fa" -> "Persian"
        else -> {
            val short = langShort(lang)
            val region = lang.substringAfter("_", "")
            if (region.isEmpty()) {
                short
            } else {
                "$short (${Locale("", region).getDisplayCountry(Locale.US).ifEmpty { region }})"
            }
        }
    }

    /**
     * Official Piper sample clip for ([lang], [voice], [quality]). [quality] is the
     * base quality: compacts reuse their voice's non-compact clip. Best effort for
     * region-less langs (`fa`).
     */
    fun piperSampleUrl(lang: String, voice: String, quality: String): String {
        val first = if (Regex("[a-z]{2,3}_[A-Z]{2}").matches(lang)) {
            lang.substringBefore("_")
        } else {
            lang
        }
        return "https://rhasspy.github.io/piper-samples/samples/$first/$lang/$voice/$quality/speaker_0.mp3"
    }

    /** Family cards in display order (the Piper tagline is computed from the JSON). */
    val FAMILIES: List<VoiceFamilyInfo> by lazy {
        listOf(
            VoiceFamilyInfo(VoiceFamily.SUPERTONIC, "Supertonic", "Fastest, natural · 10 voices"),
            VoiceFamilyInfo(VoiceFamily.KOKORO, "Kokoro", "Most natural · 11 voices"),
            VoiceFamilyInfo(VoiceFamily.PIPER, "Piper", piperTagline()),
            VoiceFamilyInfo(VoiceFamily.KITTEN, "Kitten", "Compact · 8 voices"),
            VoiceFamilyInfo(VoiceFamily.POCKET, "Pocket", "Natural Persian · 1 voice"),
        )
    }

    /** `Light and fast · 179 voices in 50 languages`, counted from the JSON. */
    fun piperTagline(): String {
        val packs = requirePiper()
        val standard = packs.count { it.variant in NON_COMPACT_VARIANTS }
        val langs = packs.mapNotNull { it.lang }.distinct().size
        return "Light and fast · $standard voices in $langs languages"
    }

    private val NON_COMPACT_VARIANTS = setOf("Tiny", "Low", "Standard", "HD")

    /** Card info for [family]. */
    fun familyInfo(family: VoiceFamily): VoiceFamilyInfo =
        FAMILIES.first { it.family == family }

    /** Packs in [family], in catalog order. */
    fun packsFor(family: VoiceFamily): List<VoicePack> =
        ALL.filter { it.family == family }

    /** Piper lang codes: en_US, en_GB, fa_IR first, then the rest A–Z by display name. */
    fun piperLangs(): List<String> {
        val langs = requirePiper().mapNotNull { it.lang }.distinct()
        val pinned = listOf("en_US", "en_GB", "fa_IR").filter { it in langs }
        return pinned + (langs - pinned.toSet()).sortedBy { langDisplay(it) }
    }

    /** Distinct Piper [VoicePack.voiceName]s for [lang], in catalog order. */
    fun piperVoices(lang: String): List<String> =
        requirePiper().filter { it.lang == lang }.mapNotNull { it.voiceName }.distinct()

    /** The Piper pack for [lang] + [voiceName] + [variant], or null when missing. */
    fun piperPack(lang: String, voiceName: String, variant: String): VoicePack? =
        requirePiper().find {
            it.lang == lang && it.voiceName == voiceName && it.variant == variant
        }

    /** Canonical Piper quality order for the Quality segments. */
    val PIPER_QUALITY_ORDER = listOf("Tiny", "Low", "Standard", "HD")

    /**
     * Base quality of a Piper [VoicePack.variant]: `"Compact"` is Standard's
     * int8 build, `"Low Compact"` Low's, and the rest are their own quality.
     * Piper variants only (other families reuse the word differently).
     */
    fun baseQuality(variant: String): String = when {
        variant == "Compact" -> "Standard"
        variant.endsWith(" Compact") -> variant.removeSuffix(" Compact")
        else -> variant
    }

    /**
     * True for the Piper int8 builds (`"Compact"`, `"Low Compact"`, …).
     * Piper variants only.
     */
    fun isCompactVariant(variant: String): Boolean = variant.endsWith("Compact")

    /**
     * One-line control labels for the Voices screen. Every one fits without
     * truncation: ButtonOneLineTest sweeps the whole catalog at ≤ 18 chars.
     */

    /** Family-card segment: `"v2 · 85 MB"`, `"Full · 320 MB"`. */
    fun segmentLabel(variant: String, sizeMb: Int): String = "$variant · $sizeMb MB"

    /** Piper Size segment: `"Full · 67 MB"` or `"Compact · 21 MB"`. */
    fun sizeLabel(compact: Boolean, sizeMb: Int): String =
        "${if (compact) "Compact" else "Full"} · $sizeMb MB"

    /** Main download button: `"Download · 21 MB"`. */
    fun downloadLabel(sizeMb: Int): String = "Download · $sizeMb MB"

    /**
     * Piper language chip: `"<Language> (<REGION CODE>)"`, e.g. `"English (US)"`;
     * region-less codes show only the language. The list sheet keeps [langDisplay].
     */
    fun langChipLabel(lang: String): String {
        if (lang == "fa_en") return langShort(lang)
        val region = lang.substringAfter("_", "")
        return if (region.isEmpty()) langShort(lang) else "${langShort(lang)} ($region)"
    }

    /** Voice names longer than this show their first word in the voice chip. */
    const val VOICE_CHIP_MAX_CHARS = 14

    /**
     * Piper voice chip: the full name up to [VOICE_CHIP_MAX_CHARS] chars, else
     * the first word (`"Lessac (English)"` → `"Lessac"`). A hyphenated single
     * token splits on `"-"` too, so the longest catalog name still fits one line.
     */
    fun voiceChipLabel(voiceName: String): String {
        if (voiceName.length <= VOICE_CHIP_MAX_CHARS) return voiceName
        return voiceName.substringBefore(" ").substringBefore("-")
    }

    /**
     * Short display name for the Storage list and the main screen, e.g.
     * `"Supertonic v2"`, `"Kokoro (Full)"`, `"Piper Lessac (HD)"`, `"Piper Ryan"`.
     * Piper Standard omits the variant.
     */
    fun shortName(pack: VoicePack): String {
        val title = familyInfo(pack.family).title
        return when (pack.family) {
            VoiceFamily.SUPERTONIC, VoiceFamily.KITTEN, VoiceFamily.POCKET -> "$title ${pack.variant}"
            VoiceFamily.KOKORO -> "$title (${pack.variant})"
            VoiceFamily.PIPER -> {
                val short = pack.voiceName?.substringBefore(" (") ?: pack.displayName
                if (pack.variant == "Standard") "$title $short" else "$title $short (${pack.variant})"
            }
        }
    }
}
