package app.readly

import android.app.Application
import android.content.Context
import app.readly.input.ArticleFetcher
import app.readly.input.nyt.FileNytPolicyStorage
import app.readly.input.sites.AtomicJsonFileStore
import app.readly.input.sites.FixedOriginDownloader
import app.readly.input.sites.SiteCatalog
import app.readly.input.sites.SiteCatalogStore
import app.readly.input.sites.SiteUpdateCoordinator
import app.readly.model.VoiceCatalog
import app.readly.playback.ReaderService
import app.readly.tts.EngineManager
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Holds the single app-wide background executor so the voice install keeps running across
 * rotation; the finished/cancelled/error state is delivered to whatever activity is attached.
 */
class ReadlyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any activity or service touches the catalog.
        VoiceCatalog.ensureLoaded(this)
        // Initializes the shared rule store and runs one throttled automatic
        // check, all on readly-sites; returns immediately.
        siteRules.ensureStarted()
    }

    val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Serial background thread for rule init and update checks. */
    private val sitesExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "readly-sites") }
    }

    /**
     * App-owned shared website reading-fix rules: bundled asset plus the
     * validated cache under `noBackupFilesDir/sites`, with a daily automatic
     * refresh while Readly is in use. The legacy `noBackupFilesDir/nyt` files
     * are read migration-only (never written) until a compatible catalog at
     * or above the legacy version replaces them. This is the only active
     * rule updater; the NYT updater class remains solely as the tested
     * legacy wrapper and is never initialized here. No persistent
     * scheduler, server or WebView.
     */
    val siteRules: SiteUpdateCoordinator by lazy {
        SiteUpdateCoordinator(
            storeFactory = {
                val bundled = assets.open("sites/site_catalog.json").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                SiteCatalogStore(
                    bundled,
                    AtomicJsonFileStore(
                        File(noBackupFilesDir, "sites"),
                        SiteCatalogStore.POLICY_NAME,
                        SiteCatalogStore.STATUS_NAME,
                        "Site rule",
                    ),
                    FileNytPolicyStorage(File(noBackupFilesDir, "nyt")),
                    FixedOriginDownloader(
                        ArticleFetcher::httpUrlOpener,
                        SiteCatalog.OFFICIAL_UPDATE_URL,
                        "Site rule update",
                    )::download,
                )
            },
            isAutoUpdateEnabled = {
                getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(ReaderService.KEY_NYT_AUTO_UPDATE, true)
            },
            runBackground = sitesExecutor::execute,
        )
    }

    /**
     * App-level speech engines (LRU of 2 on `readly-tts`), shared by the reader
     * and the system TTS service. Lives for the whole process; [EngineManager]
     * never quits its thread.
     */
    val engines: EngineManager by lazy { EngineManager(this) }

    /** The in-flight voice install, or null when none is running. */
    @Volatile
    var installTask: InstallTask? = null
}
