package app.readly.article

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinator behavior with faked engine calls: coalescing, throttle,
 * null-update success, failure retention, attempt persistence, the hung-
 * check watchdog, and late-callback rejection.
 */
class BpcUpdateCoordinatorTest {

    private fun direct(run: Runnable) = run.run()

    private fun coord(
        store: BpcUpdateStore,
        now: Long,
        runner: (String?, (BpcUpdateCoordinator.UpdateResult) -> Unit) -> Unit,
        scheduler: (Long, Runnable) -> Unit = { _, _ -> },
        auto: Boolean = true,
    ) = BpcUpdateCoordinator(
        store = store,
        isAutoEnabled = { auto },
        clock = { now },
        runBackground = ::direct,
        onWarn = {},
        updateRunner = runner,
        timeoutMs = 60_000L,
        scheduleTimeout = scheduler,
    )

    @Test
    fun `successful check persists attempt and clears error`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        val coordinator = coord(
            store, 10_000L,
            runner = { _, onDone ->
                onDone(BpcUpdateCoordinator.UpdateResult("4.4.8.0", null))
            },
        )
        var result: BpcUpdateCoordinator.CheckResult? = null
        coordinator.checkUpdates(manual = false) { result = it }
        assertTrue(result!!.attempted)
        assertEquals("4.4.8.0", (result!!.outcome as BpcUpdatePolicy.Outcome.Updated).newVersion)
        assertEquals(10_000L, store.state.lastCheckMs)
        assertEquals("4.4.8.0", store.state.installedVersion)
        assertNull(store.state.lastError)
    }

    @Test
    fun `null update is success that keeps current`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        val coordinator = coord(
            store, 20_000L,
            runner = { _, onDone ->
                onDone(BpcUpdateCoordinator.UpdateResult(null, null))
            },
        )
        var result: BpcUpdateCoordinator.CheckResult? = null
        coordinator.checkUpdates(manual = true) { result = it }
        assertTrue(result!!.attempted)
        val keep = result!!.outcome as BpcUpdatePolicy.Outcome.Keep
        assertEquals("4.4.7.2", keep.versionToKeep)
        assertNull(keep.warning)
        assertEquals(20_000L, store.state.lastCheckMs)
        assertEquals("4.4.7.2", store.state.installedVersion)
        assertNull(store.state.lastError)
    }

    @Test
    fun `failure retains installed persists attempt and warns`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        val coordinator = coord(
            store, 30_000L,
            runner = { _, onDone ->
                onDone(BpcUpdateCoordinator.UpdateResult(null, "network down"))
            },
        )
        var result: BpcUpdateCoordinator.CheckResult? = null
        coordinator.checkUpdates(manual = false) { result = it }
        assertTrue(result!!.attempted)
        val keep = result!!.outcome as BpcUpdatePolicy.Outcome.Keep
        assertEquals("4.4.7.2", keep.versionToKeep)
        assertTrue(keep.warning!!.contains("network down"))
        assertEquals(30_000L, store.state.lastCheckMs)
        assertEquals("4.4.7.2", store.state.installedVersion)
        assertEquals("network down", store.state.lastError)
    }

    @Test
    fun `throttled automatic check skips the runner and persists nothing`() {
        val store = MemoryBpcUpdateStore(
            BpcUpdatePolicy.State(lastCheckMs = 1_000, installedVersion = "4.4.7.2"),
        )
        val calls = AtomicInteger(0)
        val coordinator = coord(
            store, 1_000L + ArticleConstants.UPDATE_THROTTLE_MS - 1,
            runner = { _, onDone ->
                calls.incrementAndGet()
                onDone(BpcUpdateCoordinator.UpdateResult(null, null))
            },
        )
        var result: BpcUpdateCoordinator.CheckResult? = null
        coordinator.checkUpdates(manual = false) { result = it }
        assertFalse(result!!.attempted)
        assertEquals(0, calls.get())
        assertEquals(0, store.saves)
    }

    @Test
    fun `concurrent checks coalesce to one runner call with shared result`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        val calls = AtomicInteger(0)
        val release = CountDownLatch(1)
        var runnerDone: ((BpcUpdateCoordinator.UpdateResult) -> Unit)? = null
        val coordinator = BpcUpdateCoordinator(
            store = store,
            isAutoEnabled = { true },
            clock = { 40_000L },
            runBackground = { run ->
                Thread(run, "bpc-test-bg").start()
            },
            onWarn = {},
            updateRunner = { _, onDone ->
                calls.incrementAndGet()
                runnerDone = onDone
                // Hold the check open until both waiters attach.
                Thread {
                    release.await(5, TimeUnit.SECONDS)
                    onDone(BpcUpdateCoordinator.UpdateResult(null, null))
                }.start()
            },
            timeoutMs = 60_000L,
            scheduleTimeout = { _, _ -> },
        )
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        var firstResult: BpcUpdateCoordinator.CheckResult? = null
        var secondResult: BpcUpdateCoordinator.CheckResult? = null
        coordinator.checkUpdates(manual = false) { firstResult = it; first.countDown() }
        // Spin until the runner is entered so the second check coalesces.
        val deadline = System.currentTimeMillis() + 5_000
        while (runnerDone == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        coordinator.checkUpdates(manual = true) { secondResult = it; second.countDown() }
        release.countDown()
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertTrue(second.await(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        assertTrue(firstResult!!.attempted)
        assertTrue(secondResult!!.attempted)
    }

    @Test
    fun `interrupted attempt timestamp survives a new coordinator`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        // The runner hangs and the process dies mid-check: the timestamp was
        // already persisted before the network ran.
        val hung = coord(store, 50_000L, runner = { _, _ -> })
        hung.checkUpdates(manual = false)
        assertEquals(50_000L, store.state.lastCheckMs)

        // After restart the automatic check throttles instead of hammering.
        val restarted = coord(store, 50_000L + 1000, runner = { _, _ -> })
        var result: BpcUpdateCoordinator.CheckResult? = null
        restarted.checkUpdates(manual = false) { result = it }
        assertFalse(result!!.attempted)
        // A manual check still runs through.
        var manualResult: BpcUpdateCoordinator.CheckResult? = null
        val manualRestarted = coord(
            store, 60_000L,
            runner = { _, onDone -> onDone(BpcUpdateCoordinator.UpdateResult(null, null)) },
        )
        manualRestarted.checkUpdates(manual = true) { manualResult = it }
        assertTrue(manualResult!!.attempted)
    }

    @Test
    fun `hung runner completes once with timeout and retry recovers`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        val scheduled = mutableListOf<Runnable>()
        var runnerDone: ((BpcUpdateCoordinator.UpdateResult) -> Unit)? = null
        val coordinator = coord(
            store, 70_000L,
            runner = { _, onDone -> runnerDone = onDone },
            scheduler = { _, task -> scheduled.add(task) },
        )
        val deliveries = AtomicInteger(0)
        var result: BpcUpdateCoordinator.CheckResult? = null
        coordinator.checkUpdates(manual = true) { result = it; deliveries.incrementAndGet() }
        assertTrue(coordinator.isChecking())
        assertEquals(1, scheduled.size)

        // The watchdog completes the hung attempt with an exact warning that
        // retains the installed add-on; the button recovers (not checking).
        scheduled.single().run()
        assertEquals(1, deliveries.get())
        assertTrue(result!!.attempted)
        val keep = result!!.outcome as BpcUpdatePolicy.Outcome.Keep
        assertEquals("4.4.7.2", keep.versionToKeep)
        assertTrue(keep.warning!!.contains("timed out after 60000ms"))
        assertFalse(coordinator.isChecking())
        assertEquals("timed out after 60000ms", store.state.lastError)
        assertEquals("4.4.7.2", store.state.installedVersion)

        // The runner's late callback changes nothing: no second delivery, no
        // state overwrite.
        runnerDone!!(BpcUpdateCoordinator.UpdateResult("9.9.9", null))
        assertEquals(1, deliveries.get())
        assertEquals("4.4.7.2", store.state.installedVersion)

        // A retry runs cleanly again.
        var retryResult: BpcUpdateCoordinator.CheckResult? = null
        val retry = coord(
            store, 80_000L,
            runner = { _, onDone -> onDone(BpcUpdateCoordinator.UpdateResult(null, null)) },
        )
        retry.checkUpdates(manual = true) { retryResult = it }
        assertTrue(retryResult!!.attempted)
        assertNull(store.state.lastError)
    }

    @Test
    fun `late watchdog after normal completion changes nothing`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        val scheduled = mutableListOf<Runnable>()
        val coordinator = coord(
            store, 90_000L,
            runner = { _, onDone -> onDone(BpcUpdateCoordinator.UpdateResult("4.4.8.0", null)) },
            scheduler = { _, task -> scheduled.add(task) },
        )
        val deliveries = AtomicInteger(0)
        coordinator.checkUpdates(manual = true) { deliveries.incrementAndGet() }
        assertEquals(1, deliveries.get())
        assertEquals("4.4.8.0", store.state.installedVersion)

        // The timeout task firing after completion is ignored.
        scheduled.single().run()
        assertEquals(1, deliveries.get())
        assertEquals("4.4.8.0", store.state.installedVersion)
        assertNull(store.state.lastError)
    }

    @Test
    fun `double runner callback completes only once`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        var runnerDone: ((BpcUpdateCoordinator.UpdateResult) -> Unit)? = null
        val coordinator = coord(
            store, 100_000L,
            runner = { _, onDone -> runnerDone = onDone },
        )
        val deliveries = AtomicInteger(0)
        coordinator.checkUpdates(manual = true) { deliveries.incrementAndGet() }
        runnerDone!!(BpcUpdateCoordinator.UpdateResult(null, null))
        runnerDone!!(BpcUpdateCoordinator.UpdateResult("9.9.9", null))
        assertEquals(1, deliveries.get())
        assertEquals("4.4.7.2", store.state.installedVersion)
    }

    @Test
    fun `state save failure surfaces a visible warning and retains installed`() {
        val throwing = object : BpcUpdateStore {
            val state = BpcUpdatePolicy.State(installedVersion = "4.4.7.2")
            override fun load(): BpcUpdatePolicy.State = state
            override fun save(state: BpcUpdatePolicy.State) {
                throw IllegalStateException("disk gone")
            }
        }
        val coordinator = coord(
            throwing, 110_000L,
            runner = { _, onDone -> onDone(BpcUpdateCoordinator.UpdateResult(null, null)) },
        )
        var result: BpcUpdateCoordinator.CheckResult? = null
        coordinator.checkUpdates(manual = true) { result = it }
        assertTrue(result!!.attempted)
        val keep = result!!.outcome as BpcUpdatePolicy.Outcome.Keep
        assertEquals("4.4.7.2", keep.versionToKeep)
        assertTrue(keep.warning!!.contains("couldn't be saved"))
        // Settings reads the same warning through uiState (not swallowed).
        val ui = coordinator.uiState()
        assertTrue(ui.lastError!!.contains("couldn't be saved"))
        assertEquals("4.4.7.2", ui.installedVersion)
    }

    @Test
    fun `prepare failure persists the error without throttling`() {
        val store = MemoryBpcUpdateStore(BpcUpdatePolicy.State(installedVersion = "4.4.7.2"))
        val coordinator = coord(store, 120_000L, runner = { _, _ -> })
        coordinator.notePrepareFailure("Extension list() failed: boom")
        assertEquals("Extension list() failed: boom", store.state.lastError)
        assertEquals(0, store.state.lastCheckMs)
        assertEquals("4.4.7.2", store.state.installedVersion)
        // The next automatic check still runs (no throttle was consumed).
        val ran = AtomicInteger(0)
        val next = coord(
            store, 121_000L,
            runner = { _, onDone ->
                ran.incrementAndGet()
                onDone(BpcUpdateCoordinator.UpdateResult(null, null))
            },
        )
        var result: BpcUpdateCoordinator.CheckResult? = null
        next.checkUpdates(manual = false) { result = it }
        assertEquals(1, ran.get())
        assertTrue(result!!.attempted)
    }
}
