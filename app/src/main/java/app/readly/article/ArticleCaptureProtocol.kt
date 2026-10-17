package app.readly.article

import java.nio.charset.StandardCharsets

/**
 * Pure (Android/Gecko-free) logic for the `readly-capture/2` native-messaging
 * protocol: message parsing, sender-URL matching, bounded chunk assembly and
 * capture-session state. The article activity feeds it `Map` payloads
 * converted from the `JSONObject` the engine delivers, so all of this is
 * unit-testable on the host JVM.
 *
 * Nothing here touches bypass behavior; it only validates and assembles the
 * DOM snapshots the own helper extension sends for article reading.
 *
 * Production matching is strict: a message belongs to the current document
 * only when its URL equals the session URL after fragment stripping AND it
 * echoes the native-issued request token for the current read. A differing
 * query string means a different document (reload with new params, SPA
 * navigation, tracking redirect) and the message is dropped — unlike the
 * Round 1 probe, which accepted query diffs but recorded them. The token
 * additionally rejects late messages from a prior document that happen to
 * carry the same URL (same-URL reload): the token rotates on every
 * navigation while the URL does not.
 */
object ArticleCaptureProtocol {
    const val PROTOCOL = "readly-capture/2"

    /** Native-to-helper capture request posted on the owned message port. */
    const val KIND_REQUEST = "capture-request"

    /** 5 MiB UTF-8 budget per payload (rendered text, raw HTML). */
    const val MAX_PAYLOAD_BYTES = 5 * 1024 * 1024

    /**
     * Declared chunk counts above this are rejected, bounding assembly work.
     * Worst case is a 5 MiB all-ASCII payload in 64 Ki-char slices: exactly 80
     * chunks; 96 leaves margin. The 5 MiB byte budget is always enforced too.
     */
    const val MAX_CHUNKS = 96

    /** Sanity cap for a single chunk string (helper sends <= 64 Ki chars). */
    const val MAX_CHUNK_CHARS = 128 * 1024
    const val MAX_CAPTURE_ID = 64
    const val MAX_REQUEST_TOKEN = 128
    const val MAX_URL = 4000
    const val MAX_TITLE = 500
    const val MAX_ERROR = 200
    const val MAX_GATE_ITEMS = 32

    /** Gate-node tokens the helper may report; anything else is dropped. */
    val KNOWN_GATE_NODES = setOf(
        "paywall", "registration-wall", "regwall", "signin-wall",
        "premium-lock", "metered-wall", "tp-modal", "piano",
        "tinypass", "zephr",
    )

    /** Gate-text signals the helper may report; anything else is dropped. */
    val KNOWN_GATE_TEXTS = setOf(
        "subscribe to continue reading",
        "sign in to continue reading",
        "register to continue reading",
        "you have reached your article limit",
        "remaining free article",
        "premium article",
        "members only",
        "already a subscriber",
        "continue reading with a subscription",
    )

    sealed interface Inbound {
        val captureId: String
        val url: String
        /** The native-issued token echoed back; must match the current read. */
        val requestToken: String
    }

    data class Meta(
        override val captureId: String,
        override val url: String,
        override val requestToken: String,
        val title: String,
        val readyState: String,
        val elapsedMs: Long,
        val textBytes: Int,
        val htmlBytes: Int,
        val textTruncated: Boolean,
        val htmlTruncated: Boolean,
        val textChunks: Int,
        val htmlChunks: Int,
        val paragraphs: Int,
        val gateNodes: List<String>,
        val gateText: List<String>,
    ) : Inbound

    data class TextChunk(
        override val captureId: String,
        override val url: String,
        override val requestToken: String,
        val index: Int,
        val total: Int,
        val data: String,
    ) : Inbound

    data class HtmlChunk(
        override val captureId: String,
        override val url: String,
        override val requestToken: String,
        val index: Int,
        val total: Int,
        val data: String,
    ) : Inbound

    data class Done(
        override val captureId: String,
        override val url: String,
        override val requestToken: String,
    ) : Inbound

    data class HelperError(
        override val captureId: String,
        override val url: String,
        override val requestToken: String,
        val message: String,
    ) : Inbound

    /** Outcome of [parse]: either a validated message or a bounded reason. */
    sealed interface Parsed {
        data class Ok(val message: Inbound) : Parsed
        data class Err(val reason: String) : Parsed
    }

    fun parse(map: Map<String, Any?>): Parsed {
        if (map["protocol"] != PROTOCOL) {
            return Parsed.Err("protocol mismatch")
        }
        val kind = map["kind"] as? String ?: return Parsed.Err("missing kind")
        val captureId = boundedString(map["captureId"], MAX_CAPTURE_ID)
            ?: return Parsed.Err("bad captureId")
        val url = boundedString(map["url"], MAX_URL)
            ?: return Parsed.Err("bad url")
        val requestToken = boundedString(map["requestToken"], MAX_REQUEST_TOKEN)
            ?.takeIf { it.isNotEmpty() }
            ?: return Parsed.Err("bad requestToken")
        return when (kind) {
            "meta" -> parseMeta(map, captureId, url, requestToken)
            "text-chunk" -> parseChunk(map, captureId, url, requestToken, true)
            "html-chunk" -> parseChunk(map, captureId, url, requestToken, false)
            "done" -> Parsed.Ok(Done(captureId, url, requestToken))
            "error" -> {
                val message = boundedString(map["message"], MAX_ERROR)
                    ?: return Parsed.Err("bad error message")
                Parsed.Ok(HelperError(captureId, url, requestToken, message))
            }
            else -> Parsed.Err("unknown kind")
        }
    }

    private fun parseMeta(
        map: Map<String, Any?>,
        captureId: String,
        url: String,
        requestToken: String,
    ): Parsed {
        val title = boundedString(map["title"], MAX_TITLE) ?: return Parsed.Err("bad title")
        val readyState = boundedString(map["readyState"], 32) ?: return Parsed.Err("bad readyState")
        val elapsedMs = long(map["elapsedMs"], 0L, 600_000L) ?: return Parsed.Err("bad elapsedMs")
        val textBytes = int(map["textBytes"], 0, MAX_PAYLOAD_BYTES) ?: return Parsed.Err("bad textBytes")
        val htmlBytes = int(map["htmlBytes"], 0, MAX_PAYLOAD_BYTES) ?: return Parsed.Err("bad htmlBytes")
        val textTruncated = map["textTruncated"] as? Boolean ?: return Parsed.Err("bad textTruncated")
        val htmlTruncated = map["htmlTruncated"] as? Boolean ?: return Parsed.Err("bad htmlTruncated")
        val textChunks = int(map["textChunks"], 0, MAX_CHUNKS) ?: return Parsed.Err("bad textChunks")
        val htmlChunks = int(map["htmlChunks"], 0, MAX_CHUNKS) ?: return Parsed.Err("bad htmlChunks")
        val paragraphs = int(map["paragraphs"], -1, 10_000_000) ?: return Parsed.Err("bad paragraphs")
        val gateNodes = knownItems(map["gateNodes"], KNOWN_GATE_NODES)
            ?: return Parsed.Err("bad gateNodes")
        val gateText = knownItems(map["gateText"], KNOWN_GATE_TEXTS)
            ?: return Parsed.Err("bad gateText")
        return Parsed.Ok(
            Meta(
                captureId, url, requestToken, title, readyState, elapsedMs,
                textBytes, htmlBytes, textTruncated, htmlTruncated,
                textChunks, htmlChunks, paragraphs, gateNodes, gateText,
            ),
        )
    }

    private fun parseChunk(
        map: Map<String, Any?>,
        captureId: String,
        url: String,
        requestToken: String,
        text: Boolean,
    ): Parsed {
        val index = int(map["index"], 0, MAX_CHUNKS - 1) ?: return Parsed.Err("bad index")
        val total = int(map["total"], 1, MAX_CHUNKS) ?: return Parsed.Err("bad total")
        if (index >= total) return Parsed.Err("index >= total")
        val data = boundedString(map["data"], MAX_CHUNK_CHARS) ?: return Parsed.Err("bad data")
        return Parsed.Ok(
            if (text) TextChunk(captureId, url, requestToken, index, total, data)
            else HtmlChunk(captureId, url, requestToken, index, total, data),
        )
    }

    private fun boundedString(value: Any?, max: Int): String? {
        if (value !is String) return null
        if (value.length > max) return null
        return value
    }

    private fun int(value: Any?, min: Int, max: Int): Int? {
        val n = (value as? Number)?.toLong() ?: return null
        if (n < min || n > max) return null
        return n.toInt()
    }

    private fun long(value: Any?, min: Long, max: Long): Long? {
        val n = when (value) {
            is Number -> value.toLong()
            else -> return null
        }
        if (n < min || n > max) return null
        return n
    }

    private fun knownItems(value: Any?, known: Set<String>): List<String>? {
        if (value !is List<*>) return null
        if (value.size > MAX_GATE_ITEMS) return null
        val out = ArrayList<String>(value.size)
        for (item in value) {
            if (item is String && item in known && item !in out) {
                out.add(item)
            }
        }
        return out
    }

    /** How a message URL relates to the session's current document URL. */
    enum class UrlMatch {
        /** Identical after fragment stripping. */
        EXACT,

        /**
         * Different document: stale or foreign message, must be dropped.
         * This includes query-string differences (reload with new params,
         * SPA navigation, tracking redirect) — production never accepts
         * those as the same document.
         */
        MISMATCH,
    }

    fun matchUrl(expected: String?, actual: String?): UrlMatch {
        if (expected == null || actual == null) return UrlMatch.MISMATCH
        if (expected.length > MAX_URL || actual.length > MAX_URL) return UrlMatch.MISMATCH
        val e = expected.substringBefore('#')
        val a = actual.substringBefore('#')
        if (e == a) return UrlMatch.EXACT
        return UrlMatch.MISMATCH
    }

    fun utf8Bytes(s: String): Int = s.toByteArray(StandardCharsets.UTF_8).size
}

/**
 * Bounded reassembly of one payload (text or HTML). Totals come from the
 * validated `meta` message; every chunk is range, duplicate and UTF-8 budget
 * checked before it is kept.
 */
class ArticleChunkAssembler(
    val total: Int,
    private val maxBytes: Int = ArticleCaptureProtocol.MAX_PAYLOAD_BYTES,
) {
    private val parts: Array<String?> = arrayOfNulls(total.coerceAtLeast(0))
    private var bytes = 0

    /** Chunks accepted so far. */
    var received: Int = 0
        private set

    /**
     * Stores one chunk. Returns null on success, or a bounded reason the
     * caller must treat as a session failure.
     */
    fun add(index: Int, data: String): String? {
        if (total <= 0) return "no chunks expected"
        if (index < 0 || index >= total) return "index out of range"
        if (parts[index] != null) return "duplicate chunk $index"
        if (data.length > ArticleCaptureProtocol.MAX_CHUNK_CHARS) return "chunk too large"
        val grown = bytes + ArticleCaptureProtocol.utf8Bytes(data)
        if (grown > maxBytes) return "payload exceeds 5 MiB budget"
        parts[index] = data
        bytes = grown
        received++
        return null
    }

    fun isComplete(): Boolean = total >= 0 && received == total

    /** Concatenated payload, or null unless every chunk arrived. */
    fun assembled(): String? {
        if (!isComplete()) return null
        return buildString(bytes.coerceAtLeast(0)) {
            for (part in parts) append(part)
        }
    }
}

/**
 * One armed DOM capture: exactly one `meta`, then the declared text/HTML
 * chunks, then `done`. Any protocol violation fails the session with a reason;
 * a new navigation cancels it via [cancel], and messages from other captures
 * are rejected by id.
 */
class ArticleCaptureSession(
    val generation: Int,
    val captureId: String,
    val pageUrl: String,
) {
    var meta: ArticleCaptureProtocol.Meta? = null
        private set
    private var textAsm: ArticleChunkAssembler? = null
    private var htmlAsm: ArticleChunkAssembler? = null

    var cancelled: Boolean = false
        private set

    /** Non-null once the session failed; further messages are refused. */
    var failure: String? = null
        private set

    var doneReceived: Boolean = false
        private set

    val textReceived: Int get() = textAsm?.received ?: 0
    val htmlReceived: Int get() = htmlAsm?.received ?: 0

    fun cancel() {
        cancelled = true
    }

    private fun fail(reason: String): String {
        if (failure == null) failure = reason
        return failure!!
    }

    /**
     * Applies one parsed message. Returns null on success, else the session
     * failure reason.
     */
    fun apply(message: ArticleCaptureProtocol.Inbound): String? {
        if (cancelled) return fail("cancelled")
        failure?.let { return it }
        if (message.captureId != captureId) return fail("capture-id mismatch")
        return when (message) {
            is ArticleCaptureProtocol.Meta -> {
                if (meta != null) return fail("duplicate meta")
                meta = message
                textAsm = ArticleChunkAssembler(message.textChunks)
                htmlAsm = ArticleChunkAssembler(message.htmlChunks)
                null
            }
            is ArticleCaptureProtocol.TextChunk -> {
                val asm = textAsm ?: return fail("text chunk before meta")
                if (message.total != asm.total) return fail("text total changed")
                asm.add(message.index, message.data)?.let { return fail(it) }
                null
            }
            is ArticleCaptureProtocol.HtmlChunk -> {
                val asm = htmlAsm ?: return fail("html chunk before meta")
                if (message.total != asm.total) return fail("html total changed")
                asm.add(message.index, message.data)?.let { return fail(it) }
                null
            }
            is ArticleCaptureProtocol.Done -> {
                doneReceived = true
                if (!isComplete()) return fail("done before all chunks arrived")
                null
            }
            is ArticleCaptureProtocol.HelperError ->
                fail("helper error: ${message.message}")
        }
    }

    fun isComplete(): Boolean {
        val m = meta ?: return false
        if (!doneReceived) return false
        return textAsm?.isComplete() == true && htmlAsm?.isComplete() == true &&
            m.textChunks == (textAsm?.total ?: -1) &&
            m.htmlChunks == (htmlAsm?.total ?: -1)
    }

    fun text(): String? = textAsm?.assembled()
    fun html(): String? = htmlAsm?.assembled()
}
