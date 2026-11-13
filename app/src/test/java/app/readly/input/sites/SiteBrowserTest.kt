package app.readly.input.sites

import app.readly.input.ArticleFetcher
import app.readly.input.nyt.NytBrowserPolicy
import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import app.readly.input.sites.SiteTestFixtures.nytEntry
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * General website browser fallback: eligible-HTTPS gates (no per-domain
 * list), unsafe-target reasons, cross-domain navigation with reads at the
 * current host, lifecycle/race behavior, shared snapshot caps, and guarded
 * rendered extraction with notice handoff. Every rendered test uses a
 * throwing opener, proving extraction never refetches. All fixtures are
 * synthetic; no owner article HTML.
 */
class SiteBrowserTest {

    /** Imported-rule host. */
    private val paper = "https://paper.example.com/2026/10/16/story.html"

    /** Unknown JavaScript-heavy host with no rule. */
    private val app = "https://app.example.org/article/123"

    private val story = "The council voted six to three after a long debate on Tuesday evening. "
        .repeat(14)

    private fun articlePage(extra: String = ""): String =
        "<html><head><title>Story</title>" +
            "<meta property=\"og:title\" content=\"Council Votes\"></head><body>" +
            "<article><h1>Council Votes</h1>$extra<p>$story</p></article></body></html>"

    /** Every extraction below runs through this throwing opener: no refetch, ever. */
    private val fetcher: ArticleFetcher
        get() = ArticleFetcher({ throw IOException("no network in this test") }, { _ -> })

    private fun extractFailsWith(html: String, url: String): String {
        try {
            fetcher.extractRenderedHtml(html, url)
        } catch (e: IOException) {
            return e.message ?: "(no message)"
        }
        fail("expected failure for $url")
        throw AssertionError("unreachable")
    }

    // ------------------------------------------------------------------
    // General gates: no per-domain list
    // ------------------------------------------------------------------

    @Test
    fun browsing_allowsOrdinaryWebsiteLinks() {
        assertTrue(SiteBrowserPolicy.allowsBrowsing(paper))
        assertTrue(SiteBrowserPolicy.allowsBrowsing(app))
        assertTrue(SiteBrowserPolicy.allowsBrowsing("https://auth.example.net/login"))
        assertTrue(SiteBrowserPolicy.allowsBrowsing("https://www.nytimes.com/2026/10/16/x.html"))
        assertTrue(SiteBrowserPolicy.allowsBrowsing("https://paper.example.com:443/x"))
        assertTrue(SiteBrowserPolicy.allowsBrowsing("HTTPS://PAPER.EXAMPLE.COM/x"))
    }

    @Test
    fun browsing_rejectsUnsafeTargetsWithReasons() {
        val cases = listOf(
            "http://paper.example.com/x" to "HTTPS",
            "file:///etc/passwd" to "file",
            "content://provider/x" to "content",
            "javascript:alert(1)" to "javascript",
            "https://user@paper.example.com/x" to "credentials",
            "https://paper.example.com:8443/x" to "8443",
            "https://1.2.3.4/x" to "host is invalid",
            "not a url" to "valid URL",
            "" to "empty",
        )
        for ((url, fragment) in cases) {
            assertFalse("allowed: $url", SiteBrowserPolicy.allowsBrowsing(url))
            assertFalse("extractable: $url", SiteBrowserPolicy.allowsExtraction(url))
            val reason = SiteBrowserPolicy.blockReason(url)
            assertTrue("$url reason was: $reason", fragment in reason)
            assertEquals(reason, SiteBrowserPolicy.blockReasonOrNull(url))
        }
        assertNull(SiteBrowserPolicy.blockReasonOrNull(paper))
    }

    @Test
    fun extraction_isAnyEligibleHost() {
        assertTrue(SiteBrowserPolicy.allowsExtraction(paper))
        assertTrue(SiteBrowserPolicy.allowsExtraction(app))
        assertTrue(SiteBrowserPolicy.allowsExtraction("https://myaccount.nytimes.com/auth/login"))
        assertFalse(SiteBrowserPolicy.allowsExtraction("http://paper.example.com/x"))
        assertFalse(SiteBrowserPolicy.allowsExtraction("https://user@paper.example.com/x"))
        assertFalse(SiteBrowserPolicy.allowsExtraction("file:///etc/passwd"))
    }

    @Test
    fun retryRouting_keepsExactEligibleLinkOnly() {
        assertEquals(paper, SiteBrowserPolicy.retryUrlForFailure(paper))
        assertEquals(app, SiteBrowserPolicy.retryUrlForFailure(app))
        assertNull(SiteBrowserPolicy.retryUrlForFailure("http://paper.example.com/x"))
        assertNull(SiteBrowserPolicy.retryUrlForFailure("file:///etc/passwd"))
        assertNull(SiteBrowserPolicy.retryUrlForFailure("not a url"))
    }

    // ------------------------------------------------------------------
    // Navigation, reads, and lifecycle on the shared trackers
    // ------------------------------------------------------------------

    private fun readTracker() = SiteBrowserReadTracker(SiteBrowserPolicy::allowsExtraction)

    private fun loadTracker() = SiteBrowserLoadTracker(
        SiteBrowserPolicy::allowsBrowsing,
        SiteBrowserPolicy::allowsExtraction,
    )

    @Test
    fun crossDomainNavigation_readsAtCurrentHost() {
        val reads = readTracker()
        val loads = loadTracker()
        assertTrue(loads.onNavigationStarted(paper) is SiteBrowserLoadTracker.NavStart.Allowed)
        assertTrue(loads.onFinished(paper) is SiteBrowserLoadTracker.Finish.ShowLoaded)
        // A legitimate cross-domain login hop is browsable and readable.
        val login = "https://auth.example.net/login"
        assertTrue(loads.onNavigationStarted(login) is SiteBrowserLoadTracker.NavStart.Allowed)
        assertTrue(loads.onFinished(login) is SiteBrowserLoadTracker.Finish.ShowLoaded)
        assertTrue(loads.canEnableRead())
        val read = reads.startRead(login)!!
        assertTrue(reads.snapshotBelongs(read, login, login))
        var ran = false
        val outcome = reads.finishRead(read, login) { ran = true }
        assertTrue(outcome is SiteBrowserReadTracker.HandoffOutcome.Done)
        assertTrue(ran)
        // Unsafe hops stay blocked and never become current.
        val evil = "http://auth.example.net/login"
        val decision = loads.onNavigationStarted(evil)
        assertTrue(decision is SiteBrowserLoadTracker.NavStart.Blocked)
        assertEquals(login, loads.currentUrl())
    }

    @Test
    fun staleSameDocumentAndPause_retireGeneralReads() {
        val reads = readTracker()
        val read = reads.startRead(paper)!!
        reads.onNavigationStarted()
        assertFalse(reads.isCurrent(read))
        var ran = false
        assertTrue(
            reads.finishRead(read, paper) { ran = true }
                is SiteBrowserReadTracker.HandoffOutcome.Stale,
        )
        assertFalse(ran)

        val second = reads.startRead(app)!!
        reads.onSameDocumentChange()
        assertFalse(reads.snapshotBelongs(second, app, app))

        val third = reads.startRead(app)!!
        reads.onPaused()
        assertNull(reads.startRead(app))
        assertTrue(
            reads.finishRead(third, app) { ran = true }
                is SiteBrowserReadTracker.HandoffOutcome.Stale,
        )
        assertFalse(ran)
        reads.onResumed()
        assertFalse(reads.isCurrent(third))
        val fresh = reads.startRead(app)
        assertTrue(fresh != null && reads.isCurrent(fresh))
        reads.onDestroyed()
        assertNull(reads.startRead(app))
    }

    @Test
    fun errorFinish_retainedUntilReload() {
        val loads = loadTracker()
        loads.onNavigationStarted(app)
        val detail = "Couldn't load app.example.org: HTTP 403 Forbidden"
        assertTrue(loads.onError(app, detail))
        assertTrue(loads.onFinished(app) is SiteBrowserLoadTracker.Finish.RetainError)
        assertFalse(loads.canEnableRead())
        loads.onNavigationStarted(app)
        assertTrue(loads.onFinished(app) is SiteBrowserLoadTracker.Finish.ShowLoaded)
        assertTrue(loads.canEnableRead())
        loads.onReadSucceeded()
        assertNull(loads.currentError())
    }

    @Test
    fun unsuccessfulStop_isItselfAFailureNeverLoaded() {
        val loads = loadTracker()
        loads.onNavigationStarted(app)
        // No error delegate fired, but the stop reports failure: the tracker
        // records it so callers never show "Loaded" for a failed page.
        assertTrue(
            loads.onFinished(app, success = false)
                is SiteBrowserLoadTracker.Finish.RetainError,
        )
        val error = loads.currentError()
        assertTrue(error!!.startsWith(SiteBrowserLoadTracker.LOAD_FAILED_PREFIX))
        assertFalse(loads.canEnableRead())
        // A recorded error still wins over the generic marker.
        loads.onNavigationStarted(app)
        val detail = "Couldn't load app.example.org: HTTP 403 Forbidden"
        assertTrue(loads.onError(app, detail))
        assertTrue(
            loads.onFinished(app, success = false)
                is SiteBrowserLoadTracker.Finish.RetainError,
        )
        assertEquals(detail, loads.currentError())
        // A stale unsuccessful stop for a prior page changes nothing.
        loads.onNavigationStarted(paper)
        assertTrue(
            loads.onFinished(app, success = false)
                is SiteBrowserLoadTracker.Finish.IgnoreStale,
        )
        assertNull(loads.currentError())
    }

    // ------------------------------------------------------------------
    // Shared snapshot core (one implementation, legacy wrapper delegates)
    // ------------------------------------------------------------------

    @Test
    fun snapshotCore_sharedWithLegacyPolicy() {
        assertEquals(SiteBrowserPolicy.MAX_DOM_BYTES, NytBrowserPolicy.MAX_DOM_BYTES)
        assertEquals(SiteBrowserPolicy.MAX_BRIDGE_CHARS, NytBrowserPolicy.MAX_BRIDGE_CHARS)
        assertEquals(SiteBrowserPolicy.MAX_HANDOFF_CHARS, NytBrowserPolicy.MAX_HANDOFF_CHARS)
        assertEquals(SiteBrowserPolicy.SNAPSHOT_JS, NytBrowserPolicy.SNAPSHOT_JS)
        assertNull(SiteBrowserPolicy.handoffTooLargeError(SiteBrowserPolicy.MAX_HANDOFF_CHARS))
        val tooLarge = SiteBrowserPolicy.handoffTooLargeError(SiteBrowserPolicy.MAX_HANDOFF_CHARS + 1)!!
        assertTrue("message was: $tooLarge", "too large" in tooLarge)
        val payload = JSONObject()
            .put("html", "<html><body><p>Hi.</p></body></html>")
            .put("url", app)
        val ok = SiteBrowserPolicy.decodeJsResult(JSONObject.quote(payload.toString()))
        assertTrue(ok is SiteBrowserPolicy.DomSnapshot.Ok)
        // 2,000,000 CJK chars = 6,000,000 UTF-8 bytes: rejected in bytes.
        val cjkOver = "漢".repeat(2_000_000)
        val rejected = SiteBrowserPolicy.decodeJsResult(
            JSONObject.quote(JSONObject().put("html", cjkOver).put("url", app).toString()),
        ) as SiteBrowserPolicy.DomSnapshot.Rejected
        assertTrue(rejected.reason, "too large" in rejected.reason)
        assertTrue(rejected.reason, "bytes" in rejected.reason)
        assertFalse(rejected.reason, "漢漢漢" in rejected.reason)
    }

    // ------------------------------------------------------------------
    // Guarded rendered extraction
    // ------------------------------------------------------------------

    @Test
    fun rendered_unknownHostArticle_extractsWithTitle() {
        val doc = fetcher.extractRenderedHtml(articlePage(), app)
        assertNull(doc.notice)
        assertEquals("Council Votes", doc.title)
        assertTrue(doc.text.contains("council voted"))
    }

    @Test
    fun rendered_unknownHostBlockPages_rejected() {
        val paywall = "<html><head><title>Story</title></head><body>" +
            "<div class=\"article-paywall\"><h2>Subscribe to continue reading</h2></div></body></html>"
        assertTrue(
            extractFailsWith(paywall, app),
            "subscription prompt" in extractFailsWith(paywall, app),
        )
        val login = "<html><head><title>Sign in</title></head><body>" +
            "<form action=\"/login\"><input name=\"u\"></form></body></html>"
        assertTrue(
            extractFailsWith(login, "https://app.example.org/login"),
            "login" in extractFailsWith(login, "https://app.example.org/login"),
        )
        val challenge = "<html><head><title>Just a moment...</title></head><body>" +
            "<p>Verify you are human to continue.</p></body></html>"
        assertTrue(
            extractFailsWith(challenge, app),
            "human-verification challenge" in extractFailsWith(challenge, app),
        )
    }

    @Test
    fun rendered_matchedHost_appliesCleanupWithoutHeaderClaims() {
        val snapshot = SiteReadingSnapshot(
            import(
                entry(
                    "Paper",
                    "    domain: \"paper.example.com\",\n" +
                        "    useragent: \"googlebot\",\n" +
                        "    cs_code: [{\"hide_elem\": \"div.promo-banner\"}]",
                ),
            ),
            null,
        )
        val url = "https://paper.example.com/story"
        val page = articlePage("<div class=\"promo-banner\">Sponsored offer.</div>")
        val usage = SiteUsage()
        val doc = fetcher.extractRenderedHtml(page, url, snapshot, usage)
        assertNull("extracted, not fallback", doc.notice)
        assertTrue(doc.text.contains("council voted"))
        assertFalse("banner leaked into speech: ${doc.text}", doc.text.contains("Sponsored offer"))
        assertTrue(usage.cleanupApplied)
        assertTrue(usage.headerHosts.isEmpty())
        // The handoff notice names the cleanup, never native request headers.
        val notice = SiteBrowserPolicy.readingNotice(doc.notice, usage.cleanupApplied, usage.noticeHost())
        assertEquals("Cleaned up the page for paper.example.com.", notice)
        assertFalse("Using reading fixes" in notice!!)
    }

    @Test
    fun rendered_fallbackNotice_preserved() {
        val short = "<html><head><title>Note</title></head><body><p>A short note.</p></body></html>"
        val doc = fetcher.extractRenderedHtml(short, app)
        assertEquals(ArticleFetcher.FALLBACK_NOTICE, doc.notice)
        assertEquals(
            ArticleFetcher.FALLBACK_NOTICE,
            SiteBrowserPolicy.readingNotice(doc.notice, false, "app.example.org"),
        )
    }

    @Test
    fun readingNotice_composesFallbackCleanupAndExtra() {
        assertNull(SiteBrowserPolicy.readingNotice(null, false, "paper.example.com"))
        assertEquals(
            "Cleaned up the page for paper.example.com.",
            SiteBrowserPolicy.readingNotice(null, true, "paper.example.com"),
        )
        val combined = SiteBrowserPolicy.readingNotice(
            ArticleFetcher.FALLBACK_NOTICE,
            true,
            "paper.example.com",
            "Cached site catalog rejected: catalog has no rules, using bundled catalog 1.0",
        )!!
        assertTrue(ArticleFetcher.FALLBACK_NOTICE in combined)
        assertTrue("Cleaned up the page for paper.example.com." in combined)
        assertTrue("rejected" in combined)
        assertFalse("Using reading fixes" in combined)
    }

    @Test
    fun rendered_nytSubdomain_usesOrdinaryPath() {
        // The NYT extractor refuses front pages; the ordinary path reads
        // them — proving cooking.* never takes the strict NYT route (or UA).
        val doc = fetcher.extractRenderedHtml(articlePage(), "https://cooking.nytimes.com/")
        assertNull(doc.notice)
        assertTrue(doc.text.contains("council voted"))
    }

    @Test
    fun rendered_nytMainHost_preservedWithSnapshot() {
        val snapshot = SiteReadingSnapshot(import(nytEntry()), null)
        val url = "https://www.nytimes.com/2026/10/16/business/us-budget-deficit.html"
        val html = "<html><head><title>Deficit Story - The New York Times</title>" +
            "<meta property=\"og:title\" content=\"Deficit Story\"></head><body>" +
            "<section name=\"articleBody\">" +
            "<p>Rendered paragraph one with enough words to read aloud.</p>" +
            "<p>Rendered paragraph two with enough words to read aloud.</p>" +
            "</section></body></html>"
        val usage = SiteUsage()
        val doc = fetcher.extractRenderedHtml(html, url, snapshot, usage)
        assertEquals("Deficit Story", doc.title)
        assertTrue(doc.text.contains("Rendered paragraph one"))
        assertFalse(usage.cleanupApplied)
        // ... and the exact NYT gateway guard still fires first.
        val gate = "<html><head><title>X - The New York Times</title></head><body>" +
            "<section name=\"articleBody\"><p>Teaser text here.</p></section>" +
            "<div id=\"gateway-content\"><h2>Subscribe to continue reading.</h2></div>" +
            "</body></html>"
        val message = try {
            fetcher.extractRenderedHtml(gate, url, snapshot, SiteUsage())
            fail("expected gateway failure")
            ""
        } catch (e: IOException) {
            e.message ?: "(no message)"
        }
        assertTrue("message was: $message", message.startsWith("Couldn't read this New York Times article: "))
    }

    @Test
    fun blankPage_isTheBrowsersOwnStartNotABlockedSite() {
        assertTrue(SiteBrowserPolicy.isBlankPage("about:blank"))
        assertTrue(SiteBrowserPolicy.isBlankPage(" ABOUT:BLANK "))
        assertFalse(SiteBrowserPolicy.isBlankPage("about:config"))
        assertFalse(SiteBrowserPolicy.isBlankPage("https://www.nytimes.com/"))
        // Other non-website addresses stay blocked.
        assertFalse(SiteBrowserPolicy.allowsBrowsing("about:config"))
    }
}
