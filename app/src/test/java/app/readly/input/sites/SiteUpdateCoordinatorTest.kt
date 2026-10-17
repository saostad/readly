package app.readly.input.sites

import app.readly.input.nyt.NytPolicy
import app.readly.input.nyt.NytPolicyStorage
import app.readly.input.nyt.NytTestFixtures.policyJson
import app.readly.input.sites.SiteTestFixtures.NYT_UA
import app.readly.input.sites.SiteTestFixtures.catalogJson
import app.readly.input.sites.SiteTestFixtures.nytEntry
import app.readly.input.sites.SiteTestFixtures.twoRuleSitesJs
import app.readly.input.sites.SiteTestFixtures.updateZip
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared-catalog update lifecycle over the one proven coordinator engine:
 * startup checks, the persisted auto-update toggle, coalescing, failure
 * propagation, nonblocking reads, and the settings summary/notice content.
 * No Android, no network.
 */
class SiteUpdateCoordinatorTest {

    private class FakeStorage(
        var policy: String? = null,
        var status: String? = null,
    ) : JsonRuleStorage {
        override fun readPolicyJson(): String? = policy
        override fun writePolicyJson(json: String) { policy = json }
        override fun readStatusJson(): String? = status
        override fun writeStatusJson(json: String) { status = json }
    }

    private class FakeLegacy(var policy: String? = null) : NytPolicyStorage {
        override fun readPolicyJson(): String? = policy
        override fun writePolicyJson(json: String): Nothing =
            throw AssertionError("legacy must never be written")
        override fun readStatusJson(): String? = null
        override fun writeStatusJson(json: String): Nothing =
            throw AssertionError("legacy must never be written")
    }

    private class FakeDownloader(var action: () -> ByteArray) {
        var calls = 0
        fun download(url: String): ByteArray {
            calls++
            return action()
        }
    }

    /** Queued executor: posted work runs only when the test pumps it. */
    private class ManualExecutor {
        val queue = ArrayDeque<Runnable>()
        val asFn: (Runnable) -> Unit = { queue.add(it) }
        fun pumpAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val warnings = mutableListOf<String>()
    private var now = 1_000_000L
    private var autoEnabled = true

    private fun bundled(): String = catalogJson(nytEntry())

    private fun coordinator(
        storage: FakeStorage = FakeStorage(),
        legacy: FakeLegacy = FakeLegacy(),
        downloader: FakeDownloader = FakeDownloader { updateZip(twoRuleSitesJs(), "4.4.7.5") },
        runBackground: (Runnable) -> Unit = { it.run() },
        factory: (() -> SiteCatalogStore)? = null,
    ): Triple<SiteUpdateCoordinator, FakeStorage, FakeDownloader> {
        val coord = SiteUpdateCoordinator(
            storeFactory = factory ?: {
                SiteCatalogStore(bundled(), storage, legacy, downloader::download, { now }, warnings::add)
            },
            isAutoUpdateEnabled = { autoEnabled },
            runBackground = runBackground,
            onWarn = warnings::add,
        )
        return Triple(coord, storage, downloader)
    }

    @Test
    fun startup_runsInitAndAutoCheck() {
        val (coord, _, downloader) = coordinator()
        assertFalse(coord.uiState().ready)
        coord.ensureStarted()
        assertEquals(1, downloader.calls)
        val state = coord.uiState()
        assertTrue(state.ready)
        assertFalse(state.checking)
        assertEquals("4.4.7.5", state.version)
        assertEquals(RuleUpdateStatus.ORIGIN_CACHE, state.origin)
        assertNull(state.startupError)
    }

    @Test
    fun toggleOff_skipsAutoButManualRuns() {
        autoEnabled = false
        val (coord, _, downloader) = coordinator()
        coord.ensureStarted()
        assertEquals(0, downloader.calls)
        assertTrue(coord.uiState().ready)
        assertEquals("4.4.7.4", coord.uiState().version)
        var result: RuleUpdateResult? = null
        coord.checkUpdates(manual = true) { result = it }
        assertEquals(1, downloader.calls)
        assertTrue(result is RuleUpdateResult.Checked)
        assertEquals("4.4.7.5", coord.uiState().version)
    }

    @Test
    fun concurrentChecks_coalesceToOneUpdate() {
        val manual = ManualExecutor()
        val (coord, _, downloader) = coordinator(runBackground = manual.asFn)
        val results = mutableListOf<RuleUpdateResult?>()
        coord.checkUpdates(manual = true, onDone = { results.add(it) })
        coord.checkUpdates(manual = true, onDone = { results.add(it) })
        assertTrue(coord.isChecking())
        assertEquals(0, downloader.calls)
        assertTrue(coord.uiState().checking)
        manual.pumpAll()
        assertEquals(1, downloader.calls)
        assertEquals(2, results.size)
        assertTrue(results.all { it is RuleUpdateResult.Checked })
        assertFalse(coord.isChecking())
    }

    @Test
    fun submitFailure_resetsCheckingAndExposesError() {
        var broken = true
        val downloader = FakeDownloader { updateZip(twoRuleSitesJs(), "4.4.7.5") }
        val coord = SiteUpdateCoordinator(
            storeFactory = {
                SiteCatalogStore(bundled(), FakeStorage(), FakeLegacy(), downloader::download, { now }, warnings::add)
            },
            isAutoUpdateEnabled = { autoEnabled },
            runBackground = {
                if (broken) throw RejectedExecutionException("queue full")
                it.run()
            },
            onWarn = warnings::add,
        )
        var delivered = false
        var result: RuleUpdateResult? = RuleUpdateResult.Throttled(
            RuleUpdateStatus(RuleUpdateStatus.ORIGIN_BUNDLED, "4.4.7.4", 0, 0, null),
        )
        coord.checkUpdates(manual = true) {
            delivered = true
            result = it
        }
        assertFalse(coord.isChecking())
        assertTrue(delivered)
        assertNull(result)
        val failed = coord.uiState()
        assertTrue(
            "coordinatorError was: ${failed.coordinatorError}",
            failed.coordinatorError != null && "queue full" in failed.coordinatorError,
        )
        broken = false
        coord.checkUpdates(manual = true) { result = it }
        assertTrue(result is RuleUpdateResult.Checked)
        assertNull(coord.uiState().coordinatorError)
    }

    @Test
    fun snapshotForReading_needsNoNetwork() {
        val (coord, _, downloader) = coordinator()
        val snapshot = coord.snapshotForReading()
        assertEquals(NYT_UA, snapshot!!.catalog.findRule("nytimes.com")!!.request.userAgent)
        assertNull(snapshot.legacyNyt)
        assertEquals(0, downloader.calls)
        assertTrue(coord.uiState().ready)
    }

    @Test
    fun uiState_returnsDuringSlowInit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val downloader = FakeDownloader { updateZip(twoRuleSitesJs(), "4.4.7.5") }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coord = SiteUpdateCoordinator(
                storeFactory = {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    SiteCatalogStore(bundled(), FakeStorage(), FakeLegacy(), downloader::download, { now }, warnings::add)
                },
                isAutoUpdateEnabled = { autoEnabled },
                runBackground = { pool.submit(it) },
                onWarn = warnings::add,
            )
            coord.ensureStarted()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val state = pool.submit<RuleUiState> { coord.uiState() }
                .get(2, TimeUnit.SECONDS)
            assertFalse(state.ready)
            assertTrue(state.checking)
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            val done = coord.uiState()
            assertTrue(done.ready)
            assertFalse(done.checking)
            assertEquals("4.4.7.5", done.version)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun startupFailure_showsErrorAndRecovers() {
        var broken = true
        val downloader = FakeDownloader { updateZip(twoRuleSitesJs(), "4.4.7.5") }
        val (coord, _, _) = coordinator(
            downloader = downloader,
            factory = {
                if (broken) throw IOException("asset missing")
                SiteCatalogStore(bundled(), FakeStorage(), FakeLegacy(), downloader::download, { now }, warnings::add)
            },
        )
        coord.ensureStarted()
        var result: RuleUpdateResult? = null
        coord.checkUpdates(manual = true) { result = it }
        assertNull(result)
        assertNull(coord.snapshotForReading())
        val failed = coord.uiState()
        assertFalse(failed.ready)
        assertTrue(
            "startupError was: ${failed.startupError}",
            failed.startupError != null && "Site reading fixes unavailable" in failed.startupError &&
                "asset missing" in failed.startupError,
        )
        broken = false
        coord.checkUpdates(manual = true) { result = it }
        assertTrue(result is RuleUpdateResult.Checked)
        assertTrue(coord.uiState().ready)
    }

    // ------------------------------------------------------------------
    // Settings summary and reading notices
    // ------------------------------------------------------------------

    @Test
    fun siteUiState_reportsSummaryAndLegacyVersion() {
        val legacy = FakeLegacy(
            policyJson(NytPolicy("Legacy-UA/1.0", true, null, "4.4.7.9", "", NytPolicy.OFFICIAL_UPDATE_URL)),
        )
        val (coord, _, _) = coordinator(legacy = legacy)
        coord.ensureStarted()
        val state = coord.siteUiState()
        assertTrue(state.base.ready)
        // Two-rule update: NYT (custom UA) + neighbor (googlebot UA+referer).
        assertEquals("2 domains imported; 2 request rules, 0 cleanup rules.", state.summary)
        assertEquals("4.4.7.9", state.legacyOverrideVersion)
    }

    @Test
    fun siteUiState_beforeInit_hasNoSummary() {
        val manual = ManualExecutor()
        val (coord, _, _) = coordinator(runBackground = manual.asFn)
        val state = coord.siteUiState()
        assertFalse(state.base.ready)
        assertNull(state.summary)
        assertNull(state.legacyOverrideVersion)
    }

    @Test
    fun catalogSummary_countsRules() {
        val catalog = SiteTestFixtures.import(
            nytEntry(),
            SiteTestFixtures.entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [{\"hide_elem\": \"div.wall\"}]",
            ),
            SiteTestFixtures.entry("Idle", "    domain: \"idle.example.com\",\n    allow_cookies: 1"),
        )
        assertEquals("3 domains imported; 1 request rules, 1 cleanup rules.", SiteUpdateCoordinator.catalogSummary(catalog))
    }

    @Test
    fun readingNotice_claimsOnlyAppliedDirectives() {
        val base = SiteUpdateCoordinator.readingNotice("example.com", "4.4.7.4", false, null, null, null, null)
        assertEquals("Using reading fixes for example.com (rules 4.4.7.4).", base)
        val full = SiteUpdateCoordinator.readingNotice(
            "example.com", "4.4.7.4", true, "4.4.7.9",
            "startup flaw", "status flaw", "update flaw",
        )
        assertTrue("notice was: $full", full.startsWith("Using reading fixes for example.com (rules 4.4.7.4)."))
        assertTrue("notice was: $full", "legacy rules 4.4.7.9" in full)
        assertTrue("notice was: $full", "Compatible parts only" in full)
        assertTrue("notice was: $full", "startup flaw" in full && "status flaw" in full && "update flaw" in full)
        val blanks = SiteUpdateCoordinator.readingNotice("example.com", "4.4.7.4", false, null, " ", "", null)
        assertEquals(base, blanks)
    }
}
