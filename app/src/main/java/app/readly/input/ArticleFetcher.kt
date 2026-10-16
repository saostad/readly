package app.readly.input

import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import net.dankito.readability4j.Readability4J
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
 */
class ArticleFetcher(
    private val urlOpener: (String) -> FetchConnection,
    private val onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
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

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
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

    private fun fetchInner(url: String): Document {
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
        stripNonProse(doc)
        val host = hostOf(finalUrl)
        val article = try {
            // Readability4J mutates the Document it is given, so it gets a
            // clone; the fallback path below reads the cleaned original.
            Readability4J(finalUrl, doc.clone()).parse()
        } catch (e: Exception) {
            throw FetchException("Couldn't open $url: ${e.javaClass.name}: ${e.message}", e)
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

    private class HttpFetchConnection(url: String) : FetchConnection {
        private val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            // Redirects are followed by hand so http↔https crossings work too.
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        }

        override val statusCode: Int
            get() = conn.responseCode
        override val statusMessage: String
            get() = conn.responseMessage ?: ""
        override fun header(name: String): String? = conn.getHeaderField(name)
        override fun input(): InputStream = conn.inputStream
        override fun close() = conn.disconnect()
    }

    private class FetchException(message: String, cause: Throwable? = null) : IOException(message, cause)
}
