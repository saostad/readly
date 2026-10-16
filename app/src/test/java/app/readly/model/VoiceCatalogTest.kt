package app.readly.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {

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
        assertEquals(21, VoiceCatalog.byId("piper-lessac-compact")!!.sizeMb)
    }
}
