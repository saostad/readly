package app.readly.input

import app.readly.input.nyt.NytArticle
import app.readly.input.sites.RuleStatus
import app.readly.input.sites.SiteArticleGuard
import app.readly.input.sites.SiteBrowserPolicy
import app.readly.input.sites.SiteCleanup
import app.readly.input.sites.SiteReadingSnapshot
import app.readly.input.sites.SiteRequests
import app.readly.input.sites.SiteUsage
import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale
import net.dankito.readability4j.Readability4J
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Minimal HTTP response. The fetcher talks to this instead of [HttpURLConnection] directly so
 * unit tests can serve fixture pages without a network.
 */
interface FetchConnection : Closeable {
    val statusCode: Int
    val statusMessage: String
    fun header(name: String): String?
    fun input(): InputStream
}

/**
 * Fetches a shared URL and turns it into readable text.
 *
 * Pure JVM Kotlin (jsoup + readability4j for HTML), so it is unit-testable on the host JVM.
 *
 * @param urlOpener opens a URL for a single request (no redirect following); production
 *   passes [httpUrlOpener].
 * @param onWarn receives messages that production logs with `Log.w("Readly", …)`; injected
 *   so JVM tests don't touch `android.util.Log`.
 * @param siteRules the pinned per-reading catalog snapshot, or null to read
 *   without shared fixes (unknown-host behavior everywhere).
 * @param siteUsage collects applied fixes for the reading notice; null
 *   records nothing.
 */
class ArticleFetcher(
    private val urlOpener: (String) -> FetchConnection,
    private val onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
    private val siteRules: SiteReadingSnapshot? = null,
    private val siteUsage: SiteUsage? = null,
) {
    /** A fetched page, ready for [app.readly.text.TextSanitizer]. */
    data class Document(
        val title: String,
        val text: String,
        val format: SourceFormat,
        /** Set when the whole page is read because no main article was found. */
        val notice: String?,
    )

    companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val MIN_ARTICLE_CHARS = 200
        const val MAX_TITLE_CHARS = 120

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0 Mobile Safari/537.36"

        const val FALLBACK_NOTICE =
            "Couldn't find the main article, so Readly is reading the whole page."

        /** Production [ArticleFetcher] opener backed by [HttpURLConnection]. */
        fun httpUrlOpener(url: String): FetchConnection = HttpFetchConnection(url)

        /**
         * Production opener with an explicit User-Agent. [NytFetchPolicy][app.readly.input.nyt.NytFetchPolicy]
         * uses this per redirect hop; the update downloader keeps [httpUrlOpener].
         */
        fun httpUrlOpenerWithUserAgent(url: String, userAgent: String): FetchConnection =
            HttpFetchConnection(url, userAgent)

        /**
         * Production opener with an explicit header map (User-Agent, Referer,
         * validated extras). [SiteFetchPolicy][app.readly.input.sites.SiteFetchPolicy]
         * uses this per redirect hop; an empty map sends the standard
         * User-Agent and Accept-Language. Map entries override the defaults.
         */
        fun httpUrlOpenerWithHeaders(url: String, headers: Map<String, String>): FetchConnection =
            HttpFetchConnection(url, USER_AGENT, headers)

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        const val OEMBED_BASE = "https://publish.x.com/oembed"
        const val X_POST_ERROR_PREFIX = "Couldn't load this post from X: "

        /** Hosts that serve X/Twitter post pages. */
        private val POST_HOSTS = setOf(
            "x.com", "www.x.com", "mobile.x.com",
            "twitter.com", "www.twitter.com", "mobile.twitter.com",
        )

        /** `/user/status/<digits>` plus anything after it (photo suffix, trailing slash). */
        private val POST_PATH = Regex("^/([^/]+)/status/(\\d+)(?:/.*)?$")

        /**
         * Normalizes an X/Twitter post URL to `https://x.com/<user>/status/<id>`,
         * dropping the query, or null when [url] isn't a post page.
         */
        internal fun normalizePostUrl(url: String): String? {
            val uri = try {
                URI(url)
            } catch (_: Exception) {
                return null
            }
            if (uri.host?.lowercase(Locale.US) !in POST_HOSTS) return null
            val match = POST_PATH.matchEntire(uri.path.orEmpty()) ?: return null
            return "https://x.com/${match.groupValues[1]}/status/${match.groupValues[2]}"
        }
    }

    /**
     * Fetches [url] and extracts its readable text.
     *
     * @throws IOException with a user-readable message on any failure (network, HTTP
     *   status, oversize page, unsupported type, too many redirects).
     */
    fun fetch(url: String): Document {
        try {
            return fetchInner(url)
        } catch (e: FetchException) {
            throw e
        } catch (e: Exception) {
            throw FetchException("Couldn't open $url: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    /**
     * Extracts a readable article from already-rendered markup (the browser
     * fallback's DOM snapshot) without any network fetch — the opener is
     * never consulted, so tests prove that with a throwing opener. Any
     * eligible website address may be read; unsafe targets fail instead of
     * handing arbitrary DOM to speech.
     *
     * Main NYT article hosts run the exact NYT guard/extractor, unchanged.
     * Every other host — matched-rule or unknown — runs the shared block
     * guard first (recognizable login, challenge, and paywall pages fail
     * with an explanation instead of being spoken as UI), then the final
     * page's eligible declarative cleanup, then the same Readability-or-
     * fallback extraction as [fetch]. [siteRules] supplies the cleanup
     * match only; the browser sends its normal UA, never rule headers.
     * Other NYT subdomains take the ordinary path (never the NYT UA).
     *
     * @throws IOException with a user-readable message on any failure.
     */
    fun extractRenderedHtml(
        html: String,
        finalUrl: String,
        siteRules: SiteReadingSnapshot? = null,
        siteUsage: SiteUsage? = null,
    ): Document {
        try {
            // Byte budget, same 5 MiB definition as the DOM snapshot: cheap
            // char check first (each char is at least one UTF-8 byte), then
            // an exact UTF-8 measurement for multibyte markup.
            if (html.length > MAX_BYTES) {
                throw FetchException(
                    "Read page failed: the rendered page is too large " +
                        "(${html.length} chars, over 5 MB).",
                )
            }
            val byteSize = html.toByteArray(Charsets.UTF_8).size
            if (byteSize > MAX_BYTES) {
                throw FetchException(
                    "Read page failed: the rendered page is too large " +
                        "($byteSize bytes, over 5 MB).",
                )
            }
            if (!SiteBrowserPolicy.allowsExtraction(finalUrl)) {
                throw FetchException(
                    "Read page failed: ${SiteBrowserPolicy.blockReason(finalUrl)}: $finalUrl",
                )
            }
            val doc = Jsoup.parse(html, finalUrl)
            val host = hostOf(finalUrl)
            if (NytArticle.isNytHost(finalUrl)) {
                NytArticle.requireReadable(doc, finalUrl)
                stripNonProse(doc)
                val body = NytArticle.extractBody(doc)
                return Document(cleanTitle(body.rawTitle, host), body.html, SourceFormat.HTML, null)
            }
            siteUsage?.finalHost = host
            // Guarded before cleanup could erase the evidence, like native —
            // but for unknown hosts too: a rendered script-heavy page is
            // often an app shell or wall, never article prose to speak.
            SiteArticleGuard.blockReason(doc, finalUrl, host)?.let { throw FetchException(it) }
            val siteMatch = if (siteRules != null && SiteRequests.isEligibleUrl(finalUrl)) {
                siteRules.catalog.match(host)
            } else {
                null
            }
            if (siteMatch != null && siteMatch.rule.html.cleanup.isNotEmpty()) {
                val report = applySiteCleanup(doc, siteMatch.rule.html.cleanup, host)
                if (report.applied()) {
                    siteUsage?.cleanupApplied = true
                    if (siteMatch.rule.status == RuleStatus.PARTIAL) siteUsage?.partialApplied = true
                }
            }
            stripNonProse(doc)
            return readabilityDocument(doc, finalUrl, host, "Read page failed")
        } catch (e: FetchException) {
            throw e
        } catch (e: Exception) {
            throw FetchException(
                "Read page failed: ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
    }

    private fun fetchInner(url: String): Document {
        // The post page itself is chrome (avatar, handle, timestamp, view
        // count), so posts go through oEmbed instead of Readability.
        normalizePostUrl(url)?.let { return fetchXPost(it) }
        val (finalUrl, conn) = openFollowingRedirects(url)
        conn.use {
            if (it.statusCode !in 200..299) {
                val detail = if (it.statusMessage.isBlank()) "" else " ${it.statusMessage}"
                throw FetchException("Couldn't open $url: HTTP ${it.statusCode}$detail")
            }
            val contentType = it.header("Content-Type") ?: ""
            val baseType = contentType.substringBefore(";").trim().lowercase(Locale.US)
            val bytes = readBounded(it.input(), url)
            if (finalUrl.pathEndsWithMd()) {
                val text = decode(bytes, contentType, htmlForMeta = null)
                return Document(titleFor(finalUrl), text, SourceFormat.MARKDOWN, null)
            }
            when (baseType) {
                "text/html", "application/xhtml+xml", "" -> return extractArticle(bytes, contentType, url, finalUrl)
                "text/plain" -> {
                    val text = decode(bytes, contentType, htmlForMeta = null)
                    return Document(titleFor(finalUrl), text, SourceFormat.PLAIN, null)
                }
                "text/markdown", "text/x-markdown" -> {
                    val text = decode(bytes, contentType, htmlForMeta = null)
                    return Document(titleFor(finalUrl), text, SourceFormat.MARKDOWN, null)
                }
                "application/pdf" -> throw FetchException("PDFs aren't supported yet: $url")
                else -> throw FetchException("Can't read this kind of page ($baseType): $url")
            }
        }
    }

    /**
     * Reads one X/Twitter post via the oEmbed endpoint. Every failure names
     * the post failure; there is no fallback to the page chrome.
     */
    private fun fetchXPost(normalized: String): Document {
        val oembedUrl = "$OEMBED_BASE?url=${urlEncode(normalized)}&omit_script=1&dnt=true"
        val bytes = try {
            downloadOembed(oembedUrl)
        } catch (e: FetchException) {
            throw e.toXPostError(oembedUrl)
        } catch (e: Exception) {
            throw FetchException(X_POST_ERROR_PREFIX + "${e.javaClass.name}: ${e.message}", e)
        }
        return parseOembed(bytes)
    }

    /** Same opener, timeouts, redirects and size cap as pages; HTTP errors are X-post errors. */
    private fun downloadOembed(oembedUrl: String): ByteArray {
        val (_, conn) = openFollowingRedirects(oembedUrl)
        conn.use {
            if (it.statusCode !in 200..299) {
                val detail = if (it.statusMessage.isBlank()) "" else " ${it.statusMessage}"
                throw FetchException(X_POST_ERROR_PREFIX + "HTTP ${it.statusCode}$detail")
            }
            return readBounded(it.input(), oembedUrl)
        }
    }

    /**
     * Rewrites helper errors (`Couldn't open <oembed>: <reason>`, oversize
     * page) as X-post errors. Already-prefixed errors pass through untouched.
     */
    private fun FetchException.toXPostError(oembedUrl: String): FetchException {
        val msg = message.orEmpty()
        if (msg.startsWith(X_POST_ERROR_PREFIX)) return this
        val reason = msg.removePrefix("Couldn't open $oembedUrl: ")
        return FetchException(X_POST_ERROR_PREFIX + reason, this)
    }

    /**
     * Parses the oEmbed JSON, keeping only the `blockquote > p` elements (the
     * `— Name (@handle) date` footer sits outside the `<p>`, so it's dropped).
     * `<br>` survives as a paragraph break through [SourceFormat.HTML].
     */
    private fun parseOembed(bytes: ByteArray): Document {
        // JSON is UTF-8 by spec (RFC 8259 section 8.1).
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw FetchException(X_POST_ERROR_PREFIX + "${e.javaClass.name}: ${e.message}", e)
        }
        val author: String
        val html: String
        try {
            author = json.getString("author_name")
            html = json.getString("html")
        } catch (e: Exception) {
            throw FetchException(X_POST_ERROR_PREFIX + "${e.javaClass.name}: ${e.message}", e)
        }
        val paras = Jsoup.parseBodyFragment(html).select("blockquote > p")
        if (paras.isEmpty()) {
            throw FetchException(X_POST_ERROR_PREFIX + "oEmbed response has no post text")
        }
        val text = paras.joinToString("") { it.outerHtml() }
        return Document("Post by $author", text, SourceFormat.HTML, null)
    }

    // The String overload: URLEncoder.encode(String, Charset) needs API 33 (minSdk 26).
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Opens [url], following up to [MAX_REDIRECTS] redirects by hand. Returns the final URL. */
    private fun openFollowingRedirects(url: String): Pair<String, FetchConnection> {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = urlOpener(current)
            val code = conn.statusCode
            if (code !in REDIRECT_CODES) return current to conn
            val location = conn.header("Location")
            conn.close()
            if (location.isNullOrBlank()) {
                throw FetchException(
                    "Couldn't open $url: HTTP $code redirect with no Location header",
                )
            }
            current = URI(current).resolve(location).toString()
        }
        throw FetchException("Couldn't open $url: too many redirects (over $MAX_REDIRECTS)")
    }

    /** Reads the body, failing past [MAX_BYTES]. */
    private fun readBounded(input: InputStream, url: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) {
                    throw FetchException("Page is too large to read (over 5 MB): $url")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun extractArticle(
        bytes: ByteArray,
        contentType: String,
        url: String,
        finalUrl: String,
    ): Document {
        val probe = Jsoup.parse(String(bytes, Charsets.UTF_8), finalUrl)
        val html = decode(bytes, contentType, probe)
        val doc = Jsoup.parse(html, finalUrl)
        val host = hostOf(finalUrl)
        val nytHost = NytArticle.isNytHost(finalUrl)
        // Only the final response URL's matched rule applies, and only on
        // eligible fetches (same https/no-userinfo/port/domain boundary as
        // request routing): never the original URL's rule after a redirect.
        val siteMatch = if (siteRules != null && SiteRequests.isEligibleUrl(finalUrl)) {
            siteRules.catalog.match(host)
        } else {
            null
        }
        siteUsage?.finalHost = host
        // Blocking evidence is inspected before any cleanup could strip it;
        // bodies are extracted after cleanup, like others. NYT keeps its
        // exact guard and extractor; other matched hosts get the narrow
        // shared guard, and unknown hosts keep the ordinary fallback.
        if (nytHost) {
            NytArticle.requireReadable(doc, finalUrl)
        } else if (siteMatch != null) {
            SiteArticleGuard.blockReason(doc, finalUrl, host)?.let { throw FetchException(it) }
        }
        if (siteMatch != null && siteMatch.rule.html.cleanup.isNotEmpty()) {
            val report = applySiteCleanup(doc, siteMatch.rule.html.cleanup, host)
            if (report.applied()) {
                siteUsage?.cleanupApplied = true
                if (siteMatch.rule.status == RuleStatus.PARTIAL) siteUsage?.partialApplied = true
            }
        }
        stripNonProse(doc)
        if (nytHost) {
            val body = NytArticle.extractBody(doc)
            return Document(cleanTitle(body.rawTitle, host), body.html, SourceFormat.HTML, null)
        }
        return readabilityDocument(doc, finalUrl, host, "Couldn't open $url")
    }

    /**
     * Runs Readability over the cleaned [doc] (guard and site cleanup have
     * already run); falls back to the whole page with [FALLBACK_NOTICE] when
     * no main article is found. Shared by [fetch] and [extractRenderedHtml]
     * so both paths keep the same titles and fallback behavior; only the
     * [failurePrefix] differs (`Couldn't open <url>` vs `Read page failed`).
     */
    private fun readabilityDocument(
        doc: org.jsoup.nodes.Document,
        finalUrl: String,
        host: String,
        failurePrefix: String,
    ): Document {
        val article = try {
            // Readability4J mutates the Document it is given, so it gets a
            // clone; the fallback path below reads the cleaned original.
            Readability4J(finalUrl, doc.clone()).parse()
        } catch (e: Exception) {
            throw FetchException("$failurePrefix: ${e.javaClass.name}: ${e.message}", e)
        }
        val articleText = article?.textContent.orEmpty()
        val articleHtml = article?.content.orEmpty()
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        if (article == null || articleHtml.isBlank() || articleText.length < MIN_ARTICLE_CHARS) {
            onWarn(FALLBACK_NOTICE)
            val raw = ogTitle.ifBlank { doc.title() }.ifBlank { host }
            return Document(cleanTitle(raw, host), doc.body().html(), SourceFormat.HTML, FALLBACK_NOTICE)
        }
        val raw = ogTitle.ifBlank { article.title.orEmpty().ifBlank { doc.title() } }.ifBlank { host }
        return Document(cleanTitle(raw, host), articleHtml, SourceFormat.HTML, null)
    }

    /**
     * Runs the final page's declarative cleanup and returns its report. A
     * selector failure fails the read with the operation, selector, and real
     * error: the uncleaned page may still show blocked UI that must not be
     * spoken as an article. Internal for the deterministic failing-document
     * seam test; production callers pass the parsed page.
     */
    internal fun applySiteCleanup(
        doc: org.jsoup.nodes.Document,
        ops: List<app.readly.input.sites.CleanupOp>,
        host: String,
    ): SiteCleanup.Report {
        val report = SiteCleanup.apply(doc, ops)
        report.errors.firstOrNull()?.let { failure ->
            val detail = failure.message ?: "(no details)"
            throw FetchException(
                "Couldn't clean this article from $host: cleanup selector " +
                    "\"${failure.selector}\" failed: ${failure.errorClass}: $detail",
            )
        }
        return report
    }

    /**
     * Removes reference-style clutter before Readability runs: Readability4J
     * drops `class` attributes from its output, so class selectors (and section
     * headings it keeps as plain text) can only be stripped here.
     */
    private fun stripNonProse(doc: org.jsoup.nodes.Document) {
        doc.select(TextSanitizer.NON_PROSE_SELECTORS).remove()
        doc.body()?.select("h1, h2, h3, h4, h5, h6")
            ?.filter(::isTailSectionHeading)
            ?.forEach(::removeSection)
    }

    /** Trailing sections that list sources instead of telling the story. */
    private val tailSectionNames = setOf(
        "references", "notes", "citations", "further reading", "external links", "see also",
    )

    /**
     * True when the heading's own or inner `id` (Wikipedia puts it on a span,
     * e.g. `id="See_also"`) or its text names a trailing section. Runs after
     * the selector strip above, so edit links are already gone from the text.
     */
    private fun isTailSectionHeading(heading: org.jsoup.nodes.Element): Boolean {
        val candidates = mutableListOf(heading.id(), heading.text())
        heading.select("[id]").forEach { candidates.add(it.id()) }
        return candidates.any {
            it.replace('_', ' ').trim().lowercase(Locale.US) in tailSectionNames
        }
    }

    /** Removes [heading] and its following siblings up to the next heading of the same or higher level. */
    private fun removeSection(heading: org.jsoup.nodes.Element) {
        val level = heading.tagName().substring(1).toInt()
        var sibling = heading.nextElementSibling()
        heading.remove()
        while (sibling != null) {
            val next = sibling.nextElementSibling()
            if (sibling.isSameOrHigherHeading(level)) break
            sibling.remove()
            sibling = next
        }
    }

    private fun org.jsoup.nodes.Element.isSameOrHigherHeading(level: Int): Boolean {
        val tag = tagName().lowercase(Locale.US)
        return tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' && (tag[1] - '0') <= level
    }

    /**
     * Shortens a page title for speech and display: strips one trailing site
     * suffix (` - `, ` | `, ` · `) when at least 10 chars remain, then caps at
     * [MAX_TITLE_CHARS] on a word boundary with `…`.
     */
    private fun cleanTitle(raw: String, host: String): String {
        var title = raw.trim()
        if (title.isBlank() || title == host) return host
        val cuts = listOf(" - ", " | ", " · ").map { title.lastIndexOf(it) }
        val cut = cuts.maxOrNull() ?: -1
        if (cut > 0) {
            val left = title.substring(0, cut).trim()
            if (left.length >= 10) title = left
        }
        if (title.length > MAX_TITLE_CHARS) {
            val head = title.substring(0, MAX_TITLE_CHARS - 1)
            val space = head.lastIndexOf(' ')
            title = (if (space > 0) head.substring(0, space) else head).trimEnd() + "…"
        }
        return title.ifBlank { host }
    }

    /**
     * Decodes [bytes]: charset from `Content-Type`, else `<meta charset>` ([htmlForMeta] is
     * the UTF-8 probe parse, only built for HTML), else UTF-8.
     */
    private fun decode(bytes: ByteArray, contentType: String, htmlForMeta: org.jsoup.nodes.Document?): String {
        val charset = charsetFrom(contentType.substringAfter("charset=", "").substringBefore(";").trim())
            ?: htmlForMeta?.let(::metaCharset)?.let(::charsetFrom)
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun charsetFrom(name: String): Charset? {
        if (name.isBlank()) return null
        return try {
            Charset.forName(name.trim().removeSurrounding("\""))
        } catch (_: Exception) {
            null
        }
    }

    private fun metaCharset(doc: org.jsoup.nodes.Document): String? {
        doc.selectFirst("meta[charset]")?.attr("charset")?.takeIf { it.isNotBlank() }?.let { return it }
        doc.select("meta[http-equiv]").forEach { meta ->
            if (meta.attr("http-equiv").equals("content-type", ignoreCase = true)) {
                meta.attr("content").substringAfter("charset=", "").substringBefore(";").trim()
                    .takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun titleFor(finalUrl: String): String = hostOf(finalUrl)

    private fun hostOf(finalUrl: String): String {
        return try {
            URI(finalUrl).host ?: finalUrl
        } catch (_: Exception) {
            finalUrl
        }
    }

    private fun String.pathEndsWithMd(): Boolean {
        val path = try {
            URI(this).path.orEmpty()
        } catch (_: Exception) {
            substringBefore("?").substringBefore("#")
        }
        return path.lowercase(Locale.US).endsWith(".md")
    }

    private class HttpFetchConnection(
        url: String,
        userAgent: String = USER_AGENT,
        headers: Map<String, String> = emptyMap(),
    ) : FetchConnection {
        private val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            // Redirects are followed by hand so http↔https crossings work too.
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            // Validated rule headers only (User-Agent, Referer, literal
            // extras); later values win, so a rule User-Agent replaces the
            // default above. Transport headers can never arrive here: the
            // importer and cache codec forbid them.
            for ((name, value) in headers) setRequestProperty(name, value)
        }

        override val statusCode: Int
            get() = conn.responseCode
        override val statusMessage: String
            get() = conn.responseMessage ?: ""
        override fun header(name: String): String? = conn.getHeaderField(name)
        override fun input(): InputStream = conn.inputStream
        override fun close() = conn.disconnect()
    }

    /** User-readable fetch failures; pass through [fetch] unwrapped. Visible to the NYT extractor. */
    internal class FetchException(message: String, cause: Throwable? = null) : IOException(message, cause)
}
