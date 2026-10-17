package app.readly.article

import app.readly.input.sites.SiteBrowserPolicy
import app.readly.input.sites.SiteBrowserReadTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 3 regression coverage for the document-bound capture flow. These
 * tests drive the exact shared admission/transaction logic the article
 * activity uses ([ArticleCaptureProtocol.parse], [ArticleCaptureAuth],
 * [ArticleParseGate], [SiteBrowserReadTracker]): token rotation across
 * reloads and SPA transitions, and read-scoped parse completions.
 */
class ArticleCaptureBindingTest {

    private val article = "https://paper.example.com/2026/10/16/story"

    private fun tracker() = SiteBrowserReadTracker(SiteBrowserPolicy::allowsExtraction)

    private fun metaFor(token: String, url: String = article): ArticleCaptureProtocol.Meta {
        val parsed = ArticleCaptureProtocol.parse(
            mapOf(
                "protocol" to "readly-capture/2",
                "kind" to "meta",
                "captureId" to "cap-1",
                "url" to url,
                "requestToken" to token,
                "title" to "t",
                "readyState" to "complete",
                "elapsedMs" to 100,
                "textBytes" to 10,
                "htmlBytes" to 10,
                "textTruncated" to false,
                "htmlTruncated" to false,
                "textChunks" to 1,
                "htmlChunks" to 0,
                "paragraphs" to 1,
                "gateNodes" to emptyList<String>(),
                "gateText" to emptyList<String>(),
            ),
        )
        return (parsed as ArticleCaptureProtocol.Parsed.Ok).message as ArticleCaptureProtocol.Meta
    }

    @Test
    fun `old meta after same-url reload is rejected by token`() {
        val reads = tracker()
        var counter = 0
        val auth = ArticleCaptureAuth(nextToken = { "tok-${++counter}" })

        // First document arms and admits its meta.
        val readA = reads.startRead(article)!!
        val tokenA = auth.arm(readA)
        val metaA = metaFor(tokenA)
        assertTrue(reads.isCurrent(readA))
        assertTrue(auth.accepts(readA, metaA.requestToken))

        // Same-URL reload: the URL is identical but the document is new.
        reads.onNavigationStarted()
        val readB = reads.startRead(article)!!
        val tokenB = auth.arm(readB)
        assertTrue(tokenA != tokenB)

        // The late old-document meta echoes the retired token: rejected, so
        // no session is ever stamped with the new read for stale content.
        assertFalse(auth.accepts(readB, metaA.requestToken))
        assertFalse(auth.accepts(readB, null))
        assertFalse(auth.accepts(readB, ""))
        // The new document's meta admits.
        assertTrue(auth.accepts(readB, tokenB))
        // The retired read admits nothing anymore, even with its own token.
        assertFalse(reads.isCurrent(readA))
    }

    @Test
    fun `old chunks after same-url reload are rejected by token`() {
        val reads = tracker()
        var counter = 0
        val auth = ArticleCaptureAuth(nextToken = { "tok-${++counter}" })

        val readA = reads.startRead(article)!!
        val tokenA = auth.arm(readA)
        reads.onNavigationStarted()
        val readB = reads.startRead(article)!!
        auth.arm(readB)

        val parsed = ArticleCaptureProtocol.parse(
            mapOf(
                "protocol" to "readly-capture/2",
                "kind" to "text-chunk",
                "captureId" to "cap-1",
                "url" to article,
                "requestToken" to tokenA,
                "index" to 0,
                "total" to 1,
                "data" to "stale",
            ),
        )
        val chunk = (parsed as ArticleCaptureProtocol.Parsed.Ok).message
        assertFalse(auth.accepts(readB, chunk.requestToken))
    }

    @Test
    fun `query transition issues a fresh token and rejects old messages`() {
        val reads = tracker()
        var counter = 0
        val auth = ArticleCaptureAuth(nextToken = { "tok-${++counter}" })

        val readA = reads.startRead(article)!!
        val tokenA = auth.arm(readA)
        assertTrue(auth.accepts(readA, tokenA))

        // SPA/query transition to a different document on the live port.
        val spaUrl = "$article?live=2"
        reads.onSameDocumentChange()
        val readB = reads.startRead(spaUrl)!!
        val tokenB = auth.arm(readB)
        assertTrue(tokenA != tokenB)

        // Old-token messages (same port, prior request) are rejected; the
        // fresh request's messages admit.
        assertFalse(auth.accepts(readB, tokenA))
        assertTrue(auth.accepts(readB, tokenB))
        // The old token no longer admits for its own retired read either.
        auth.retire()
        assertFalse(auth.accepts(readA, tokenA))
    }

    @Test
    fun `pause retires the binding so background messages are rejected`() {
        val reads = tracker()
        val auth = ArticleCaptureAuth(nextToken = { "tok-pause" })
        val read = reads.startRead(article)!!
        val token = auth.arm(read)
        assertTrue(auth.accepts(read, token))

        reads.onPaused()
        auth.retire()
        assertFalse(auth.accepts(read, token))
        assertEquals(null, auth.currentToken(read))
    }

    @Test
    fun `retired parse completion cannot disturb the active parse`() {
        val reads = tracker()
        val gate = ArticleParseGate()

        val readA = reads.startRead(article)!!
        assertTrue(gate.tryBegin(readA))

        // Same-URL reload retires A; B arms and begins parsing.
        reads.onNavigationStarted()
        gate.retire()
        val readB = reads.startRead(article)!!
        assertTrue(gate.tryBegin(readB))

        // A's late completion releases nothing and changes nothing.
        assertFalse(gate.finish(readA))
        assertTrue(gate.isBusy())
        assertTrue(gate.isOwner(readB))
        // No duplicate B parse can start while B owns the slot.
        assertFalse(gate.tryBegin(readB))

        // B's own completion releases the slot.
        assertTrue(gate.finish(readB))
        assertFalse(gate.isBusy())
    }

    @Test
    fun `stale owner completion releases only its own slot`() {
        val reads = tracker()
        val gate = ArticleParseGate()

        val readA = reads.startRead(article)!!
        assertTrue(gate.tryBegin(readA))
        // A is retired by backgrounding with no successor armed.
        reads.onPaused()
        assertTrue(gate.finish(readA))
        assertFalse(gate.isBusy())
        // A second completion for A is a no-op.
        assertFalse(gate.finish(readA))
    }
}
