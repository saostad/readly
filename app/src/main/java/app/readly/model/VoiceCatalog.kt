package app.readly.model

/** TTS model family. Decides the required files and the sherpa-onnx config. */
enum class VoiceFamily {
    KOKORO,
    PIPER,
    SUPERTONIC,
    KITTEN,
}

/**
 * One downloadable voice pack.
 *
 * @param modelFile the family's main `.onnx` (PIPER/KITTEN/KOKORO). SUPERTONIC has four
 *   model files instead, so this is `""` and unused for that family.
 * @param speakers display names; the speaker id passed to synthesis is the index.
 * @param rtfNote honest speed note, measured on a Pixel 11 Pro.
 */
data class VoicePack(
    val id: String,
    val displayName: String,
    val family: VoiceFamily,
    val archive: String,
    val sizeBytes: Long,
    val sha256: String,
    val topDir: String,
    val modelFile: String,
    val speakers: List<String>,
    val rtfNote: String,
) {
    /** Download URL: every pack lives in the sherpa-onnx `tts-models` release. */
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archive"

    /** [sizeBytes] in decimal MB, rounded; the progress label shows this total. */
    val sizeMb: Int
        get() = ((sizeBytes + 500_000) / 1_000_000).toInt()
}

/** The 10 voice packs the owner approved (Kitten nano was rejected after listening). */
object VoiceCatalog {
    /** Fresh installs download this pack. */
    const val DEFAULT_ID = "supertonic-2"

    /** The only pack before pack switching; kept as the default when already installed. */
    const val KOKORO_ID = "kokoro-en"

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

    val ALL: List<VoicePack> = listOf(
        VoicePack(
            id = "supertonic-2",
            displayName = "Supertonic 2",
            family = VoiceFamily.SUPERTONIC,
            archive = "sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2",
            sizeBytes = 84692981L,
            sha256 = "8c74359f63edd5045d47747f65331f0f6dbcbc91d7e898dd756d631295fe3259",
            topDir = "sherpa-onnx-supertonic-tts-int8-2026-03-06",
            modelFile = "",
            speakers = numberedVoices(10),
            rtfNote = "About 12× faster than real time",
        ),
        VoicePack(
            id = "supertonic-3",
            displayName = "Supertonic 3",
            family = VoiceFamily.SUPERTONIC,
            archive = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            sizeBytes = 128774318L,
            sha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
            topDir = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
            modelFile = "",
            speakers = numberedVoices(10),
            rtfNote = "About 3× faster",
        ),
        VoicePack(
            id = "kokoro-en",
            displayName = "Kokoro",
            family = VoiceFamily.KOKORO,
            archive = "kokoro-en-v0_19.tar.bz2",
            sizeBytes = 319625534L,
            sha256 = "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7",
            topDir = "kokoro-en-v0_19",
            modelFile = "model.onnx",
            speakers = KOKORO_SPEAKERS,
            rtfNote = "About 1.5–2× faster",
        ),
        VoicePack(
            id = "piper-lessac",
            displayName = "Piper Lessac (American)",
            family = VoiceFamily.PIPER,
            archive = "vits-piper-en_US-lessac-medium.tar.bz2",
            sizeBytes = 67230653L,
            sha256 = "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
            topDir = "vits-piper-en_US-lessac-medium",
            modelFile = "en_US-lessac-medium.onnx",
            speakers = listOf("Default"),
            rtfNote = "About 11× faster",
        ),
        VoicePack(
            id = "piper-ryan",
            displayName = "Piper Ryan (American)",
            family = VoiceFamily.PIPER,
            archive = "vits-piper-en_US-ryan-medium.tar.bz2",
            sizeBytes = 67213100L,
            sha256 = "c546af78b6395b4e7c4ce1ed899438b64426a362f5d4ec5fecd090ded9ad7505",
            topDir = "vits-piper-en_US-ryan-medium",
            modelFile = "en_US-ryan-medium.onnx",
            speakers = listOf("Default"),
            rtfNote = "About 11× faster",
        ),
        VoicePack(
            id = "piper-amy",
            displayName = "Piper Amy (American)",
            family = VoiceFamily.PIPER,
            archive = "vits-piper-en_US-amy-medium.tar.bz2",
            sizeBytes = 67223746L,
            sha256 = "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264",
            topDir = "vits-piper-en_US-amy-medium",
            modelFile = "en_US-amy-medium.onnx",
            speakers = listOf("Default"),
            rtfNote = "About 11× faster",
        ),
        VoicePack(
            id = "piper-alba",
            displayName = "Piper Alba (British)",
            family = VoiceFamily.PIPER,
            archive = "vits-piper-en_GB-alba-medium.tar.bz2",
            sizeBytes = 67212349L,
            sha256 = "fcd45962906933eec4431d3688f7d74aaac8713c87c6717f91fd3b23463aa1a1",
            topDir = "vits-piper-en_GB-alba-medium",
            modelFile = "en_GB-alba-medium.onnx",
            speakers = listOf("Default"),
            rtfNote = "About 11× faster",
        ),
        VoicePack(
            id = "piper-lessac-high",
            displayName = "Piper Lessac HD",
            family = VoiceFamily.PIPER,
            archive = "vits-piper-en_US-lessac-high.tar.bz2",
            sizeBytes = 115545841L,
            sha256 = "8619d204c7005866fe4f420181dfa79622af6a6222389f0b0818d2af31e0db0e",
            topDir = "vits-piper-en_US-lessac-high",
            modelFile = "en_US-lessac-high.onnx",
            speakers = listOf("Default"),
            rtfNote = "About 2.5× faster",
        ),
        VoicePack(
            id = "piper-lessac-compact",
            displayName = "Piper Lessac Compact",
            family = VoiceFamily.PIPER,
            archive = "vits-piper-en_US-lessac-medium-int8.tar.bz2",
            sizeBytes = 20969179L,
            sha256 = "f1c6d0295cf16087b05f80fdca5b44daca5cd78e2c425d419a42ba34929805f9",
            topDir = "vits-piper-en_US-lessac-medium-int8",
            modelFile = "en_US-lessac-medium.onnx",
            speakers = listOf("Default"),
            rtfNote = "About 5× faster",
        ),
        VoicePack(
            id = "kitten-mini",
            displayName = "Kitten Mini",
            family = VoiceFamily.KITTEN,
            archive = "kitten-mini-en-v0_8.tar.bz2",
            sizeBytes = 67547594L,
            sha256 = "518f9b130320f690d5b5476df77bde4215fca67773cda16710318e5081234b9d",
            topDir = "kitten-mini-en-v0_8",
            modelFile = "model.onnx",
            speakers = numberedVoices(8),
            rtfNote = "About 2× faster",
        ),
    )

    /** The pack with [id], or null when unknown. */
    fun byId(id: String): VoicePack? = ALL.find { it.id == id }
}
