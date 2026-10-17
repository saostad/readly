package app.readly.input.nyt

import app.readly.input.nyt.NytTestFixtures.UA
import app.readly.input.nyt.NytTestFixtures.bundledJson
import app.readly.input.nyt.NytTestFixtures.manifestJson
import app.readly.input.nyt.NytTestFixtures.nytBody
import app.readly.input.nyt.NytTestFixtures.realStyleSitesJs
import app.readly.input.nyt.NytTestFixtures.updateZip
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
 * App-owned NYT update lifecycle, driven with fake storage/downloaders and
 * direct or queued executors. No Android, no network, no string mirroring:
 * assertions check state fields and error propagation, not exact UI text.
 */
class NytUpdateCoordinatorTest {

    private class FakeStorage(
        var policy: String? = null,
        var status: String? = null,
    ) : NytPolicyStorage {
        override fun readPolicyJson(): String? = policy
        override fun writePolicyJson(json: String) { policy = json }
        override fun readStatusJson(): String? = status
        override fun writeStatusJson(json: String) { status = json }
    }

    private class FakeDownloader(var action: () -> ByteArray) : NytUpdateDownloader {
        var calls = 0
        override fun download(url: String): ByteArray {
            calls++
            return action()
        }
    }

    /** Queued executor: posted work runs only when the test pumps it. */
    private class ManualExecutor {
        val queue = ArrayDeque<Runnable>()
        val asFn: (Runnable) -> Unit = { queue.add(it) }
        fun pumpOne() {
            queue.removeFirst().run()
        }
        fun pumpAll() {
            while (queue.isNotEmpty()) pumpOne()
        }
    }

    private val warnings = mutableListOf<String>()
    private var now = 1_000_000L
    private var autoEnabled = true

    private fun updateZipFor(version: String): ByteArray =
        updateZip(realStyleSitesJs(nytBody()), manifestJson("\"$version\""))

    private fun coordinator(
        storage: FakeStorage = FakeStorage(),
        downloader: FakeDownloader = FakeDownloader { updateZipFor("2.0") },
        runBackground: (Runnable) -> Unit = { it.run() },
        factory: (() -> NytPolicyStore)? = null,
    ): Triple<NytUpdateCoordinator, FakeStorage, FakeDownloader> {
        val coord = NytUpdateCoordinator(
            storeFactory = factory ?: { NytPolicyStore(bundledJson(), storage, downloader, { now }, warnings::add) },
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
        assertEquals("2.0", state.version)
        assertEquals(NytUpdateStatus.ORIGIN_CACHE, state.origin)
        assertEquals(now, state.lastCheckMs)
        assertNull(state.lastError)
        assertNull(state.startupError)
    }

    @Test
    fun toggleOff_skipsAutoButManualRuns() {
        autoEnabled = false
        val (coord, _, downloader) = coordinator()
        coord.ensureStarted()
        assertEquals(0, downloader.calls)
        val idle = coord.uiState()
        assertTrue(idle.ready)
        assertEquals("1.0", idle.version)
        assertEquals(NytUpdateStatus.ORIGIN_BUNDLED, idle.origin)
        var result: NytUpdateResult? = null
        coord.checkUpdates(manual = true) { result = it }
        assertEquals(1, downloader.calls)
        assertTrue(result is NytUpdateResult.Checked)
        assertEquals("2.0", coord.uiState().version)
    }

    @Test
    fun startupFailure_showsErrorAndRecovers() {
        var broken = true
        val good = FakeStorage()
        val downloader = FakeDownloader { updateZipFor("2.0") }
        val (coord, _, _) = coordinator(
            downloader = downloader,
            factory = {
                if (broken) throw IOException("asset missing")
                NytPolicyStore(bundledJson(), good, downloader, { now }, warnings::add)
            },
        )
        coord.ensureStarted()
        var result: NytUpdateResult? = NytUpdateResult.Throttled(coord.uiState().let {
            NytUpdateStatus(NytUpdateStatus.ORIGIN_BUNDLED, "1.0", 0, 0, null)
        })
        coord.checkUpdates(manual = true) { result = it }
        assertNull(result)
        assertNull(coord.policyForReading())
        val failed = coord.uiState()
        assertFalse(failed.ready)
        assertTrue(
            "startupError was: ${failed.startupError}",
            failed.startupError != null &&
                "IOException" in failed.startupError &&
                "asset missing" in failed.startupError,
        )
        assertTrue(warnings.any { "asset missing" in it })
        // A later attempt retries init and recovers.
        broken = false
        coord.checkUpdates(manual = true) { result = it }
        assertTrue(result is NytUpdateResult.Checked)
        val recovered = coord.uiState()
        assertTrue(recovered.ready)
        assertNull(recovered.startupError)
        assertEquals(UA, coord.policyForReading()?.userAgent)
    }

    @Test
    fun concurrentChecks_coalesceToOneUpdate() {
        val manual = ManualExecutor()
        val (coord, _, downloader) = coordinator(runBackground = manual.asFn)
        val results = mutableListOf<NytUpdateResult?>()
        coord.checkUpdates(manual = true, onDone = { results.add(it) })
        coord.checkUpdates(manual = true, onDone = { results.add(it) })
        assertTrue(coord.isChecking())
        assertEquals(0, downloader.calls)
        // Settings never waits: state is immediate even mid-check.
        assertTrue(coord.uiState().checking)
        manual.pumpAll()
        assertEquals(1, downloader.calls)
        assertEquals(2, results.size)
        assertTrue(results.all { it is NytUpdateResult.Checked })
        assertFalse(coord.isChecking())
        assertEquals("2.0", coord.uiState().version)
    }

    @Test
    fun throttledAutoWithManualWaiter_rerunsOnceAsManual() {
        // Seed a recent successful check so auto throttles but manual runs.
        val storage = FakeStorage()
        val seedDownloader = FakeDownloader { updateZipFor("2.0") }
        val seed = NytPolicyStore(bundledJson(), storage, seedDownloader, { now }, warnings::add)
        seed.checkForUpdates(manual = true)
        assertEquals(1, seedDownloader.calls)
        val manual = ManualExecutor()
        val downloader = FakeDownloader { updateZipFor("3.0") }
        val (coord, _, _) = coordinator(
            storage = storage,
            downloader = downloader,
            runBackground = manual.asFn,
        )
        var manualResult: NytUpdateResult? = null
        coord.checkUpdates(manual = false, onDone = null)
        coord.checkUpdates(manual = true, onDone = { manualResult = it })
        manual.pumpAll()
        // The throttled auto ran nothing; the manual rerun downloaded once.
        assertEquals(1, downloader.calls)
        assertTrue(manualResult is NytUpdateResult.Checked)
        assertEquals("3.0", coord.uiState().version)
    }

    @Test
    fun manualFailure_stateShowsFullErrorAndKeepsPolicy() {
        val (coord, _, _) = coordinator(
            downloader = FakeDownloader { throw IOException("offline") },
        )
        var result: NytUpdateResult? = null
        coord.checkUpdates(manual = true) { result = it }
        assertTrue(result is NytUpdateResult.Failed)
        val state = coord.uiState()
        assertTrue(state.ready)
        assertEquals("1.0", state.version)
        assertTrue(
            "lastError was: ${state.lastError}",
            state.lastError != null && "offline" in state.lastError,
        )
        assertEquals("1.0", coord.policyForReading()?.sourceVersion)
    }

    @Test
    fun failingCallback_doesNotBreakOthers() {
        val manual = ManualExecutor()
        val (coord, _, _) = coordinator(runBackground = manual.asFn)
        var second: NytUpdateResult? = null
        coord.checkUpdates(manual = true) { throw IOException("boom") }
        coord.checkUpdates(manual = true, onDone = { second = it })
        manual.pumpAll()
        assertTrue(second is NytUpdateResult.Checked)
        assertTrue(warnings.any { "callback failed" in it && "boom" in it })
        assertFalse(coord.isChecking())
    }

    @Test
    fun policyForReading_needsNoNetwork() {
        val (coord, _, downloader) = coordinator()
        assertEquals(UA, coord.policyForReading()?.userAgent)
        assertEquals(0, downloader.calls)
        assertTrue(coord.uiState().ready)
    }

    @Test
    fun uiState_returnsDuringSlowInit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val coord = NytUpdateCoordinator(
                storeFactory = {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    NytPolicyStore(
                        bundledJson(), FakeStorage(),
                        FakeDownloader { updateZipFor("2.0") }, { now }, warnings::add,
                    )
                },
                isAutoUpdateEnabled = { autoEnabled },
                runBackground = { pool.submit(it) },
                onWarn = warnings::add,
            )
            coord.ensureStarted()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // UI reads must not wait behind the latched factory.
            val state = pool.submit<NytUiState> { coord.uiState() }
                .get(2, TimeUnit.SECONDS)
            assertFalse(state.ready)
            assertTrue(state.checking)
            assertTrue(pool.submit<Boolean> { coord.isChecking() }.get(2, TimeUnit.SECONDS))
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            val done = coord.uiState()
            assertTrue(done.ready)
            assertFalse(done.checking)
            assertEquals("2.0", done.version)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun submitFailure_resetsCheckingAndExposesError() {
        var broken = true
        val downloader = FakeDownloader { updateZipFor("2.0") }
        val coord = NytUpdateCoordinator(
            storeFactory = {
                NytPolicyStore(bundledJson(), FakeStorage(), downloader, { now }, warnings::add)
            },
            isAutoUpdateEnabled = { autoEnabled },
            runBackground = {
                if (broken) throw RejectedExecutionException("queue full")
                it.run()
            },
            onWarn = warnings::add,
        )
        var delivered = false
        var result: NytUpdateResult? = NytUpdateResult.Throttled(
            NytUpdateStatus(NytUpdateStatus.ORIGIN_BUNDLED, "1.0", 0, 0, null),
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
            failed.coordinatorError != null &&
                "RejectedExecutionException" in failed.coordinatorError &&
                "queue full" in failed.coordinatorError,
        )
        // A later clean check clears the coordinator error.
        broken = false
        coord.checkUpdates(manual = true) { result = it }
        assertTrue(result is NytUpdateResult.Checked)
        assertNull(coord.uiState().coordinatorError)
        assertFalse(coord.isChecking())
    }

    @Test
    fun submitFailure_deliversEveryCoalescedWaiterOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var failSubmit = true
        val downloader = FakeDownloader { updateZipFor("2.0") }
        val coord = NytUpdateCoordinator(
            storeFactory = {
                NytPolicyStore(bundledJson(), FakeStorage(), downloader, { now }, warnings::add)
            },
            isAutoUpdateEnabled = { autoEnabled },
            runBackground = {
                if (failSubmit) {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    throw RejectedExecutionException("submission failed")
                } else {
                    it.run()
                }
            },
            onWarn = warnings::add,
        )
        val callbacks = java.util.concurrent.atomic.AtomicInteger(0)
        val results = java.util.Collections.synchronizedList(mutableListOf<NytUpdateResult?>())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit { coord.checkUpdates(manual = true) { results.add(it); callbacks.incrementAndGet() } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // Second check coalesces while the first submission is blocked.
            coord.checkUpdates(manual = true) { results.add(it); callbacks.incrementAndGet() }
            assertTrue(coord.isChecking())
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            assertEquals(2, callbacks.get())
            assertEquals(2, results.size)
            assertTrue(results.all { it == null })
            assertFalse(coord.isChecking())
            val failed = coord.uiState()
            assertTrue(
                "coordinatorError was: ${failed.coordinatorError}",
                failed.coordinatorError != null &&
                    "RejectedExecutionException" in failed.coordinatorError &&
                    "submission failed" in failed.coordinatorError,
            )
            // A retry must not redeliver the drained waiters.
            failSubmit = false
            var retry: NytUpdateResult? = null
            coord.checkUpdates(manual = true) { retry = it }
            assertTrue(retry is NytUpdateResult.Checked)
            assertEquals(2, callbacks.get())
            assertNull(coord.uiState().coordinatorError)
            assertFalse(coord.isChecking())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun throwingEnabledProvider_resetsCheckingAndExposesError() {
        val downloader = FakeDownloader { updateZipFor("2.0") }
        val coord = NytUpdateCoordinator(
            storeFactory = {
                NytPolicyStore(bundledJson(), FakeStorage(), downloader, { now }, warnings::add)
            },
            isAutoUpdateEnabled = { throw IllegalStateException("prefs boom") },
            runBackground = { it.run() },
            onWarn = warnings::add,
        )
        var delivered = false
        coord.checkUpdates(manual = false) { delivered = true }
        assertTrue(delivered)
        assertFalse(coord.isChecking())
        assertEquals(0, downloader.calls)
        val state = coord.uiState()
        assertTrue(state.ready)
        assertTrue(
            "coordinatorError was: ${state.coordinatorError}",
            state.coordinatorError != null &&
                "IllegalStateException" in state.coordinatorError &&
                "prefs boom" in state.coordinatorError,
        )
    }

    @Test
    fun readingNotice_reportsVersionAndWarnings() {
        val base = NytUpdateCoordinator.readingNotice("4.4.7.4", null, null, null)
        assertTrue("notice was: $base", "4.4.7.4" in base)
        val full = NytUpdateCoordinator.readingNotice(
            "4.4.7.4",
            "startup flaw detail",
            "status flaw detail",
            "update flaw detail",
        )
        assertTrue("notice was: $full", "4.4.7.4" in full)
        assertTrue("notice was: $full", "startup flaw detail" in full)
        assertTrue("notice was: $full", "status flaw detail" in full)
        assertTrue("notice was: $full", "update flaw detail" in full)
        val blanks = NytUpdateCoordinator.readingNotice("4.4.7.4", " ", "", null)
        assertEquals(base, blanks)
    }
}
