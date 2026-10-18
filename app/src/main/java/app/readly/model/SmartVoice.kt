package app.readly.model

/**
 * Smart voice ranking: picks the best installed voice for a detected language,
 * or decides whether to auto-download or ask.
 *
 * Pure Kotlin; the service supplies the installed list, the explicit per-language
 * pick, measured RTFs and the download context.
 */
object SmartVoice {
    /** Result of [pick]. */
    sealed interface Choice {
        /** Read now with [pack]. */
        data class Use(val pack: VoicePack) : Choice

        /** Download [pack] on Wi-Fi, then read with it. */
        data class AutoDownload(val pack: VoicePack) : Choice

        /** Ask the user; [pack] is the recommended download (null when none). */
        data class Ask(val pack: VoicePack?) : Choice

        /** Smart voice is off: use the main voice (Round 15 behavior). */
        data object MainVoice : Choice
    }

    /** Auto-downloads never exceed this (70 decimal MB). */
    const val MAX_AUTO_DOWNLOAD_BYTES = 70L * 1_000_000

    /**
     * Quality rank for [pack] (higher is better): Kokoro Full > Supertonic 3 >
     * Kokoro Compact > Supertonic 2 > Piper HD > Standard > Low > Tiny >
     * Piper Compact (any int8) > Kitten.
     */
    fun rank(pack: VoicePack): Int {
        when (pack.id) {
            "kokoro-en" -> return 9
            "supertonic-3" -> return 8
            "kokoro-en-compact" -> return 7
            "supertonic-2" -> return 6
            "kitten-mini" -> return 0
        }
        if (pack.family == VoiceFamily.PIPER) {
            if ("Compact" in pack.variant) return 1
            return when (pack.variant) {
                "HD" -> 5
                "Standard" -> 4
                "Low" -> 3
                "Tiny" -> 2
                else -> 1
            }
        }
        return 0
    }

    /**
     * Recommended download for 2-letter [lang]: the first Piper voice A–Z for
     * that language, as Compact when it has one, else Standard, else its
     * smallest pack (so Ask can show a size). When no Piper voice covers [lang]
     * but Supertonic 3 does, recommends Supertonic 3. Null when nothing covers
     * [lang].
     */
    fun recommendedPack(lang: String, all: List<VoicePack> = VoiceCatalog.ALL): VoicePack? {
        // A pack with no download host (Pocket v1) is never a download recommendation.
        val downloadable = all.filter { it.downloadable }
        val piper = downloadable.filter { it.family == VoiceFamily.PIPER && lang in it.languages() }
        if (piper.isNotEmpty()) {
            val firstVoice = piper.mapNotNull { it.voiceName }.minOrNull() ?: return null
            val candidates = piper.filter { it.voiceName == firstVoice }
            candidates.find { it.variant == "Compact" }?.let { return it }
            candidates.find { it.variant == "Standard" }?.let { return it }
            return candidates.minByOrNull { it.sizeBytes }
        }
        return downloadable.find { it.id == "supertonic-3" }?.takeIf { lang in it.languages() }
    }

    /**
     * Picks how to read 2-letter [lang].
     *
     * @param installed installed packs (in any order).
     * @param explicit the user's per-language pick when set and installed, else null.
     * @param rtfByPack measured on-device RTF (EMA) by pack id; missing means unknown (fast).
     * @param smartOn false reproduces Round 15: explicit wins, else [Choice.MainVoice].
     * @param mainPack the main voice (for the main-supports-lang exception).
     * @param recommended [recommendedPack] for [lang] (null when none).
     * @param canAutoDownload unmetered network, the auto-download toggle on, no install running.
     */
    fun pick(
        lang: String,
        installed: List<VoicePack>,
        explicit: VoicePack?,
        rtfByPack: Map<String, Double> = emptyMap(),
        smartOn: Boolean = true,
        mainPack: VoicePack? = null,
        recommended: VoicePack? = null,
        canAutoDownload: Boolean = false,
    ): Choice {
        if (!smartOn) {
            if (explicit != null) return Choice.Use(explicit)
            return Choice.MainVoice
        }
        if (explicit != null) return Choice.Use(explicit)
        val supporting = installed.filter { lang in it.languages() }
        if (supporting.isNotEmpty()) {
            // Skip packs measured slower than real time when a faster one exists;
            // when all are slow (or unknown), rank them all.
            val fast = supporting.filter { (rtfByPack[it.id] ?: 0.0) <= 0.9 }
            val candidates = if (fast.isNotEmpty()) fast else supporting
            val best = candidates.sortedWith(
                compareByDescending<VoicePack> { rank(it) }
                    .thenBy { it.voiceName ?: it.id }
                    .thenBy { it.id },
            ).first()
            return Choice.Use(best)
        }
        if (mainPack != null && lang in mainPack.languages()) return Choice.Use(mainPack)
        if (recommended != null && canAutoDownload && isAutoDownloadable(recommended)) {
            return Choice.AutoDownload(recommended)
        }
        return Choice.Ask(recommended)
    }

    /** Only a small Piper Compact or Standard is ever auto-downloaded. */
    private fun isAutoDownloadable(pack: VoicePack): Boolean {
        // A pack with no download host (Pocket v1) can never auto-download.
        if (!pack.downloadable) return false
        if (pack.family != VoiceFamily.PIPER) return false
        if (pack.variant != "Compact" && pack.variant != "Standard") return false
        return pack.sizeBytes <= MAX_AUTO_DOWNLOAD_BYTES
    }

    /**
     * System TTS pick for 2-letter [lang]: the installed pack supporting [lang]
     * with the lowest measured on-device RTF (system speech must be instant).
     * Packs with no measurement sort as fast (0.0); ties break by [rank], then
     * name. Null when nothing installed supports [lang].
     */
    fun pickForSystem(
        lang: String,
        installed: List<VoicePack>,
        rtfByPack: Map<String, Double> = emptyMap(),
    ): VoicePack? =
        installed.filter { lang in it.languages() }.sortedWith(
            compareBy<VoicePack> { rtfByPack[it.id] ?: 0.0 }
                .thenByDescending { rank(it) }
                .thenBy { it.voiceName ?: it.id }
                .thenBy { it.id },
        ).firstOrNull()
}
