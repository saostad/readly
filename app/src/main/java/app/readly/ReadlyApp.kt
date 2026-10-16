package app.readly

import android.app.Application
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Holds the single app-wide background executor so the voice install keeps running across
 * rotation; the finished/cancelled/error state is delivered to whatever activity is attached.
 */
class ReadlyApp : Application() {
    val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** The in-flight voice install, or null when none is running. */
    @Volatile
    var installTask: InstallTask? = null
}
