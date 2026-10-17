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
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.readly.InstallTask
import app.readly.MainActivity
import app.readly.R
import app.readly.ReadlyApp
import app.readly.VoicesActivity
import app.readly.input.ArticleFetcher
import app.readly.input.sites.SiteBrowserPolicy
import app.readly.input.sites.SiteFetchPolicy
import app.readly.input.sites.SiteUpdateCoordinator
import app.readly.input.sites.SiteUsage
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
import app.readly.tts.EngineManager
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background playback service: synthesizes through the app-level [EngineManager], runs
 * the [ChunkPipeline] and publishes state to [ReaderState].
 *
 * Threading: engines live on the single `readly-tts` thread inside [EngineManager];
 * pipeline `synth` calls block on it. PRELOAD loads the engine from the dedicated
 * `readly-preload` thread, so it never blocks document handling. Document handling
 * (READ / READ_URL / NEXT / PREVIOUS / STOP) runs on the single `readly-read`
 * executor so stop-and-join never blocks the main thread. Speaker previews run on
 * their own single `readly-preview` executor: a preview must never block `readly-read`
 * (round 20: a wedged preview froze every later READ behind it), and a new READ
 * cancels an in-flight preview instead. PAUSE / PLAY / TOGGLE are instant and run on
 * the main thread.
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
        const val ACTION_PREVIEW = "app.readly.PREVIEW"

        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_NOTICE = "notice"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE_HINT = "titleHint"
        const val EXTRA_PREVIEW_PACK = "previewPack"
        const val EXTRA_PREVIEW_SPEAKER = "previewSpeaker"
        const val EXTRA_PREVIEW_TEXT = "previewText"

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

        /** Daily automatic NYT reading-fix checks while Readly is in use (default ON). */
        const val KEY_NYT_AUTO_UPDATE = "nytAutoUpdate"

        /** Per-pack RTF EMA key (`rtf.<packId>`, float). */
        fun rtfKey(packId: String): String = EngineManager.rtfKey(packId)

        fun isSmartOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SMART_VOICE, true)

        fun isAutoDownloadOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_DOWNLOAD, true)

        fun isNytAutoUpdateOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_NYT_AUTO_UPDATE, true)

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

        /**
         * Longest [read] notice kept; notices are short sentences (fallback,
         * cleanup, startup errors), and anything longer is truncated rather
         * than dropped so the handoff still carries the warning.
         */
        const val MAX_READ_NOTICE_CHARS = 2000

        /**
         * Reads [text] aloud. [format] is the [SourceFormat] name (default
         * PLAIN). [notice] is an optional short notice shown with the
         * reading (the browser fallback's extraction/cleanup notices);
         * longer values are truncated to [MAX_READ_NOTICE_CHARS].
         */
        fun read(
            context: Context,
            text: String,
            title: String,
            format: SourceFormat,
            notice: String? = null,
        ) {
            context.startForegroundService(
                Intent(context, ReaderService::class.java)
                    .setAction(ACTION_READ)
                    .putExtra(EXTRA_TEXT, text)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_FORMAT, format.name)
                    .putExtra(EXTRA_NOTICE, notice?.take(MAX_READ_NOTICE_CHARS)),
            )
        }

        /**
         * Fetches [url] in the service, then reads the extracted article aloud.
         * Internal/native compatibility only: no UI link entrypoint uses this
         * — shares, pasted links and retries all open the automatic article
         * engine ([app.readly.ArticleActivity]) instead, which never runs a
         * parallel native fetch (that would read previews and skip the
         * add-on). Kept for tests and shared-text edge cases.
         */
        fun readUrl(context: Context, url: String, titleHint: String?) {
            context.startForegroundService(
                Intent(context, ReaderService::class.java)
                    .setAction(ACTION_READ_URL)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE_HINT, titleHint),
            )
        }

        /**
         * Plays a short on-device preview of [speakerId] in [packId] saying [text].
         * Never interrupts reading: when the reader is READING the service Toasts
         * instead.
         */
        fun preview(context: Context, packId: String, speakerId: Int, text: String) {
            context.startForegroundService(
                Intent(context, ReaderService::class.java)
                    .setAction(ACTION_PREVIEW)
                    .putExtra(EXTRA_PREVIEW_PACK, packId)
                    .putExtra(EXTRA_PREVIEW_SPEAKER, speakerId)
                    .putExtra(EXTRA_PREVIEW_TEXT, text),
            )
        }

        /**
         * Preview sentence for [speakerName] in [pack]: English, or Persian for
         * Persian packs (Supertonic 3 previews in the main language, English).
         */
        fun previewText(context: Context, pack: VoicePack, speakerName: String): String =
            if (bucketForPack(pack) == "fa") {
                context.getString(R.string.preview_text_fa, speakerName)
            } else {
                context.getString(R.string.preview_text_en, speakerName)
            }

        /** Short speaker name for previews (`Bella (American, female)` → `Bella`). */
        fun speakerShortName(pack: VoicePack, speakerId: Int): String =
            pack.speakers[speakerId.coerceIn(pack.speakers.indices)].substringBefore(" (")

        /**
         * Waits up to [timeoutMs] for a cancelled preview task's `finally` (which
         * flushes the shared track), so it can't run after the new pipeline's first
         * writes and clip them. Returns false (after [onTimeout]) on timeout; a
         * missing, cancelled or already-failed task returns true at once. Done tasks
         * return immediately, so the common no-preview path costs nothing.
         */
        internal fun awaitPreviewDone(
            task: Future<*>?,
            timeoutMs: Long,
            onTimeout: () -> Unit,
        ): Boolean {
            val pending = task ?: return true
            return try {
                pending.get(timeoutMs, TimeUnit.MILLISECONDS)
                true
            } catch (_: TimeoutException) {
                onTimeout()
                false
            } catch (_: CancellationException) {
                true // Never ran (shutdown); nothing to wait for.
            } catch (_: ExecutionException) {
                true // The preview reports its own errors.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        /**
         * Loads the voice in the background so the next READ starts instantly. Skips
         * when the pack is already loaded, so reopening the app doesn't restart the
         * service (and flash a notification) for nothing.
         */
        fun preload(context: Context) {
            val engines = (context.applicationContext as? ReadlyApp)?.engines
            if (engines != null && engines.isLoaded(activePackId(context))) return
            context.startForegroundService(
                Intent(context, ReaderService::class.java).setAction(ACTION_PRELOAD),
            )
        }

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "playback"

        /** Idle grace after FINISHED / STOP before the service shuts down. */
        private const val IDLE_TIMEOUT_MS = 10L * 60 * 1000

        /** Hang watchdog: check interval and the busy time that logs a warning. */
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val WATCHDOG_BUSY_MS = 30_000L

        /** How long a new READ waits for a cancelled preview's `finally`. */
        private const val PREVIEW_CANCEL_TIMEOUT_MS = 300L
    }

    private lateinit var mainHandler: Handler
    private lateinit var readerExecutor: ExecutorService
    private lateinit var preloadExecutor: ExecutorService
    private lateinit var previewExecutor: ExecutorService
    private val engines: EngineManager by lazy { (application as ReadlyApp).engines }
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession

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
    private var currentHost: String = ""
    /** Audio ms and chars synthesized so far in this document (producer writes). */
    @Volatile
    private var docAudioMs = 0.0
    @Volatile
    private var docChars = 0

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

    /**
     * Preview generations. Each posted preview takes the next [previewSeq]; a preview is
     * cancelled once its number is at most [previewCancelSeq]. The number is taken at
     * post time (not when the task starts), so cancelling also covers previews still
     * queued on `readly-preview`, while a preview posted later runs normally.
     */
    private val previewSeq = AtomicInteger(0)
    private val previewCancelSeq = AtomicInteger(0)

    /**
     * Latest preview task on `readly-preview`, posted from the main thread and
     * awaited from `readly-read` in [cancelPreview]. Waiting on the latest task
     * covers earlier ones too: the executor is FIFO, so they finish first.
     */
    @Volatile
    private var previewTask: Future<*>? = null

    /** Name + start of the task currently on `readly-read` ("" when idle), for the watchdog. */
    private val readTaskLock = Object()
    private var readTaskName = ""
    private var readTaskStartMs = 0L

    /** Main thread only: start mark of the stuck task already warned about. */
    private var warnedTaskStart = 0L

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

    /**
     * Hang watchdog: if `readly-read` has been busy on one task for over 30 s, logs it
     * with the task name (round 20: a wedged preview froze that thread with no log
     * line at all). Fires once per stuck task; a long but healthy task (a page fetch,
     * a voice download) logs once too.
     */
    private val watchdogCheck = object : Runnable {
        override fun run() {
            if (destroyed) return
            val (name, start) = synchronized(readTaskLock) { readTaskName to readTaskStartMs }
            if (name.isNotEmpty() && start != 0L) {
                val busyMs = SystemClock.elapsedRealtime() - start
                if (busyMs > WATCHDOG_BUSY_MS && start != warnedTaskStart) {
                    warnedTaskStart = start
                    Log.w("Readly", "readly-read busy on $name for ${busyMs / 1000} s")
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
        readerExecutor = Executors.newSingleThreadExecutor { Thread(it, "readly-read") }
        preloadExecutor = Executors.newSingleThreadExecutor { Thread(it, "readly-preload") }
        previewExecutor = Executors.newSingleThreadExecutor { Thread(it, "readly-preview") }
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reading aloud", NotificationManager.IMPORTANCE_LOW),
        )
        audioManager = getSystemService(AudioManager::class.java)
        mainHandler.postDelayed(watchdogCheck, WATCHDOG_INTERVAL_MS)
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
                    intent.getStringExtra(EXTRA_NOTICE),
                )
            }
            ACTION_READ_URL -> {
                pendingRequests.incrementAndGet()
                handleReadUrl(
                    intent.getStringExtra(EXTRA_URL) ?: "",
                    intent.getStringExtra(EXTRA_TITLE_HINT),
                )
            }
            // On its own thread: going through readerExecutor would block the page
            // fetch behind the whole model load. A READ's ensureEngineOrThrow() then
            // waits behind this on the EngineManager's FIFO queue.
            ACTION_PRELOAD -> preloadExecutor.execute(::preloadEngine)
            ACTION_TOGGLE -> toggleReading()
            ACTION_PAUSE -> pauseReading()
            ACTION_PLAY -> resumeReading()
            ACTION_NEXT -> moveBy(1)
            ACTION_PREVIOUS -> moveBy(-1)
            ACTION_STOP -> handleStop()
            ACTION_READ_ANYWAY -> handleReadAnyway()
            ACTION_PREVIEW -> handlePreview(
                intent.getStringExtra(EXTRA_PREVIEW_PACK) ?: "",
                intent.getIntExtra(EXTRA_PREVIEW_SPEAKER, 0),
                intent.getStringExtra(EXTRA_PREVIEW_TEXT) ?: "",
            )
            else -> Log.w("Readly", "ReaderService ignoring unknown action ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(idleShutdown)
        mainHandler.removeCallbacks(watchdogCheck)
        cancelPreview()
        readerExecutor.shutdownNow()
        preloadExecutor.shutdownNow()
        previewExecutor.shutdownNow()
        // Join playback first: the producer may be inside a synth call on readly-tts,
        // and the engine must not close under it. The readly-tts thread itself is
        // app-owned and stays alive for the system TTS service.
        stopPipeline()
        engines.closeAll()
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

    /** Runs [block] on `readly-read`, tracking its [name] for the hang watchdog. */
    private fun executeRead(name: String, block: () -> Unit) {
        readerExecutor.execute {
            synchronized(readTaskLock) {
                readTaskName = name
                readTaskStartMs = SystemClock.elapsedRealtime()
            }
            try {
                block()
            } finally {
                synchronized(readTaskLock) {
                    readTaskName = ""
                    readTaskStartMs = 0
                }
            }
        }
    }

    private fun handleRead(text: String, title: String, formatName: String, notice: String?) {
        executeRead("READ") {
            currentHost = ""
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
                    return@executeRead
                }
                startReading(title, text, format, notice = notice, onRequestDone = ::finish)
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
        executeRead("READ_URL") {
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
                currentHost = host
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
                        prevText = "",
                        nextText = "",
                        host = host,
                        packId = "",
                        playedChunks = 0,
                        msPerChar = 0.0,
                        remainingChars = 0,
                        retryUrl = null,
                    )
                }
                updateNotification()
                val sites = (application as ReadlyApp).siteRules
                sites.ensureStarted()
                // Snapshot the current catalog without waiting for any update
                // download; the refresh runs separately on readly-sites.
                val snapshot = sites.snapshotForReading()
                val usage = SiteUsage()
                val opener = if (snapshot != null) {
                    SiteFetchPolicy.opener(
                        snapshot,
                        usage,
                        ArticleFetcher::httpUrlOpenerWithHeaders,
                    )
                } else {
                    ArticleFetcher::httpUrlOpener
                }
                val doc = ArticleFetcher(opener, siteRules = snapshot, siteUsage = usage).fetch(url)
                val siteNotice = when {
                    snapshot != null && usage.applied() -> {
                        sites.onRead()
                        val state = sites.siteUiState()
                        SiteUpdateCoordinator.readingNotice(
                            usage.noticeHost(),
                            snapshot.version(),
                            usage.partialApplied,
                            if (usage.legacyUsed) usage.legacyVersion else null,
                            state.base.startupNotice,
                            state.base.warning,
                            state.base.lastError,
                        )
                    }
                    snapshot == null -> sites.uiState().startupError
                    else -> null
                }
                // Articles bring their own title; fetched text/markdown falls back
                // to the shared headline when there is one.
                val title = if (doc.format == SourceFormat.HTML) {
                    doc.title
                } else {
                    titleHint?.takeIf { it.isNotBlank() } ?: doc.title
                }
                val notice = listOfNotNull(doc.notice, siteNotice)
                    .joinToString(" ").takeIf { it.isNotBlank() }
                startReading(title, doc.text, doc.format, notice, ::finish)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                finish()
            } catch (e: Exception) {
                finish()
                // A failed eligible link keeps its exact source URL for the
                // user-invoked browser fallback; anything else clears it.
                postError(e, SiteBrowserPolicy.retryUrlForFailure(url))
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
        ReaderState.update { it.copy(notice = null, smartLine = null) }
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
            is SmartVoice.Choice.Use -> {
                setSmartLine(lang, choice.pack)
                startWithPack(title, clean, lang, choice.pack, notice, onRequestDone)
            }
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
                retryUrl = null,
                errorMessage = message,
                notice = null,
                smartLine = null,
                prevText = "",
                nextText = "",
                host = "",
                packId = "",
                playedChunks = 0,
                msPerChar = 0.0,
                remainingChars = 0,
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

    /** Shows `Smart voice: <Language> → <shortName>` on the main screen. */
    private fun setSmartLine(lang: String, pack: VoicePack) {
        val line = getString(R.string.smart_line_format, languageName(lang), VoiceCatalog.shortName(pack))
        ReaderState.update { it.copy(smartLine = line) }
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
                retryUrl = null,
                errorMessage = null,
                notice = null,
                prevText = "",
                nextText = "",
                host = currentHost,
                packId = "",
                playedChunks = 0,
                msPerChar = 0.0,
                remainingChars = 0,
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
        setSmartLine(lang, pack)
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
                retryUrl = null,
                errorMessage = null,
                notice = combined,
                smartLine = null,
                prevText = "",
                nextText = "",
                host = "",
                packId = "",
                playedChunks = 0,
                msPerChar = 0.0,
                remainingChars = 0,
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
        executeRead("READ_ANYWAY") {
            try {
                val held = pendingAsk ?: run {
                    Log.w("Readly", "Read-anyway tapped with nothing held; ignoring")
                    return@executeRead
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

    /**
     * Plays a short speaker preview through the shared AudioTrack. Runs on
     * `readly-preview`, never on `readly-read`: a new READ cancels an in-flight
     * preview instead of queueing behind it ([cancelPreview]), waiting at most
     * 300 ms for the cancelled task's `finally` so its track flush can't clip
     * the new reading. Title and index are untouched; the previous status is
     * restored afterwards (PAUSED stays PAUSED), unless a READ took over meanwhile.
     */
    private fun handlePreview(packId: String, speakerId: Int, text: String) {
        val mySeq = previewSeq.incrementAndGet()
        previewTask = previewExecutor.submit {
            val isCancelled = { mySeq <= previewCancelSeq.get() }
            try {
                // A READ posted after this preview owns the track from here: leave
                // the status alone, it is already showing the new reading.
                if (destroyed || isCancelled()) return@submit
                val snap = ReaderState.snapshot
                if (snap.status == ReaderStatus.READING) {
                    mainHandler.post {
                        Toast.makeText(this, R.string.preview_busy, Toast.LENGTH_LONG).show()
                    }
                    goIdle()
                    return@submit
                }
                val pack = VoiceCatalog.byId(packId)
                if (pack == null || text.isBlank()) {
                    previewError(
                        packId,
                        IllegalStateException("Unknown voice pack $packId"),
                    )
                    return@submit
                }
                // A READ may have started while this preview was queued. Every READ sets
                // the cancel flag before it ever posts READING, so bailing here keeps
                // the restore below from landing after the READ takes over.
                if (isCancelled()) return@submit
                val sid = speakerId.coerceIn(pack.speakers.indices)
                val name = speakerShortName(pack, sid)
                val prevStatus = snap.status
                val prevText = snap.currentText
                ReaderState.update {
                    it.copy(
                        status = ReaderStatus.PREVIEWING,
                        currentText = getString(R.string.previewing_format, name),
                    )
                }
                updateNotification()
                try {
                    playPreviewAudio(pack, text, sid, isCancelled)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@submit
                } catch (e: Exception) {
                    // A READ that cancelled this preview reports its own errors.
                    if (isCancelled()) {
                        Log.w("Readly", "Preview ${pack.id} failed after cancel; ignoring", e)
                    } else {
                        previewError(pack.id, e)
                    }
                    return@submit
                } finally {
                    // A concurrent preload failure may have set ERROR meanwhile, and a
                    // new READ may have taken over; only restore when the preview
                    // status is still ours. The check runs on the main thread, where
                    // every status change is serialized: reading the status here and
                    // restoring later could pause under a live reading and wedge its
                    // consumer.
                    mainHandler.post {
                        if (destroyed || ReaderState.snapshot.status != ReaderStatus.PREVIEWING) {
                            return@post
                        }
                        ReaderState.update { it.copy(status = prevStatus, currentText = prevText) }
                        if (prevStatus == ReaderStatus.PAUSED) {
                            synchronized(trackLock) { audioTrack?.pause() }
                        }
                        updateNotification()
                        // A paused reading session stays foreground; anything else
                        // detaches (a no-op while a request is live).
                        if (prevStatus != ReaderStatus.PAUSED) goIdle()
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // A READ that cancelled this preview reports its own errors.
                if (isCancelled()) {
                    Log.w("Readly", "Preview $packId failed after cancel; ignoring", e)
                } else {
                    previewError(packId, e)
                }
            }
        }
    }

    /**
     * Cancels the in-flight preview, if any; the next READ owns the track from here.
     * Also waits (up to 300 ms) for the cancelled task's `finally`, which flushes
     * the track: without the wait it can run after the new pipeline's first writes
     * and clip the start of the shared text (round 21). Never waits on the main
     * thread (onDestroy also calls this); there the flag alone suffices because
     * the track is released right after.
     */
    private fun cancelPreview() {
        previewCancelSeq.set(previewSeq.get())
        if (Looper.myLooper() == Looper.getMainLooper()) return
        awaitPreviewDone(previewTask, PREVIEW_CANCEL_TIMEOUT_MS) {
            Log.w("Readly", "Timed out waiting for the cancelled preview to finish")
        }
    }

    /**
     * Synthesizes [text] and plays it through the shared track, then drains it.
     * [isCancelled] aborts the synth, the write loop and the drain loop; every exit
     * path after the track is touched flushes it and resets the shared frame
     * accounting, so the reader's drain math stays consistent.
     */
    private fun playPreviewAudio(
        pack: VoicePack,
        text: String,
        speakerId: Int,
        isCancelled: () -> Boolean,
    ) {
        val speed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1.0f).coerceIn(0.25f, 4.0f)
        val lang = if (bucketForPack(pack) == "fa") "fa" else "en"
        val rate = engines.preload(pack.id)
        val audio = engines.synthesize(pack.id, text, speakerId, speed, lang, isStopped = isCancelled)
        if (audio.isEmpty() || isCancelled()) return
        val track = ensureTrack(rate)
        // A READ owns the track from here; don't touch it (a flush would drop the new
        // reading's first audio).
        if (isCancelled()) return
        // Flush first: a paused session may hold stale queued audio, and the head must
        // read 0 so the drain target below measures exactly this preview. Dropping the
        // queued half-second means resume skips it; playing it first would cut the
        // preview short instead.
        resetTrack(track)
        track.play()
        try {
            // The head advances during blocking writes, so capture it BEFORE writing:
            // measuring it after overshoots, and the drain then loops forever once
            // playback stops (round 20 hang).
            val startHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val target = DrainLoop.drainTarget(startHead, audio.size.toLong())
            val deadlineMs = maxOf(3_000L, audio.size * 1_000L / rate + 2_000)
            val startMs = SystemClock.elapsedRealtime()
            var offset = 0
            // Non-blocking: a blocking write on a paused-full track never returns, and
            // STOP pauses the track from the main thread. Cancellation then always lands
            // within one 10 ms sleep.
            while (offset < audio.size) {
                if (isCancelled()) return
                val n = track.write(audio, offset, audio.size - offset, AudioTrack.WRITE_NON_BLOCKING)
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
                offset += n
            }
            while (true) {
                if (isCancelled()) return
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val elapsed = SystemClock.elapsedRealtime() - startMs
                if (DrainLoop.isDrained(head, target, elapsed, deadlineMs)) {
                    if (elapsed >= deadlineMs) {
                        Log.w(
                            "Readly",
                            "Playback drain timed out after $elapsed ms (head=$head, target=$target)",
                        )
                    }
                    return
                }
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        } finally {
            resetTrack(track)
        }
    }

    /**
     * Drops everything queued on [track] and zeroes the shared frame accounting,
     * atomically under [trackLock]. [cancelPreview] waits for the cancelled
     * preview's `finally`, so this reset normally lands before the new pipeline's
     * first writes; if that wait timed out, the atomic reset still keeps `drain()`
     * consistent either way. Ends playing: leaving a pause here could wedge a
     * racing new pipeline's consumer (blocking writes on a paused track stall once
     * its buffer fills); the preview's caller re-pauses for a paused session.
     */
    private fun resetTrack(track: AudioTrack) {
        synchronized(trackLock) {
            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {
                // Terminal state; the write loop surfaces real errors.
            }
            framesWritten = 0
            try {
                track.play()
            } catch (_: Exception) {
                // ensureTrack re-plays for the next pipeline.
            }
        }
    }

    private fun previewError(packId: String, e: Throwable) {
        val message = getString(R.string.preview_error_format, e.javaClass.name, e.message)
        Log.e("Readly", "$message ($packId)", e)
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        updateNotification()
        goIdle()
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
     * Loads the engine from `readly-preload` (never through `readly-read`, so the
     * fetch and the load run in parallel). A concurrent READ waits behind this on
     * the EngineManager's FIFO queue via [ensureEngineOrThrow].
     */
    private fun preloadEngine() {
        try {
            if (destroyed) return
            val mainId = activePackId(this)
            if (engines.isLoaded(mainId)) return
            try {
                engines.preload(mainId)
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
            // The throwaway stays out of the RTF EMA (recordRtf = false).
            val pack = activePack(this)
            val warmSpeaker = speakerIdFor(this, pack.id).coerceIn(pack.speakers.indices)
            try {
                val warmStart = SystemClock.elapsedRealtime()
                engines.synthesize(
                    mainId,
                    "Hello.",
                    speakerId = warmSpeaker,
                    speed = 1.0f,
                    lang = "en",
                    isStopped = { pendingRequests.get() > 0 },
                    recordRtf = false,
                )
                Log.i("Readly", "Voice warm-up took ${SystemClock.elapsedRealtime() - warmStart} ms")
            } catch (e: Exception) {
                Log.w("Readly", "Voice warm-up failed; the first read will warm the engine instead", e)
            }
            if (pendingRequests.get() > 0) return
            val active = synchronized(pipelineLock) { pipeline != null }
            if (!active && ReaderState.snapshot.status == ReaderStatus.LOADING_VOICE) {
                ReaderState.update { it.copy(status = ReaderStatus.IDLE, retryUrl = null) }
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
        executeRead(if (delta > 0) "NEXT" else "PREVIOUS") {
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
                if (chunks.isEmpty()) return@executeRead
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
        executeRead("STOP") {
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
                        retryUrl = null,
                        errorMessage = null,
                        notice = null,
                        smartLine = null,
                        prevText = "",
                        nextText = "",
                        host = "",
                        packId = "",
                        playedChunks = 0,
                        msPerChar = 0.0,
                        remainingChars = 0,
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
     * Returns the sample rate for [packId], loading its engine first. Throws on load
     * failure (callers report via [postError] after settling their request). Must
     * not be called on the main thread (it blocks).
     */
    private fun ensureEngineOrThrow(packId: String): Int {
        if (!engines.isLoaded(packId)) {
            // A fetch shows FETCHING until the engine load actually starts; only
            // then does the status become LOADING_VOICE.
            ReaderState.update { it.copy(status = ReaderStatus.LOADING_VOICE, errorMessage = null) }
            updateNotification()
        }
        return engines.preload(packId)
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
        // A fresh document restarts the time-left stats; NEXT/PREVIOUS restarts
        // (onRequestDone == null) keep measuring the same document.
        if (onRequestDone != null) {
            docAudioMs = 0.0
            docChars = 0
        }
        val rate = try {
            ensureEngineOrThrow(docPack.id)
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
                        prevText = chunks.getOrNull(index - 1)?.text.orEmpty(),
                        nextText = chunks.getOrNull(index + 1)?.text.orEmpty(),
                        playedChunks = it.playedChunks + 1,
                        msPerChar = avgMsPerChar(),
                        remainingChars = chunks.drop(index).sumOf { chunk -> chunk.text.length },
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
                retryUrl = null,
                errorMessage = null,
                prevText = chunks.getOrNull(startIndex - 1)?.text.orEmpty(),
                nextText = chunks.getOrNull(startIndex + 1)?.text.orEmpty(),
                host = currentHost,
                packId = docPack.id,
                playedChunks = 0,
                msPerChar = avgMsPerChar(),
                remainingChars = chunks.drop(startIndex).sumOf { chunk -> chunk.text.length },
            )
        }
        requestFocus()
        setNoisyRegistered(true)
        updateNotification()
        mine.start()
        onRequestDone?.invoke()
    }

    private fun stopPipeline() {
        // A new READ owns the track from here: stop any in-flight preview first, so it
        // can never write into the new pipeline (every pipeline starts here).
        cancelPreview()
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
     * Runs on the producer thread; the engine call itself runs on `readly-tts`
     * inside [EngineManager] (which also records the per-pack RTF EMA).
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
        val audio = engines.synthesize(pack.id, text, speakerId, speed, lang, isStopped)
        val synthMs = SystemClock.elapsedRealtime() - begin
        val rate = engines.sampleRateIfLoaded(pack.id) ?: 0
        val audioMs = if (rate > 0) audio.size * 1000.0 / rate else 0.0
        docAudioMs += audioMs
        docChars += text.length
        ReaderState.update { it.copy(lastRtf = if (audioMs > 0) synthMs / audioMs else 0.0) }
        return audio
    }

    /** Average audio-ms per char synthesized so far in this document (0 = unknown). */
    private fun avgMsPerChar(): Double {
        val chars = docChars
        return if (chars > 0) docAudioMs / chars else 0.0
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
            // The head epoch is 0: every flush and every new track resets it (see
            // flush() and ensureTrack), and framesWritten counts from the same point.
            val target = DrainLoop.drainTarget(0L, total)
            val rate = track.sampleRate
            val deadlineMs = if (rate > 0) maxOf(3_000L, total * 1_000 / rate + 2_000) else 3_000L
            val startMs = SystemClock.elapsedRealtime()
            while (true) {
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val elapsed = SystemClock.elapsedRealtime() - startMs
                if (DrainLoop.isDrained(head, target, elapsed, deadlineMs)) {
                    if (elapsed >= deadlineMs) {
                        Log.w(
                            "Readly",
                            "Playback drain timed out after $elapsed ms (head=$head, target=$target)",
                        )
                        flush()
                    }
                    return
                }
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

    /**
     * Reports a terminal failure. [retryUrl] is the exact failed-link URL
     * for the browser fallback, set only by a failed eligible link read;
     * every other error clears it so no stale link button appears elsewhere.
     */
    private fun postError(e: Throwable, retryUrl: String? = null) {
        val message = "Couldn't read aloud: ${e.javaClass.name}: ${e.message}"
        Log.e("Readly", message, e)
        setNoisyRegistered(false)
        ReaderState.update { it.copy(status = ReaderStatus.ERROR, errorMessage = message, retryUrl = retryUrl) }
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
            ReaderStatus.PREVIEWING -> snap.currentText.ifBlank { "Previewing…" }
        }
        // No ic_media_stop on the platform; the close glyph reads as stop here.
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_play)
            .setContentTitle(notificationTitle(snap))
            .setContentText(snap.currentText.ifBlank { statusLine })
            .setContentIntent(openApp)
            .setOngoing(
                snap.status == ReaderStatus.READING ||
                    snap.status == ReaderStatus.PAUSED ||
                    snap.status == ReaderStatus.LOADING_VOICE ||
                    snap.status == ReaderStatus.FETCHING ||
                    snap.status == ReaderStatus.PREVIEWING,
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
            .setSmallIcon(R.drawable.ic_notif_play)
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
            ReaderStatus.LOADING_VOICE, ReaderStatus.FETCHING, ReaderStatus.PREVIEWING ->
                PlaybackState.STATE_BUFFERING
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
