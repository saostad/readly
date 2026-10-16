package app.readly.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.readly.R
import app.readly.VoicesActivity
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import java.io.File

/**
 * Answers Android Settings' `CHECK_TTS_DATA`: the installed languages are
 * available, nothing is pending. Translucent; finishes at once.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceCatalog.ensureLoaded(this)
        val base = File(noBackupFilesDir, "voices")
        val langs = VoiceModelStore.installedIds(base).mapNotNull { VoiceCatalog.byId(it) }
            .flatMap { it.languages() }.distinct().sorted()
        val result = Intent().apply {
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(langs),
            )
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf(),
            )
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}

/**
 * Answers `GET_SAMPLE_TEXT` with a per-language sample sentence (English unless
 * the request names Persian). Translucent; finishes at once.
 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = intent.getStringExtra("language").orEmpty().lowercase()
        val sample = if (lang.startsWith("fa")) {
            getString(R.string.tts_sample_fa)
        } else {
            getString(R.string.tts_sample_en)
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample),
        )
        finish()
    }
}

/**
 * Answers `INSTALL_TTS_DATA` by opening the Voices screen, where voices are
 * downloaded. Translucent; finishes at once.
 */
class InstallVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, VoicesActivity::class.java))
        finish()
    }
}
