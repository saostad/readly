package app.readly.input.nyt

import app.readly.input.sites.SiteBrowserLoadTracker
import app.readly.input.sites.SiteBrowserReadTracker
import app.readly.input.sites.sameReadUrl as sharedSameReadUrl
import app.readly.input.sites.stripReadFragment as sharedStripReadFragment

/**
 * Legacy NYT-only browser state over the shared sites core
 * ([SiteBrowserReadTracker], [SiteBrowserLoadTracker]) with the original
 * stricter NYT gates. Same public API as before, so the NYT event-sequence
 * tests keep passing unmodified; production now uses the general website
 * trackers.
 */

/** Strips the fragment (`#…`) for read matching; fragments don't change the article. */
fun stripReadFragment(url: String): String = sharedStripReadFragment(url)

/**
 * True when [observed] is the same article page as [captured]:
 * fragment-insensitive, exact otherwise. Null/blank never matches.
 */
fun sameReadUrl(captured: String?, observed: String?): Boolean =
    sharedSameReadUrl(captured, observed)

/**
 * One `Read page` transaction: the exact article URL captured when the user
 * tapped, plus the page generation at that moment.
 */
typealias BrowserRead = app.readly.input.sites.BrowserRead

/**
 * Tracks read transactions across navigations and lifecycle. Thread-safe:
 * the activity touches it on main, the parse thread validates against it.
 */
class BrowserReadTracker {
    private val core = SiteBrowserReadTracker(NytBrowserPolicy::allowsExtraction)

    /**
     * Captures a read for [currentUrl], or null when no read may start
     * (background/destroyed, blank URL, or not a main NYT article page).
     */
    fun startRead(currentUrl: String?): BrowserRead? = core.startRead(currentUrl)

    /** A full navigation started; retires any in-flight read. */
    fun onNavigationStarted() = core.onNavigationStarted()

    /** A same-document URL change; retires any in-flight read. */
    fun onSameDocumentChange() = core.onSameDocumentChange()

    /** Activity paused/stopped; retires reads and blocks background handoffs. */
    fun onPaused() = core.onPaused()

    /** Activity stopped after pause; retires again (pause already set). */
    fun onStopped() = core.onStopped()

    /** Activity resumed; new reads may start, old ones stay retired. */
    fun onResumed() = core.onResumed()

    /** Activity destroyed; retires reads permanently. */
    fun onDestroyed() = core.onDestroyed()

    fun isPaused(): Boolean = core.isPaused()

    fun isDestroyed(): Boolean = core.isDestroyed()

    /** True when [read] may still complete (foreground, alive, current generation). */
    fun isCurrent(read: BrowserRead): Boolean = core.isCurrent(read)

    /**
     * True when the snapshot and callback URLs both belong to [read]. The
     * parse thread calls this before extracting; the final WebView URL is
     * rechecked on main in [finishRead] to catch late navigations.
     */
    fun snapshotBelongs(read: BrowserRead, snapshotUrl: String?, callbackUrl: String?): Boolean =
        core.snapshotBelongs(read, snapshotUrl, callbackUrl)

    /** Outcome of [finishRead]: applied, failed with a message, or stale. */
    sealed interface HandoffOutcome {
        /** The page moved on or the activity left foreground; discard silently. */
        data object Stale : HandoffOutcome

        /** The handoff ran and failed; [reason] names the real error. */
        data class Failed(val reason: String) : HandoffOutcome

        /** The handoff ran successfully. */
        data object Done : HandoffOutcome
    }

    /**
     * Validates [finalUrl] (the current WebView URL on main) against [read]
     * and runs [handoff] when current. Catches handoff exceptions into
     * [HandoffOutcome.Failed] with the real class/message; never runs while
     * paused, stopped or destroyed. Call on main only.
     */
    fun finishRead(read: BrowserRead, finalUrl: String?, handoff: () -> Unit): HandoffOutcome =
        when (val outcome = core.finishRead(read, finalUrl, handoff)) {
            is SiteBrowserReadTracker.HandoffOutcome.Stale -> HandoffOutcome.Stale
            is SiteBrowserReadTracker.HandoffOutcome.Done -> HandoffOutcome.Done
            is SiteBrowserReadTracker.HandoffOutcome.Failed -> HandoffOutcome.Failed(outcome.reason)
        }
}

/**
 * Tracks the active main-frame navigation's load state so errors survive
 * their `onPageFinished` and stale events for prior pages are ignored.
 * Main-thread only (WebView callbacks); tests drive it single-threaded.
 */
class BrowserLoadTracker {
    private val core = SiteBrowserLoadTracker(
        NytBrowserPolicy::allowsBrowsing,
        NytBrowserPolicy::allowsExtraction,
    )

    /** Point-in-time load state for status/button decisions. */
    data class State(val currentUrl: String?, val loading: Boolean, val error: String?)

    fun snapshot(): State = core.snapshot().let { State(it.currentUrl, it.loading, it.error) }

    fun isLoading(): Boolean = core.isLoading()

    fun currentError(): String? = core.currentError()

    fun currentUrl(): String? = core.currentUrl()

    /** Outcome of [onNavigationStarted]: allowed loads proceed, others stay blocked. */
    sealed interface NavStart {
        data object Allowed : NavStart
        data class Blocked(val reasonHost: String) : NavStart
    }

    /**
     * A main-frame navigation to [url] was observed. Allowed targets become
     * current, clear any prior error and start loading; disallowed targets
     * keep the previous page current and record a blocked error. Returns the
     * decision so the activity can `stopLoading()` when blocked.
     */
    fun onNavigationStarted(url: String): NavStart =
        when (val decision = core.onNavigationStarted(url)) {
            is SiteBrowserLoadTracker.NavStart.Allowed -> NavStart.Allowed
            is SiteBrowserLoadTracker.NavStart.Blocked -> NavStart.Blocked(decision.reasonHost)
        }

    /**
     * Records a main-frame load failure for [url]. Stale errors for prior
     * URLs are ignored. Returns true when applied to the current navigation.
     */
    fun onError(url: String, detail: String): Boolean = core.onError(url, detail)

    /** Outcome of [onFinished]: show loaded, keep the error, or ignore stale. */
    sealed interface Finish {
        data object ShowLoaded : Finish
        data object RetainError : Finish
        data object IgnoreStale : Finish
    }

    /**
     * A main-frame finish for [url]. Stale finishes for prior pages are
     * ignored; a finish never erases its navigation's error.
     */
    fun onFinished(url: String): Finish =
        when (core.onFinished(url)) {
            is SiteBrowserLoadTracker.Finish.ShowLoaded -> Finish.ShowLoaded
            is SiteBrowserLoadTracker.Finish.RetainError -> Finish.RetainError
            is SiteBrowserLoadTracker.Finish.IgnoreStale -> Finish.IgnoreStale
        }

    /**
     * A same-document URL change (no reload). Updates the current URL and
     * preserves any load error (only a full navigation, reload or successful
     * reading clears it).
     */
    fun onSameDocument(url: String) = core.onSameDocument(url)

    /** A `Read page` handoff succeeded; the page proved readable. */
    fun onReadSucceeded() = core.onReadSucceeded()

    /**
     * True when `Read page` may be enabled: a loaded, error-free, main NYT
     * article page. Callers also gate on their own busy flag.
     */
    fun canEnableRead(): Boolean = core.canEnableRead()
}
