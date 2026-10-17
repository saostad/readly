package app.readly.input.sites

import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared pure-JVM state for the browser fallback's read transactions and
 * page loads (see [SiteBrowserPolicy]). The activity drives these from
 * WebView callbacks on the main thread (plus the parse thread for
 * [SiteBrowserReadTracker] validation); host tests drive the same methods
 * to replay event sequences without a WebView.
 *
 * URL gates arrive as injected predicates so one proven core serves the
 * general website browser and the stricter legacy NYT wrapper. The legacy
 * NYT trackers keep their exact public API as thin wrappers over these.
 */

/** Strips the fragment (`#…`) for read matching; fragments don't change the article. */
fun stripReadFragment(url: String): String = url.substringBefore("#")

/**
 * True when [observed] is the same article page as [captured]:
 * fragment-insensitive, exact otherwise. Null/blank never matches.
 */
fun sameReadUrl(captured: String?, observed: String?): Boolean {
    if (captured.isNullOrBlank() || observed.isNullOrBlank()) return false
    return stripReadFragment(captured) == stripReadFragment(observed)
}

/**
 * One `Read page` transaction: the exact article URL captured when the user
 * tapped, plus the page generation at that moment. A result is applied only
 * when its generation is still current, the activity is foreground and alive,
 * and the snapshot, callback-time and final URLs all match [url].
 */
data class BrowserRead(val url: String, val generation: Int)

/**
 * Generation guard against stale browser callbacks: the activity bumps the
 * generation on every navigation and on destroy, and a `Read page` result
 * is applied only when its generation is still current. Thread-safe; the
 * parse runs off the main thread while navigations land on it.
 */
class PageGeneration {
    private val current = AtomicInteger(0)

    /** The current generation, captured before an async read. */
    fun current(): Int = current.get()

    /** Moves to the next generation (navigation started); returns it. */
    fun next(): Int = current.incrementAndGet()

    /** True when [generation] is still the current one. */
    fun isCurrent(generation: Int): Boolean = current.get() == generation

    /** Retires every outstanding generation (activity destroyed). */
    fun invalidate() {
        current.incrementAndGet()
    }
}

/**
 * Tracks read transactions across navigations and lifecycle. Thread-safe:
 * the activity touches it on main, the parse thread validates against it.
 *
 * @param allowsExtraction gate for the URL captured at tap time and for the
 *   snapshot/callback/final URLs (general: any eligible website address;
 *   legacy NYT: main NYT article hosts only).
 */
class SiteBrowserReadTracker(
    private val allowsExtraction: (String) -> Boolean,
) {
    private val generation = PageGeneration()

    @Volatile
    private var paused = false

    @Volatile
    private var destroyed = false

    /**
     * Captures a read for [currentUrl], or null when no read may start
     * (background/destroyed, blank URL, or an ineligible page).
     */
    fun startRead(currentUrl: String?): BrowserRead? {
        if (paused || destroyed) return null
        if (currentUrl.isNullOrBlank() || !allowsExtraction(currentUrl)) return null
        return BrowserRead(currentUrl, generation.current())
    }

    /** A full navigation started; retires any in-flight read. */
    fun onNavigationStarted() {
        generation.next()
    }

    /** A same-document URL change; retires any in-flight read. */
    fun onSameDocumentChange() {
        generation.next()
    }

    /** Activity paused/stopped; retires reads and blocks background handoffs. */
    fun onPaused() {
        paused = true
        generation.next()
    }

    /** Activity stopped after pause; retires again (pause already set). */
    fun onStopped() {
        generation.next()
    }

    /** Activity resumed; new reads may start, old ones stay retired. */
    fun onResumed() {
        paused = false
    }

    /** Activity destroyed; retires reads permanently. */
    fun onDestroyed() {
        destroyed = true
        generation.next()
    }

    fun isPaused(): Boolean = paused

    fun isDestroyed(): Boolean = destroyed

    /** True when [read] may still complete (foreground, alive, current generation). */
    fun isCurrent(read: BrowserRead): Boolean =
        !paused && !destroyed && generation.isCurrent(read.generation)

    /**
     * True when the snapshot and callback URLs both belong to [read]. The
     * parse thread calls this before extracting; the final WebView URL is
     * rechecked on main in [finishRead] to catch late navigations.
     */
    fun snapshotBelongs(read: BrowserRead, snapshotUrl: String?, callbackUrl: String?): Boolean {
        if (!isCurrent(read)) return false
        if (!sameReadUrl(read.url, snapshotUrl)) return false
        if (!sameReadUrl(read.url, callbackUrl)) return false
        if (snapshotUrl.isNullOrBlank() || !allowsExtraction(snapshotUrl)) return false
        if (callbackUrl.isNullOrBlank() || !allowsExtraction(callbackUrl)) return false
        return true
    }

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
    fun finishRead(read: BrowserRead, finalUrl: String?, handoff: () -> Unit): HandoffOutcome {
        if (!isCurrent(read)) return HandoffOutcome.Stale
        if (!sameReadUrl(read.url, finalUrl)) return HandoffOutcome.Stale
        if (finalUrl.isNullOrBlank() || !allowsExtraction(finalUrl)) {
            return HandoffOutcome.Stale
        }
        return try {
            handoff()
            HandoffOutcome.Done
        } catch (e: Exception) {
            HandoffOutcome.Failed("Read page failed: ${e.javaClass.name}: ${e.message}")
        }
    }
}

/**
 * Tracks the active main-frame navigation's load state so errors survive
 * their `onPageFinished` and stale events for prior pages are ignored.
 * Main-thread only (WebView callbacks); tests drive it single-threaded.
 *
 * @param allowsBrowsing main-frame navigation gate; @param allowsExtraction
 *   `Read page` enable gate.
 */
class SiteBrowserLoadTracker(
    private val allowsBrowsing: (String) -> Boolean,
    private val allowsExtraction: (String) -> Boolean,
) {
    private var currentUrl: String? = null
    private var loading = false
    private var error: String? = null

    /** Point-in-time load state for status/button decisions. */
    data class State(val currentUrl: String?, val loading: Boolean, val error: String?)

    fun snapshot(): State = State(currentUrl, loading, error)

    fun isLoading(): Boolean = loading

    fun currentError(): String? = error

    fun currentUrl(): String? = currentUrl

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
    fun onNavigationStarted(url: String): NavStart {
        if (!allowsBrowsing(url)) {
            error = "blocked:$url"
            loading = false
            return NavStart.Blocked(url)
        }
        currentUrl = url
        loading = true
        error = null
        return NavStart.Allowed
    }

    /**
     * Records a main-frame load failure for [url]. Stale errors for prior
     * URLs are ignored. Returns true when applied to the current navigation.
     */
    fun onError(url: String, detail: String): Boolean {
        if (currentUrl == null || !sameReadUrl(currentUrl, url)) return false
        error = detail
        loading = false
        return true
    }

    /** Outcome of [onFinished]: show loaded, keep the error, or ignore stale. */
    sealed interface Finish {
        data object ShowLoaded : Finish
        data object RetainError : Finish
        data object IgnoreStale : Finish
    }

    companion object {
        /** [onFinished] records this marker when a stop reports failure. */
        const val LOAD_FAILED_PREFIX = "load-failed:"
    }

    /**
     * A main-frame finish for [url]. Stale finishes for prior pages are
     * ignored; a finish never erases its navigation's error. An unsuccessful
     * stop ([success] false) is itself a failure: it records a
     * [LOAD_FAILED_PREFIX] error unless one is already set, so callers never
     * show "Loaded" for it. Defaults to success for WebView-style callers
     * whose finish callback carries no status.
     */
    fun onFinished(url: String, success: Boolean = true): Finish {
        if (currentUrl == null || !sameReadUrl(currentUrl, url)) return Finish.IgnoreStale
        if (!success && error == null) {
            error = "$LOAD_FAILED_PREFIX$url"
            loading = false
            return Finish.RetainError
        }
        if (error != null) {
            loading = false
            return Finish.RetainError
        }
        loading = false
        return Finish.ShowLoaded
    }

    /**
     * A same-document URL change (no reload). Updates the current URL and
     * preserves any load error (only a full navigation, reload or successful
     * reading clears it).
     */
    fun onSameDocument(url: String) {
        currentUrl = url
    }

    /** A `Read page` handoff succeeded; the page proved readable. */
    fun onReadSucceeded() {
        error = null
    }

    /**
     * True when `Read page` may be enabled: a loaded, error-free,
     * extraction-eligible page. Callers also gate on their own busy flag.
     */
    fun canEnableRead(): Boolean {
        if (loading || error != null) return false
        val url = currentUrl ?: return false
        return allowsExtraction(url)
    }
}
