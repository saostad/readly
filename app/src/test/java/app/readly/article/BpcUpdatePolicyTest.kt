package app.readly.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Daily throttle, manual bypass, and null-update interpretation. */
class BpcUpdatePolicyTest {

    private val day = ArticleConstants.UPDATE_THROTTLE_MS

    @Test
    fun `automatic check runs when never checked and toggle on`() {
        assertTrue(
            BpcUpdatePolicy.shouldCheck(
                nowMs = 1_000,
                state = BpcUpdatePolicy.State(),
                manual = false,
                autoEnabled = true,
            ),
        )
    }

    @Test
    fun `automatic check throttles within 24h including after failures`() {
        val afterSuccess = BpcUpdatePolicy.State(lastCheckMs = 1_000, lastError = null)
        assertFalse(BpcUpdatePolicy.shouldCheck(1_000 + day - 1, afterSuccess, false, true))
        assertTrue(BpcUpdatePolicy.shouldCheck(1_000 + day, afterSuccess, false, true))

        val afterFailure = BpcUpdatePolicy.State(lastCheckMs = 1_000, lastError = "boom")
        assertFalse(BpcUpdatePolicy.shouldCheck(1_000 + day - 1, afterFailure, false, true))
    }

    @Test
    fun `manual bypasses throttle and toggle`() {
        val recent = BpcUpdatePolicy.State(lastCheckMs = 5_000, lastError = null)
        assertTrue(BpcUpdatePolicy.shouldCheck(5_001, recent, true, true))
        assertTrue(BpcUpdatePolicy.shouldCheck(5_001, recent, true, false))
    }

    @Test
    fun `automatic check respects the toggle`() {
        assertFalse(
            BpcUpdatePolicy.shouldCheck(
                9_000,
                BpcUpdatePolicy.State(),
                manual = false,
                autoEnabled = false,
            ),
        )
    }

    @Test
    fun `null update keeps current without warning`() {
        val outcome = BpcUpdatePolicy.interpret("4.4.7.2", null, null)
        val keep = outcome as BpcUpdatePolicy.Outcome.Keep
        assertEquals("4.4.7.2", keep.versionToKeep)
        assertNull(keep.warning)
    }

    @Test
    fun `same version keeps current without warning`() {
        val outcome = BpcUpdatePolicy.interpret("4.4.7.2", "4.4.7.2", null)
        assertTrue(outcome is BpcUpdatePolicy.Outcome.Keep)
        assertNull((outcome as BpcUpdatePolicy.Outcome.Keep).warning)
    }

    @Test
    fun `new version updates`() {
        val outcome = BpcUpdatePolicy.interpret("4.4.7.2", "4.4.8.0", null)
        assertEquals("4.4.8.0", (outcome as BpcUpdatePolicy.Outcome.Updated).newVersion)
    }

    @Test
    fun `failure retains installed with a concrete warning`() {
        val outcome = BpcUpdatePolicy.interpret("4.4.7.2", null, "network down")
        val keep = outcome as BpcUpdatePolicy.Outcome.Keep
        assertEquals("4.4.7.2", keep.versionToKeep)
        assertTrue(keep.warning!!.contains("network down"))
        assertTrue(keep.warning.contains("4.4.7.2"))
    }

    @Test
    fun `engine null result maps to no-update success`() {
        val (version, error) = BpcUpdatePolicy.mapEngineUpdate(
            ArticleConstants.BPC_ID, null, null, null,
        )
        assertNull(version)
        assertNull(error)
        val outcome = BpcUpdatePolicy.interpret("4.4.7.2", version, error)
        val keep = outcome as BpcUpdatePolicy.Outcome.Keep
        assertEquals("4.4.7.2", keep.versionToKeep)
        assertNull(keep.warning)
    }

    @Test
    fun `engine wrong-id result maps to an error never a silent no-update`() {
        val (version, error) = BpcUpdatePolicy.mapEngineUpdate(
            ArticleConstants.BPC_ID, "stranger@example.com", "1.0", null,
        )
        assertNull(version)
        assertTrue(error!!.contains("unexpected add-on id"))
        val outcome = BpcUpdatePolicy.interpret("4.4.7.2", version, error)
        val keep = outcome as BpcUpdatePolicy.Outcome.Keep
        assertEquals("4.4.7.2", keep.versionToKeep)
        assertTrue(keep.warning!!.contains("unexpected add-on id"))
    }

    @Test
    fun `engine matching result maps to its version`() {
        val (version, error) = BpcUpdatePolicy.mapEngineUpdate(
            ArticleConstants.BPC_ID, ArticleConstants.BPC_ID, "4.4.8.0", null,
        )
        assertEquals("4.4.8.0", version)
        assertNull(error)
    }

    @Test
    fun `engine error passes through`() {
        val (version, error) = BpcUpdatePolicy.mapEngineUpdate(
            ArticleConstants.BPC_ID, null, null, "InstallException code=NETWORK_FAILURE",
        )
        assertNull(version)
        assertEquals("InstallException code=NETWORK_FAILURE", error)
    }
}
