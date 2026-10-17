package app.readly.input.nyt

import app.readly.input.ArticleFetcher
import java.net.URI
import java.util.Locale
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * New York Times article extraction and incomplete-page guard. Pure JVM over
 * jsoup (already used by [ArticleFetcher]); no network, no scripts.
 *
 * [requireReadable] MUST run before [ArticleFetcher]'s generic cleanup: the
 * blocking evidence it looks for (gateway prompts, challenge pages) could
 * otherwise be stripped before it is recognized, and a paywall page would
 * then be spoken as an article or whole-page fallback. Recognizable blocks
 * fail with `"Couldn't read this New York Times article: <reason>"` instead.
 *
 * What this can and cannot do: it detects visible paywall prompts,
 * access-denied pages, human-verification challenges, login walls, the
 * homepage, and missing article bodies in the served HTML. It cannot pass a
 * challenge or subscription check (those need a browser session or a paid
 * account), and it cannot prove completeness — a teaser with no gateway
 * markers is indistinguishable from a short article, so undetectable
 * truncation may still be read. No claim of guaranteed completeness is made
 * from these heuristics.
 */
object NytArticle {
    const val ERROR_PREFIX = "Couldn't read this New York Times article: "

    /** Longest page-supplied snippet quoted inside a failure message. */
    const val MAX_QUOTED_CHARS = 140

    /** True when [finalUrl] (after redirects) is a main NYT article host. */
    fun isNytHost(finalUrl: String): Boolean {
        val host = try {
            URI(finalUrl).host
        } catch (_: Exception) {
            return false
        }
        return NytPolicy.isSupportedHost(host)
    }

    /**
     * Inspects the raw parsed page (before generic cleanup) and throws when
     * it is recognizably not a readable article: paywall/gateway prompt,
     * access-denied, human-verification challenge, or login wall. A
     * subscription metadata flag (`isAccessibleForFree`) alone never blocks,
     * and neither does mere article length.
     */
    fun requireReadable(doc: Document, finalUrl: String) {
        gatewayReason(doc)?.let { throw ArticleFetcher.FetchException(ERROR_PREFIX + it) }
        // Paywall prompts fail even beside a partial body (teaser + gate);
        // the other walls only count when no specific article body exists,
        // so an article that merely mentions them still reads.
        if (!hasSpecificBody(doc)) {
            challengeReason(doc)?.let { throw ArticleFetcher.FetchException(ERROR_PREFIX + it) }
            deniedReason(doc)?.let { throw ArticleFetcher.FetchException(ERROR_PREFIX + it) }
            loginReason(doc, finalUrl)?.let { throw ArticleFetcher.FetchException(ERROR_PREFIX + it) }
        }
        if (isFrontPage(finalUrl)) {
            throw ArticleFetcher.FetchException(
                ERROR_PREFIX + "this looks like the homepage, not an article. " +
                    "Open an article link to read it aloud.",
            )
        }
    }

    /** Article body plus its uncleaned title; the caller applies title cleanup. */
    data class NytBody(val rawTitle: String, val html: String)

    /**
     * Selects the main article body (after generic cleanup) and returns its
     * paragraphs. Chrome inside the body (asides, promos, buttons, forms) is
     * removed. Throws when no main body can be established.
     */
    fun extractBody(doc: Document): NytBody {
        val target = doc.selectFirst(SPECIFIC_BODY)
            ?: singleArticle(doc)
            ?: throw ArticleFetcher.FetchException(ERROR_PREFIX + "no readable article body was found.")
        target.select(CHROME_WITHIN_BODY).remove()
        val selected = target.select("p, h2, h3, h4, li")
        // Speak non-overlapping blocks only: a block nested inside another
        // selected block (e.g. li > p) would otherwise repeat its sentence.
        val selectedSet = selected.toSet()
        val blocks = selected.filter { el ->
            generateSequence(el.parent()) { it.parent() }
                .takeWhile { it != target }
                .none { it in selectedSet }
        }
        if (blocks.none { it.text().isNotBlank() }) {
            throw ArticleFetcher.FetchException(ERROR_PREFIX + "no readable article body was found.")
        }
        val html = blocks.joinToString("") { it.outerHtml() }
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        return NytBody(ogTitle.ifBlank { doc.title() }, html)
    }

    private const val SPECIFIC_BODY = "section[name=\"articleBody\"], [data-testid=\"article-body\"]"

    private const val CHROME_WITHIN_BODY =
        "aside, button, form, script, style, nav, #gateway-content, " +
            "[data-testid=\"gateway-content\"], [data-testid=\"gateway-body\"], " +
            "[class*=promo], [id*=promo]"

    /** `article` fallback only when unambiguous; listings have many. */
    private fun singleArticle(doc: Document): Element? {
        val articles = doc.select("article")
        return if (articles.size == 1) articles.first() else null
    }

    private fun hasSpecificBody(doc: Document): Boolean = doc.selectFirst(SPECIFIC_BODY) != null

    private fun isFrontPage(finalUrl: String): Boolean {
        val path = try {
            URI(finalUrl).path.orEmpty()
        } catch (_: Exception) {
            return false
        }
        return path.isEmpty() || path == "/"
    }

    // ------------------------------------------------------------------
    // Paywall / gateway
    // ------------------------------------------------------------------

    private val GATEWAY_CONTAINERS =
        "#gateway-content, [data-testid=\"gateway-content\"], [data-testid=\"gateway-body\"]"

    private val GATEWAY_PHRASES = listOf(
        "subscribe to continue reading",
        "you've reached your limit",
        "you have reached your limit",
        "create a free account to continue reading",
    )

    private fun gatewayReason(doc: Document): String? {
        val gate = doc.select(GATEWAY_CONTAINERS).firstOrNull { isShown(it) }
        // Hidden gate markup is not a visible prompt: scrub all gate
        // containers (shown or not) before scanning for phrases, so only
        // visible copy can match. Article prose is scrubbed too: a genuine
        // article may quote the prompt copy, and a real teaser still fails
        // on its visible gate container or on the prompt outside the body.
        val scrubbed = doc.body()?.clone()
        scrubbed?.select(GATEWAY_CONTAINERS)?.remove()
        scrubbed?.select(SPECIFIC_BODY)?.remove()
        scrubbed?.select("article")?.takeIf { it.size == 1 }?.remove()
        val lower = scrubbed?.text().orEmpty().lowercase(Locale.US)
        val phrase = GATEWAY_PHRASES.firstOrNull { it in lower }
        if (gate == null && phrase == null) return null
        val base = "the page shows a subscription prompt instead of the full article"
        val headline = gate?.selectFirst("h1, h2, h3, p")?.text()?.trim().orEmpty()
        val quote = headline.ifBlank { phrase.orEmpty() }.take(MAX_QUOTED_CHARS)
        return if (quote.isBlank()) "$base." else "$base: \"$quote\"."
    }

    /**
     * Skips gate markup the page itself hides (not a visible prompt). The
     * hiding markers may sit on any ancestor (e.g. a hidden wrapping div),
     * so the whole chain is checked.
     */
    private fun isShown(el: Element): Boolean {
        var node: Element? = el
        while (node != null) {
            if (node.hasAttr("hidden")) return false
            if (node.attr("aria-hidden").equals("true", ignoreCase = true)) return false
            val style = node.attr("style").lowercase(Locale.US).replace(" ", "")
            if ("display:none" in style || "visibility:hidden" in style) return false
            node = node.parent()
        }
        return true
    }

    // ------------------------------------------------------------------
    // Human-verification challenge
    // ------------------------------------------------------------------

    private fun challengeReason(doc: Document): String? {
        if (doc.selectFirst("script[src*=captcha-delivery]") == null &&
            doc.selectFirst("#cf-challenge-running, #challenge-running, .cf-challenge") == null
        ) {
            val title = doc.title().lowercase(Locale.US)
            val body = doc.body()?.text().orEmpty().lowercase(Locale.US)
            val titled = "just a moment" in title
            val messaged = "please enable js and disable any ad blocker" in body ||
                "verify you are human" in body ||
                "verify you are a human" in body
            if (!titled && !messaged) return null
        }
        val base = "the page shows a human-verification challenge this reader cannot solve"
        val title = doc.title().trim().take(MAX_QUOTED_CHARS)
        return if (title.isBlank()) "$base." else "$base: \"$title\"."
    }

    // ------------------------------------------------------------------
    // Access denied
    // ------------------------------------------------------------------

    private val REFERENCE_RE = Regex("Reference\\s*#[0-9a-fA-F._-]+")

    private fun deniedReason(doc: Document): String? {
        val title = doc.title()
        if ("access denied" in title.lowercase(Locale.US)) {
            return "access was denied: \"${title.trim().take(MAX_QUOTED_CHARS)}\"."
        }
        val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (heading.equals("Access Denied", ignoreCase = true)) {
            return "access was denied."
        }
        // Classic edge-denial pages pair the phrase with a reference token;
        // the phrase alone could be article prose, so require both.
        val body = doc.body()?.text().orEmpty()
        if ("access denied" in body.lowercase(Locale.US)) {
            val ref = REFERENCE_RE.find(body)?.value
            if (ref != null) return "access was denied ($ref)."
        }
        return null
    }

    // ------------------------------------------------------------------
    // Login wall
    // ------------------------------------------------------------------

    private fun loginReason(doc: Document, finalUrl: String): String? {
        val path = try {
            URI(finalUrl).path.orEmpty().lowercase(Locale.US)
        } catch (_: Exception) {
            ""
        }
        val loginPath = path == "/login" || path.startsWith("/login/") || "/auth/login" in path
        val loginForm = doc.selectFirst(
            "form[action*=login], form[action*=Login], #login-form, [data-testid=\"login-form\"]",
        ) != null
        if (!loginPath && !loginForm) return null
        val base = "the page asks for a login"
        val title = doc.title().trim()
        return if ("log in" in title.lowercase(Locale.US)) {
            "$base: \"${title.take(MAX_QUOTED_CHARS)}\"."
        } else {
            "$base."
        }
    }
}
