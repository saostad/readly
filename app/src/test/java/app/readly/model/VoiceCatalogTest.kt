package app.readly.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceCatalogTest {

    @Before
    fun loadCatalog() = TestVoices.loadCatalog()

    @Test
    fun ids_areUnique() {
        val ids = VoiceCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun defaultId_exists() {
        assertNotNull(VoiceCatalog.byId(VoiceCatalog.DEFAULT_ID))
    }

    @Test
    fun urls_endWithArchiveName() {
        for (pack in VoiceCatalog.ALL) {
            assertTrue("${pack.id}: ${pack.url}", pack.url.endsWith("/${pack.archive}"))
        }
    }

    @Test
    fun sha256_is64LowercaseHex() {
        val hex64 = Regex("[0-9a-f]{64}")
        for (pack in VoiceCatalog.ALL) {
            assertTrue("${pack.id}: ${pack.sha256}", hex64.matches(pack.sha256))
        }
    }

    @Test
    fun speakerCounts_matchFamily() {
        val expected = mapOf(
            VoiceFamily.KOKORO to 11,
            VoiceFamily.PIPER to 1,
            VoiceFamily.SUPERTONIC to 10,
            VoiceFamily.KITTEN to 8,
        )
        for (pack in VoiceCatalog.ALL) {
            assertEquals(pack.id, expected[pack.family], pack.speakers.size)
        }
    }

    @Test
    fun sizeMb_isDecimalRounded() {
        assertEquals(320, VoiceCatalog.byId("kokoro-en")!!.sizeMb)
        assertEquals(85, VoiceCatalog.byId("supertonic-2")!!.sizeMb)
        assertEquals(21, VoiceCatalog.byId("piper-en_US-lessac-medium-compact")!!.sizeMb)
    }

    @Test
    fun kokoroCompact_sitsAfterKokoroAndUsesInt8Model() {
        val compact = VoiceCatalog.byId("kokoro-en-compact")!!
        assertEquals(VoiceFamily.KOKORO, compact.family)
        assertEquals(11, compact.speakers.size)
        assertEquals(11, VoiceCatalog.byId(VoiceCatalog.KOKORO_ID)!!.speakers.size)
        assertEquals("model.int8.onnx", compact.modelFile)
        assertEquals("kokoro-int8-en-v0_19", compact.topDir)
        assertEquals(103, compact.sizeMb)
        val ids = VoiceCatalog.ALL.map { it.id }
        assertEquals(ids.indexOf("kokoro-en") + 1, ids.indexOf("kokoro-en-compact"))
    }

    @Test
    fun everyPack_hasVariant() {
        for (pack in VoiceCatalog.ALL) {
            assertTrue("${pack.id}: blank variant", pack.variant.isNotBlank())
        }
    }

    @Test
    fun voiceName_onlyOnPiper() {
        for (pack in VoiceCatalog.ALL) {
            if (pack.family == VoiceFamily.PIPER) {
                assertTrue("${pack.id}: blank voiceName", !pack.voiceName.isNullOrBlank())
            } else {
                assertEquals("${pack.id}: non-Piper must not have a voiceName", null, pack.voiceName)
            }
        }
    }

    @Test
    fun piperLangVoiceVariant_areUnique() {
        val keys = VoiceCatalog.packsFor(VoiceFamily.PIPER)
            .map { Triple(it.lang, it.voiceName, it.variant) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun eachFamily_hasAtLeastOnePack() {
        for (family in VoiceFamily.values()) {
            assertTrue("$family has no packs", VoiceCatalog.packsFor(family).isNotEmpty())
        }
        assertEquals(4, VoiceCatalog.FAMILIES.size)
    }

    @Test
    fun piperPack_lookupByLangVoiceVariant() {
        assertEquals(null, VoiceCatalog.piperPack("en_GB", "Alba (English)", "HD"))
        assertEquals(
            "piper-en_US-lessac-high",
            VoiceCatalog.piperPack("en_US", "Lessac (English)", "HD")!!.id,
        )
        assertEquals(
            listOf(
                "Amir (Persian)",
                "Ganji (Persian)",
                "Ganji Adabi (Persian)",
                "Gyro (Persian)",
                "Reza Ibrahim (Persian)",
            ),
            VoiceCatalog.piperVoices("fa_IR"),
        )
        val us = VoiceCatalog.piperVoices("en_US")
        assertTrue(us.containsAll(listOf("Lessac (English)", "Ryan (English)", "Amy (English)")))
    }

    @Test
    fun shortName_matchesSpec() {
        fun short(id: String) = VoiceCatalog.shortName(VoiceCatalog.byId(id)!!)
        assertEquals("Supertonic v2", short("supertonic-2"))
        assertEquals("Kokoro (Full)", short("kokoro-en"))
        assertEquals("Kokoro (Compact)", short("kokoro-en-compact"))
        assertEquals("Piper Lessac (HD)", short("piper-en_US-lessac-high"))
        assertEquals("Piper Ryan", short("piper-en_US-ryan-medium"))
        assertEquals("Kitten Mini", short("kitten-mini"))
    }

    @Test
    fun everyHandWrittenPack_hasSampleClipInResRaw() {
        // Unit tests run from the module dir (`app/`), but fall back to the repo
        // root so the check also works when run from there.
        val bundled = VoiceCatalog.ALL.filter { it.sampleRes != 0 }
        assertTrue(bundled.isNotEmpty())
        for (pack in bundled) {
            val file = "src/main/res/raw/${pack.sampleName}.ogg"
            val exists = java.io.File(file).isFile || java.io.File("app/$file").isFile
            assertTrue("${pack.id}: missing res/raw/${pack.sampleName}.ogg", exists)
        }
    }

    @Test
    fun piperJsonPacks_streamSamples() {
        val piper = VoiceCatalog.packsFor(VoiceFamily.PIPER)
        assertTrue(piper.size >= 150)
        for (pack in piper) {
            assertEquals("${pack.id}: expected no bundled clip", 0, pack.sampleRes)
            assertTrue(
                "${pack.id}: bad sample URL ${pack.sampleUrl}",
                pack.sampleUrl!!.startsWith("https://rhasspy.github.io/piper-samples/samples/"),
            )
        }
    }

    @Test
    fun piperSampleUrl_usesBaseQuality() {
        assertEquals(
            "https://rhasspy.github.io/piper-samples/samples/en/en_US/ryan/medium/speaker_0.mp3",
            VoiceCatalog.byId("piper-en_US-ryan-medium")!!.sampleUrl,
        )
        // Compacts reuse their voice's non-compact clip.
        assertEquals(
            "https://rhasspy.github.io/piper-samples/samples/en/en_US/lessac/medium/speaker_0.mp3",
            VoiceCatalog.byId("piper-en_US-lessac-medium-compact")!!.sampleUrl,
        )
        assertEquals(
            "https://rhasspy.github.io/piper-samples/samples/fa/fa_IR/amir/medium/speaker_0.mp3",
            VoiceCatalog.byId("piper-fa_IR-amir-medium")!!.sampleUrl,
        )
    }

    @Test
    fun piperLangs_englishAndPersianFirstThenAlphabetical() {
        val langs = VoiceCatalog.piperLangs()
        assertEquals(listOf("en_US", "en_GB", "fa_IR"), langs.take(3))
        val rest = langs.drop(3)
        assertEquals(
            rest.sortedBy { VoiceCatalog.langDisplay(it) },
            rest,
        )
        assertTrue("fa" in rest && "fa_en" in rest)
    }

    @Test
    fun langDisplay_matchesSpec() {
        assertEquals("Persian (Iran)", VoiceCatalog.langDisplay("fa_IR"))
        assertEquals("Persian", VoiceCatalog.langDisplay("fa"))
        assertEquals("Persian + English", VoiceCatalog.langDisplay("fa_en"))
        assertEquals("Spanish", VoiceCatalog.langDisplay("es"))
        assertEquals("English (United States)", VoiceCatalog.langDisplay("en_US"))
        assertEquals("English (United Kingdom)", VoiceCatalog.langDisplay("en_GB"))
    }

    @Test
    fun piperTagline_countsComeFromJson() {
        val entries = TestVoices.loadEntries()
        val standard = entries.count { !it.compact }
        val langs = entries.map { it.lang }.distinct().size
        assertEquals(
            "Light and fast · $standard voices in $langs languages",
            VoiceCatalog.piperTagline(),
        )
    }

    @Test
    fun piperVariants_coverQualityAndCompact() {
        fun variant(id: String) = VoiceCatalog.byId(id)!!.variant
        assertEquals("Standard", variant("piper-en_US-lessac-medium"))
        assertEquals("HD", variant("piper-en_US-lessac-high"))
        assertEquals("Compact", variant("piper-en_US-lessac-medium-compact"))
        assertEquals("Low", variant("piper-ar_JO-kareem-low"))
        // One voice can ship several int8 builds: only medium's is plain "Compact".
        assertEquals("Compact", variant("piper-de_DE-thorsten-medium-compact"))
        assertEquals("Low Compact", variant("piper-de_DE-thorsten-low-compact"))
        assertEquals("HD Compact", variant("piper-de_DE-thorsten-high-compact"))
    }

    @Test
    fun piperRtfNote_byQualityAndCompact() {
        fun note(id: String) = VoiceCatalog.byId(id)!!.rtfNote
        assertEquals("About 11× faster", note("piper-en_US-lessac-medium"))
        assertEquals("About 2.5× faster", note("piper-en_US-lessac-high"))
        assertEquals("About 5× faster", note("piper-en_US-lessac-medium-compact"))
    }

    @Test
    fun oldPiperIds_resolveViaAlias() {
        for ((old, target) in VoiceCatalog.PIPER_ALIASES) {
            assertEquals(target, VoiceCatalog.byId(old)!!.id)
            assertEquals(target, VoiceCatalog.resolvePackId(old))
        }
        assertEquals("kokoro-en", VoiceCatalog.resolvePackId("kokoro-en"))
    }

    @Test
    fun ganjiAdabi_voiceNameIsTitleCased() {
        assertEquals(
            "Ganji Adabi (Persian)",
            VoiceCatalog.byId("piper-fa_IR-ganji_adabi-medium")!!.voiceName,
        )
    }

    @Test
    fun languages_englishOnlyPacks_coverEn() {
        for (id in listOf("supertonic-2", "kokoro-en", "kokoro-en-compact", "kitten-mini")) {
            assertEquals(id, setOf("en"), VoiceCatalog.byId(id)!!.languages())
        }
    }

    @Test
    fun languages_supertonic3_matchesSpecList() {
        assertEquals(
            setOf(
                "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et",
                "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl",
                "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
            ),
            VoiceCatalog.SUPERTONIC3_LANGS,
        )
        assertEquals(31, VoiceCatalog.SUPERTONIC3_LANGS.size)
        assertEquals(
            VoiceCatalog.SUPERTONIC3_LANGS,
            VoiceCatalog.byId("supertonic-3")!!.languages(),
        )
    }

    @Test
    fun langChipLabel_showsRegionCode() {
        assertEquals("English (US)", VoiceCatalog.langChipLabel("en_US"))
        assertEquals("English (GB)", VoiceCatalog.langChipLabel("en_GB"))
        assertEquals("Persian (IR)", VoiceCatalog.langChipLabel("fa_IR"))
        assertEquals("Persian", VoiceCatalog.langChipLabel("fa"))
        assertEquals("Persian + English", VoiceCatalog.langChipLabel("fa_en"))
    }

    @Test
    fun voiceChipLabel_capsLongNamesAtFirstWord() {
        assertEquals("Lessac", VoiceCatalog.voiceChipLabel("Lessac (English)"))
        // Exactly 14 chars: kept whole.
        assertEquals("Alba (English)", VoiceCatalog.voiceChipLabel("Alba (English)"))
        assertEquals("Ganji", VoiceCatalog.voiceChipLabel("Ganji Adabi (Persian)"))
        // A hyphenated single token splits on "-" too.
        assertEquals(
            "Rezahedayatfar",
            VoiceCatalog.voiceChipLabel("Rezahedayatfar-Ibrahimwalk (Persian + English)"),
        )
    }

    @Test
    fun baseQuality_mapsCompactVariants() {
        assertEquals("Standard", VoiceCatalog.baseQuality("Standard"))
        assertEquals("Standard", VoiceCatalog.baseQuality("Compact"))
        assertEquals("Low", VoiceCatalog.baseQuality("Low Compact"))
        assertEquals("HD", VoiceCatalog.baseQuality("HD Compact"))
        assertEquals("Tiny", VoiceCatalog.baseQuality("Tiny Compact"))
        assertTrue(VoiceCatalog.isCompactVariant("Compact"))
        assertTrue(VoiceCatalog.isCompactVariant("Low Compact"))
        assertFalse(VoiceCatalog.isCompactVariant("Standard"))
        assertFalse(VoiceCatalog.isCompactVariant("Low"))
    }

    @Test
    fun segmentSizeAndDownloadLabels_matchSpec() {
        assertEquals("v2 · 85 MB", VoiceCatalog.segmentLabel("v2", 85))
        assertEquals("Full · 67 MB", VoiceCatalog.sizeLabel(false, 67))
        assertEquals("Compact · 21 MB", VoiceCatalog.sizeLabel(true, 21))
        assertEquals("Download · 21 MB", VoiceCatalog.downloadLabel(21))
    }

    @Test
    fun languages_piper_usesLangPrefix() {
        fun langs(id: String) = VoiceCatalog.byId(id)!!.languages()
        assertEquals(setOf("en"), langs("piper-en_US-lessac-medium"))
        assertEquals(setOf("en"), langs("piper-en_GB-alba-medium"))
        assertEquals(setOf("fa"), langs("piper-fa_IR-amir-medium"))
        assertEquals(setOf("fa"), langs("piper-fa-haaniye-low"))
        assertEquals(setOf("fa", "en"), langs("piper-fa_en-rezahedayatfar-ibrahimwalk-medium"))
        assertEquals(setOf("es"), langs("piper-es_ES-davefx-medium"))
        assertEquals(setOf("ar"), langs("piper-ar_JO-kareem-low"))
        assertEquals(setOf("ru"), langs("piper-ru_RU-dmitri-medium"))
    }
}
