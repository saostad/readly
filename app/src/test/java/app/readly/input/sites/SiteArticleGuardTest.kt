package app.readly.input.sites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

/**
 * Narrow block-page guard for matched hosts: recognized login, subscription,
 * verification, and denial pages fail with an actionable error, while pages
 * with a real article body — even quoting subscription language — pass.
 * All fixtures are synthetic; no owner article HTML.
 */
class SiteArticleGuardTest {
    private fun reasonFor(html: String, finalUrl: String = "https://paper.example.com/story"): String? {
        val doc = Jsoup.parse(html, finalUrl)
        val host = try {
            java.net.URI(finalUrl).host ?: finalUrl
        } catch (e: Exception) {
            finalUrl
        }
        return SiteArticleGuard.blockReason(doc, finalUrl, host)
    }

    private fun longArticleBody(quote: String = ""): String {
        val prose = "Reporters documented the council vote late Tuesday evening. ".repeat(12)
        return "<article><h1>Council Acts</h1><p>$prose</p><p>$quote</p></article>"
    }

    @Test
    fun articleWithBody_passes() {
        assertNull(reasonFor("<html><head><title>Story</title></head><body>${longArticleBody()}</body></html>"))
    }

    @Test
    fun quotedSubscriptionLanguage_passes() {
        val quote = "The banner once read “subscribe to continue reading,” the editor recalled."
        assertNull(
            reasonFor("<html><head><title>Story</title></head><body>${longArticleBody(quote)}</body></html>"),
        )
    }

    @Test
    fun shortPageWithoutEvidence_passesToOrdinaryFallback() {
        assertNull(
            reasonFor("<html><head><title>Brief</title></head><body><p>A short note.</p></body></html>"),
        )
    }

    @Test
    fun challengePage_blocked() {
        val reason = reasonFor(
            "<html><head><title>Just a moment...</title></head>" +
                "<body><p>Verify you are human to continue.</p></body></html>",
        )!!
        assertTrue("reason was: $reason", "human-verification challenge" in reason)
        assertTrue("reason was: $reason", "paper.example.com" in reason)
        assertTrue("reason was: $reason", "browser" in reason)
    }

    @Test
    fun captchaMarker_blocked() {
        val reason = reasonFor(
            "<html><head><script src=\"https://challenges.example.com/captcha-delivery/x.js\"></script></head>" +
                "<body><p>Loading.</p></body></html>",
        )!!
        assertTrue("reason was: $reason", "human-verification challenge" in reason)
    }

    @Test
    fun challengeBesideRealArticle_passes() {
        assertNull(
            reasonFor(
                "<html><head><title>Story</title></head><body>${longArticleBody()}<p>Verify you are human.</p></body></html>",
            ),
        )
    }

    @Test
    fun accessDenied_blocked() {
        val reason = reasonFor(
            "<html><head><title>Access Denied</title></head><body><h1>Access Denied</h1></body></html>",
        )!!
        assertTrue("reason was: $reason", "access was denied" in reason)
    }

    @Test
    fun loginForm_blocked() {
        val reason = reasonFor(
            "<html><head><title>Sign in</title></head><body>" +
                "<form action=\"/login\"><input name=\"u\"></form></body></html>",
            "https://paper.example.com/login",
        )!!
        assertTrue("reason was: $reason", "login" in reason)
        assertTrue("reason was: $reason", "Sign in" in reason || "browser" in reason)
    }

    @Test
    fun paywallContainerWithPrompt_blocked() {
        val reason = reasonFor(
            "<html><head><title>Story</title></head><body>" +
                "<div class=\"article-paywall\" id=\"paywall-1\">" +
                "<h2>Subscribe to continue reading</h2><p>Join today.</p></div>" +
                "</body></html>",
        )!!
        assertTrue("reason was: $reason", "subscription prompt" in reason)
        assertTrue("reason was: $reason", "browser" in reason)
    }

    @Test
    fun hiddenPaywallContainer_passes() {
        assertNull(
            reasonFor(
                "<html><head><title>Story</title></head><body>" +
                    "<div class=\"paywall\" style=\"display:none\">Subscribe to continue reading</div>" +
                    "<p>A short note.</p></body></html>",
            ),
        )
    }

    @Test
    fun barePhraseWithoutContainer_passes() {
        // No structural container: not recognizable evidence, ordinary
        // fallback still applies.
        assertNull(
            reasonFor(
                "<html><head><title>Story</title></head><body>" +
                    "<p>Some pages say subscribe to continue.</p></body></html>",
            ),
        )
    }

    @Test
    fun longPaywallInsideArticle_blocked() {
        // Long sales copy inside <article> must not pose as article prose:
        // the paywall subtree is excluded from the body measurement, so the
        // structural evidence still blocks instead of being read aloud.
        val pitch = "Choose a plan. Get unlimited access to all our reporting. " +
            "Payment will renew automatically. "
        val reason = reasonFor(
            "<html><head><title>Subscribe</title></head><body><article>" +
                "<div class=\"paywall\"><h1>Subscribe to continue reading</h1>" +
                "<p>${pitch.repeat(9)}</p><button>Subscribe now</button></div>" +
                "</article></body></html>",
        )!!
        assertTrue("reason was: $reason", "subscription prompt" in reason)
        assertTrue("reason was: $reason", "paper.example.com" in reason)
    }

    @Test
    fun longLoginFormInsideArticle_blocked() {
        val formText = "Enter your email address and password to sign in to your account. "
        val reason = reasonFor(
            "<html><head><title>Story</title></head><body><article>" +
                "<form action=\"/login\"><h2>Sign in to continue</h2>" +
                "<p>${formText.repeat(8)}</p><button>Sign in</button></form>" +
                "</article></body></html>",
        )!!
        assertTrue("reason was: $reason", "login" in reason)
    }

    @Test
    fun longChallengeInsideArticle_blocked() {
        val checkText = "Verifying your browser before you can continue reading this page. "
        val reason = reasonFor(
            "<html><head><title>Story</title></head><body><article>" +
                "<div class=\"cf-challenge\"><p>${checkText.repeat(8)}</p></div>" +
                "</article></body></html>",
        )!!
        assertTrue("reason was: $reason", "human-verification challenge" in reason)
    }

    @Test
    fun fullBodyPlusSmallPromo_passes() {
        // Real article prose still counts once the separate promo box is set
        // aside — even though the promo alone would be blocking evidence.
        val prose = "Reporters documented the council vote late Tuesday evening. ".repeat(12)
        assertNull(
            reasonFor(
                "<html><head><title>Story</title></head><body><article>" +
                    "<h1>Council Acts</h1><p>$prose</p>" +
                    "<div class=\"paywall-promo\">" +
                    "<p>Subscribe to continue reading. Join today.</p></div>" +
                    "</article></body></html>",
            ),
        )
    }

    @Test
    fun quotedChallengePhraseInPlainDiv_passes() {
        // No semantic <article> wrapper, and the only challenge evidence is a
        // quoted phrase inside a long normal article: bare phrases never
        // block substantial prose.
        val prose = "The clerk described the new verification policy at length. ".repeat(8) +
            "The notice once read “verify you are human,” the clerk recalled. " +
            "Council members asked how visitors would be affected. ".repeat(4)
        assertNull(
            reasonFor(
                "<html><head><title>Council Acts</title></head><body>" +
                    "<div><h1>Council Acts</h1><p>$prose</p></div></body></html>",
            ),
        )
    }

    @Test
    fun barePhraseShortPage_stillBlocked() {
        // A genuine challenge page carries no other prose, so the bare
        // phrase still blocks when nothing substantial surrounds it.
        val reason = reasonFor(
            "<html><head><title>Checking</title></head><body>" +
                "<p>Verify you are human to continue.</p></body></html>",
        )!!
        assertTrue("reason was: $reason", "human-verification challenge" in reason)
    }

    @Test
    fun hiddenAncestorPaywall_passes() {
        // The hiding marker may sit on any ancestor, not just the container.
        for (hiding in listOf("style=\"display:none\"", "hidden")) {
            assertNull(
                "hiding was: $hiding",
                reasonFor(
                    "<html><head><title>Story</title></head><body>" +
                        "<div $hiding><div><div class=\"paywall\">" +
                        "Subscribe to continue reading.</div></div></div>" +
                        "<p>A short note.</p></body></html>",
                ),
            )
        }
    }

    @Test
    fun meaningfulBody_needsArticleShapedText() {
        val doc = Jsoup.parse(
            "<html><body><main><p>${"Navigation and boilerplate. ".repeat(20)}</p></main></body></html>",
        )
        assertEquals(false, SiteArticleGuard.hasMeaningfulBody(doc))
        val article = Jsoup.parse(
            "<html><body><article><p>${"Actual reporting. ".repeat(20)}</p></article></body></html>",
        )
        assertEquals(true, SiteArticleGuard.hasMeaningfulBody(article))
    }
}
