package app.readly.input.sites

import java.net.URI
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Pure JVM policy for the user-invoked browser fallback: which pages the
 * embedded browser may show, which pages may be handed to the reader, and
 * how a rendered-DOM snapshot is decoded and bounded. No Android, no
 * network, no WebView.
 *
 * This is the shared core behind every browser fallback. The general gates
 * accept any ordinary website link — an eligible HTTPS URL (same boundary
 * as request routing: HTTPS, default/443 port, no userinfo, matchable
 * host), including imported-rule hosts and unknown JavaScript-heavy sites.
 * There is no per-domain list and no per-site activity: main-frame
 * navigation follows normal eligible HTTPS links, including legitimate
 * cross-domain login flows, while unsafe targets (other schemes, plain
 * HTTP, userinfo, odd ports, invalid hosts) are blocked with a reason and
 * never auto-opened elsewhere. The legacy NYT policy keeps its stricter
 * gates as a thin wrapper over this core.
 *
 * This fallback cannot guarantee passing publisher challenges or access
 * checks; it only lets the owner interact with the page and hand a
 * successfully rendered article to the existing reader.
 */
object SiteBrowserPolicy {
    /** Largest rendered-DOM snapshot accepted, in UTF-8 bytes (same budget as fetched pages). */
    const val MAX_DOM_BYTES = 5 * 1024 * 1024

    /** Largest `evaluateJavascript` bridge result parsed, in chars (JSON escaping can expand markup). */
    const val MAX_BRIDGE_CHARS = 32 * 1024 * 1024

    /** Largest text handed to the reader, kept below Binder's transaction limit. */
    const val MAX_HANDOFF_CHARS = 128 * 1024

    /**
     * Top-level DOM snapshot script for `evaluateJavascript`. Returns a JSON
     * string; oversize pages report `{tooLarge, length, unit, url}` instead
     * of the markup, so a huge DOM never crosses the bridge as a giant
     * string. The budget is UTF-8 bytes: a cheap char check rejects strings
     * whose length alone exceeds it (each char is at least one byte), then
     * `TextEncoder`/`Blob` measures multibyte pages accurately. `unit` is
     * `"bytes"` when measured, `"chars"` for the cheap path.
     */
    const val SNAPSHOT_JS =
        "(function(){try{var u=location.href;" +
            "var h=document.documentElement?document.documentElement.outerHTML:'';" +
            "if(!h){return JSON.stringify({empty:true,url:u});}" +
            "var MAX=" + MAX_DOM_BYTES + ";" +
            "if(h.length>MAX){return JSON.stringify({tooLarge:true,length:h.length,unit:\"chars\",url:u});}" +
            "var n=-1;" +
            "try{if(typeof TextEncoder!=='undefined'){n=new TextEncoder().encode(h).length;}" +
            "else if(typeof Blob!=='undefined'){n=new Blob([h]).size;}}catch(e){n=-1;}" +
            "if(n<0){if(h.length*3>MAX){return JSON.stringify({tooLarge:true,length:h.length*3,unit:\"bytes\",url:u});}}" +
            "else if(n>MAX){return JSON.stringify({tooLarge:true,length:n,unit:\"bytes\",url:u});}" +
            "return JSON.stringify({html:h,url:u});" +
            "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()"

    /**
     * True when the browser may navigate its main frame to [url]: any
     * eligible HTTPS website address. Cross-domain navigation is allowed
     * (login flows legitimately leave the article host); only unsafe
     * targets are rejected — see [blockReason].
     */
    fun allowsBrowsing(url: String): Boolean = SiteRequests.isEligibleUrl(url)

    /**
     * True when [url] may be opened initially and handed to the reader: any
     * eligible HTTPS website address. Reading runs the same guarded
     * extraction as the native quick reader, so unknown hosts are safe to
     * attempt: recognizable block pages fail with an explanation instead of
     * being spoken.
     */
    fun allowsExtraction(url: String): Boolean = SiteRequests.isEligibleUrl(url)

    /**
     * The browser retry URL for a failed link fetch, or null when the link
     * is not an eligible website address. Callers keep the exact source URL —
     * never parse one out of exception text — and clear it when a
     * different read or stop starts.
     */
    fun retryUrlForFailure(requestUrl: String): String? =
        if (allowsExtraction(requestUrl)) requestUrl else null

    /**
     * Why the browser refuses [url], naming the concrete problem (scheme,
     * credentials, port, or host). Call only when [allowsBrowsing] is false;
     * [blockReasonOrNull] covers both cases.
     */
    fun blockReason(url: String): String {
        if (url.isBlank()) return "the address is empty"
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "the address is not a valid URL"
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return "only HTTPS website addresses can open here " +
                "(got \"${(uri.scheme ?: "missing").take(16)}\")"
        }
        if (uri.userInfo != null) return "addresses with credentials cannot open here"
        val port = uri.port
        if (port != -1 && port != 443) return "only the default HTTPS port can open here (got port $port)"
        val host = uri.host ?: return "the address has no host"
        if (SiteDomains.normalizeLookupHost(host) == null) return "the address host is invalid"
        return "the address cannot open here"
    }

    /** Null when [url] may open, else the [blockReason] why not. */
    fun blockReasonOrNull(url: String): String? = if (allowsBrowsing(url)) null else blockReason(url)

    /**
     * Notice for a browser reading handed to the service: the extraction
     * notice (whole-page fallback), plus a short line when declarative
     * cleanup matched, plus an optional [extra] (startup errors). Null when
     * there is nothing to say. Never claims native request headers: the
     * browser sends its normal UA, never rule headers.
     */
    fun readingNotice(
        docNotice: String?,
        cleanupApplied: Boolean,
        host: String,
        extra: String? = null,
    ): String? {
        val parts = mutableListOf<String>()
        if (!docNotice.isNullOrBlank()) parts.add(docNotice)
        if (cleanupApplied) parts.add("Cleaned up the page for $host.")
        if (!extra.isNullOrBlank()) parts.add(extra)
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** A decoded `evaluateJavascript` snapshot: usable markup or a reason. */
    sealed interface DomSnapshot {
        /** Rendered markup plus the page URL the script observed. */
        data class Ok(val html: String, val url: String) : DomSnapshot

        /** Why the snapshot cannot be used; safe to show (no markup). */
        data class Rejected(val reason: String) : DomSnapshot
    }

    /**
     * Decodes one `evaluateJavascript` result. The bridge returns the
     * script's return value JSON-encoded (our JSON object arrives as a
     * quoted JSON string); a bare object is accepted too. Oversize, empty
     * and error reports become [DomSnapshot.Rejected] with the real
     * detail, never the markup.
     */
    fun decodeJsResult(jsResult: String?): DomSnapshot {
        if (jsResult.isNullOrBlank() || jsResult.trim() == "null") {
            return DomSnapshot.Rejected("Read page failed: the browser returned no page data.")
        }
        // Bound the encoded bridge input before JSON parsing (escaping can
        // expand markup); the string itself already crossed the bridge, but
        // parsing it would allocate more. No markup in the reason.
        if (jsResult.length > MAX_BRIDGE_CHARS) {
            return DomSnapshot.Rejected(
                "Read page failed: the browser result is too large " +
                    "(${jsResult.length} chars, limit $MAX_BRIDGE_CHARS).",
            )
        }
        val payload: JSONObject = try {
            when (val outer = JSONTokener(jsResult).nextValue()) {
                is String -> JSONObject(outer)
                is JSONObject -> outer
                else -> return DomSnapshot.Rejected(
                    "Read page failed: unexpected browser result " +
                        describeJsonValue(outer) + ".",
                )
            }
        } catch (e: Exception) {
            return DomSnapshot.Rejected(
                "Read page failed: couldn't decode the browser result: " +
                    "${e.javaClass.name}: ${e.message}",
            )
        }
        payload.opt("error")?.let { detail ->
            if (detail is String && detail.isNotBlank()) {
                return DomSnapshot.Rejected("Read page failed: the page reported: ${detail.take(200)}.")
            }
        }
        if (payload.optBoolean("tooLarge", false)) {
            val length = payload.opt("length")
            val unit = payload.opt("unit")
            val unitText = if (unit is String && unit == "chars") "chars" else "bytes"
            return DomSnapshot.Rejected(
                "Read page failed: the rendered page is too large " +
                    "(${describeJsonValue(length)} $unitText, limit $MAX_DOM_BYTES bytes).",
            )
        }
        if (payload.optBoolean("empty", false)) {
            return DomSnapshot.Rejected("Read page failed: the page has no readable markup yet.")
        }
        val html = payload.opt("html")
        if (html !is String || html.isBlank()) {
            return DomSnapshot.Rejected("Read page failed: the browser returned no page markup.")
        }
        // Cheap char check first: each char is at least one UTF-8 byte, so a
        // longer string is definitely over without allocating a byte array.
        if (html.length > MAX_DOM_BYTES) {
            return DomSnapshot.Rejected(
                "Read page failed: the rendered page is too large " +
                    "(${html.length} chars, over $MAX_DOM_BYTES bytes).",
            )
        }
        val byteSize = html.toByteArray(Charsets.UTF_8).size
        if (byteSize > MAX_DOM_BYTES) {
            return DomSnapshot.Rejected(
                "Read page failed: the rendered page is too large " +
                    "($byteSize bytes, limit $MAX_DOM_BYTES bytes).",
            )
        }
        val url = payload.opt("url")
        if (url !is String || url.isBlank()) {
            return DomSnapshot.Rejected("Read page failed: the browser returned no page URL.")
        }
        return DomSnapshot.Ok(html, url)
    }

    /**
     * Error when [chars] of handoff text exceeds the Binder-safe budget,
     * null when it fits.
     */
    fun handoffTooLargeError(chars: Int): String? =
        if (chars > MAX_HANDOFF_CHARS) {
            "Read page failed: the page text is too large to hand to the reader " +
                "($chars chars, limit $MAX_HANDOFF_CHARS)."
        } else {
            null
        }

    private fun describeJsonValue(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "missing"
        is String -> "\"${v.take(64)}\""
        is Number, is Boolean -> v.toString()
        else -> "${v.javaClass.simpleName}(${v.toString().take(64)})"
    }
}
