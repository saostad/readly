package app.readly.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 3 regression coverage for the shared BPC setup machine. The engine
 * calls these same transitions from the normal read path and the manual
 * settings-check path, so both converge on identical observed state; a
 * failed setup is explicitly retryable without reinstalling over a working
 * add-on, and stale callbacks from retired attempts change nothing.
 */
class BpcStartupTest {

    private fun navigable(startup: BpcStartup): Boolean {
        val snap = startup.snapshot()
        return ArticleReadiness.canNavigate(
            ArticleReadiness.State(
                captureHelperReady = true,
                bpcKnown = snap.known,
                bpcEnabled = snap.enabledObserved,
                bpcReady = snap.readyObserved,
                installPending = snap.installPending,
            ),
        )
    }

    @Test
    fun `cold existing-enabled addon prepared then read waits only for ready`() {
        val startup = BpcStartup()
        assertTrue(startup.needsPrepare())

        // Settings Check-now prepares first: the known enabled copy adopts
        // its observed flags (never a bare reference with flags unset).
        val manual = startup.beginAttempt()
        assertEquals(
            BpcStartup.ListedAction.WAIT_READY,
            startup.onListed(manual, found = true, enabled = true, listedVersion = "4.4.7.2"),
        )
        val afterManual = startup.snapshot()
        assertTrue(afterManual.known)
        assertTrue(afterManual.enabledObserved)
        assertFalse(afterManual.readyObserved)
        assertNull(afterManual.error)
        assertEquals("4.4.7.2", afterManual.version)

        // The later read needs no second prepare and changes nothing; the
        // gate still waits for the observed onReady, never list-alone.
        assertFalse(startup.needsPrepare())
        assertFalse(navigable(startup))
        startup.onReadyEvent("4.4.7.2")
        assertTrue(navigable(startup))
    }

    @Test
    fun `startup failure then user retry then readiness navigates once`() {
        val startup = BpcStartup()
        val first = startup.beginAttempt()
        assertTrue(startup.onListFailed(first, "Extension list() failed: boom"))
        assertNotNull(startup.snapshot().error)
        assertFalse(navigable(startup))

        // Explicit retry clears the failure and opens a fresh attempt while
        // keeping any observed install state.
        val second = startup.retry()
        assertNotNull(second)
        assertNull(startup.snapshot().error)

        // Missing copy installs; install/enable alone never navigate.
        assertEquals(
            BpcStartup.ListedAction.INSTALL_MISSING,
            startup.onListed(second!!, found = false, enabled = false, listedVersion = null),
        )
        startup.noteInstallStarted()
        assertTrue(startup.onInstalled(second, enabled = true, installedVersion = "4.4.7.2"))
        assertFalse(navigable(startup))
        startup.onEnabledEvent("4.4.7.2")
        assertFalse(navigable(startup))

        // Readiness flips exactly once, on the observed onReady.
        startup.onReadyEvent("4.4.7.2")
        assertTrue(navigable(startup))
    }

    @Test
    fun `old failure callbacks from a retired setup change nothing`() {
        val startup = BpcStartup()
        val first = startup.beginAttempt()
        assertTrue(startup.onListFailed(first, "Extension list() failed: boom"))
        val second = startup.retry()!!

        // Every stale failure reports false and leaves the fresh attempt
        // clean; the stale list result reports STALE.
        assertFalse(startup.onListFailed(first, "Extension list() failed: old"))
        assertFalse(startup.onInstallFailed(first, "Article extension install() failed: old"))
        assertFalse(startup.onEnableFailed(first, "Article extension enable() failed: old"))
        assertFalse(startup.onEnableResult(first, enabled = false, enabledVersion = null))
        assertEquals(
            BpcStartup.ListedAction.STALE,
            startup.onListed(first, found = true, enabled = true, listedVersion = "9.9.9"),
        )
        val snap = startup.snapshot()
        assertNull(snap.error)
        assertFalse(snap.known)
        assertNull(snap.version)

        // The live attempt still reconciles normally.
        assertEquals(
            BpcStartup.ListedAction.WAIT_READY,
            startup.onListed(second, found = true, enabled = true, listedVersion = "4.4.7.2"),
        )
        assertNull(startup.snapshot().error)
    }

    @Test
    fun `retry without failure is a no-op`() {
        val startup = BpcStartup()
        assertNull(startup.retry())
        val attempt = startup.beginAttempt()
        startup.onListed(attempt, found = true, enabled = true, listedVersion = "4.4.7.2")
        assertNull(startup.retry())
    }

    @Test
    fun `disabled copy enables then still waits for ready`() {
        val startup = BpcStartup()
        val attempt = startup.beginAttempt()
        assertEquals(
            BpcStartup.ListedAction.ENABLE_FOUND,
            startup.onListed(attempt, found = true, enabled = false, listedVersion = "4.4.7.2"),
        )
        assertFalse(navigable(startup))
        assertTrue(startup.onEnableResult(attempt, enabled = true, enabledVersion = "4.4.7.2"))
        assertNull(startup.snapshot().error)
        assertFalse(navigable(startup))
        startup.onReadyEvent("4.4.7.2")
        assertTrue(navigable(startup))
    }

    @Test
    fun `enable reporting disabled is a retryable failure`() {
        val startup = BpcStartup()
        val attempt = startup.beginAttempt()
        startup.onListed(attempt, found = true, enabled = false, listedVersion = "4.4.7.2")
        assertTrue(startup.onEnableResult(attempt, enabled = false, enabledVersion = null))
        assertNotNull(startup.snapshot().error)
        assertFalse(navigable(startup))
        assertNotNull(startup.retry())
        assertNull(startup.snapshot().error)
    }

    @Test
    fun `disabled event fails and retry retains the known copy`() {
        val startup = BpcStartup()
        val attempt = startup.beginAttempt()
        startup.onListed(attempt, found = true, enabled = true, listedVersion = "4.4.7.2")
        startup.onReadyEvent("4.4.7.2")
        assertTrue(navigable(startup))

        startup.onDisabledEvent()
        assertFalse(navigable(startup))
        assertNotNull(startup.snapshot().error)

        // Retry keeps the known reference and version; only the failure and
        // the enabled flag reset, so the retry re-lists/enables the known
        // disabled copy through the shared path instead of reusing it.
        assertNotNull(startup.retry())
        val snap = startup.snapshot()
        assertNull(snap.error)
        assertTrue(snap.known)
        assertEquals("4.4.7.2", snap.version)
        assertTrue(startup.needsPrepare())
    }

    @Test
    fun `disabled addon enable failure then retry re-prepares and enables`() {
        val startup = BpcStartup()
        // Exact reviewer repro: a known disabled copy, enable fails, retry.
        val first = startup.joinPrepare(null)
        assertTrue(first is BpcStartup.PrepareJoin.Started)
        val firstId = (first as BpcStartup.PrepareJoin.Started).id
        assertEquals(
            BpcStartup.ListedAction.ENABLE_FOUND,
            startup.onListed(firstId, found = true, enabled = false, listedVersion = "4.4.7.2"),
        )
        assertTrue(startup.onEnableFailed(firstId, "Article extension enable() failed: offline"))
        assertTrue(startup.settlePrepare(firstId, startup.snapshot().error))
        assertNotNull(startup.retry())

        // The retry must re-list/enable the known disabled copy through the
        // shared path — never install over it, never reuse it as-is.
        assertTrue(startup.needsPrepare())
        val second = startup.joinPrepare(null)
        assertTrue(second is BpcStartup.PrepareJoin.Started)
        val secondId = (second as BpcStartup.PrepareJoin.Started).id
        assertEquals(
            BpcStartup.ListedAction.ENABLE_FOUND,
            startup.onListed(secondId, found = true, enabled = false, listedVersion = "4.4.7.2"),
        )
        assertTrue(startup.onEnableResult(secondId, enabled = true, enabledVersion = "4.4.7.2"))
        assertNull(startup.snapshot().error)
        assertFalse(navigable(startup))
        startup.onReadyEvent("4.4.7.2")
        assertTrue(navigable(startup))
    }

    @Test
    fun `concurrent read and manual join the same prepare round`() {
        val startup = BpcStartup()
        val read = startup.joinPrepare(null)
        assertTrue(read is BpcStartup.PrepareJoin.Started)
        val readId = (read as BpcStartup.PrepareJoin.Started).id

        var manualCalls = 0
        var manualError: String? = "unset"
        val manual = startup.joinPrepare({ manualCalls += 1; manualError = it })
        assertTrue(manual is BpcStartup.PrepareJoin.Joined)
        assertEquals(readId, (manual as BpcStartup.PrepareJoin.Joined).id)

        // One settle releases the joined waiter exactly once.
        assertTrue(startup.settlePrepare(readId, null))
        assertEquals(1, manualCalls)
        assertNull(manualError)
        assertFalse(startup.settlePrepare(readId, null))
        assertEquals(1, manualCalls)
    }

    @Test
    fun `pending manual prepare times out visibly and next prepare starts fresh`() {
        val startup = BpcStartup()
        var manualCalls = 0
        var manualError: String? = null
        val first = startup.joinPrepare({ manualCalls += 1; manualError = it })
        val firstId = (first as BpcStartup.PrepareJoin.Started).id

        assertTrue(startup.timeoutPrepare(firstId, "Extension setup timed out after 90000ms"))
        assertEquals(1, manualCalls)
        assertEquals("Extension setup timed out after 90000ms", manualError)
        assertNotNull(startup.snapshot().error)

        // The timed-out round is closed: a retry clears it, the next round
        // starts fresh, and late callbacks for the old round stay stale.
        assertNotNull(startup.retry())
        val second = startup.joinPrepare(null)
        assertTrue(second is BpcStartup.PrepareJoin.Started)
        val secondId = (second as BpcStartup.PrepareJoin.Started).id
        assertTrue(secondId != firstId)
        assertFalse(startup.settlePrepare(firstId, null))
        assertFalse(startup.timeoutPrepare(firstId, "late"))
        assertTrue(startup.settlePrepare(secondId, null))
    }

    @Test
    fun `retry retires a live prepare round and releases its waiters`() {
        val startup = BpcStartup()
        var manualError: String? = null
        val first = startup.joinPrepare({ manualError = it })
        val firstId = (first as BpcStartup.PrepareJoin.Started).id
        assertTrue(startup.onListFailed(firstId, "Extension list() failed: boom"))

        assertNotNull(startup.retry())
        assertEquals(
            "Article extension setup was superseded by a newer attempt",
            manualError,
        )
        assertNull(startup.snapshot().error)
        val second = startup.joinPrepare(null)
        assertTrue(second is BpcStartup.PrepareJoin.Started)
    }

    @Test
    fun `readiness before update result is preserved for the same revision`() {
        val startup = BpcStartup()
        startup.onEnabledEvent("4.4.8")
        startup.onReadyEvent("4.4.8")
        // Exact reviewer repro: the update promise resolves after readiness,
        // with no later event guaranteed — the next link must not wait forever.
        startup.onUpdateApplied(updatedVersion = "4.4.8", enabled = true)
        assertTrue(startup.snapshot().readyObserved)
        assertTrue(navigable(startup))
    }

    @Test
    fun `same-version update keeps live readiness, old revision never blesses new`() {
        val startup = BpcStartup()
        val attempt = startup.beginAttempt()
        startup.onListed(attempt, found = true, enabled = true, listedVersion = "4.4.7.2")
        startup.onReadyEvent("4.4.7.2")
        assertTrue(navigable(startup))

        // A no-change update return must not clear live readiness.
        startup.onUpdateApplied(updatedVersion = "4.4.7.2", enabled = true)
        assertTrue(navigable(startup))

        // But readiness from the old revision must not bless a new one: the
        // replacement reports ready again (the reverse event order).
        startup.onUpdateApplied(updatedVersion = "4.4.8.0", enabled = true)
        assertFalse(navigable(startup))
        startup.onReadyEvent("4.4.8.0")
        assertTrue(navigable(startup))
    }

    @Test
    fun `disabled update result is never ready even for the same revision`() {
        val startup = BpcStartup()
        startup.onEnabledEvent("4.4.8")
        startup.onReadyEvent("4.4.8")
        startup.onUpdateApplied(updatedVersion = "4.4.8", enabled = false)
        assertFalse(startup.snapshot().readyObserved)
        assertFalse(startup.snapshot().enabledObserved)
        assertFalse(navigable(startup))
    }

    @Test
    fun `successful update resets readiness and disabled update is not ready`() {
        val startup = BpcStartup()
        val attempt = startup.beginAttempt()
        startup.onListed(attempt, found = true, enabled = true, listedVersion = "4.4.7.2")
        startup.onReadyEvent("4.4.7.2")
        assertTrue(navigable(startup))

        // The replacement revision must report ready again before navigation.
        startup.onUpdateApplied(updatedVersion = "4.4.8.0", enabled = true)
        assertEquals("4.4.8.0", startup.snapshot().version)
        assertFalse(navigable(startup))
        startup.onReadyEvent("4.4.8.0")
        assertTrue(navigable(startup))

        // A disabled update can never be marked ready.
        startup.onUpdateApplied(updatedVersion = "4.4.8.1", enabled = false)
        assertFalse(startup.snapshot().enabledObserved)
        assertFalse(navigable(startup))
        startup.onReadyEvent("4.4.8.1")
        assertFalse(navigable(startup))
    }
}
