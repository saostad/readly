package app.readly.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Playback status shown in the UI and the notification. */
enum class ReaderStatus {
    IDLE,
    LOADING_VOICE,
    FETCHING,
    READING,
    PAUSED,
    FINISHED,
    ERROR,
    /** Smart voice needs a download choice; nothing is playing. */
    ASK,
    /** Playing a short speaker preview; returns to the previous status after. */
    PREVIEWING,
}

/** Immutable snapshot of the reader UI state. */
data class ReaderSnapshot(
    val status: ReaderStatus = ReaderStatus.IDLE,
    val title: String = "",
    val currentText: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val errorMessage: String? = null,
    /** synthesis ms / audio ms of the last chunk (0 when unknown). Lower is faster. */
    val lastRtf: Double = 0.0,
    /** One-shot note about the current reading (e.g. whole-page fallback), if any. */
    val notice: String? = null,
    /** Smart voice pick for the current reading (e.g. `Smart voice: Spanish → …`), if any. */
    val smartLine: String? = null,
    /** Previous/next sentence around the current one (empty when absent). */
    val prevText: String = "",
    val nextText: String = "",
    /** Page host for link reads, "" for pasted or typed text. */
    val host: String = "",
    /** Pack id reading the current document ("" when none). */
    val packId: String = "",
    /** Chunks started on playback in this pipeline (for the time-left estimate). */
    val playedChunks: Int = 0,
    /** Average audio-ms per char measured so far in this document (0 = unknown). */
    val msPerChar: Double = 0.0,
    /** Chars from the current chunk to the end of the document. */
    val remainingChars: Int = 0,
    /** Chars in the whole document (0 when nothing is reading), for the progress bar. */
    val totalChars: Int = 0,
    /**
     * Exact source URL offered to the browser fallback on a failed NYT
     * link read, null otherwise. Set only by a failed URL read whose link
     * is a main NYT article page; cleared when a different read or stop
     * starts, so no stale link button appears on unrelated errors.
     */
    val retryUrl: String? = null,
    /** In the ASK state, the voice the Download button would fetch (null when none). */
    val askPackId: String? = null,
    /** While FETCHING a voice download: 0..100; -1 when not downloading. */
    val downloadPercent: Int = -1,
    /** While LOADING_VOICE: the voice's short name ("" when unknown). */
    val loadingVoice: String = "",
    /** When the current [LoadingStep] began ([SystemClock.elapsedRealtime]); 0 when none. */
    val stepSinceMs: Long = 0L,
    /** Immutable saved text behind Search and Bookmarks. */
    val readingId: String = "",
)

/**
 * Main-thread observable reader state, written by [ReaderService] and read by the UI.
 *
 * All mutations and listener callbacks happen on the main thread; [update] may be called
 * from any thread. [snapshot] is safe to read from any thread.
 */
object ReaderState {

    fun interface Listener {
        fun onChanged(snapshot: ReaderSnapshot)
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = mutableSetOf<Listener>()

    @Volatile
    var snapshot = ReaderSnapshot()
        private set

    fun addListener(listener: Listener) {
        runOnMain {
            listeners.add(listener)
            listener.onChanged(snapshot)
        }
    }

    fun removeListener(listener: Listener) {
        runOnMain { listeners.remove(listener) }
    }

    fun update(transform: (ReaderSnapshot) -> ReaderSnapshot) {
        runOnMain {
            val previous = snapshot
            snapshot = LoadingStep.stamp(previous, transform(previous), SystemClock.elapsedRealtime())
            listeners.toList().forEach { listener ->
                try {
                    listener.onChanged(snapshot)
                } catch (e: Exception) {
                    Log.e("Readly", "ReaderState listener failed", e)
                }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
