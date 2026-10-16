package app.readly

import android.os.Handler
import android.os.Looper
import app.readly.model.InstallStage
import app.readly.model.VoiceModelStore
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [VoiceModelStore.install] on the app-wide executor and forwards progress to the
 * attached [Listener] on the main thread. The activity attaches in `onCreate` and detaches in
 * `onDestroy`, so rotation re-attaches to the running install instead of restarting it.
 */
class InstallTask(
    private val store: VoiceModelStore,
    private val onFinished: (InstallTask) -> Unit,
) {
    interface Listener {
        fun onProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long)
        fun onDone()
        fun onCancelled()
        fun onError(e: Exception)
    }

    /** Set/cleared by the activity; always invoked on the main thread. */
    @Volatile
    var listener: Listener? = null

    @Volatile
    var isDone = false
        private set

    /** Last reported progress, so a re-attached activity can repaint immediately. */
    @Volatile
    var lastStage: InstallStage = InstallStage.DOWNLOADING
        private set

    @Volatile
    var lastDoneBytes: Long = 0
        private set

    @Volatile
    var lastTotalBytes: Long = VoiceModelStore.ARCHIVE_SIZE_BYTES
        private set

    private val cancelled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cancel() {
        cancelled.set(true)
    }

    /** Entry point for the executor thread. */
    fun run() {
        try {
            store.install(
                progress = { stage, done, total ->
                    lastStage = stage
                    lastDoneBytes = done
                    lastTotalBytes = total
                    post { it.onProgress(stage, done, total) }
                },
                cancelled = { cancelled.get() },
            )
            isDone = true
            post { it.onDone() }
        } catch (e: CancellationException) {
            isDone = true
            post { it.onCancelled() }
        } catch (e: Exception) {
            isDone = true
            post { it.onError(e) }
        } finally {
            onFinished(this)
        }
    }

    private fun post(call: (Listener) -> Unit) {
        if (listener != null) {
            // Re-read on the main thread: a detached (e.g. rotated-away) activity gets nothing.
            mainHandler.post { listener?.let(call) }
        }
    }
}
