package app.readly.input.nyt

import app.readly.input.ArticleFetcher
import app.readly.input.FetchConnection
import java.net.URI

/**
 * Selects the request User-Agent for article fetching from the current NYT
 * policy. Pure JVM, no I/O: the caller snapshots one immutable [NytPolicy]
 * per reading and wraps a `(url, userAgent) -> FetchConnection` seam; every
 * redirect hop re-enters [userAgentFor] with its own URL, so a redirect into
 * or out of nytimes.com switches headers hop by hop.
 *
 * The policy agent is sent ONLY to HTTPS `nytimes.com`/`www.nytimes.com`
 * with no userinfo and a standard port (-1 or explicit 443). Anything else —
 * other hosts or subdomains (`cooking.nytimes.com`), suffix spoofs
 * (`nytimes.com.evil.test`), plain HTTP, odd ports, userinfo URLs, or
 * unparseable URLs — uses [ArticleFetcher.USER_AGENT]. Host matching is an
 * exact set lookup ([NytPolicy.isSupportedHost]), never a suffix match.
 */
object NytFetchPolicy {
    /** True when requests to [url] must carry the policy User-Agent. */
    fun usePolicyAgent(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val port = uri.port
        if (port != -1 && port != 443) return false
        return NytPolicy.isSupportedHost(uri.host)
    }

    /** The User-Agent for one request to [url] under [policy]. */
    fun userAgentFor(url: String, policy: NytPolicy): String =
        if (usePolicyAgent(url)) policy.userAgent else ArticleFetcher.USER_AGENT

    /**
     * Adapts a `(url, userAgent)` connection seam to the one-argument opener
     * [ArticleFetcher] takes, pinning [policy] for this reading. Production
     * passes `ArticleFetcher::httpUrlOpenerWithUserAgent`; tests pass a fake
     * that records the chosen headers.
     */
    fun opener(
        policy: NytPolicy,
        open: (url: String, userAgent: String) -> FetchConnection,
    ): (String) -> FetchConnection = { url -> open(url, userAgentFor(url, policy)) }
}
