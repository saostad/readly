package app.readly.feeds

import app.readly.input.FetchConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** One article title found on a website. */
data class Headline(val title: String, val url: String, val published: String? = null)

/** Where a [HeadlineList] came from. */
enum class HeadlineSource {
    /** The address itself is an RSS/Atom feed. */
    FEED,

    /** A feed the page links to, or one at a common feed address next to it. */
    LINKED_FEED,

    /** Guessed from the page's links (no feed, or the site is set to page links). */
    PAGE_LINKS,
}

data class HeadlineList(
    val siteTitle: String,
    val headlines: List<Headline>,
    val source: HeadlineSource,
    /** The feed that was read, when [source] is a feed. */
    val feedUrl: String?,
    /** A user-visible note about how the list was made (e.g. guessed from links), or null. */
    val notice: String?,
)

/**
 * Finds the article titles on a news page or feed.
 *
 * In [FindMode.AUTO] it prefers a real feed, which carries clean titles: the address
 * itself when it is RSS/Atom, then a feed the page advertises
 * (`<link rel="alternate" type="application/rss+xml">`), then common feed addresses
 * under the page (`feed`, `rss`, `rss.xml`, …). Only when none of those work does it
 * fall back to scanning the page's links: navigation, header, footer and sidebars are
 * dropped, and each remaining same-site link is scored on whether it looks like an
 * article (a headline-length text, a heading around it, an article-like path). That
 * fallback is reported through [onWarn] with `fallback=true` and in the list's
 * [HeadlineList.notice].
 *
 * Pure JVM (jsoup), so it runs in unit tests with fake connections.
 */
class HeadlineFinder(
    private val urlOpener: (String) -> FetchConnection,
    private val onWarn: (String) -> Unit,
) {
    companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        const val MAX_REDIRECTS = 5

        /** Feed addresses tried under the page when it doesn't advertise one. */
        val COMMON_FEED_PATHS = listOf("feed", "rss", "rss.xml", "feed.xml", "atom.xml")

        /** How many advertised feeds are tried before giving up on them. */
        private const val MAX_ADVERTISED_FEEDS = 3

        const val PAGE_LINKS_NOTICE =
            "No news feed found, so these titles were picked from the page's links " +
                "and may include a few that aren't articles."

        // Link-scan heuristics: what a headline link looks like.
        private const val MIN_TITLE_WORDS = 4
        private const val MIN_TITLE_CHARS = 20
        private const val MAX_TITLE_CHARS = 300
        private const val MIN_SCORE = 2

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private val NON_CONTENT = listOf(
            "script", "style", "noscript", "template", "svg", "form", "nav", "aside",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=search]",
            "[aria-hidden=true]",
        ).joinToString(", ")

        /** Path parts that mark listing, account or utility pages rather than articles. */
        private val NON_ARTICLE_SEGMENTS = setOf(
            "tag", "tags", "category", "categories", "author", "authors", "topic", "topics",
            "login", "signin", "sign-in", "logout", "register", "signup", "sign-up",
            "subscribe", "subscription", "subscriptions", "account", "myaccount", "profile",
            "search", "about", "about-us", "contact", "contact-us", "privacy", "terms",
            "cookies", "help", "faq", "newsletter", "newsletters", "careers", "jobs",
            "advertise", "shop", "store", "cdn-cgi", "wp-login.php",
        )

        private val NON_ARTICLE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "svg", "pdf", "mp3", "mp4", "m4a", "zip",
            "xml", "rss", "json", "css", "js",
        )

        private val DATE_IN_PATH = Regex("/(19|20)\\d{2}/(0?[1-9]|1[0-2])(/|$)|/(19|20)\\d{2}-\\d{2}-\\d{2}")
        private val LONG_NUMBER = Regex("\\d{5,}")
        private val ARTICLE_SUFFIX = Regex("\\.(html?|ece|php|aspx?)$", RegexOption.IGNORE_CASE)
        private val WHITESPACE = Regex("\\s+")
    }

    private class Page(val finalUrl: String, val contentType: String, val bytes: ByteArray)

    /**
     * Returns up to [maxArticles] titles for [url]. Throws [IOException] with a
     * user-readable message when the page can't be loaded or has no article links.
     */
    fun find(url: String, mode: FindMode, maxArticles: Int): HeadlineList {
        val host = FeedSites.hostOf(url)
        val page = download(url)
        if (looksLikeFeed(page)) {
            val feed = parseFeed(page)
            if (feed.headlines.isNotEmpty()) {
                return feed.copy(
                    headlines = feed.headlines.take(maxArticles),
                    source = HeadlineSource.FEED,
                )
            }
            throw IOException("The feed at $host has no articles right now.")
        }
        val html = Jsoup.parse(decode(page.bytes, page.contentType), page.finalUrl)
        val pageTitle = html.title().trim().ifBlank { host }
        if (mode == FindMode.AUTO) {
            val reasons = mutableListOf<String>()
            val feed = findLinkedFeed(html, page.finalUrl, reasons)
            if (feed != null) {
                return feed.copy(
                    siteTitle = feed.siteTitle.ifBlank { pageTitle },
                    headlines = feed.headlines.take(maxArticles),
                    source = HeadlineSource.LINKED_FEED,
                )
            }
            onWarn(
                "fallback=true attempted=feed host=$host reason=\"${reasons.joinToString("; ")}\" " +
                    "taken=page-links",
            )
        }
        val links = scanLinks(html, page.finalUrl)
        if (links.isEmpty()) {
            throw IOException(
                "Couldn't find any article links on $host. Try adding the site's RSS feed address instead.",
            )
        }
        return HeadlineList(
            siteTitle = pageTitle,
            headlines = links.take(maxArticles),
            source = HeadlineSource.PAGE_LINKS,
            feedUrl = null,
            // A site set to page links on purpose isn't a fallback, so it gets no notice.
            notice = if (mode == FindMode.AUTO) PAGE_LINKS_NOTICE else null,
        )
    }

    // ------------------------------------------------------------------ feeds ---

    private fun findLinkedFeed(html: Document, pageUrl: String, reasons: MutableList<String>): HeadlineList? {
        val advertised = html.select("link[rel~=(?i)alternate][type~=(?i)(rss|atom)\\+xml][href]")
            .map { it.absUrl("href") }
            .filter { it.startsWith("http") }
            .distinct()
            .take(MAX_ADVERTISED_FEEDS)
        if (advertised.isEmpty()) reasons += "page advertises no feed"
        for (feedUrl in advertised) {
            tryFeed(feedUrl, reasons)?.let { return it }
        }
        if (advertised.isNotEmpty()) return null
        val base = pageUrl.substringBefore('#').substringBefore('?').let { if (it.endsWith('/')) it else "$it/" }
        for (path in COMMON_FEED_PATHS) {
            tryFeed(base + path, reasons, quiet = true)?.let { return it }
        }
        reasons += "no feed at ${COMMON_FEED_PATHS.joinToString(", ") { "./$it" }}"
        return null
    }

    private fun tryFeed(feedUrl: String, reasons: MutableList<String>, quiet: Boolean = false): HeadlineList? {
        val page = try {
            download(feedUrl)
        } catch (e: IOException) {
            if (!quiet) reasons += "feed $feedUrl failed: ${e.message}"
            return null
        }
        if (!looksLikeFeed(page)) {
            if (!quiet) reasons += "feed $feedUrl is not RSS or Atom"
            return null
        }
        val feed = try {
            parseFeed(page)
        } catch (e: Exception) {
            reasons += "feed $feedUrl couldn't be parsed: ${e.javaClass.simpleName}"
            return null
        }
        if (feed.headlines.isEmpty()) {
            reasons += "feed $feedUrl has no items"
            return null
        }
        return feed
    }

    private fun looksLikeFeed(page: Page): Boolean {
        val type = page.contentType.lowercase(Locale.US)
        if ("html" in type) return false
        val head = String(page.bytes, 0, minOf(page.bytes.size, 2048), Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val hasFeedRoot = Regex("<(rss|feed|rdf:RDF)[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(head)
        return hasFeedRoot && (head.startsWith("<") || "xml" in type)
    }

    private fun parseFeed(page: Page): HeadlineList {
        val xml = Jsoup.parse(decode(page.bytes, page.contentType), page.finalUrl, Parser.xmlParser())
        val atomEntries = xml.select("feed > entry")
        val items = mutableListOf<Headline>()
        val siteTitle: String
        if (atomEntries.isNotEmpty() || xml.selectFirst("feed") != null) {
            siteTitle = plain(xml.selectFirst("feed > title")?.text())
            for (entry in atomEntries) {
                val link = entry.select("link").firstOrNull { link ->
                    link.attr("rel").ifBlank { "alternate" } == "alternate"
                }?.attr("href").orEmpty()
                val date = entry.selectFirst("published")?.text() ?: entry.selectFirst("updated")?.text()
                addItem(items, entry.selectFirst("title")?.text(), link, date, page.finalUrl)
            }
        } else {
            siteTitle = plain(xml.selectFirst("channel > title")?.text())
            for (item in xml.select("item")) {
                var link = item.children().firstOrNull { it.tagName() == "link" }?.text().orEmpty()
                if (link.isBlank()) {
                    link = item.selectFirst("guid")?.text()?.takeIf { it.startsWith("http") }.orEmpty()
                }
                val date = item.selectFirst("pubDate")?.text()
                    ?: item.children().firstOrNull { it.tagName() == "dc:date" }?.text()
                addItem(items, item.selectFirst("title")?.text(), link, date, page.finalUrl)
            }
        }
        return HeadlineList(siteTitle, items.distinctBy { it.url }, HeadlineSource.FEED, page.finalUrl, null)
    }

    private fun addItem(items: MutableList<Headline>, rawTitle: String?, rawLink: String, date: String?, base: String) {
        val title = plain(rawTitle)
        if (title.isBlank()) return
        val url = resolve(base, rawLink.trim()) ?: return
        items += Headline(title, url, date?.trim()?.ifBlank { null })
    }

    /** Feed titles may carry escaped HTML; keep only the readable text. */
    private fun plain(raw: String?): String =
        if (raw.isNullOrBlank()) "" else Jsoup.parse(raw).text().replace(WHITESPACE, " ").trim()

    // ------------------------------------------------------------- page links ---

    private data class Candidate(val headline: Headline, val score: Int, val order: Int)

    internal fun scanLinks(html: Document, pageUrl: String): List<Headline> {
        val body = html.body()?.clone() ?: return emptyList()
        body.select(NON_CONTENT).remove()
        // Site-wide headers and footers are chrome, but an article card's own
        // <header>/<footer> (inside <article>) holds its headline.
        body.select("header, footer").filter { it.parents().none { p -> p.tagName() == "article" } }
            .forEach { it.remove() }
        val pageHost = siteHost(pageUrl)
        val pageKey = urlKey(pageUrl)
        val best = LinkedHashMap<String, Candidate>()
        var order = 0
        for (a in body.select("a[href]")) {
            val url = resolve(pageUrl, a.attr("href")) ?: continue
            val key = urlKey(url)
            if (key == pageKey) continue
            if (!sameSite(pageHost, siteHost(url))) continue
            val path = try {
                URI(url).path.orEmpty()
            } catch (_: Exception) {
                continue
            }
            if (!isArticlePath(path)) continue
            val title = linkTitle(a)
            if (!isHeadlineText(title)) continue
            val score = score(a, path)
            if (score < MIN_SCORE) continue
            val candidate = Candidate(Headline(title, url), score, order++)
            val previous = best[key]
            if (previous == null) {
                best[key] = candidate
            } else if (score > previous.score ||
                (score == previous.score && title.length > previous.headline.title.length)
            ) {
                // Keep the first position on the page, with the better title.
                best[key] = candidate.copy(order = previous.order)
            }
        }
        return best.values.sortedBy { it.order }.map { it.headline }
    }

    private fun linkTitle(a: Element): String {
        val heading = a.selectFirst("h1, h2, h3, h4, h5, h6")
        val text = (heading?.text() ?: a.text()).replace(WHITESPACE, " ").trim()
        if (text.isNotEmpty()) return text
        return a.attr("title").ifBlank { a.attr("aria-label") }.replace(WHITESPACE, " ").trim()
    }

    private fun isHeadlineText(text: String): Boolean {
        if (text.length < MIN_TITLE_CHARS || text.length > MAX_TITLE_CHARS) return false
        return text.split(' ').count { it.isNotBlank() } >= MIN_TITLE_WORDS
    }

    private fun isArticlePath(path: String): Boolean {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (segments.any { it.lowercase(Locale.US) in NON_ARTICLE_SEGMENTS }) return false
        val ext = segments.last().substringAfterLast('.', "").lowercase(Locale.US)
        return ext !in NON_ARTICLE_EXTENSIONS
    }

    private fun score(a: Element, path: String): Int {
        var score = 0
        val headingNear = a.selectFirst("h1, h2, h3, h4") != null ||
            a.parents().take(3).any { it.tagName() in setOf("h1", "h2", "h3", "h4") }
        if (headingNear) score += 2
        if (a.parents().any { it.tagName() == "article" }) score += 1
        val segments = path.split('/').filter { it.isNotEmpty() }
        val last = segments.lastOrNull().orEmpty()
        val slugLike = last.count { it == '-' } >= 2 && last.length >= 12
        if (slugLike || DATE_IN_PATH.containsMatchIn(path) || LONG_NUMBER.containsMatchIn(path) ||
            ARTICLE_SUFFIX.containsMatchIn(last)
        ) {
            score += 2
        }
        if (segments.size >= 2) score += 1
        return score
    }

    private fun siteHost(url: String): String = try {
        URI(url).host.orEmpty().lowercase(Locale.US).removePrefix("www.")
    } catch (_: Exception) {
        ""
    }

    /** edition.cnn.com belongs with cnn.com; other domains don't. */
    private fun sameSite(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && (a == b || a.endsWith(".$b") || b.endsWith(".$a"))

    /** Compares addresses without fragments, tracking parameters or a trailing slash. */
    private fun urlKey(url: String): String {
        val noFragment = url.substringBefore('#')
        val base = noFragment.substringBefore('?').trimEnd('/').lowercase(Locale.US)
            .replaceFirst("://www.", "://")
        val query = noFragment.substringAfter('?', "").split('&')
            .filter { it.isNotBlank() && !it.startsWith("utm_") }
            .sorted()
            .joinToString("&")
        return if (query.isEmpty()) base else "$base?$query"
    }

    // ------------------------------------------------------------ downloading ---

    private fun resolve(base: String, href: String): String? {
        if (href.isBlank() || href.startsWith("#")) return null
        val resolved = try {
            URI(base).resolve(href.replace(" ", "%20")).toString()
        } catch (_: Exception) {
            return null
        }
        val scheme = resolved.substringBefore(':').lowercase(Locale.US)
        return if (scheme == "http" || scheme == "https") resolved.substringBefore('#') else null
    }

    private fun download(url: String): Page {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = try {
                urlOpener(current)
            } catch (e: IOException) {
                throw IOException("Couldn't open ${FeedSites.hostOf(current)}: ${e.message}", e)
            }
            try {
                val code = conn.statusCode
                if (code in REDIRECT_CODES) {
                    val location = conn.header("Location")
                    if (location.isNullOrBlank()) {
                        throw IOException("Couldn't open $current: HTTP $code redirect with no Location header")
                    }
                    current = URI(current).resolve(location.trim()).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    throw IOException("Couldn't open ${FeedSites.hostOf(current)}: HTTP $code ${conn.statusMessage}".trim())
                }
                return Page(current, conn.header("Content-Type").orEmpty(), readBounded(conn, current))
            } finally {
                conn.close()
            }
        }
        throw IOException("Couldn't open $url: too many redirects (over $MAX_REDIRECTS)")
    }

    private fun readBounded(conn: FetchConnection, url: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        conn.input().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IOException("Page is too large to read (over 5 MB): $url")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, contentType: String): String {
        val declared = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(contentType)
            ?.groupValues?.get(1)?.trim('"', '\'')
        val charset = declared?.let {
            try {
                Charset.forName(it)
            } catch (_: Exception) {
                null
            }
        } ?: Charsets.UTF_8
        return String(bytes, charset)
    }
}
