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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.readly.MainActivity
import app.readly.input.ArticleFetcher
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.text.Chunk
import app.readly.text.SentenceChunker
import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer
import app.readly.tts.KokoroEngine
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background playback service: owns the [KokoroEngine], runs the [ChunkPipeline] and
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

        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE_HINT = "titleHint"

        const val PREFS_NAME = "settings"
        const val KEY_SPEAKER_ID = "speakerId"
        const val KEY_SPEED = "speed"

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
         * True once the engine is loaded. Checked by [preload] so reopening the app
         * doesn't restart the service (and flash a notification) for nothing.
         */
        @Volatile
        var engineLoaded = false

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
            if (engineLoaded) return
            context.startForegroundService(
                Intent(context, ReaderService::class.java).setAction(ACTION_PRELOAD),
            )
        }

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "playback"

        /** Idle grace after FINISHED / STOP before the service and engine shut down. */
        private const val IDLE_TIMEOUT_MS = 10L * 60 * 1000
    }

    private lateinit var mainHandler: Handler
    private lateinit var ttsThread: HandlerThread
    private lateinit var ttsHandler: Handler
    private lateinit var readerExecutor: ExecutorService
    private lateinit var store: VoiceModelStore
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession

    /**
     * The engine. Constructed, used and closed on `readly-tts` only; the volatile read
     * from other threads is just the loaded check (construction is guarded on the
     * thread, so a racing second load is impossible).
     */
    @Volatile
    private var engine: KokoroEngine? = null

    /** Sample rate of [engine]; written on `readly-tts` at load, read anywhere. */
    @Volatile
    private var engineRate = 0

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
        val pack = VoiceCatalog.byId(VoiceCatalog.KOKORO_ID)!!
        store = VoiceModelStore(pack, File(noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
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
                    try {
                        engine?.close()
                    } catch (e: Exception) {
                        Log.w("Readly", "Closing the Kokoro voice failed", e)
                    }
                    engine = null
                    engineLoaded = false
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
     * Sanitizes [text], chunks it and starts the pipeline, replacing any current
     * reading. Runs on `readly-read`. [notice] is shown on the main screen and
     * Toasted once reading starts. [onRequestDone] is invoked exactly once when the
     * request starts a pipeline or ends empty (before any idle transition).
     */
    private fun startReading(
        title: String,
        text: String,
        format: SourceFormat,
        notice: String?,
        onRequestDone: () -> Unit,
    ) {
        stopPipeline()
        ReaderState.update { it.copy(notice = null) }
        val clean = TextSanitizer.sanitize(text, format)
        val chunks = SentenceChunker.chunk(clean)
        if (chunks.isEmpty()) {
            val message =
                "Nothing to read: the text was empty after removing formatting, " +
                "links and symbols."
            Log.w("Readly", "READ produced no chunks (${text.length} chars in)")
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
            return
        }
        startPipeline(chunks, title, 0, onRequestDone)
        if (notice != null) {
            ReaderState.update { it.copy(notice = notice) }
            mainHandler.post {
                Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Loads the engine on `readly-tts` (posted directly from [onStartCommand], never
     * through `readly-read`, so the fetch and the load run in parallel). A concurrent
     * READ waits behind this on the same FIFO queue via [ensureEngineOrThrow].
     */
    private fun preloadEngine() {
        try {
            if (engine != null || destroyed) return
            try {
                ensureEngineOrThrow()
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
            val eng = engine ?: return
            val warmSpeaker = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_SPEAKER_ID, 0)
                .coerceIn(0, KokoroEngine.SPEAKERS.size - 1)
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
                val chunks = synchronized(pipelineLock) { currentChunks }
                if (chunks.isEmpty()) return@execute
                val title = synchronized(pipelineLock) { currentTitle }
                val target = (ReaderState.snapshot.index + delta).coerceIn(0, chunks.size - 1)
                stopPipeline()
                startPipeline(chunks, title, target)
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
     * Returns the loaded engine, loading it on `readly-tts` first. Throws on load
     * failure (callers report via [postError] after settling their request); inline
     * when already on `readly-tts`, otherwise waits FIFO behind any queued preload.
     * Must not be called on the main thread (it blocks).
     */
    private fun ensureEngineOrThrow(): KokoroEngine {
        engine?.let { return it }
        // A fetch shows FETCHING until the engine load actually starts; only then
        // does the status become LOADING_VOICE.
        ReaderState.update { it.copy(status = ReaderStatus.LOADING_VOICE, errorMessage = null) }
        updateNotification()
        return runOnTtsThreadAndWait({
            if (engine == null) {
                engine = KokoroEngine(store)
                engineRate = engine!!.sampleRate
                engineLoaded = true
            }
            engine!!
        })!!
    }

    /**
     * Starts playback. [onRequestDone] settles the originating READ/READ_URL request
     * (null for NEXT/PREVIOUS restarts, which own no request): it runs exactly once,
     * before any error report and right after the pipeline starts.
     */
    private fun startPipeline(
        chunks: List<Chunk>,
        title: String,
        startIndex: Int,
        onRequestDone: (() -> Unit)? = null,
    ) {
        if (destroyed || Thread.currentThread().isInterrupted) {
            onRequestDone?.invoke()
            return
        }
        val rate = try {
            ensureEngineOrThrow()
            engineRate
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
            synth = { index, isStopped -> synthChunk(chunks[index].text, isStopped) },
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
     * Synthesizes one chunk, reading voice and speed from settings every time so
     * changes apply from the next sentence. Runs on the producer thread; the engine
     * call itself runs on `readly-tts`.
     */
    private fun synthChunk(text: String, isStopped: () -> Boolean): FloatArray {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val speakerId = prefs.getInt(KEY_SPEAKER_ID, 0)
            .coerceIn(0, KokoroEngine.SPEAKERS.size - 1)
        val speed = prefs.getFloat(KEY_SPEED, 1.0f).coerceIn(0.25f, 4.0f)
        val begin = SystemClock.elapsedRealtime()
        val audio = runOnTtsThreadAndWait({ engine!!.synthesize(text, speakerId, speed, isStopped) })
            ?: return FloatArray(0)
        val synthMs = SystemClock.elapsedRealtime() - begin
        val audioMs = if (engineRate > 0) audio.size * 1000.0 / engineRate else 0.0
        val rtf = if (audioMs > 0) synthMs / audioMs else 0.0
        ReaderState.update { it.copy(lastRtf = rtf) }
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
            ReaderStatus.FINISHED, ReaderStatus.IDLE -> PlaybackState.STATE_STOPPED
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
