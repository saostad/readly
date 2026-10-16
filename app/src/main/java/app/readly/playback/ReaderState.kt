package app.readly.playback

import android.os.Handler
import android.os.Looper
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
            snapshot = transform(snapshot)
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
