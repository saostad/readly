package app.readly.input.sites

import app.readly.input.FetchConnection
import app.readly.input.nyt.NytPolicy
import java.net.URI

/**
 * Tracks which shared fixes one reading actually applied, so the notice
 * claims only applied directives. Collects per-hop header use (in the
 * opener) plus the final page's cleanup outcome (in the fetcher).
 */
class SiteUsage {
    /** Hosts where rule headers were actually sent, first-seen order. */
    val headerHosts = LinkedHashSet<String>()

    /** Final response URL's host, set by the fetcher. */
    var finalHost: String? = null

    /** True when the final page's cleanup matched at least one node. */
    var cleanupApplied: Boolean = false

    /** True when any applied rule (headers or cleanup) was [RuleStatus.PARTIAL]. */
    var partialApplied: Boolean = false

    /** True when any applied hop used the legacy NYT override. */
    var legacyUsed: Boolean = false

    /** Legacy override version in force, for the notice. */
    var legacyVersion: String? = null

    /** True when headers were sent or cleanup matched — never for no-op rules. */
    fun applied(): Boolean = headerHosts.isNotEmpty() || cleanupApplied

    /**
     * Host named in the reading notice: the final page when fixes applied
     * there, else the first hop that sent rule headers.
     */
    fun noticeHost(): String {
        val final = finalHost
        if (final != null && (final in headerHosts || cleanupApplied)) return final
        return headerHosts.firstOrNull() ?: final ?: "this site"
    }
}

/**
 * Selects request headers for article fetching from one immutable
 * [SiteReadingSnapshot]. Pure JVM, no I/O: the caller snapshots the catalog
 * once per reading and wraps a `(url, headers) -> FetchConnection` seam;
 * every redirect hop re-enters [headersFor] with its own URL, so a redirect
 * into or out of a matched domain switches headers hop by hop and never
 * carries one host's User-Agent/Referer/custom headers to another hop.
 *
 * Rule headers are sent ONLY to eligible URLs (HTTPS, default/443 port, no
 * userinfo, matchable host) that match a rule with request directives. A
 * newer legacy NYT override replaces the catalog headers on exact NYT hosts
 * only. Anything else — unknown hosts, suffix spoofs, plain HTTP, odd
 * ports, userinfo URLs, unparseable URLs, or matched rules without request
 * directives — sends the default headers (empty map: the opener's standard
 * User-Agent and Accept-Language).
 */
object SiteFetchPolicy {
    /** Resolved headers for one request hop. */
    data class HopHeaders(
        /** Concrete headers to send; empty means the opener's defaults. */
        val headers: Map<String, String>,
        /** The matched rule, if any (even when it carries no directives). */
        val rule: SiteRule?,
        /** True when the headers came from the legacy NYT override. */
        val legacyNyt: Boolean,
    )

    /** Concrete headers for one request to [url] under [snapshot]. */
    fun headersFor(url: String, snapshot: SiteReadingSnapshot): HopHeaders {
        if (!SiteRequests.isEligibleUrl(url)) {
            return HopHeaders(emptyMap(), null, false)
        }
        val host = try {
            URI(url).host
        } catch (e: Exception) {
            return HopHeaders(emptyMap(), null, false)
        }
        val normalized = SiteDomains.normalizeLookupHost(host)
            ?: return HopHeaders(emptyMap(), null, false)
        val legacy = snapshot.legacyNyt
        if (legacy != null && NytPolicy.isSupportedHost(normalized)) {
            // Explicit override: the newer legacy NYT policy replaces the
            // catalog headers on exact NYT hosts only (never subdomains).
            return HopHeaders(mapOf("User-Agent" to legacy.userAgent), null, true)
        }
        val match = snapshot.catalog.match(normalized)
            ?: return HopHeaders(emptyMap(), null, false)
        return HopHeaders(match.rule.request.asHeaders(), match.rule, false)
    }

    /**
     * Adapts a `(url, headers)` connection seam to the one-argument opener
     * the fetcher takes, pinning [snapshot] for this reading and recording
     * applied hops in [usage]. Production passes
     * `ArticleFetcher::httpUrlOpenerWithHeaders`; tests pass a fake that
     * records the chosen headers.
     */
    fun opener(
        snapshot: SiteReadingSnapshot,
        usage: SiteUsage,
        open: (url: String, headers: Map<String, String>) -> FetchConnection,
    ): (String) -> FetchConnection = { url ->
        val hop = headersFor(url, snapshot)
        if (hop.headers.isNotEmpty()) {
            usage.headerHosts.add(hostLabel(url))
            if (hop.legacyNyt) {
                usage.legacyUsed = true
                usage.legacyVersion = snapshot.legacyNyt?.sourceVersion
                usage.partialApplied = true
            } else if (hop.rule?.status == RuleStatus.PARTIAL) {
                usage.partialApplied = true
            }
        }
        open(url, hop.headers)
    }

    private fun hostLabel(url: String): String {
        return try {
            URI(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }
}
