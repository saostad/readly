package app.readly

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneVoiceSettingsTest {

    @Test
    fun statusText_defaultIsReadly() {
        assertEquals(
            "✓ Readly is your phone's voice.",
            PhoneVoiceSettings.statusText("app.readly", "app.readly", ""),
        )
    }

    @Test
    fun statusText_defaultIsAnotherEngine() {
        assertEquals(
            "Your phone speaks with Google Text-to-Speech.",
            PhoneVoiceSettings.statusText(
                "com.google.android.tts",
                "app.readly",
                "Google Text-to-Speech",
            ),
        )
    }

    @Test
    fun statusText_defaultIsNull() {
        assertEquals(
            "Your phone's voice engine couldn't be read.",
            PhoneVoiceSettings.statusText(null, "app.readly", ""),
        )
    }
}
