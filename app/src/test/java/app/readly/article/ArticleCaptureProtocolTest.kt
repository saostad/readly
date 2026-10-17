package app.readly.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the production capture protocol: strict document
 * matching, the native-issued request token, trigger cancellation,
 * stale-URL rejection and byte/chunk bounds. The article activity feeds
 * validated engine messages into this same code.
 */
class ArticleCaptureProtocolTest {

    private fun metaMap(
        captureId: String = "abc123",
        url: String = "https://www.forbes.com/x",
        textChunks: Int = 2,
        htmlChunks: Int = 1,
        requestToken: Any? = "tok-1",
    ): Map<String, Any?> = mapOf(
        "protocol" to "readly-capture/2",
        "kind" to "meta",
        "captureId" to captureId,
        "url" to url,
        "requestToken" to requestToken,
        "title" to "Some article",
        "readyState" to "complete",
        "elapsedMs" to 4200,
        "textBytes" to 100,
        "htmlBytes" to 50,
        "textTruncated" to false,
        "htmlTruncated" to false,
        "textChunks" to textChunks,
        "htmlChunks" to htmlChunks,
        "paragraphs" to 12,
        "gateNodes" to listOf("paywall", "not-a-real-token"),
        "gateText" to listOf("members only", 42),
    )

    private fun chunkMap(kind: String, index: Int, total: Int, data: String): Map<String, Any?> =
        mapOf(
            "protocol" to "readly-capture/2",
            "kind" to kind,
            "captureId" to "abc123",
            "url" to "https://www.forbes.com/x",
            "requestToken" to "tok-1",
            "index" to index,
            "total" to total,
            "data" to data,
        )

    @Test
    fun `parses meta and drops unknown gate tokens but keeps known ones`() {
        val parsed = ArticleCaptureProtocol.parse(metaMap())
        assertTrue(parsed is ArticleCaptureProtocol.Parsed.Ok)
        val meta = (parsed as ArticleCaptureProtocol.Parsed.Ok).message as ArticleCaptureProtocol.Meta
        assertEquals("abc123", meta.captureId)
        assertEquals("tok-1", meta.requestToken)
        assertEquals(listOf("paywall"), meta.gateNodes)
        assertEquals(listOf("members only"), meta.gateText)
        assertEquals(2, meta.textChunks)
    }

    @Test
    fun `rejects wrong protocol and unknown kind`() {
        val badProtocol = metaMap().toMutableMap()
        badProtocol["protocol"] = "readly-capture/1"
        assertTrue(ArticleCaptureProtocol.parse(badProtocol) is ArticleCaptureProtocol.Parsed.Err)

        val badKind = metaMap().toMutableMap()
        badKind["kind"] = "snapshot"
        assertTrue(ArticleCaptureProtocol.parse(badKind) is ArticleCaptureProtocol.Parsed.Err)
    }

    @Test
    fun `rejects missing empty and oversized request tokens`() {
        val missing = metaMap().toMutableMap()
        missing.remove("requestToken")
        assertTrue(ArticleCaptureProtocol.parse(missing) is ArticleCaptureProtocol.Parsed.Err)

        val empty = metaMap(requestToken = "")
        assertTrue(ArticleCaptureProtocol.parse(empty) is ArticleCaptureProtocol.Parsed.Err)

        val oversized = metaMap(requestToken = "t".repeat(129))
        assertTrue(ArticleCaptureProtocol.parse(oversized) is ArticleCaptureProtocol.Parsed.Err)

        val nonString = metaMap(requestToken = 42)
        assertTrue(ArticleCaptureProtocol.parse(nonString) is ArticleCaptureProtocol.Parsed.Err)
    }

    @Test
    fun `rejects oversized and out-of-range meta fields`() {
        val overChunks = metaMap(textChunks = 999)
        assertTrue(ArticleCaptureProtocol.parse(overChunks) is ArticleCaptureProtocol.Parsed.Err)

        val longId = metaMap(captureId = "x".repeat(65))
        assertTrue(ArticleCaptureProtocol.parse(longId) is ArticleCaptureProtocol.Parsed.Err)

        val negative = metaMap().toMutableMap()
        negative["textBytes"] = -1
        assertTrue(ArticleCaptureProtocol.parse(negative) is ArticleCaptureProtocol.Parsed.Err)
    }

    @Test
    fun `rejects chunks with bad index or total`() {
        assertTrue(
            ArticleCaptureProtocol.parse(chunkMap("text-chunk", 2, 2, "hi"))
                is ArticleCaptureProtocol.Parsed.Err,
        )
        assertTrue(
            ArticleCaptureProtocol.parse(chunkMap("html-chunk", 0, 0, "hi"))
                is ArticleCaptureProtocol.Parsed.Err,
        )
        assertTrue(
            ArticleCaptureProtocol.parse(chunkMap("text-chunk", 0, 1, "x".repeat(200_000)))
                is ArticleCaptureProtocol.Parsed.Err,
        )
    }

    @Test
    fun `parses done and helper error`() {
        val done = ArticleCaptureProtocol.parse(
            mapOf(
                "protocol" to "readly-capture/2",
                "kind" to "done",
                "captureId" to "c",
                "url" to "https://www.forbes.com/x",
                "requestToken" to "tok-1",
            ),
        )
        assertTrue(done is ArticleCaptureProtocol.Parsed.Ok)

        val err = ArticleCaptureProtocol.parse(
            mapOf(
                "protocol" to "readly-capture/2",
                "kind" to "error",
                "captureId" to "c",
                "url" to "https://www.forbes.com/x",
                "requestToken" to "tok-1",
                "message" to "boom",
            ),
        )
        assertTrue((err as ArticleCaptureProtocol.Parsed.Ok).message is ArticleCaptureProtocol.HelperError)
    }

    @Test
    fun `matches exact urls ignoring fragments`() {
        assertEquals(
            ArticleCaptureProtocol.UrlMatch.EXACT,
            ArticleCaptureProtocol.matchUrl("https://a/b?x=1", "https://a/b?x=1#frag"),
        )
    }

    @Test
    fun `rejects query-only differences as a different document`() {
        // Production is strict: a new query means reload/SPA/redirect, never
        // the same document. The probe accepted these; production drops them.
        assertEquals(
            ArticleCaptureProtocol.UrlMatch.MISMATCH,
            ArticleCaptureProtocol.matchUrl("https://a/b", "https://a/b?sh=zzz"),
        )
        assertEquals(
            ArticleCaptureProtocol.UrlMatch.MISMATCH,
            ArticleCaptureProtocol.matchUrl("https://a/b?x=1", "https://a/b?x=2"),
        )
    }

    @Test
    fun `rejects stale and foreign urls`() {
        assertEquals(
            ArticleCaptureProtocol.UrlMatch.MISMATCH,
            ArticleCaptureProtocol.matchUrl("https://www.forbes.com/new", "https://www.forbes.com/old"),
        )
        assertEquals(
            ArticleCaptureProtocol.UrlMatch.MISMATCH,
            ArticleCaptureProtocol.matchUrl("https://www.forbes.com/x", "https://evil.test/x"),
        )
        assertEquals(
            ArticleCaptureProtocol.UrlMatch.MISMATCH,
            ArticleCaptureProtocol.matchUrl(null, "https://www.forbes.com/x"),
        )
        assertEquals(
            ArticleCaptureProtocol.UrlMatch.MISMATCH,
            ArticleCaptureProtocol.matchUrl("https://www.forbes.com/x", null),
        )
    }

    @Test
    fun `assembles out-of-order chunks`() {
        val asm = ArticleChunkAssembler(3)
        assertNull(asm.add(2, "c"))
        assertNull(asm.add(0, "a"))
        assertFalse(asm.isComplete())
        assertNull(asm.add(1, "b"))
        assertTrue(asm.isComplete())
        assertEquals("abc", asm.assembled())
    }

    @Test
    fun `rejects duplicates and out-of-range indexes`() {
        val asm = ArticleChunkAssembler(1)
        assertNull(asm.add(0, "a"))
        assertEquals("duplicate chunk 0", asm.add(0, "a"))
        assertEquals("index out of range", asm.add(1, "b"))
        assertEquals("a", asm.assembled())
    }

    @Test
    fun `enforces the utf8 byte budget`() {
        val asm = ArticleChunkAssembler(4, maxBytes = 10)
        assertNull(asm.add(0, "12345"))
        assertEquals("payload exceeds 5 MiB budget", asm.add(1, "123456"))
        assertNull(asm.assembled())
    }

    @Test
    fun `zero-chunk payloads complete immediately as empty`() {
        val asm = ArticleChunkAssembler(0)
        assertTrue(asm.isComplete())
        assertEquals("", asm.assembled())
        assertEquals("no chunks expected", asm.add(0, "x"))
    }

    private fun session(): ArticleCaptureSession =
        ArticleCaptureSession(7, "abc123", "https://www.forbes.com/x")

    private fun meta(): ArticleCaptureProtocol.Meta =
        (ArticleCaptureProtocol.parse(metaMap()) as ArticleCaptureProtocol.Parsed.Ok).message
            as ArticleCaptureProtocol.Meta

    @Test
    fun `happy path completes with concatenated payloads`() {
        val cap = session()
        assertNull(cap.apply(meta()))
        assertNull(cap.apply(ArticleCaptureProtocol.TextChunk("abc123", "u", "tok-1", 1, 2, "world")))
        assertNull(cap.apply(ArticleCaptureProtocol.TextChunk("abc123", "u", "tok-1", 0, 2, "hello ")))
        assertNull(cap.apply(ArticleCaptureProtocol.HtmlChunk("abc123", "u", "tok-1", 0, 1, "<p>hi</p>")))
        assertFalse(cap.isComplete())
        assertNull(cap.apply(ArticleCaptureProtocol.Done("abc123", "u", "tok-1")))
        assertTrue(cap.isComplete())
        assertEquals("hello world", cap.text())
        assertEquals("<p>hi</p>", cap.html())
    }

    @Test
    fun `chunk before meta fails the session`() {
        val cap = session()
        assertEquals(
            "text chunk before meta",
            cap.apply(ArticleCaptureProtocol.TextChunk("abc123", "u", "tok-1", 0, 1, "x")),
        )
        assertEquals("text chunk before meta", cap.apply(meta()))
        assertFalse(cap.isComplete())
    }

    @Test
    fun `total change and early done fail the session`() {
        val cap = session()
        assertNull(cap.apply(meta()))
        assertEquals(
            "text total changed",
            cap.apply(ArticleCaptureProtocol.TextChunk("abc123", "u", "tok-1", 0, 5, "x")),
        )

        val cap2 = session()
        assertNull(cap2.apply(meta()))
        assertEquals(
            "done before all chunks arrived",
            cap2.apply(ArticleCaptureProtocol.Done("abc123", "u", "tok-1")),
        )
    }

    @Test
    fun `wrong capture id and duplicate meta fail the session`() {
        val cap = session()
        assertNull(cap.apply(meta()))
        assertEquals(
            "capture-id mismatch",
            cap.apply(ArticleCaptureProtocol.TextChunk("other", "u", "tok-1", 0, 2, "x")),
        )

        val cap2 = session()
        assertNull(cap2.apply(meta()))
        assertEquals("duplicate meta", cap2.apply(meta()))
    }

    @Test
    fun `cancel refuses further messages (trigger cancellation)`() {
        val cap = session()
        assertNull(cap.apply(meta()))
        cap.cancel()
        assertEquals(
            "cancelled",
            cap.apply(ArticleCaptureProtocol.TextChunk("abc123", "u", "tok-1", 0, 2, "x")),
        )
        assertFalse(cap.isComplete())
    }

    @Test
    fun `helper error fails the session with its message`() {
        val cap = session()
        assertNull(cap.apply(meta()))
        assertEquals(
            "helper error: send failed",
            cap.apply(ArticleCaptureProtocol.HelperError("abc123", "u", "tok-1", "send failed")),
        )
    }
}
