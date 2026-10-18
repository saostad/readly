package app.readly.playback

import app.readly.model.TestVoices
import app.readly.model.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Pins which packs become per-language voices (pure `ReaderService` logic). */
class LangVoiceTest {

    @Before
    fun loadCatalog() = TestVoices.loadCatalog()

    @Test
    fun bucketForPack_routesPersianArabicAndMain() {
        fun bucket(id: String) = ReaderService.bucketForPack(VoiceCatalog.byId(id)!!)
        assertEquals("fa", bucket("piper-fa_IR-amir-medium"))
        assertEquals("fa", bucket("piper-fa-haaniye-low"))
        assertEquals("fa", bucket("piper-fa_en-rezahedayatfar-ibrahimwalk-medium"))
        assertEquals("ar", bucket("piper-ar_JO-kareem-low"))
        assertEquals(null, bucket("piper-en_US-ryan-medium"))
        assertEquals(null, bucket("kokoro-en"))
        assertEquals(null, bucket("supertonic-2"))
    }

    @Test
    fun langName_andKeys() {
        assertEquals("Persian", ReaderService.langName("fa"))
        assertEquals("Arabic", ReaderService.langName("ar"))
        assertEquals("activePackId.fa", ReaderService.langPackKey("fa"))
        assertEquals("activePackId.ar", ReaderService.langPackKey("ar"))
    }

    @Test
    fun langName_otherCodes_useDisplayLanguageWithCodeFallback() {
        assertEquals("English", ReaderService.langName("en"))
        assertEquals("Persian", ReaderService.langName("fa"))
        assertEquals("zz", ReaderService.langName("zz"))
    }
}
