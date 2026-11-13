package app.readly.tts

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.playback.ReaderService
import app.readly.tts.pocket.G2p
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * App-level speech engine owner: the LRU of loaded engines, the single `readly-tts`
 * owner thread, and the per-pack RTF persistence. Held by
 * [ReadlyApp][app.readly.ReadlyApp] and shared by
 * [ReaderService][app.readly.playback.ReaderService] and the system TTS service;
 * synth calls from both are serialized on `readly-tts`.
 *
 * [preload], [synthesize] and [closeAll] run their engine work on `readly-tts` and
 * block the caller, so they must never be called on the main thread. Calls made on
 * `readly-tts` itself run inline.
 */
class EngineManager(appContext: Context) {

    companion object {
        /** Loaded engines kept: English↔Persian alternation stays cached. */
        const val MAX_ENGINES = 2

        /** Per-pack RTF EMA key (`rtf.<packId>`, float) in the `settings` prefs. */
        fun rtfKey(packId: String): String = "rtf.$packId"

        /** Same prefs file the reader settings live in. */
        private const val PREFS_NAME = "settings"
    }

    private val app = appContext.applicationContext
    private val ttsThread = HandlerThread("readly-tts").also { it.start() }
    private val ttsHandler = Handler(ttsThread.looper)

    private data class EngineSlot(val engine: SpeechEngine, val rate: Int)

    /**
     * Loaded engines by pack id, LRU (access order), at most [MAX_ENGINES].
     * Touched on `readly-tts` ONLY; other threads use the snapshots below. A Piper
     * engine holds about 70–120 MB of model weights, so the 2-engine cap matters:
     * alternating documents stay cached without ballooning memory.
     */
    private val engines = LinkedHashMap<String, EngineSlot>(4, 0.75f, true)

    /** Pack ids with a loaded engine. Written on `readly-tts`, read anywhere. */
    @Volatile
    var loadedPackIds: Set<String> = emptySet()
        private set

    /** True once any engine is loaded. Written on `readly-tts`, read anywhere. */
    @Volatile
    var engineLoaded = false
        private set

    /** Sample rate by loaded pack id; written on `readly-tts`, read anywhere. */
    @Volatile
    private var rateByPack: Map<String, Int> = emptyMap()

    /**
     * Loaded engines by pack id. Written on `readly-tts`, read anywhere; lets
     * [prefetch]/[cancelPrefetch] reach the engine without queueing behind a
     * running synthesis on `readly-tts` (which would erase the head start).
     */
    @Volatile
    private var engineSnapshot: Map<String, SpeechEngine> = emptyMap()

    /** True when [packId]'s engine is currently loaded (snapshot read, never blocks). */
    fun isLoaded(packId: String): Boolean = packId in loadedPackIds

    /** Loaded engine's sample rate, or null when [packId] isn't loaded (never blocks). */
    fun sampleRateIfLoaded(packId: String): Int? = rateByPack[packId]

    /**
     * Loads [packId]'s engine unless loaded and returns its sample rate. Blocks the
     * caller; the load runs on `readly-tts`. Throws on load failure.
     */
    fun preload(packId: String): Int =
        runOnTtsThreadAndWait({ engineFor(packId).sampleRate })!!

    /**
     * Synthesizes [text] with [packId]'s voice (loading it first when needed) and
     * records the measured RTF in the per-pack EMA unless [recordRtf] is false (the
     * warm-up throwaway). Blocks the caller; the engine call runs on `readly-tts`.
     * [lang] is the 2-letter document language. [onChunk] streams partial audio from
     * sherpa's callback (on `readly-tts`); generation stops early once [isStopped]
     * returns true.
     */
    fun synthesize(
        packId: String,
        text: String,
        speakerId: Int,
        speed: Float,
        lang: String,
        isStopped: () -> Boolean,
        onChunk: ((FloatArray) -> Unit)? = null,
        recordRtf: Boolean = true,
    ): FloatArray {
        val begin = SystemClock.elapsedRealtime()
        // engineFor (not a cached engine): another pack's load may have evicted
        // this one; reloading is then silent.
        val audio = runOnTtsThreadAndWait({
            engineFor(packId).synthesize(text, speakerId, speed, isStopped, lang, onChunk)
        }) ?: return FloatArray(0)
        if (recordRtf) {
            val synthMs = SystemClock.elapsedRealtime() - begin
            val rate = rateByPack[packId] ?: 0
            val audioMs = if (rate > 0) audio.size * 1000.0 / rate else 0.0
            updateRtf(packId, if (audioMs > 0) synthMs / audioMs else 0.0)
        }
        return audio
    }

    /**
     * Starts pronouncing [text] ahead for [packId]'s voice (Pocket v2 only;
     * no-op when the engine isn't loaded or isn't v2). Non-blocking and
     * thread-safe: it reads the engine snapshot, so the producer thread can
     * start the next chunk's G2P while the current chunk synthesizes. Never
     * throws.
     */
    fun prefetch(packId: String, text: String) {
        try {
            engineSnapshot[packId]?.prefetch(text)
        } catch (_: Exception) {
            // Prefetch never fails synthesis.
        }
    }

    /** Cancels prefetched work on every loaded engine. Non-blocking. */
    fun cancelPrefetch() {
        for (engine in engineSnapshot.values) {
            try {
                engine.cancelPrefetch()
            } catch (_: Exception) {
                // Prefetch never fails playback control.
            }
        }
    }

    /** Closes every loaded engine on `readly-tts`. Blocks the caller. */
    fun closeAll() {
        try {
            runOnTtsThreadAndWait({
                for ((id, slot) in engines) {
                    try {
                        slot.engine.close()
                    } catch (e: Exception) {
                        Log.w("Readly", "Closing the $id voice failed", e)
                    }
                }
                engines.clear()
                publishSnapshot()
            })
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w("Readly", "Engine shutdown was interrupted", e)
        }
    }

    /**
     * Loads or returns the engine for [packId], evicting the LRU engine past the cap.
     * Must run on `readly-tts`.
     */
    private fun engineFor(packId: String): SpeechEngine {
        // Read the Pocket tuning fresh: when it differs from the one the
        // loaded engine was built with, that engine is rebuilt, so a
        // settings change applies to the next reading with no restart.
        val tuning = ReaderService.pocketTuning(app)
        engines[packId]?.let { slot ->
            if (slot.engine.pocketTuning == tuning) return slot.engine
            engines.remove(packId)
            try {
                slot.engine.close()
            } catch (e: Exception) {
                Log.w("Readly", "Closing the $packId voice for new Pocket tuning failed", e)
            }
            publishSnapshot()
        }
        val pack = VoiceCatalog.byId(packId) ?: throw IllegalStateException("Unknown voice pack $packId")
        val store = VoiceModelStore(pack, File(app.noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
        // Pocket v2 pronounces through the G2P in app assets; v1 never calls this.
        val engine = SpeechEngine(store, { G2p.fromAssets(app.assets) }, tuning)
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
        publishSnapshot()
        return engine
    }

    /** Refreshes the cross-thread snapshots. Must run on `readly-tts`. */
    private fun publishSnapshot() {
        loadedPackIds = engines.keys.toSet()
        rateByPack = engines.mapValues { it.value.rate }
        engineSnapshot = engines.mapValues { it.value.engine }
        engineLoaded = engines.isNotEmpty()
    }

    /** Updates the per-pack RTF EMA (α = 0.3) after each synthesized chunk. */
    private fun updateRtf(packId: String, rtf: Double) {
        if (rtf <= 0 || !rtf.isFinite()) return
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val old = prefs.getFloat(rtfKey(packId), -1f)
        val ema = if (old < 0) rtf.toFloat() else (old * 0.7f + rtf.toFloat() * 0.3f)
        prefs.edit().putFloat(rtfKey(packId), ema).apply()
    }

    /** Posts [block] to `readly-tts` and waits. Rethrows the cause unwrapped. */
    private fun <T> runOnTtsThreadAndWait(block: () -> T): T? {
        if (Looper.myLooper() == ttsThread.looper) return block()
        val task = FutureTask(Callable(block))
        if (!ttsHandler.post(task)) {
            throw IllegalStateException("TTS thread is gone")
        }
        try {
            return task.get()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            throw (e as? ExecutionException)?.cause ?: e
        }
    }
}
