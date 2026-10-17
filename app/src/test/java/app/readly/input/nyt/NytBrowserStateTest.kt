package app.readly.input.nyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event-sequence tests for the browser fallback's shared state helpers
 * ([BrowserReadTracker], [BrowserLoadTracker]), the same classes
 * `NytBrowserActivity` drives from WebView callbacks. No WebView, no network.
 */
class NytBrowserStateTest {

    private val articleA = "https://www.nytimes.com/2026/10/16/business/us-budget-deficit.html"
    private val articleB = "https://www.nytimes.com/2026/10/16/business/other-article.html"
    private val login = "https://myaccount.nytimes.com/auth/login"

    // ------------------------------------------------------------------
    // Read transactions (finding 1)
    // ------------------------------------------------------------------

    @Test
    fun read_currentPageHandoffSucceeds() {
        val tracker = BrowserReadTracker()
        val read = tracker.startRead(articleA)
        assertNotNull(read)
        read!!
        assertTrue(tracker.isCurrent(read))
        assertTrue(tracker.snapshotBelongs(read, articleA, articleA))
        var ran = false
        val outcome = tracker.finishRead(read, articleA) { ran = true }
        assertTrue(outcome is BrowserReadTracker.HandoffOutcome.Done)
        assertTrue(ran)
    }

    @Test
    fun read_fragmentChangeStillMatches() {
        val tracker = BrowserReadTracker()
        val read = tracker.startRead(articleA)!!
        assertTrue(sameReadUrl(articleA, "$articleA#section"))
        assertTrue(tracker.snapshotBelongs(read, "$articleA#section", articleA))
        var ran = false
        val outcome = tracker.finishRead(read, "$articleA#other") { ran = true }
        assertTrue(outcome is BrowserReadTracker.HandoffOutcome.Done)
        assertTrue(ran)
    }

    @Test
    fun read_articleAtoB_rejected() {
        val tracker = BrowserReadTracker()
        val read = tracker.startRead(articleA)!!
        // Snapshot for A while the callback already sees B: stale.
        assertFalse(tracker.snapshotBelongs(read, articleA, articleB))
        assertFalse(tracker.snapshotBelongs(read, articleB, articleA))
        // Final URL B never matches captured A.
        var ran = false
        val outcome = tracker.finishRead(read, articleB) { ran = true }
        assertTrue(outcome is BrowserReadTracker.HandoffOutcome.Stale)
        assertFalse(ran)
    }

    @Test
    fun read_lateNavigationBeforeParseCompletion_stale() {
        val tracker = BrowserReadTracker()
        val read = tracker.startRead(articleA)!!
        assertTrue(tracker.snapshotBelongs(read, articleA, articleA))
        // Navigation to B after the snapshot, before parse completion.
        tracker.onNavigationStarted()
        assertFalse(tracker.isCurrent(read))
        var ran = false
        val outcome = tracker.finishRead(read, articleA) { ran = true }
        assertTrue(outcome is BrowserReadTracker.HandoffOutcome.Stale)
        assertFalse(ran)
    }

    @Test
    fun read_sameDocumentChange_retires() {
        val tracker = BrowserReadTracker()
        val read = tracker.startRead(articleA)!!
        tracker.onSameDocumentChange()
        assertFalse(tracker.isCurrent(read))
        assertFalse(tracker.snapshotBelongs(read, articleA, articleA))
        var ran = false
        assertTrue(tracker.finishRead(read, articleA) { ran = true } is BrowserReadTracker.HandoffOutcome.Stale)
        assertFalse(ran)
    }

    @Test
    fun read_pauseResumeDestroy() {
        val tracker = BrowserReadTracker()
        val read = tracker.startRead(articleA)!!
        tracker.onPaused()
        assertTrue(tracker.isPaused())
        assertFalse(tracker.isCurrent(read))
        // No background handoff while paused.
        var ran = false
        assertTrue(tracker.finishRead(read, articleA) { ran = true } is BrowserReadTracker.HandoffOutcome.Stale)
        assertFalse(ran)
        // New reads are refused while paused.
        assertNull(tracker.startRead(articleA))
        tracker.onResumed()
        assertFalse(tracker.isPaused())
        // Old read stays retired; a fresh read works.
        assertFalse(tracker.isCurrent(read))
        val fresh = tracker.startRead(articleA)
        assertNotNull(fresh)
        tracker.onDestroyed()
        assertTrue(tracker.isDestroyed())
        assertFalse(tracker.isCurrent(fresh!!))
        assertNull(tracker.startRead(articleA))
        assertTrue(tracker.finishRead(fresh, articleA) { ran = true } is BrowserReadTracker.HandoffOutcome.Stale)
        assertFalse(ran)
    }

    @Test
    fun read_stoppedRetiresWithoutUnpausing() {
        val tracker = BrowserReadTracker()
        tracker.onPaused()
        tracker.onStopped()
        assertTrue(tracker.isPaused())
        assertNull(tracker.startRead(articleA))
        tracker.onResumed()
        assertNotNull(tracker.startRead(articleA))
    }

    @Test
    fun read_thrownHandoff_surfacesRealError() {
        val tracker = BrowserReadTracker()
        val read = tracker.startRead(articleA)!!
        val outcome = tracker.finishRead(read, articleA) { throw IllegalStateException("handoff boom") }
        assertTrue(outcome is BrowserReadTracker.HandoffOutcome.Failed)
        outcome as BrowserReadTracker.HandoffOutcome.Failed
        assertTrue(outcome.reason, "IllegalStateException" in outcome.reason)
        assertTrue(outcome.reason, "handoff boom" in outcome.reason)
    }

    @Test
    fun read_rejectsIneligibleAndBlank() {
        val tracker = BrowserReadTracker()
        assertNull(tracker.startRead(login))
        assertNull(tracker.startRead("https://example.com/x"))
        assertNull(tracker.startRead(""))
        assertNull(tracker.startRead(null))
        val read = tracker.startRead(articleA)!!
        // Ineligible snapshot/callback URLs never belong, even with matching text.
        assertFalse(tracker.snapshotBelongs(read, login, articleA))
        assertFalse(tracker.snapshotBelongs(read, articleA, login))
        assertFalse(tracker.snapshotBelongs(read, null, articleA))
    }

    // ------------------------------------------------------------------
    // Load state (finding 2)
    // ------------------------------------------------------------------

    @Test
    fun load_http403_finishRetainsError() {
        val loads = BrowserLoadTracker()
        assertTrue(loads.onNavigationStarted(articleA) is BrowserLoadTracker.NavStart.Allowed)
        assertTrue(loads.isLoading())
        assertFalse(loads.canEnableRead())
        val detail = "Couldn't load www.nytimes.com: HTTP 403 Forbidden"
        assertTrue(loads.onError(articleA, detail))
        assertEquals(detail, loads.currentError())
        assertFalse(loads.isLoading())
        assertFalse(loads.canEnableRead())
        // A finish event cannot erase its navigation's error.
        assertTrue(loads.onFinished(articleA) is BrowserLoadTracker.Finish.RetainError)
        assertEquals(detail, loads.currentError())
        assertFalse(loads.canEnableRead())
    }

    @Test
    fun load_reloadClearsErrorAndEnablesRead() {
        val loads = BrowserLoadTracker()
        loads.onNavigationStarted(articleA)
        loads.onError(articleA, "Couldn't load www.nytimes.com: HTTP 403 Forbidden")
        // Fresh reload starts a new allowed navigation and clears the error.
        assertTrue(loads.onNavigationStarted(articleA) is BrowserLoadTracker.NavStart.Allowed)
        assertNull(loads.currentError())
        assertTrue(loads.isLoading())
        assertFalse(loads.canEnableRead())
        assertTrue(loads.onFinished(articleA) is BrowserLoadTracker.Finish.ShowLoaded)
        assertFalse(loads.isLoading())
        assertTrue(loads.canEnableRead())
        // Login subdomains browse but never enable extraction.
        loads.onNavigationStarted(login)
        loads.onFinished(login)
        assertFalse(loads.canEnableRead())
    }

    @Test
    fun load_staleFinishAndErrorIgnored() {
        val loads = BrowserLoadTracker()
        loads.onNavigationStarted(articleA)
        loads.onFinished(articleA)
        assertTrue(loads.canEnableRead())
        // Navigate to B; old-page events for A are ignored.
        loads.onNavigationStarted(articleB)
        assertFalse(loads.onError(articleA, "stale error"))
        assertNull(loads.currentError())
        assertTrue(loads.onFinished(articleA) is BrowserLoadTracker.Finish.IgnoreStale)
        assertTrue(loads.isLoading())
        // Current page still loads; only its own finish completes it.
        assertTrue(loads.onFinished(articleB) is BrowserLoadTracker.Finish.ShowLoaded)
        assertTrue(loads.canEnableRead())
    }

    @Test
    fun load_unsafeTargetStaysBlocked() {
        val loads = BrowserLoadTracker()
        loads.onNavigationStarted(articleA)
        loads.onFinished(articleA)
        assertTrue(loads.canEnableRead())
        val evil = "https://nytimes.com.evil.test/x"
        val decision = loads.onNavigationStarted(evil)
        assertTrue(decision is BrowserLoadTracker.NavStart.Blocked)
        // Blocked target never becomes current; extraction stays disabled.
        assertEquals(articleA, loads.currentUrl())
        assertFalse(loads.canEnableRead())
        assertNotNull(loads.currentError())
        // A later allowed reload recovers.
        loads.onNavigationStarted(articleA)
        loads.onFinished(articleA)
        assertTrue(loads.canEnableRead())
    }

    @Test
    fun load_sameDocumentPreservesError_readSuccessClears() {
        val loads = BrowserLoadTracker()
        loads.onNavigationStarted(articleA)
        loads.onError(articleA, "Couldn't load www.nytimes.com: HTTP 403 Forbidden")
        loads.onSameDocument(articleB)
        assertEquals(articleB, loads.currentUrl())
        // Same-document alone doesn't clear; only full nav/reload or a read does.
        assertNotNull(loads.currentError())
        assertFalse(loads.canEnableRead())
        loads.onReadSucceeded()
        assertNull(loads.currentError())
    }

    @Test
    fun load_readDisabledWhileLoading() {
        val loads = BrowserLoadTracker()
        // No navigation yet: cannot read.
        assertFalse(loads.canEnableRead())
        loads.onNavigationStarted(articleA)
        // Loading: cannot read even though the URL is eligible.
        assertFalse(loads.canEnableRead())
    }
}
