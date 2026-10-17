package app.readly.article

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation gates on observed readiness: enabled-alone never navigates, for
 * fresh installs and pre-installed copies alike.
 */
class ArticleReadinessTest {

    private fun state(
        capture: Boolean = true,
        known: Boolean = true,
        enabled: Boolean = true,
        ready: Boolean = true,
        pending: Boolean = false,
    ) = ArticleReadiness.State(capture, known, enabled, ready, pending)

    @Test
    fun `navigates only when helper known enabled and ready`() {
        assertTrue(ArticleReadiness.canNavigate(state()))
        assertNull(ArticleReadiness.waitReason(state()))
    }

    @Test
    fun `fresh install pending without ready does not navigate`() {
        // onEnabled may arrive before install() returns; readiness is still
        // required, so the early enabled signal alone never navigates.
        val early = state(known = true, enabled = true, ready = false, pending = true)
        assertFalse(ArticleReadiness.canNavigate(early))
        assertNotNull(ArticleReadiness.waitReason(early))
    }

    @Test
    fun `pre-installed enabled without ready does not navigate`() {
        // Readiness/defaults are asynchronous even for same-version enabled
        // copies found via list(); list() alone never navigates.
        val listed = state(known = true, enabled = true, ready = false, pending = false)
        assertFalse(ArticleReadiness.canNavigate(listed))
    }

    @Test
    fun `missing helper or unknown extension does not navigate`() {
        assertFalse(ArticleReadiness.canNavigate(state(capture = false)))
        assertFalse(ArticleReadiness.canNavigate(state(known = false)))
        assertFalse(ArticleReadiness.canNavigate(state(enabled = false)))
    }
}
