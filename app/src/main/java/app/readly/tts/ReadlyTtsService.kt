package app.readly.tts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import app.readly.ReadlyApp
import app.readly.model.SmartVoice
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.playback.ReaderService
import app.readly.text.TextSanitizer
import java.io.File
import java.util.Locale
import java.util.MissingResourceException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Readly as the phone's system TTS engine (TalkBack, Maps, …): selectable in
 * Settings → System → Languages → Text-to-speech output.
 *
 * Voices are the installed packs × their speakers, named `"<packId>#<sid>"`.
 * Synthesis runs through the shared [EngineManager] (serialized on `readly-tts`
 * with the reader), streams 16-bit PCM via the sherpa chunk callback, and
 * honors the request's speech rate. Pitch is ignored (logged once). Text is
 * spoken as given except emoji and URLs ([TextSanitizer.forSystemTts]).
 */
class ReadlyTtsService : TextToSpeechService() {

    private val engines: EngineManager by lazy { (applicationContext as ReadlyApp).engines }

    /** Set by [onStop]; the sherpa chunk callback aborts promptly when true. */
    private val stopRequested = AtomicBoolean(false)

    private val pitchLogged = AtomicBoolean(false)

    /** Last logged language→pack pair, so the pick is logged once per load. */
    @Volatile
    private var lastTtsLogKey: String? = null

    @Volatile
    private var currentLang: Array<String> = arrayOf("eng", "", "")

    @Volatile
    private var currentVoice: String? = null

    override fun onCreate() {
        super.onCreate()
        // Belt and braces: ReadlyApp already loads the catalog before any component.
        VoiceCatalog.ensureLoaded(this)
    }

    // ------------------------------------------------------------------
    // Languages and voices
    // ------------------------------------------------------------------

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val two = iso3ToLang2(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val packs = installedPacks().filter { two in it.languages() }
        if (packs.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        val wantCountry = country?.uppercase(Locale.US).orEmpty()
        if (wantCountry.isNotBlank() && packs.any { packCountry3(it) == wantCountry }) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability == TextToSpeech.LANG_AVAILABLE ||
            availability == TextToSpeech.LANG_COUNTRY_AVAILABLE
        ) {
            currentLang = arrayOf(lang ?: "", country ?: "", variant ?: "")
        }
        return availability
    }

    override fun onGetLanguage(): Array<String> = currentLang

    override fun onGetVoices(): List<Voice> {
        val rtf = rtfByPack()
        return installedPacks().flatMap { pack ->
            pack.speakers.indices.map { sid ->
                Voice(
                    "${pack.id}#$sid",
                    voiceLocale(pack),
                    if (SmartVoice.rank(pack) >= 4) Voice.QUALITY_HIGH else Voice.QUALITY_NORMAL,
                    latencyOf(rtf[pack.id]),
                    false,
                    emptySet(),
                )
            }
        }
    }

    override fun onIsValidVoiceName(name: String?): Int =
        if (parseVoice(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(name: String?): Int {
        val parsed = parseVoice(name) ?: return TextToSpeech.ERROR
        currentVoice = name
        currentLang = arrayOf(iso2ToIso3(parsed.first.languages().first()), "", "")
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val (pack, sid) = defaultForRequest(lang, country) ?: return null
        return "${pack.id}#$sid"
    }

    // ------------------------------------------------------------------
    // Synthesis
    // ------------------------------------------------------------------

    override fun onStop() {
        stopRequested.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested.set(false)
        val text = TextSanitizer.forSystemTts(request.charSequenceText.toString())
        if (text.isBlank()) {
            callback.done()
            return
        }
        val resolved = parseVoice(request.voiceName)
            ?: defaultForRequest(request.language, request.country)
        if (resolved == null) {
            Log.e("Readly", "System TTS has no voice for ${request.language}-${request.country}")
            callback.error()
            return
        }
        val (pack, sid) = resolved
        if (request.pitch != 100 && pitchLogged.compareAndSet(false, true)) {
            Log.i("Readly", "Ignoring pitch ${request.pitch}: Readly voices don't support pitch")
        }
        val speed = (request.speechRate / 100f).coerceIn(0.5f, 2.0f)
        val lang = iso3ToLang2(request.language) ?: "en"
        val logKey = "$lang/${pack.id}"
        if (logKey != lastTtsLogKey) {
            lastTtsLogKey = logKey
            Log.i("Readly", "System TTS: $lang → ${pack.id}")
        }
        val rate = try {
            engines.preload(pack.id)
        } catch (e: Exception) {
            Log.e("Readly", "System TTS could not load ${pack.id}: ${e.javaClass.name}: ${e.message}", e)
            callback.error()
            return
        }
        if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        try {
            val maxChunk = maxOf(callback.maxBufferSize, 1024)
            engines.synthesize(
                pack.id, text, sid, speed, lang,
                isStopped = { stopRequested.get() },
                onChunk = { samples -> writePcm16(callback, samples, maxChunk) },
            )
        } catch (e: Exception) {
            Log.e(
                "Readly",
                "System TTS synthesis failed (${pack.id}): ${e.javaClass.name}: ${e.message}",
                e,
            )
            callback.error()
            return
        }
        callback.done()
    }

    /**
     * Default voice for a request language: the user's system voice when it is
     * installed and supports the language, else the fastest installed pack
     * supporting it ([SmartVoice.pickForSystem]).
     */
    private fun defaultForRequest(language: String?, country: String?): Pair<VoicePack, Int>? {
        val two = iso3ToLang2(language) ?: "en"
        val installed = installedPacks()
        val system = systemPackId(this)?.let { VoiceCatalog.byId(it) }
        val pack = if (system != null && system in installed && two in system.languages()) {
            system
        } else {
            SmartVoice.pickForSystem(two, installed, rtfByPack())
        } ?: return null
        val sid = ReaderService.speakerIdFor(this, pack.id).coerceIn(pack.speakers.indices)
        return pack to sid
    }

    /** `"<packId>#<sid>"` when the pack is installed and the speaker exists, else null. */
    private fun parseVoice(name: String?): Pair<VoicePack, Int>? {
        if (name.isNullOrBlank()) return null
        val pack = VoiceCatalog.byId(name.substringBeforeLast("#")) ?: return null
        val sid = name.substringAfterLast("#", "").toIntOrNull() ?: return null
        if (sid !in pack.speakers.indices) return null
        if (!VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener).isInstalled()) {
            return null
        }
        return pack to sid
    }

    /** Converts sherpa float samples to 16-bit PCM, clamped, honoring [maxChunk]. */
    private fun writePcm16(callback: SynthesisCallback, samples: FloatArray, maxChunk: Int) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort().toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(maxChunk, bytes.size - offset)
            if (callback.audioAvailable(bytes, offset, n) != TextToSpeech.SUCCESS) return
            offset += n
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun voicesDir(): File = File(noBackupFilesDir, "voices")

    /** Installed packs in catalog order. */
    private fun installedPacks(): List<VoicePack> {
        val ids = VoiceModelStore.installedIds(voicesDir()).toSet()
        return VoiceCatalog.ALL.filter { it.id in ids }
    }

    /** Measured RTF (EMA) by pack id, for packs that have synthesized at least once. */
    private fun rtfByPack(): Map<String, Double> {
        val prefs = getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        return installedPacks().mapNotNull { pack ->
            val rtf = prefs.getFloat(EngineManager.rtfKey(pack.id), -1f)
            if (rtf >= 0) pack.id to rtf.toDouble() else null
        }.toMap()
    }

    /** Latency flag from the measured RTF (unknown → normal). */
    private fun latencyOf(rtf: Double?): Int = when {
        rtf == null -> Voice.LATENCY_NORMAL
        rtf < 0.35 -> Voice.LATENCY_LOW
        rtf > 0.9 -> Voice.LATENCY_HIGH
        else -> Voice.LATENCY_NORMAL
    }

    /** Display locale for [pack]'s voice: Piper's own, Supertonic 3's best match, else English. */
    private fun voiceLocale(pack: VoicePack): Locale {
        pack.lang?.let { code ->
            if (code == "fa_en") return Locale("fa")
            val parts = code.split("_")
            if (parts.size == 2 && parts[1].length == 2 && parts[1].all { it.isUpperCase() }) {
                return Locale(parts[0], parts[1])
            }
            return Locale(parts[0])
        }
        if (pack.id == "supertonic-3") {
            val system = Locale.getDefault().language.lowercase(Locale.US)
            if (system in VoiceCatalog.SUPERTONIC3_LANGS) return Locale.getDefault()
        }
        return Locale.ENGLISH
    }

    /** ISO3 country of [pack]'s region (`en_US` → `USA`), or null when none. */
    private fun packCountry3(pack: VoicePack): String? {
        val region = pack.lang?.substringAfter("_", "")?.takeIf {
            it.length == 2 && it.all { c -> c.isUpperCase() }
        } ?: return null
        return try {
            Locale("", region).isO3Country.uppercase(Locale.US)
        } catch (_: MissingResourceException) {
            null
        }
    }

    /** ISO3 language (`eng`) → 2-letter (`en`), or null. */
    private fun iso3ToLang2(iso3: String?): String? {
        if (iso3.isNullOrBlank()) return null
        val want = iso3.lowercase(Locale.US)
        if (want.length == 2) return want
        for (two in SUPPORTED_LANGS) {
            if (iso2ToIso3(two).lowercase(Locale.US) == want) return two
        }
        return null
    }

    /** 2-letter language → ISO3 (`en` → `eng`); unknown codes pass through. */
    private fun iso2ToIso3(two: String): String = try {
        Locale(two).isO3Language
    } catch (_: MissingResourceException) {
        two
    }

    companion object {
        /** System TTS voice pack id in the `settings` prefs; null means automatic. */
        const val KEY_SYSTEM_PACK = "systemPackId"

        /**
         * The user's system voice pack id, or null for automatic (fastest voice
         * per language). Unknown ids read as null.
         */
        fun systemPackId(context: Context): String? {
            val id = context.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SYSTEM_PACK, null) ?: return null
            return id.takeIf { VoiceCatalog.byId(it) != null }
        }

        /**
         * 2-letter codes worth mapping from ISO3: every code any pack can serve
         * (Supertonic 3's 31 plus the generated Piper prefixes, which are covered
         * because the service only maps codes the catalog knows — see below).
         */
        private val SUPPORTED_LANGS: Set<String> by lazy {
            VoiceCatalog.SUPERTONIC3_LANGS + VoiceCatalog.ALL.mapNotNull { pack ->
                pack.lang?.substringBefore("_")?.lowercase(Locale.US)
            }
        }
    }
}
