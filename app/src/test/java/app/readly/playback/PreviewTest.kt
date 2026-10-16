package app.readly.playback

import app.readly.model.VoiceFamily
import app.readly.model.VoicePack
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTest {

    private fun pack(speakers: List<String>) = VoicePack(
        id = "test",
        displayName = "Test",
        variant = "v",
        voiceName = null,
        family = VoiceFamily.KOKORO,
        archive = "a.tar.bz2",
        sizeBytes = 1L,
        sha256 = "0".repeat(64),
        topDir = "t",
        modelFile = "m.onnx",
        speakers = speakers,
        rtfNote = "",
        sampleRes = 0,
    )

    @Test
    fun speakerShortName_stripsParenthetical() {
        val pack = pack(listOf("Default (American, female)", "Bella (American, female)"))
        assertEquals("Default", ReaderService.speakerShortName(pack, 0))
        assertEquals("Bella", ReaderService.speakerShortName(pack, 1))
    }

    @Test
    fun speakerShortName_keepsPlainNamesAndCoercesId() {
        val pack = pack(listOf("Voice 1", "Voice 2"))
        assertEquals("Voice 1", ReaderService.speakerShortName(pack, 0))
        assertEquals("Voice 2", ReaderService.speakerShortName(pack, 99))
        assertEquals("Voice 1", ReaderService.speakerShortName(pack, -1))
    }
}
