package app.readly.article

import app.readly.input.sites.SiteBrowserPolicy
import app.readly.input.sites.SiteBrowserReadTracker
import app.readly.input.sites.sameReadUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle and staleness rules the automatic article engine inherits from
 * the shared browser trackers: reload/query/SPA navigation retires captures,
 * and pause/stop/destroy blocks handoff even for already-complete captures.
 */
class ArticleLifecycleTest {

    private fun tracker() = SiteBrowserReadTracker(SiteBrowserPolicy::allowsExtraction)

    private val article = "https://paper.example.com/2026/10/16/story"

    @Test
    fun `same-url reload retires the armed capture`() {
        val reads = tracker()
        val read = reads.startRead(article)!!
        assertTrue(reads.isCurrent(read))
        reads.onNavigationStarted()
        assertFalse(reads.isCurrent(read))
        assertTrue(reads.finishRead(read, article) {} is SiteBrowserReadTracker.HandoffOutcome.Stale)
    }

    @Test
    fun `query change is a different document`() {
        assertFalse(sameReadUrl(article, "$article?sh=zzz"))
        assertFalse(sameReadUrl("$article?x=1", "$article?x=2"))
        assertTrue(sameReadUrl(article, "$article#section"))
    }

    @Test
    fun `foreign snapshot or callback urls never belong`() {
        val reads = tracker()
        val read = reads.startRead(article)!!
        assertFalse(reads.snapshotBelongs(read, "https://other.example.com/x", article))
        assertFalse(reads.snapshotBelongs(read, article, "https://other.example.com/x"))
        assertFalse(reads.snapshotBelongs(read, "$article?sh=zzz", article))
    }

    @Test
    fun `pause blocks handoff of an already-complete capture`() {
        val reads = tracker()
        val read = reads.startRead(article)!!
        // Capture completed while foreground; the app backgrounds before handoff.
        reads.onPaused()
        var ran = false
        val outcome = reads.finishRead(read, article) { ran = true }
        assertTrue(outcome is SiteBrowserReadTracker.HandoffOutcome.Stale)
        assertFalse(ran)
        // Resume does not revive the retired read; a recapture must start fresh.
        reads.onResumed()
        assertFalse(reads.isCurrent(read))
    }

    @Test
    fun `destroy blocks handoff permanently`() {
        val reads = tracker()
        val read = reads.startRead(article)!!
        reads.onDestroyed()
        var ran = false
        val outcome = reads.finishRead(read, article) { ran = true }
        assertTrue(outcome is SiteBrowserReadTracker.HandoffOutcome.Stale)
        assertFalse(ran)
        reads.onResumed()
        assertFalse(reads.isCurrent(read))
    }

    @Test
    fun `handoff exception surfaces the real error`() {
        val reads = tracker()
        val read = reads.startRead(article)!!
        val outcome = reads.finishRead(read, article) {
            throw IllegalStateException("speech gone")
        }
        val failed = outcome as SiteBrowserReadTracker.HandoffOutcome.Failed
        assertEquals(
            "Read page failed: java.lang.IllegalStateException: speech gone",
            failed.reason,
        )
    }
}
