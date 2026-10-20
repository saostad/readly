package app.readly.tts.phonon

import app.readly.model.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of [PhononEngine]; the native library is not available on the host. */
class PhononEngineTest {

    @Test
    fun clampSpeed_matchesPocketRange() {
        assertEquals(1.0f, PhononEngine.clampSpeed(1.0f), 0f)
        assertEquals(0.5f, PhononEngine.clampSpeed(0.5f), 0f)
        assertEquals(2.0f, PhononEngine.clampSpeed(2.0f), 0f)
        assertEquals(0.5f, PhononEngine.clampSpeed(0.1f), 0f)
        assertEquals(2.0f, PhononEngine.clampSpeed(3.0f), 0f)
        assertEquals(1.0f, PhononEngine.clampSpeed(Float.NaN), 0f)
        assertEquals(1.0f, PhononEngine.clampSpeed(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1.0f, PhononEngine.clampSpeed(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun voiceNameFor_mapsSpeakerIdToVoiceFile() {
        assertEquals(6, VoiceCatalog.PHONON_VOICE_FILES.size)
        assertEquals("zoey", PhononEngine.voiceNameFor(0))
        assertEquals("everly", PhononEngine.voiceNameFor(1))
        assertEquals("easton", PhononEngine.voiceNameFor(2))
        assertEquals("micah", PhononEngine.voiceNameFor(3))
        assertEquals("freya", PhononEngine.voiceNameFor(4))
        assertEquals("alfie", PhononEngine.voiceNameFor(5))
    }

    @Test
    fun voiceNameFor_outOfRangeNamesIdAndRange() {
        val low = assertThrows(IllegalArgumentException::class.java) {
            PhononEngine.voiceNameFor(-1)
        }
        assertTrue(low.message!!.contains("-1"))
        assertTrue(low.message!!.contains("0..5"))

        val high = assertThrows(IllegalArgumentException::class.java) {
            PhononEngine.voiceNameFor(6)
        }
        assertTrue(high.message!!.contains("6"))
        assertTrue(high.message!!.contains("0..5"))
    }
}
