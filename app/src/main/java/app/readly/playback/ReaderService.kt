package app.readly.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.readly.InstallTask
import app.readly.MainActivity
import app.readly.ReadlyApp
import app.readly.VoicesActivity
import app.readly.input.ArticleFetcher
import app.readly.model.SmartVoice
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceFamily
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.text.Chunk
import app.readly.text.LanguageDetector
import app.readly.text.SentenceChunker
import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer
import app.readly.tts.SpeechEngine
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background playback service: owns the [SpeechEngine], runs the [ChunkPipeline] and
 * publishes state to [ReaderState].
 *
 * Threading: the engine lives on the single `readly-tts` thread for the whole service
 * life; pipeline `synth` calls are posted there and waited on. PRELOAD loads the engine
 * by posting directly to `readly-tts`, so it never blocks document handling. Document
 * handling (READ / READ_URL / NEXT / PREVIOUS / STOP) runs on the single `readly-read`
 * executor so stop-and-join never blocks the main thread. PAUSE / PLAY / TOGGLE are
 * instant and run on the main thread.
 */
class ReaderService : Service() {

    companion object {
        const val ACTION_READ = "app.readly.READ"
        const val ACTION_PRELOAD = "app.readly.PRELOAD"
        const val ACTION_TOGGLE = "app.readly.TOGGLE"
        const val ACTION_PAUSE = "app.readly.PAUSE"
        const val ACTION_PLAY = "app.readly.PLAY"
        const val ACTION_NEXT = "app.readly.NEXT"
        const val ACTION_PREVIOUS = "app.readly.PREVIOUS"
        const val ACTION_STOP = "app.readly.STOP"
        const val ACTION_READ_URL = "app.readly.READ_URL"
        const val ACTION_READ_ANYWAY = "app.readly.READ_ANYWAY"

        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE_HINT = "titleHint"

        const val PREFS_NAME = "settings"
        const val KEY_ACTIVE_PACK = "activePackId"

        /** Most recently selected Piper language (the card's default). */
        const val KEY_PIPER_LANG = "piperLang"

        /** Legacy single speaker key; migrated to [speakerKey] once (see [speakerIdFor]). */
        const val KEY_SPEAKER_ID = "speakerId"
        const val KEY_SPEED = "speed"

        /** Smart voice master toggle (default ON). */
        const val KEY_SMART_VOICE = "smartVoice"

        /** Auto-download small voices on Wi-Fi (default ON). */
        const val KEY_AUTO_DOWNLOAD = "autoDownloadVoices"

        /** Per-pack RTF EMA key (`rtf.<packId>`, float). */
        fun rtfKey(packId: String): String = "rtf.$packId"

        fun isSmartOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SMART_VOICE, true)

        fun isAutoDownloadOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_DOWNLOAD, true)

        /** Display name for a 2-letter language code (e.g. `es` → `Spanish`). */
        fun languageName(lang: String): String =
            Locale(lang).getDisplayLanguage(Locale.US)
                .replaceFirstChar { it.titlecase(Locale.US) }.ifEmpty { lang }

        /** Per-pack speaker key. */
        fun speakerKey(packId: String): String = "speakerId.$packId"

        /** Per-language voice key (`activePackId.fa`, `activePackId.ar`). */
        fun langPackKey(lang: String): String = "$KEY_ACTIVE_PACK.$lang"

        /**
         * Pack id assigned to [lang] (`"fa"`/`"ar"`), or null when unset or
         * unknown. Old Piper ids migrate to the generated catalog ids.
         */
        fun packIdForLang(context: Context, lang: String): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val id = prefs.getString(langPackKey(lang), null) ?: return null
            val canonical = VoiceCatalog.resolvePackId(id)
            return if (VoiceCatalog.byId(canonical) != null) canonical else null
        }

        fun setPackIdForLang(context: Context, lang: String, packId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(langPackKey(lang), packId).apply()
        }

        /** `"fa"` → `"Persian"`, `"ar"` → `"Arabic"`. */
        fun langName(lang: String): String = when (lang) {
            "fa" -> "Persian"
            "ar" -> "Arabic"
            else -> lang
        }

        /**
         * Language bucket for [pack]: `"fa"` for Persian Piper packs (lang `fa`,
         * `fa_IR`, `fa_en`), `"ar"` for Arabic ones, null for everything else
         * (those are main-voice packs).
         */
        fun bucketForPack(pack: VoicePack): String? = when {
            pack.family != VoiceFamily.PIPER || pack.lang == null -> null
            pack.lang.startsWith("fa") -> "fa"
            pack.lang.startsWith("ar") -> "ar"
            else -> null
        }

        /**
         * The active voice pack id. When unset: an existing Kokoro install keeps
         * working (no re-download); otherwise the catalog default. Persisted on
         * first use.
         */
        fun activePackId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_ACTIVE_PACK, null)?.let { stored ->
                // Old Piper ids migrate to the generated catalog ids (same install dir).
                val canonical = VoiceCatalog.resolvePackId(stored)
                if (VoiceCatalog.byId(canonical) != null) {
                    if (canonical != stored) {
                        prefs.edit().putString(KEY_ACTIVE_PACK, canonical).apply()
                    }
                    return canonical
                }
            }
            val base = File(context.noBackupFilesDir, "voices")
            val kokoro = VoiceCatalog.byId(VoiceCatalog.KOKORO_ID)!!
            val id =
                if (VoiceModelStore.isPackInstalled(base, kokoro)) kokoro.id
                else VoiceCatalog.DEFAULT_ID
            prefs.edit().putString(KEY_ACTIVE_PACK, id).apply()
            return id
        }

        /** The active [VoicePack]. */
        fun activePack(context: Context): VoicePack =
            VoiceCatalog.byId(activePackId(context))!!

        /**
         * Speaker id for [packId], migrating the legacy `speakerId` setting to
         * `speakerId.kokoro-en` the first time it is read.
         */
        fun speakerIdFor(context: Context, packId: String): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_SPEAKER_ID)) {
                val old = prefs.getInt(KEY_SPEAKER_ID, 0)
                val kokoroKey = speakerKey(VoiceCatalog.KOKORO_ID)
                if (!prefs.contains(kokoroKey)) {
                    prefs.edit().putInt(kokoroKey, old).apply()
                }
                prefs.edit().remove(KEY_SPEAKER_ID).apply()
            }
            val canonical = VoiceCatalog.resolvePackId(packId)
            if (prefs.contains(speakerKey(canonical))) {
                return prefs.getInt(speakerKey(canonical), 0)
            }
            // A speaker setting stored under an old Piper id moves with the pack.
            for ((old, target) in VoiceCatalog.PIPER_ALIASES) {
                if (target == canonical && prefs.contains(speakerKey(old))) {
                    return prefs.getInt(speakerKey(old), 0)
                }
            }
            return 0
        }

        fun setSpeakerId(context: Context, packId: String, id: Int) {
            val canonical = VoiceCatalog.resolvePackId(packId)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(speakerKey(canonical), id).apply()
        }

        /** Reads [text] aloud. [format] is the [SourceFormat] name (default PLAIN). */
        fun read(context: Context, text: String, title: String, format: SourceFormat) {
            context.startForegroundService(
                Intent(context, ReaderService::class.java)
                    .setAction(ACTION_READ)
                    .putExtra(EXTRA_TEXT, text)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_FORMAT, format.name),
            )
        }

        /**
         * True once any engine is loaded. Checked by [preload] so reopening the app
         * doesn't restart the service (and flash a notification) for nothing.
         */
        @Volatile
        var engineLoaded = false

        /** Pack ids with a loaded engine (at most [MAX_ENGINES], LRU). */
        @Volatile
        var loadedPackIds: Set<String> = emptySet()

        /** Fetches [url] in the service, then reads the extracted article aloud. */
        fun readUrl(context: Context, url: String, titleHint: String?) {
            context.startForegroundService(
                Intent(context, ReaderService::class.java)
                    .setAction(ACTION_READ_URL)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE_HINT, titleHint),
            )
        }

        /** Loads the voice in the background so the next READ starts instantly. */
        fun preload(context: Context) {
            if (activePackId(context) in loadedPackIds) return
            context.startForegroundService(
                Intent(context, ReaderService::class.java).setAction(ACTION_PRELOAD),
            )
        }

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "playback"

        /** Idle grace after FINISHED / STOP before the service and engine shut down. */
        private const val IDLE_TIMEOUT_MS = 10L * 60 * 1000

        /** Loaded engines kept: English↔Persian alternation stays cached. */
        private const val MAX_ENGINES = 2
    }

    private lateinit var mainHandler: Handler
    private lateinit var ttsThread: HandlerThread
    private lateinit var ttsHandler: Handler
    private lateinit var readerExecutor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession

    private data class EngineSlot(val engine: SpeechEngine, val rate: Int)

    /**
     * Loaded engines by pack id, LRU (access order), at most [MAX_ENGINES].
     * Touched on `readly-tts` ONLY; other threads use the [loadedPackIds] and
     * [rateByPack] snapshots. A Piper engine holds about 70–120 MB of model
     * weights, so the 2-engine cap matters: alternating documents stay cached
     * without ballooning memory.
     */
    private val engines = LinkedHashMap<String, EngineSlot>(4, 0.75f, true)

    /** Sample rate by loaded pack id; written on `readly-tts`, read anywhere. */
    @Volatile
    private var rateByPack: Map<String, Int> = emptyMap()

    private val pipelineLock = Object()

    /**
     * READ/READ_URL requests accepted but not yet started, failed or emptied.
     * Incremented in [onStartCommand], decremented exactly once per request before its
     * terminal state change. Guards [preloadEngine]'s idle path and [goIdle] so a
     * concurrent preload or an older completion can't detach while a request is live.
     */
    private val pendingRequests = AtomicInteger(0)

    @Volatile
    private var pipeline: ChunkPipeline? = null

    private var currentChunks: List<Chunk> = emptyList()
    private var currentTitle: String = ""
    private var currentPackId: String = ""
    private var currentLang: String = "en"

    /** Text held while Smart voice waits for the user's Ask choice. Read-anyway consumes it. */
    private data class PendingAsk(
        val title: String,
        val text: String,
        val format: SourceFormat,
        val notice: String?,
        val recommendedId: String?,
    )

    @Volatile
    private var pendingAsk: PendingAsk? = null

    private val trackLock = Object()
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var framesWritten = 0L

    /** Main thread only: whether startForeground is currently in effect. */
    private var inForeground = false

    @Volatile
    private var destroyed = false

    private val focusLock = Object()
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Main thread only: an audio-focus transient loss paused us, so the next gain
     * resumes. Cleared by any manual pause/resume (the user took over).
     */
    private var pausedForFocusLoss = false

    private val noisyLock = Object()
    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseReading()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pauseReading()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (ReaderState.snapshot.status == ReaderStatus.READING) {
                    pausedForFocusLoss = true
                    pauseReading(clearFocusResume = false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Let the system duck us; keep reading quietly underneath.
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedForFocusLoss) {
                    pausedForFocusLoss = false
                    resumeReading()
                }
            }
        }
    }

    private val idleShutdown = Runnable {
        Log.i("Readly", "Reader idle for 10 minutes, shutting down")
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
        ttsThread = HandlerThread("readly-tts").also { it.start() }
        ttsHandler = Handler(ttsThread.looper)
        readerExecutor = Executors.newSingleThreadExecutor { Thread(it, "readly-read") }
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reading aloud", NotificationManager.IMPORTANCE_LOW),
        )
        audioManager = getSystemService(AudioManager::class.java)
        mediaSession = MediaSession(this, "Readly").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = resumeReading()
                    override fun onPause() = pauseReading()
                    override fun onSkipToNext() = moveBy(1)
                    override fun onSkipToPrevious() = moveBy(-1)
                    override fun onStop() = handleStop()
                },
                mainHandler,
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The single foreground entry point: every command starts foreground here, on
        // the main thread, so worker paths only ever call updateNotification().
        ensureForeground()
        mainHandler.removeCallbacks(idleShutdown)
        when (intent?.action) {
            ACTION_READ -> {
                pendingRequests.incrementAndGet()
                handleRead(
                    intent.getStringExtra(EXTRA_TEXT) ?: "",
                    intent.getStringExtra(EXTRA_TITLE) ?: "",
                    intent.getStringExtra(EXTRA_FORMAT) ?: SourceFormat.PLAIN.name,
                )
            }
            ACTION_READ_URL -> {
                pendingRequests.incrementAndGet()
                handleReadUrl(
                    intent.getStringExtra(EXTRA_URL) ?: "",
                    intent.getStringExtra(EXTRA_TITLE_HINT),
                )
            }
            // Straight to readly-tts: posting through readerExecutor would block the
            // page fetch behind the whole model load. A READ's ensureEngineOrThrow() then
            // waits behind this on the same FIFO queue.
            ACTION_PRELOAD -> ttsHandler.post(::preloadEngine)
            ACTION_TOGGLE -> toggleReading()
            ACTION_PAUSE -> pauseReading()
            ACTION_PLAY -> resumeReading()
            ACTION_NEXT -> moveBy(1)
            ACTION_PREVIOUS -> moveBy(-1)
            ACTION_STOP -> handleStop()
            ACTION_READ_ANYWAY -> handleReadAnyway()
            else -> Log.w("Readly", "ReaderService ignoring unknown action ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(idleShutdown)
        readerExecutor.shutdownNow()
        // Join playback first: the producer may be inside a synth call on readly-tts,
        // and the engine must not close under it.
        stopPipeline()
        try {
            runOnTtsThreadAndWait(
                {
                    for ((id, slot) in engines) {
                        try {
                            slot.engine.close()
                        } catch (e: Exception) {
                            Log.w("Readly", "Closing the $id voice failed", e)
                        }
                    }
                    engines.clear()
                    publishEngineSnapshot()
                },
                timeoutMs = 10_000,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w("Readly", "Engine shutdown was interrupted", e)
        }
        ttsThread.quitSafely()
        try {
            ttsThread.join(5000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(trackLock) {
            try {
                audioTrack?.release()
            } catch (_: Exception) {
                // Shutting down; nothing to report to.
            }
            audioTrack = null
        }
        notificationManager.cancel(NOTIF_ID)
        abandonFocus()
        setNoisyRegistered(false)
        mediaSession.isActive = false
        mediaSession.release()
        ReaderState.update { it.copy(status = ReaderStatus.IDLE) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private fun handleRead(text: String, title: String, formatName: String) {
        readerExecutor.execute {
            val finished = AtomicBoolean(false)
            fun finish() {
                if (finished.compareAndSet(false, true)) pendingRequests.decrementAndGet()
            }
            try {
                val format = try {
                    SourceFormat.valueOf(formatName)
                } catch (e: IllegalArgumentException) {
                    finish()
                    postError(e)
                    return@execute
                }
                startReading(title, text, format, notice = null, onRequestDone = ::finish)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                finish()
            } catch (e: Exception) {
                finish()
                postError(e)
            } finally {
                finish()
            }
        }
    }

    private fun handleReadUrl(url: String, titleHint: String?) {
        readerExecutor.execute {
            val finished = AtomicBoolean(false)
            fun finish() {
                if (finished.compareAndSet(false, true)) pendingRequests.decrementAndGet()
            }
            try {
                stopPipeline()
                val host = try {
                    URI(url).host ?: url
                } catch (_: Exception) {
                    url
                }
                // Foreground was already entered in onStartCommand; a worker-side
                // startForeground here could run after a concurrent goIdle() detached.
                ReaderState.update {
                    it.copy(
                        status = ReaderStatus.FETCHING,
                        title = titleHint.orEmpty(),
                        currentText = "Opening $host…",
                        index = 0,
                        total = 0,
                        errorMessage = null,
                        notice = null,
                    )
                }
                updateNotification()
                val doc = ArticleFetcher(ArticleFetcher::httpUrlOpener).fetch(url)
                // Articles bring their own title; fetched text/markdown falls back
                // to the shared headline when there is one.
                val title = if (doc.format == SourceFormat.HTML) {
                    doc.title
                } else {
                    titleHint?.takeIf { it.isNotBlank() } ?: doc.title
                }
                startReading(title, doc.text, doc.format, doc.notice, ::finish)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                finish()
            } catch (e: Exception) {
                finish()
                postError(e)
            } finally {
                finish()
            }
        }
    }

    /**
     * Sanitizes [text], picks a voice with Smart voice, chunks it and starts the
     * pipeline, replacing any current reading. Runs on `readly-read`. [notice] is
     * shown on the main screen and Toasted once reading starts. [onRequestDone] is
     * invoked exactly once when the request starts a pipeline, asks, or ends empty
     * (before any idle transition). [forceMain] reads with the main voice without
     * asking (the Ask notification's "Read anyway").
     */
    private fun startReading(
        title: String,
        text: String,
        format: SourceFormat,
        notice: String?,
        onRequestDone: () -> Unit,
        forceMain: Boolean = false,
    ) {
        stopPipeline()
        pendingAsk = null
        ReaderState.update { it.copy(notice = null) }
        val clean = TextSanitizer.sanitize(text, format)
        val detected = LanguageDetector.detect(this, clean)
        val lang = detected.lang
        val smartOn = isSmartOn(this)
        val mainPack = activePack(this)
        if (forceMain) {
            Log.i(
                "Readly",
                "Smart voice: lang=$lang (conf ${detected.confidence}, ${detected.source}) " +
                    "→ ReadAnyway ${mainPack.id}",
            )
            startWithPack(title, clean, lang, mainPack, notice, onRequestDone)
            return
        }
        val explicit = installedLangPack(lang)
        val recommended = SmartVoice.recommendedPack(lang)
        val canAuto = isAutoDownloadOn(this) && !isMetered() && !isInstallRunning()
        val choice = SmartVoice.pick(
            lang, installedPacks(), explicit, rtfByPack(), smartOn,
            mainPack, recommended, canAuto,
        )
        Log.i(
            "Readly",
            "Smart voice: lang=$lang (conf ${detected.confidence}, ${detected.source}) " +
                "→ ${describe(choice, mainPack)}",
        )
        when (choice) {
            is SmartVoice.Choice.Use ->
                startWithPack(title, clean, lang, choice.pack, notice, onRequestDone)
            SmartVoice.Choice.MainVoice -> {
                val langNotice = if (!smartOn && (lang == "fa" || lang == "ar") && explicit == null) {
                    val name = langName(lang)
                    Log.w("Readly", "Text looks $name but no $name voice is selected; using the main voice")
                    "This text looks $name, but no $name voice is selected. " +
                        "Open Change voice pack → Piper → Language: $name to pick one."
                } else {
                    null
                }
                startWithPack(
                    title, clean, lang, mainPack,
                    listOfNotNull(notice, langNotice).joinToString(" ").takeIf { it.isNotBlank() },
                    onRequestDone,
                )
            }
            is SmartVoice.Choice.AutoDownload ->
                autoDownloadAndRead(title, text, clean, lang, format, notice, choice.pack, onRequestDone)
            is SmartVoice.Choice.Ask ->
                showAsk(title, text, format, notice, lang, choice.pack, onRequestDone)
        }
    }

    /** Chunks [clean] and starts the pipeline with [docPack], or reports empty. */
    private fun startWithPack(
        title: String,
        clean: String,
        lang: String,
        docPack: VoicePack,
        notice: String?,
        onRequestDone: () -> Unit,
    ) {
        val chunks = SentenceChunker.chunk(clean, lang)
        if (chunks.isEmpty()) {
            showEmpty(title, clean.length, onRequestDone)
            return
        }
        startPipeline(chunks, title, 0, docPack, lang, onRequestDone)
        if (!notice.isNullOrBlank()) {
            ReaderState.update { it.copy(notice = notice) }
            mainHandler.post {
                Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showEmpty(title: String, inChars: Int, onRequestDone: () -> Unit) {
        val message =
            "Nothing to read: the text was empty after removing formatting, " +
            "links and symbols."
        Log.w("Readly", "READ produced no chunks ($inChars chars in)")
        ReaderState.update {
            it.copy(
                status = ReaderStatus.ERROR,
                title = title,
                currentText = "",
                index = 0,
                total = 0,
                errorMessage = message,
                notice = null,
            )
        }
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        updateNotification()
        onRequestDone()
        goIdle()
    }

    private fun describe(choice: SmartVoice.Choice, mainPack: VoicePack): String = when (choice) {
        is SmartVoice.Choice.Use -> "Use ${choice.pack.id}"
        is SmartVoice.Choice.AutoDownload -> "AutoDownload ${choice.pack.id}"
        is SmartVoice.Choice.Ask -> "Ask ${choice.pack?.id ?: "none"}"
        SmartVoice.Choice.MainVoice -> "MainVoice ${mainPack.id}"
    }

    /**
     * Downloads [pack] with the single shared [InstallTask], then reads with it.
     * Runs on `readly-read`. Failures Toast the exact text and fall back to asking.
     */
    private fun autoDownloadAndRead(
        title: String,
        text: String,
        clean: String,
        lang: String,
        format: SourceFormat,
        notice: String?,
        pack: VoicePack,
        onRequestDone: () -> Unit,
    ) {
        val chunks = SentenceChunker.chunk(clean, lang)
        if (chunks.isEmpty()) {
            showEmpty(title, clean.length, onRequestDone)
            return
        }
        val display = languageName(lang)
        ReaderState.update {
            it.copy(
                status = ReaderStatus.FETCHING,
                title = title,
                currentText = "Downloading a $display voice (${pack.sizeMb} MB)…",
                index = 0,
                total = 0,
                errorMessage = null,
                notice = null,
            )
        }
        updateNotification()
        val error = runSharedInstall(pack)
        if (error != null) {
            val message = "Voice download failed: ${error.javaClass.name}: ${error.message}"
            Log.e("Readly", "$message (${pack.id})", error)
            mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            showAsk(title, text, format, notice, lang, pack, onRequestDone)
            return
        }
        startPipeline(chunks, title, 0, pack, lang, onRequestDone)
        if (!notice.isNullOrBlank()) {
            ReaderState.update { it.copy(notice = notice) }
            mainHandler.post {
                Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Runs [pack]'s install on the shared app-wide task and waits. Returns null on
     * success, the failure otherwise. Callers checked [isInstallRunning] first, so a
     * live task here means a race (also returned as a failure to ask instead).
     */
    private fun runSharedInstall(pack: VoicePack): Exception? {
        val app = application as ReadlyApp
        if (app.installTask?.isDone == false) {
            return IOException("another voice is already downloading")
        }
        val store = VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener)
        val done = CountDownLatch(1)
        // Written on the main thread, read after the latch: the latch guarantees visibility.
        var failure: Exception? = null
        val task = InstallTask(store) { finished ->
            if (app.installTask === finished) app.installTask = null
        }
        task.listener = object : InstallTask.Listener {
            override fun onProgress(stage: app.readly.model.InstallStage, bytesDone: Long, bytesTotal: Long) = Unit
            override fun onDone() {
                done.countDown()
            }
            override fun onCancelled() {
                failure = IOException("voice download was cancelled")
                done.countDown()
            }
            override fun onError(e: Exception) {
                failure = e
                done.countDown()
            }
        }
        app.installTask = task
        try {
            task.run()
        } catch (e: Exception) {
            // InstallTask.run already reported via the listener; the latch still trips.
            if (failure == null) failure = e
            while (done.count > 0) done.countDown()
        }
        try {
            // Main-thread callbacks trip the latch; the looper is always alive here.
            while (!done.await(5, TimeUnit.SECONDS)) {
                if (destroyed || Thread.currentThread().isInterrupted) {
                    task.cancel()
                    Thread.currentThread().interrupt()
                    return IOException("voice download was interrupted")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            task.cancel()
            return IOException("voice download was interrupted")
        }
        if (failure == null && !store.isInstalled()) {
            failure = IOException("voice install finished but files are missing")
        }
        return failure
    }

    /**
     * Asks which voice to download: holds [text] in [pendingAsk], shows the Ask
     * notification and the same message as the main-screen notice. Nothing plays.
     */
    private fun showAsk(
        title: String,
        text: String,
        format: SourceFormat,
        notice: String?,
        lang: String,
        recommended: VoicePack?,
        onRequestDone: () -> Unit,
    ) {
        pendingAsk = PendingAsk(title, text, format, notice, recommended?.id)
        val display = languageName(lang)
        val ask = if (recommended != null) {
            "This text is in $display. Download a $display voice (${recommended.sizeMb} MB)?"
        } else {
            "This text is in $display. No $display voice is available yet."
        }
        val combined = listOfNotNull(notice?.takeIf { it.isNotBlank() }, ask).joinToString(" ")
        ReaderState.update {
            it.copy(
                status = ReaderStatus.ASK,
                title = title,
                currentText = "",
                index = 0,
                total = 0,
                errorMessage = null,
                notice = combined,
            )
        }
        mainHandler.post {
            Toast.makeText(this, ask, Toast.LENGTH_LONG).show()
        }
        updateNotification()
        onRequestDone()
        goIdle()
    }

    /** The Ask notification's "Read anyway": reads the held text with the main voice. */
    private fun handleReadAnyway() {
        readerExecutor.execute {
            try {
                val held = pendingAsk ?: run {
                    Log.w("Readly", "Read-anyway tapped with nothing held; ignoring")
                    return@execute
                }
                pendingAsk = null
                pendingRequests.incrementAndGet()
                val finished = AtomicBoolean(false)
                fun finish() {
                    if (finished.compareAndSet(false, true)) pendingRequests.decrementAndGet()
                }
                try {
                    startReading(held.title, held.text, held.format, held.notice, ::finish, forceMain = true)
                } finally {
                    finish()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                postError(e)
            }
        }
    }

    /** The installed pack assigned to [lang] (`"fa"`/`"ar"`), or null. */
    private fun installedLangPack(lang: String): VoicePack? {
        if (lang != "fa" && lang != "ar") return null
        val id = packIdForLang(this, lang) ?: return null
        val pack = VoiceCatalog.byId(id) ?: return null
        return if (VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener).isInstalled()) {
            pack
        } else {
            null
        }
    }

    /** Installed packs in catalog order. */
    private fun installedPacks(): List<VoicePack> {
        val ids = VoiceModelStore.installedIds(voicesDir()).toSet()
        return VoiceCatalog.ALL.filter { it.id in ids }
    }

    /** Measured RTF (EMA) by pack id, for packs that have synthesized at least once. */
    private fun rtfByPack(): Map<String, Double> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return installedPacks().mapNotNull { pack ->
            val rtf = prefs.getFloat(rtfKey(pack.id), -1f)
            if (rtf >= 0) pack.id to rtf.toDouble() else null
        }.toMap()
    }

    /** Updates the per-pack RTF EMA (α = 0.3) after each synthesized chunk. */
    private fun updateRtf(packId: String, rtf: Double) {
        if (rtf <= 0 || !rtf.isFinite()) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val old = prefs.getFloat(rtfKey(packId), -1f)
        val ema = if (old < 0) rtf.toFloat() else (old * 0.7f + rtf.toFloat() * 0.3f)
        prefs.edit().putFloat(rtfKey(packId), ema).apply()
    }

    /** True when another screen or download currently owns the shared install task. */
    private fun isInstallRunning(): Boolean =
        (application as ReadlyApp).installTask?.isDone == false

    /** True on mobile data (or when the state can't be read: fail closed, ask instead). */
    private fun isMetered(): Boolean = try {
        getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    } catch (e: Exception) {
        Log.w("Readly", "Couldn't read metered state; treating as metered", e)
        true
    }

    /**
     * Loads the engine on `readly-tts` (posted directly from [onStartCommand], never
     * through `readly-read`, so the fetch and the load run in parallel). A concurrent
     * READ waits behind this on the same FIFO queue via [ensureEngineOrThrow].
     */
    private fun preloadEngine() {
        try {
            if (destroyed) return
            val mainId = activePackId(this)
            if (mainId in loadedPackIds) return
            try {
                ensureEngineOrThrow(mainId)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                postError(e)
                return
            }
            if (pendingRequests.get() > 0) return
            // Warm-up on the preload path only: the first inference after a load is
            // slower than later ones, so run a throwaway synth now (discarded). Never
            // on the READ path, where it would add latency before the real first
            // chunk. A request landing mid-warm-up aborts it via the 0/1 callback.
            // This runs on `readly-tts`, so `engines` is touched directly.
            val eng = engines[mainId]?.engine ?: return
            val pack = activePack(this)
            val warmSpeaker = speakerIdFor(this, pack.id).coerceIn(pack.speakers.indices)
            try {
                val warmStart = SystemClock.elapsedRealtime()
                eng.synthesize(
                    "Hello.",
                    speakerId = warmSpeaker,
                    speed = 1.0f,
                    isStopped = { pendingRequests.get() > 0 },
                )
                Log.i("Readly", "Voice warm-up took ${SystemClock.elapsedRealtime() - warmStart} ms")
            } catch (e: Exception) {
                Log.w("Readly", "Voice warm-up failed; the first read will warm the engine instead", e)
            }
            if (pendingRequests.get() > 0) return
            val active = synchronized(pipelineLock) { pipeline != null }
            if (!active && ReaderState.snapshot.status == ReaderStatus.LOADING_VOICE) {
                ReaderState.update { it.copy(status = ReaderStatus.IDLE) }
                updateNotification()
                goIdle()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            postError(e)
        }
    }

    private fun toggleReading() {
        when (ReaderState.snapshot.status) {
            ReaderStatus.READING -> pauseReading()
            ReaderStatus.PAUSED -> resumeReading()
            else -> Unit
        }
    }

    private fun pauseReading(clearFocusResume: Boolean = true) {
        val current = synchronized(pipelineLock) { pipeline } ?: return
        if (ReaderState.snapshot.status != ReaderStatus.READING) return
        if (clearFocusResume) pausedForFocusLoss = false
        synchronized(trackLock) { audioTrack?.pause() }
        current.pause()
        setNoisyRegistered(false)
        ReaderState.update { it.copy(status = ReaderStatus.PAUSED) }
        updateNotification()
    }

    private fun resumeReading() {
        val current = synchronized(pipelineLock) { pipeline } ?: return
        if (ReaderState.snapshot.status != ReaderStatus.PAUSED) return
        pausedForFocusLoss = false
        requestFocus()
        synchronized(trackLock) { audioTrack?.play() }
        current.resume()
        setNoisyRegistered(true)
        ReaderState.update { it.copy(status = ReaderStatus.READING) }
        updateNotification()
    }

    private fun moveBy(delta: Int) {
        readerExecutor.execute {
            try {
                val chunks: List<Chunk>
                val title: String
                val packId: String
                val lang: String
                synchronized(pipelineLock) {
                    chunks = currentChunks
                    title = currentTitle
                    packId = currentPackId
                    lang = currentLang
                }
                if (chunks.isEmpty()) return@execute
                // Same document, so the same voice and language (no re-detection).
                val pack = VoiceCatalog.byId(packId) ?: activePack(this)
                val target = (ReaderState.snapshot.index + delta).coerceIn(0, chunks.size - 1)
                stopPipeline()
                startPipeline(chunks, title, target, pack, lang)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                postError(e)
            }
        }
    }

    private fun handleStop() {
        // Immediate silence on the main thread; the join happens below.
        synchronized(trackLock) { audioTrack?.pause() }
        readerExecutor.execute {
            try {
                stopPipeline()
                synchronized(pipelineLock) {
                    currentChunks = emptyList()
                    currentTitle = ""
                    currentPackId = ""
                    currentLang = "en"
                }
                setNoisyRegistered(false)
                ReaderState.update {
                    it.copy(
                        status = ReaderStatus.IDLE,
                        title = "",
                        currentText = "",
                        index = 0,
                        total = 0,
                        errorMessage = null,
                        notice = null,
                    )
                }
                updateNotification()
                goIdle()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                postError(e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Engine + pipeline
    // ------------------------------------------------------------------

    /**
     * Returns the loaded engine for [packId], loading it on `readly-tts` first.
     * Up to [MAX_ENGINES] stay loaded (LRU), so English↔Persian alternation
     * doesn't reload each time. Throws on load failure (callers report via
     * [postError] after settling their request); inline when already on
     * `readly-tts`, otherwise waits FIFO behind any queued preload. Must not be
     * called on the main thread (it blocks).
     */
    private fun ensureEngineOrThrow(packId: String): SpeechEngine {
        if (packId !in loadedPackIds) {
            // A fetch shows FETCHING until the engine load actually starts; only
            // then does the status become LOADING_VOICE.
            ReaderState.update { it.copy(status = ReaderStatus.LOADING_VOICE, errorMessage = null) }
            updateNotification()
        }
        return runOnTtsThreadAndWait({ engineFor(packId) })!!
    }

    /** Loads or returns the engine for [packId], evicting the LRU idle engine past the cap. Must run on `readly-tts`. */
    private fun engineFor(packId: String): SpeechEngine {
        engines[packId]?.let { return it.engine }
        val pack = VoiceCatalog.byId(packId) ?: throw IllegalStateException("Unknown voice pack $packId")
        val engine = SpeechEngine(VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener))
        engines[packId] = EngineSlot(engine, engine.sampleRate)
        while (engines.size > MAX_ENGINES) {
            val eldest = engines.entries.iterator().next()
            engines.remove(eldest.key)
            try {
                eldest.value.engine.close()
            } catch (e: Exception) {
                Log.w("Readly", "Closing the idle ${eldest.key} voice failed", e)
            }
            Log.i("Readly", "Closed the idle ${eldest.key} voice (keeping at most $MAX_ENGINES loaded)")
        }
        publishEngineSnapshot()
        return engine
    }

    /** Refreshes the cross-thread engine snapshots. Must run on `readly-tts`. */
    private fun publishEngineSnapshot() {
        loadedPackIds = engines.keys.toSet()
        rateByPack = engines.mapValues { it.value.rate }
        engineLoaded = engines.isNotEmpty()
    }

    private fun voicesDir(): File = File(noBackupFilesDir, "voices")

    /**
     * Starts playback with [docPack]'s voice for 2-letter [lang]. [onRequestDone]
     * settles the originating READ/READ_URL request (null for NEXT/PREVIOUS
     * restarts, which own no request): it runs exactly once, before any error
     * report and right after the pipeline starts.
     */
    private fun startPipeline(
        chunks: List<Chunk>,
        title: String,
        startIndex: Int,
        docPack: VoicePack,
        lang: String,
        onRequestDone: (() -> Unit)? = null,
    ) {
        if (destroyed || Thread.currentThread().isInterrupted) {
            onRequestDone?.invoke()
            return
        }
        val rate = try {
            ensureEngineOrThrow(docPack.id)
            checkNotNull(rateByPack[docPack.id]) { "Voice ${docPack.id} unloaded mid-read" }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            onRequestDone?.invoke()
            return
        } catch (e: Exception) {
            onRequestDone?.invoke()
            postError(e)
            return
        }
        val track = try {
            ensureTrack(rate)
        } catch (e: Exception) {
            onRequestDone?.invoke()
            postError(e)
            return
        }
        // No ensureForeground(): onStartCommand entered the foreground on the main
        // thread for this command already. A worker-side startForeground here could
        // throw ForegroundServiceStartNotAllowedException after a goIdle() detach.
        lateinit var mine: ChunkPipeline
        mine = ChunkPipeline(
            chunks = chunks,
            startIndex = startIndex,
            sampleRate = rate,
            synth = { index, isStopped -> synthChunk(chunks[index].text, isStopped, docPack, lang) },
            sink = TrackSink(track),
            onChunkStarted = { index ->
                // A stopped generation never calls back; this guard is belt and braces
                // for callbacks already in flight when a restart lands.
                synchronized(pipelineLock) { if (pipeline !== mine) return@ChunkPipeline }
                ReaderState.update {
                    it.copy(
                        index = index,
                        currentText = chunks[index].text,
                        status = if (it.status == ReaderStatus.PAUSED) {
                            ReaderStatus.PAUSED
                        } else {
                            ReaderStatus.READING
                        },
                    )
                }
                updateNotification()
            },
            onFinished = {
                synchronized(pipelineLock) {
                    if (pipeline !== mine) return@ChunkPipeline
                    pipeline = null
                }
                setNoisyRegistered(false)
                ReaderState.update { it.copy(status = ReaderStatus.FINISHED) }
                updateNotification()
                goIdle()
            },
            onError = { e ->
                synchronized(pipelineLock) {
                    if (pipeline !== mine) return@ChunkPipeline
                    pipeline = null
                }
                // Joins the sibling worker (never the caller, so no deadlock) before
                // reporting, so no further callback can follow the error.
                mine.stop()
                postError(e)
            },
            onUnderrun = { index ->
                Log.w(
                    "Readly",
                    "Playback underrun waiting for chunk $index: synthesis can't keep " +
                        "up on this phone, you may hear gaps between sentences.",
                )
            },
        )
        synchronized(pipelineLock) {
            pipeline = mine
            currentChunks = chunks
            currentTitle = title
            currentPackId = docPack.id
            currentLang = lang
        }
        ReaderState.update {
            it.copy(
                status = ReaderStatus.READING,
                title = title,
                currentText = chunks[startIndex].text,
                index = startIndex,
                total = chunks.size,
                errorMessage = null,
            )
        }
        requestFocus()
        setNoisyRegistered(true)
        updateNotification()
        mine.start()
        onRequestDone?.invoke()
    }

    private fun stopPipeline() {
        val current = synchronized(pipelineLock) {
            val existing = pipeline
            pipeline = null
            existing
        }
        current?.stop()
    }

    /**
     * Synthesizes one chunk with [pack]'s voice for 2-letter [lang], reading speaker
     * and speed from settings every time so changes apply from the next sentence.
     * Runs on the producer thread; the engine call itself runs on `readly-tts`.
     */
    private fun synthChunk(
        text: String,
        isStopped: () -> Boolean,
        pack: VoicePack,
        lang: String,
    ): FloatArray {
        val speakerId = speakerIdFor(this, pack.id).coerceIn(pack.speakers.indices)
        val speed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1.0f).coerceIn(0.25f, 4.0f)
        val begin = SystemClock.elapsedRealtime()
        // engineFor (not a cached engine): a third pack's load may have evicted
        // this one since the pipeline started; reloading is then silent and the
        // status stays READING.
        val audio = runOnTtsThreadAndWait({
            engineFor(pack.id).synthesize(text, speakerId, speed, isStopped, lang)
        }) ?: return FloatArray(0)
        val synthMs = SystemClock.elapsedRealtime() - begin
        val rate = rateByPack[pack.id] ?: 0
        val audioMs = if (rate > 0) audio.size * 1000.0 / rate else 0.0
        val rtf = if (audioMs > 0) synthMs / audioMs else 0.0
        ReaderState.update { it.copy(lastRtf = rtf) }
        updateRtf(pack.id, rtf)
        return audio
    }

    /** Posts [block] to `readly-tts` and waits. Rethrows the cause unwrapped. */
    private fun <T> runOnTtsThreadAndWait(block: () -> T, timeoutMs: Long = 0): T? {
        if (Looper.myLooper() == ttsThread.looper) return block()
        val task = FutureTask(Callable(block))
        if (!ttsHandler.post(task)) {
            throw IllegalStateException("TTS thread is gone")
        }
        try {
            return if (timeoutMs > 0) task.get(timeoutMs, TimeUnit.MILLISECONDS) else task.get()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            throw cause
        }
    }

    // ------------------------------------------------------------------
    // AudioTrack
    // ------------------------------------------------------------------

    /** Builds the float PCM streaming track once per sample rate and reuses it. */
    private fun ensureTrack(rate: Int): AudioTrack {
        synchronized(trackLock) {
            val existing = audioTrack
            if (existing != null && trackSampleRate == rate) {
                existing.play()
                return existing
            }
            try {
                existing?.release()
            } catch (_: Exception) {
                // Replacing a dead track; the new one below is what matters.
            }
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(if (minBuf > 0) minBuf else 0, rate * 4 / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                try {
                    track.release()
                } catch (_: Exception) {
                    // Reporting the init failure below is what matters.
                }
                throw IOException("AudioTrack failed to initialize (sampleRate=$rate)")
            }
            track.play()
            audioTrack = track
            trackSampleRate = rate
            framesWritten = 0
            return track
        }
    }

    private inner class TrackSink(private val track: AudioTrack) : AudioSink {
        override fun write(samples: FloatArray, isStopped: () -> Boolean) {
            var offset = 0
            while (offset < samples.size) {
                if (isStopped()) return
                val n = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                if (n < 0) throw IOException("AudioTrack.write failed with error $n")
                if (n == 0) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    continue
                }
                synchronized(trackLock) { framesWritten += n }
                offset += n
            }
        }

        override fun flush() {
            try {
                // flush() is a no-op on a running track, so pause first: that also
                // drops the ~0.5 s already queued, and the head reads 0 again after
                // flushing a paused track, so the next pipeline's drain() measures
                // correctly. ensureTrack calls play() for the next pipeline.
                track.pause()
                track.flush()
            } catch (_: Exception) {
                // Terminal state; stop() must still join.
            }
            synchronized(trackLock) { framesWritten = 0 }
        }

        override fun drain() {
            val total = synchronized(trackLock) { framesWritten }
            while (true) {
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head >= total) return
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Errors + foreground
    // ------------------------------------------------------------------

    private fun postError(e: Throwable) {
        val message = "Couldn't read aloud: ${e.javaClass.name}: ${e.message}"
        Log.e("Readly", message, e)
        setNoisyRegistered(false)
        ReaderState.update { it.copy(status = ReaderStatus.ERROR, errorMessage = message) }
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        updateNotification()
        goIdle()
    }

    private fun ensureForeground() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureForegroundNow()
        } else {
            mainHandler.post { ensureForegroundNow() }
        }
    }

    private fun ensureForegroundNow() {
        if (inForeground || destroyed) return
        inForeground = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * Leaves the foreground and schedules the idle shutdown. Does nothing while a
     * READ/READ_URL request is still live, so an older completion can't detach
     * under a newer fetch or engine load.
     */
    private fun goIdle() {
        mainHandler.post {
            if (pendingRequests.get() > 0) return@post
            val status = ReaderState.snapshot.status
            val quiet = status == ReaderStatus.IDLE || status == ReaderStatus.FINISHED
            if (inForeground) {
                inForeground = false
                stopForeground(if (quiet) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            }
            if (quiet) {
                // No lingering "Ready" notification: nothing is actionable.
                notificationManager.cancel(NOTIF_ID)
            }
            abandonFocus()
            // Only ERROR keeps a detached notification so the user can see the text.
            if (status == ReaderStatus.ERROR) updateNotification() else syncSession()
            mainHandler.removeCallbacks(idleShutdown)
            mainHandler.postDelayed(idleShutdown, IDLE_TIMEOUT_MS)
        }
    }

    // ------------------------------------------------------------------
    // Audio focus + headphones
    // ------------------------------------------------------------------

    /** Requests permanent focus. A denial is logged and playback proceeds anyway. */
    private fun requestFocus() {
        synchronized(focusLock) {
            if (focusRequest != null) return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
            focusRequest = request
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w("Readly", "Audio focus denied; reading anyway, another app may play over us.")
            }
        }
    }

    private fun abandonFocus() {
        synchronized(focusLock) {
            focusRequest?.let {
                focusRequest = null
                audioManager.abandonAudioFocusRequest(it)
            }
        }
        pausedForFocusLoss = false
    }

    /** The headphones-unplugged receiver lives only while reading. */
    private fun setNoisyRegistered(reading: Boolean) {
        synchronized(noisyLock) {
            if (reading && !noisyRegistered) {
                noisyRegistered = true
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(noisyReceiver, filter)
                }
            } else if (!reading && noisyRegistered) {
                noisyRegistered = false
                unregisterReceiver(noisyReceiver)
            }
        }
    }

    // ------------------------------------------------------------------
    // Notification + media session
    // ------------------------------------------------------------------

    private fun updateNotification() {
        if (destroyed) return
        try {
            notificationManager.notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.w("Readly", "Updating the notification failed", e)
        }
        syncSession()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ReaderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(): Notification {
        val snap = ReaderState.snapshot
        if (snap.status == ReaderStatus.ASK) return buildAskNotification(snap)
        val playing = snap.status == ReaderStatus.READING
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val statusLine = when (snap.status) {
            ReaderStatus.LOADING_VOICE -> "Loading voice…"
            ReaderStatus.FETCHING -> snap.currentText.ifBlank { "Opening…" }
            ReaderStatus.READING -> "Reading…"
            ReaderStatus.PAUSED -> "Paused"
            ReaderStatus.FINISHED -> "Finished."
            ReaderStatus.ERROR -> snap.errorMessage ?: "Something went wrong"
            ReaderStatus.IDLE -> "Ready"
            ReaderStatus.ASK -> snap.notice ?: "Choose a voice"
        }
        // No ic_media_stop on the platform; the close glyph reads as stop here.
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(notificationTitle(snap))
            .setContentText(snap.currentText.ifBlank { statusLine })
            .setContentIntent(openApp)
            .setOngoing(
                snap.status == ReaderStatus.READING ||
                    snap.status == ReaderStatus.PAUSED ||
                    snap.status == ReaderStatus.LOADING_VOICE ||
                    snap.status == ReaderStatus.FETCHING,
            )
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous sentence",
                serviceAction(ACTION_PREVIOUS, 1),
            )
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                serviceAction(if (playing) ACTION_PAUSE else ACTION_PLAY, 2),
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next sentence",
                serviceAction(ACTION_NEXT, 3),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                serviceAction(ACTION_STOP, 4),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    /** Non-ongoing Ask notification: pick a voice or read with the main one. */
    private fun buildAskNotification(snap: ReaderSnapshot): Notification {
        val ask = snap.notice ?: "Choose a voice"
        val recommended = pendingAsk?.recommendedId?.let { VoiceCatalog.byId(it) }
        val chooseIntent = Intent(this, VoicesActivity::class.java).apply {
            recommended?.let { pack ->
                putExtra(VoicesActivity.EXTRA_PRESELECT_FAMILY, pack.family.name)
                putExtra(VoicesActivity.EXTRA_PRESELECT_PACK, pack.id)
                if (pack.lang != null) putExtra(VoicesActivity.EXTRA_PRESELECT_LANG, pack.lang)
                if (pack.voiceName != null) putExtra(VoicesActivity.EXTRA_PRESELECT_VOICE, pack.voiceName)
            }
        }
        val choose = PendingIntent.getActivity(
            this, 10, chooseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(snap.title.ifBlank { "Readly" })
            .setContentText(ask)
            .setStyle(Notification.BigTextStyle().bigText(ask))
            .setContentIntent(openApp)
            .setOngoing(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_add, "Choose voice", choose)
            .addAction(
                android.R.drawable.ic_media_play, "Read anyway",
                serviceAction(ACTION_READ_ANYWAY, 11),
            )
            .build()
    }

    /** The document title, or the first 60 chars of the text. */
    private fun notificationTitle(snap: ReaderSnapshot): String {
        if (snap.title.isNotBlank()) return snap.title
        val first = synchronized(pipelineLock) { currentChunks.firstOrNull()?.text }
        return first?.take(60)?.ifBlank { "Readly" } ?: "Readly"
    }

    /** Keeps PlaybackState and MediaMetadata in sync with [ReaderState]. */
    private fun syncSession() {
        if (destroyed) return
        val snap = ReaderState.snapshot
        val state = when (snap.status) {
            ReaderStatus.READING -> PlaybackState.STATE_PLAYING
            ReaderStatus.PAUSED -> PlaybackState.STATE_PAUSED
            ReaderStatus.LOADING_VOICE, ReaderStatus.FETCHING -> PlaybackState.STATE_BUFFERING
            ReaderStatus.FINISHED, ReaderStatus.IDLE, ReaderStatus.ASK -> PlaybackState.STATE_STOPPED
            ReaderStatus.ERROR -> PlaybackState.STATE_ERROR
        }
        try {
            mediaSession.setPlaybackState(
                PlaybackState.Builder()
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                    )
                    .build(),
            )
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, notificationTitle(snap))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Readly")
                    .build(),
            )
        } catch (e: Exception) {
            Log.w("Readly", "Syncing the media session failed", e)
        }
    }
}
