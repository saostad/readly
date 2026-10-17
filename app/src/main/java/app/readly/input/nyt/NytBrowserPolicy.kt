package app.readly.input.nyt

import app.readly.input.sites.SiteBrowserPolicy
import java.net.URI
import java.util.Locale

/**
 * Legacy NYT-only browser policy over the shared [SiteBrowserPolicy] core:
 * same snapshot script, decoding, budgets, and generation guard, with the
 * original stricter NYT URL gates. Production now uses the general website
 * browser; this object keeps the owner-tested NYT contract meaningful and
 * its regression tests compiling.
 *
 * Two URL gates:
 * - [allowsBrowsing]: main-frame navigation while browsing. HTTPS
 *   `nytimes.com` and its subdomains (official login/account hosts live on
 *   subdomains), standard port, no userinfo. Anything else — other hosts,
 *   suffix spoofs (`nytimes.com.evil.test`), plain HTTP, odd ports,
 *   userinfo, unsafe schemes — is blocked with a note, never auto-opened
 *   elsewhere.
 * - [allowsExtraction]: the stricter gate for the initial retry URL and for
 *   handing a page to the reader: main NYT article hosts only
 *   ([NytFetchPolicy.usePolicyAgent]).
 */
object NytBrowserPolicy {
    /** Largest rendered-DOM snapshot accepted, in UTF-8 bytes (same budget as fetched pages). */
    const val MAX_DOM_BYTES = SiteBrowserPolicy.MAX_DOM_BYTES

    /** Backward-compatible alias; the budget is bytes, not chars. */
    @Deprecated("Use MAX_DOM_BYTES; the budget is UTF-8 bytes.", ReplaceWith("MAX_DOM_BYTES"))
    const val MAX_DOM_CHARS = MAX_DOM_BYTES

    /** Largest `evaluateJavascript` bridge result parsed, in chars (JSON escaping can expand markup). */
    const val MAX_BRIDGE_CHARS = SiteBrowserPolicy.MAX_BRIDGE_CHARS

    /** Largest text handed to the reader, kept below Binder's transaction limit. */
    const val MAX_HANDOFF_CHARS = SiteBrowserPolicy.MAX_HANDOFF_CHARS

    /** Top-level DOM snapshot script for `evaluateJavascript`; see [SiteBrowserPolicy]. */
    const val SNAPSHOT_JS = SiteBrowserPolicy.SNAPSHOT_JS

    /**
     * True when the browser may navigate its main frame to [url]: HTTPS
     * `nytimes.com` or a real subdomain of it, standard port, no userinfo.
     * Subdomain matching is exact (`host == domain || host endsWith
     * ".nytimes.com"`), so `nytimes.com.evil.test` and `evilnytimes.com`
     * are rejected.
     */
    fun allowsBrowsing(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val port = uri.port
        if (port != -1 && port != 443) return false
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        return host == NytPolicy.DOMAIN || host.endsWith("." + NytPolicy.DOMAIN)
    }

    /**
     * True when [url] may be opened initially and handed to the reader:
     * main NYT article hosts only (same gate as the native fetch policy).
     */
    fun allowsExtraction(url: String): Boolean = NytFetchPolicy.usePolicyAgent(url)

    /**
     * The browser retry URL for a failed link fetch, or null when the link
     * is not a main NYT article page. Callers keep the exact source URL —
     * never parse one out of exception text — and clear it when a
     * different read or stop starts.
     */
    fun retryUrlForFailure(requestUrl: String): String? =
        if (allowsExtraction(requestUrl)) requestUrl else null

    /**
     * Decodes one `evaluateJavascript` result; see [SiteBrowserPolicy]
     * for the accepted shapes and rejection reasons. The snapshot type is
     * [SiteBrowserPolicy.DomSnapshot], shared with the general browser.
     */
    fun decodeJsResult(jsResult: String?): SiteBrowserPolicy.DomSnapshot =
        SiteBrowserPolicy.decodeJsResult(jsResult)

    /**
     * Error when [chars] of handoff text exceeds the Binder-safe budget,
     * null when it fits.
     */
    fun handoffTooLargeError(chars: Int): String? = SiteBrowserPolicy.handoffTooLargeError(chars)
}

/**
 * Generation guard against stale browser callbacks; see the shared
 * [app.readly.input.sites.PageGeneration].
 */
typealias PageGeneration = app.readly.input.sites.PageGeneration
