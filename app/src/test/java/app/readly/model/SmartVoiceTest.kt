package app.readly.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartVoiceTest {

    @Before
    fun loadCatalog() = TestVoices.loadCatalog()

    private fun pack(id: String): VoicePack = VoiceCatalog.byId(id)!!

    @Test
    fun rank_ordersKokoroSupertonicPiperKitten() {
        val ordered = listOf(
            "kokoro-en",
            "supertonic-3",
            "kokoro-en-compact",
            "supertonic-2",
            "piper-en_US-lessac-high",
            "piper-en_US-lessac-medium",
            "piper-ar_JO-kareem-low",
            "piper-en_US-lessac-medium-compact",
            "kitten-mini",
        ).map(::pack)
        val ranks = ordered.map { SmartVoice.rank(it) }
        assertEquals(ranks.sortedDescending(), ranks)
        // Piper HD still loses to Supertonic 2; Compact still beats Kitten.
        assertTrue(SmartVoice.rank(pack("supertonic-2")) > SmartVoice.rank(pack("piper-en_US-lessac-high")))
        assertTrue(SmartVoice.rank(pack("piper-en_US-lessac-medium-compact")) > SmartVoice.rank(pack("kitten-mini")))
    }

    @Test
    fun pick_explicitWinsOverRanking() {
        val explicit = pack("piper-fa_IR-gyro-medium")
        val installed = listOf(pack("piper-fa_IR-amir-medium"), explicit)
        assertEquals(
            SmartVoice.Choice.Use(explicit),
            SmartVoice.pick("fa", installed, explicit, emptyMap(), true),
        )
    }

    @Test
    fun pick_rankingOrderPicksBestInstalled() {
        val installed = listOf(
            pack("kitten-mini"),
            pack("piper-en_US-lessac-medium"),
            pack("supertonic-2"),
            pack("kokoro-en"),
        )
        assertEquals(
            SmartVoice.Choice.Use(pack("kokoro-en")),
            SmartVoice.pick("en", installed, null, emptyMap(), true),
        )
        assertEquals(
            SmartVoice.Choice.Use(pack("supertonic-2")),
            SmartVoice.pick(
                "en",
                listOf(pack("piper-en_US-lessac-high"), pack("supertonic-2")),
                null, emptyMap(), true,
            ),
        )
    }

    @Test
    fun pick_skipsSlowPackWhenFasterExists() {
        val installed = listOf(pack("kokoro-en"), pack("supertonic-2"))
        assertEquals(
            SmartVoice.Choice.Use(pack("supertonic-2")),
            SmartVoice.pick(
                "en", installed, null,
                mapOf("kokoro-en" to 1.0, "supertonic-2" to 0.5), true,
            ),
        )
        // All slow: the ranking still picks one instead of asking.
        assertEquals(
            SmartVoice.Choice.Use(pack("kokoro-en")),
            SmartVoice.pick(
                "en", installed, null,
                mapOf("kokoro-en" to 1.2, "supertonic-2" to 1.5), true,
            ),
        )
        // Unknown RTF counts as fast.
        assertEquals(
            SmartVoice.Choice.Use(pack("supertonic-2")),
            SmartVoice.pick(
                "en", installed, null, mapOf("kokoro-en" to 1.0), true,
            ),
        )
    }

    @Test
    fun pick_faEnServesFaAndEn() {
        val bilingual = pack("piper-fa_en-rezahedayatfar-ibrahimwalk-medium")
        assertEquals(
            SmartVoice.Choice.Use(bilingual),
            SmartVoice.pick("fa", listOf(bilingual), null, emptyMap(), true),
        )
        assertEquals(
            SmartVoice.Choice.Use(bilingual),
            SmartVoice.pick("en", listOf(bilingual), null, emptyMap(), true),
        )
    }

    @Test
    fun pick_smartOff_reproducesRound15() {
        val explicit = pack("piper-fa_IR-amir-medium")
        assertEquals(
            SmartVoice.Choice.Use(explicit),
            SmartVoice.pick("fa", listOf(explicit), explicit, emptyMap(), false),
        )
        assertEquals(
            SmartVoice.Choice.MainVoice,
            SmartVoice.pick("fa", emptyList(), null, emptyMap(), false),
        )
        assertEquals(
            SmartVoice.Choice.MainVoice,
            SmartVoice.pick("es", emptyList(), null, emptyMap(), false),
        )
    }

    @Test
    fun pick_noVoice_autoDownloadsSmallCompactOnWifi() {
        val recommended = pack("piper-es_MX-ald-medium-compact")
        assertEquals(
            SmartVoice.Choice.AutoDownload(recommended),
            SmartVoice.pick(
                "es", emptyList(), null, emptyMap(), true,
                mainPack = pack("supertonic-2"),
                recommended = recommended, canAutoDownload = true,
            ),
        )
        // Metered / toggle off / install running: ask instead.
        assertEquals(
            SmartVoice.Choice.Ask(recommended),
            SmartVoice.pick(
                "es", emptyList(), null, emptyMap(), true,
                mainPack = pack("supertonic-2"),
                recommended = recommended, canAutoDownload = false,
            ),
        )
    }

    @Test
    fun pick_noVoice_over70MbAsksEvenOnWifi() {
        // An 80 MB Standard is never auto-downloaded.
        val big = pack("piper-es_ES-sharvard-medium")
        assertTrue(big.sizeBytes > SmartVoice.MAX_AUTO_DOWNLOAD_BYTES)
        assertEquals(
            SmartVoice.Choice.Ask(big),
            SmartVoice.pick(
                "es", emptyList(), null, emptyMap(), true,
                mainPack = pack("supertonic-2"),
                recommended = big, canAutoDownload = true,
            ),
        )
        // Supertonic 3 is Ask-only (129 MB).
        val s3 = pack("supertonic-3")
        assertEquals(
            SmartVoice.Choice.Ask(s3),
            SmartVoice.pick(
                "ko", emptyList(), null, emptyMap(), true,
                mainPack = pack("supertonic-2"),
                recommended = s3, canAutoDownload = true,
            ),
        )
    }

    @Test
    fun pick_mainSupportsLang_readsWithMain() {
        val main = pack("piper-fa_IR-amir-medium")
        assertEquals(
            SmartVoice.Choice.Use(main),
            SmartVoice.pick("fa", emptyList(), null, emptyMap(), true, mainPack = main),
        )
    }

    @Test
    fun recommendedPack_firstVoiceAzCompactElseStandard() {
        assertEquals(
            "piper-es_MX-ald-medium-compact",
            SmartVoice.recommendedPack("es")!!.id,
        )
        assertEquals(
            "piper-fa_IR-amir-medium-compact",
            SmartVoice.recommendedPack("fa")!!.id,
        )
    }

    @Test
    fun recommendedPack_noPiperFallsBackToSupertonic3OrNull() {
        assertEquals("supertonic-3", SmartVoice.recommendedPack("ko")!!.id)
        assertEquals("supertonic-3", SmartVoice.recommendedPack("ja")!!.id)
        assertEquals(null, SmartVoice.recommendedPack("th"))
    }

    @Test
    fun pickForSystem_prefersLowestMeasuredRtfOverRank() {
        // System speech must be instant: a measured-fast Piper beats Kokoro.
        val installed = listOf(pack("kokoro-en"), pack("piper-en_US-lessac-medium"))
        assertEquals(
            "piper-en_US-lessac-medium",
            SmartVoice.pickForSystem(
                "en", installed,
                mapOf("kokoro-en" to 0.6, "piper-en_US-lessac-medium" to 0.09),
            )!!.id,
        )
    }

    @Test
    fun pickForSystem_unknownRtfSortsFastWithRankTiebreak() {
        // No measurements: the best-ranked pack wins.
        val installed = listOf(pack("kitten-mini"), pack("supertonic-2"))
        assertEquals(
            "supertonic-2",
            SmartVoice.pickForSystem("en", installed, emptyMap())!!.id,
        )
        // Unknown still beats a measured-slow pack.
        assertEquals(
            "supertonic-2",
            SmartVoice.pickForSystem(
                "en", installed, mapOf("kitten-mini" to 0.4),
            )!!.id,
        )
    }

    @Test
    fun pickForSystem_onlySupportingPacksAndNullWhenNone() {
        assertEquals(
            "piper-fa_IR-amir-medium",
            SmartVoice.pickForSystem(
                "fa",
                listOf(pack("kokoro-en"), pack("piper-fa_IR-amir-medium")),
                emptyMap(),
            )!!.id,
        )
        assertEquals(null, SmartVoice.pickForSystem("th", listOf(pack("kokoro-en")), emptyMap()))
        assertEquals(null, SmartVoice.pickForSystem("en", emptyList(), emptyMap()))
    }
}
