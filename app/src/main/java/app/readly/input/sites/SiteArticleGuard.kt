package app.readly.input.sites

import java.net.URI
import java.util.Locale
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Block-page guard for matched non-NYT hosts. Pure JVM over jsoup: no
 * network, no scripts.
 *
 * [blockReason] runs on the raw parsed page BEFORE any cleanup could erase
 * the evidence (mirroring the NYT guard's ordering): a recognizable login,
 * subscription, verification-challenge, or access-denied page fails with an
 * actionable error instead of being spoken as an article or read through the
 * whole-page fallback as paywall UI.
 *
 * Narrow by construction:
 * - a page with a meaningful article body ([hasMeaningfulBody]) always
 *   passes, so normal prose quoting subscription language still reads;
 *   gate/login/challenge UI subtrees never count as body prose, so a long
 *   sales pitch inside `<article>` cannot suppress structural evidence;
 * - challenge/denied/login evidence is structural (captcha markers, denial
 *   titles, login paths/forms), like the proven NYT detectors; bare
 *   challenge phrases alone never block a page carrying substantial prose;
 * - subscription prompts require a visible paywall container carrying the
 *   prompt copy, never a bare phrase match over article prose.
 *
 * What this cannot do: pass a challenge or subscription check (those need a
 * browser session or a paid account), or prove completeness — a teaser with
 * no gateway markers is indistinguishable from a short article. No claim of
 * guaranteed completeness is made from these heuristics.
 */
object SiteArticleGuard {
    /** Longest page-supplied snippet quoted inside a failure message. */
    const val MAX_QUOTED_CHARS = 140

    /** Minimum article-element text for [hasMeaningfulBody]. */
    const val MIN_BODY_CHARS = 200

    /**
     * Why the page at [finalUrl] (host [host]) must not be read, or null
     * when it may proceed to cleanup and extraction. The message names the
     * operation, the evidence, and the concrete next step.
     */
    fun blockReason(doc: Document, finalUrl: String, host: String): String? {
        if (hasMeaningfulBody(doc)) return null
        val prefix = "Couldn't read this article from $host: "
        challengeReason(doc)?.let { return prefix + it }
        deniedReason(doc)?.let { return prefix + it }
        loginReason(doc, finalUrl)?.let { return prefix + it }
        paywallReason(doc)?.let { return prefix + it }
        return null
    }

    /**
     * True when the page carries an article-shaped body with substantial
     * text. Only article-specific containers count (`main` is deliberately
     * excluded: it usually wraps whole pages including navigation, which
     * would let block pages through). Structurally recognized gate, login,
     * and challenge subtrees are excluded from the measurement, so a long
     * subscription pitch or sign-in form inside `<article>` cannot pose as
     * article prose; quoted phrases in ordinary prose still count.
     */
    fun hasMeaningfulBody(doc: Document): Boolean {
        return doc.select("article, [itemprop=articleBody], [data-testid=article-body], section[name=articleBody]")
            .filterNot { isGateUi(it) }
            .any { proseLength(it) >= MIN_BODY_CHARS }
    }

    /**
     * Visible prose length of [el] minus structurally recognized gate, login,
     * and challenge subtrees. The element is cloned before stripping, so the
     * caller's document is never mutated.
     */
    private fun proseLength(el: Element): Int {
        val clone = el.clone()
        for (node in clone.select("*")) {
            if (isGateUi(node)) node.remove()
        }
        return clone.text().length
    }

    /**
     * True for structurally recognized gate/login/challenge UI: a visible
     * paywall container, a login form, or a human-verification marker. Bare
     * phrases in ordinary prose never match — only the same structural
     * shapes the block detectors look for.
     */
    private fun isGateUi(el: Element): Boolean {
        if (isShownPaywallContainer(el)) return true
        if (isLoginForm(el)) return true
        if (isChallengeMarker(el)) return true
        return false
    }

    /** Login-form shape from [loginReason]: login action, `#login-form`, or test id. */
    private fun isLoginForm(el: Element): Boolean {
        if (el.id() == "login-form") return true
        if (el.attr("data-testid") == "login-form") return true
        if (!el.tagName().equals("form", ignoreCase = true)) return false
        val action = el.attr("action")
        return "login" in action || "Login" in action
    }

    /** Human-verification marker shape from [challengeReason]. */
    private fun isChallengeMarker(el: Element): Boolean {
        if (el.id() == "cf-challenge-running" || el.id() == "challenge-running") return true
        if (el.hasClass("cf-challenge")) return true
        return el.tagName().equals("script", ignoreCase = true) &&
            "captcha-delivery" in el.attr("src")
    }

    // ------------------------------------------------------------------
    // Human-verification challenge
    // ------------------------------------------------------------------

    private fun challengeReason(doc: Document): String? {
        val structural = doc.selectFirst("script[src*=captcha-delivery]") != null ||
            doc.selectFirst("#cf-challenge-running, #challenge-running, .cf-challenge") != null
        if (structural) return challengeMessage(doc)
        if ("just a moment" in doc.title().lowercase(Locale.US)) return challengeMessage(doc)
        // Bare body phrases are the weakest evidence: a genuine challenge
        // page carries no other prose, while a long article may quote the
        // same words. Only block on phrases when the page lacks substantial
        // prose of its own.
        val body = doc.body()?.text().orEmpty().lowercase(Locale.US)
        val messaged = "please enable js and disable any ad blocker" in body ||
            "verify you are human" in body ||
            "verify you are a human" in body
        if (!messaged || hasSubstantialProse(doc)) return null
        return challengeMessage(doc)
    }

    private fun challengeMessage(doc: Document): String {
        val base = "the page shows a human-verification challenge this reader cannot solve"
        val title = doc.title().trim().take(MAX_QUOTED_CHARS)
        val action = "Open it in a browser to verify, then try again."
        return if (title.isBlank()) "$base. $action" else "$base: \"$title\". $action"
    }

    /**
     * True when the page body carries substantial prose outside recognized
     * gate/login/challenge UI. Lets a long article without semantic
     * `<article>` markup quote challenge language without being blocked.
     */
    private fun hasSubstantialProse(doc: Document): Boolean {
        val body = doc.body() ?: return false
        return proseLength(body) >= MIN_BODY_CHARS
    }

    // ------------------------------------------------------------------
    // Access denied
    // ------------------------------------------------------------------

    private val REFERENCE_RE = Regex("Reference\\s*#[0-9a-fA-F._-]+")

    private fun deniedReason(doc: Document): String? {
        val title = doc.title()
        if ("access denied" in title.lowercase(Locale.US)) {
            return "access was denied: \"${title.trim().take(MAX_QUOTED_CHARS)}\". " +
                "Open it in a browser to continue."
        }
        val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (heading.equals("Access Denied", ignoreCase = true)) {
            return "access was denied. Open it in a browser to continue."
        }
        // Classic edge-denial pages pair the phrase with a reference token;
        // the phrase alone could be article prose, so require both.
        val body = doc.body()?.text().orEmpty()
        if ("access denied" in body.lowercase(Locale.US)) {
            val ref = REFERENCE_RE.find(body)?.value
            if (ref != null) return "access was denied ($ref). Open it in a browser to continue."
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
            "form[action*=login], form[action*=Login], #login-form, [data-testid=login-form]",
        ) != null
        if (!loginPath && !loginForm) return null
        val title = doc.title().trim()
        val action = "Sign in using a browser, then try again."
        return if ("log in" in title.lowercase(Locale.US)) {
            "the page asks for a login: \"${title.take(MAX_QUOTED_CHARS)}\". $action"
        } else {
            "the page asks for a login. $action"
        }
    }

    // ------------------------------------------------------------------
    // Subscription prompt
    // ------------------------------------------------------------------

    private val GATEWAY_PHRASES = listOf(
        "subscribe to continue reading",
        "subscribe to continue",
        "you've reached your limit",
        "you have reached your limit",
        "create a free account to continue reading",
    )

    private fun paywallReason(doc: Document): String? {
        val container = doc.body()?.select("*")
            ?.firstOrNull { isShownPaywallContainer(it) && GATEWAY_PHRASES.any { phrase -> phrase in it.text().lowercase(Locale.US) } }
            ?: return null
        val quote = container.selectFirst("h1, h2, h3, p")?.text()?.trim().orEmpty()
            .take(MAX_QUOTED_CHARS)
        val base = "the page shows a subscription prompt instead of the full article"
        val action = "Open it in a browser to subscribe or sign in, then try again."
        return if (quote.isBlank()) "$base. $action" else "$base: \"$quote\". $action"
    }

    /**
     * True for a visible element whose id or class names it a paywall
     * container. Both the structural marker and the prompt copy are
     * required, so quoted phrases in ordinary prose never match.
     */
    private fun isShownPaywallContainer(el: Element): Boolean {
        val idClass = (el.id() + " " + el.className()).lowercase(Locale.US)
        if ("paywall" !in idClass && "pay-wall" !in idClass &&
            "subscribe-wall" !in idClass && "subscription-wall" !in idClass
        ) {
            return false
        }
        return isShown(el)
    }

    /**
     * Skips markup the page itself hides (not a visible prompt). The hiding
     * markers may sit on any ancestor, so the whole chain is checked.
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
}
