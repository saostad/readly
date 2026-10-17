package app.readly.input.nyt

import app.readly.input.ArticleFetcher
import app.readly.input.FetchConnection
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy-aware opener must send the rule's User-Agent only to HTTPS
 * main-host NYT requests (recomputed every redirect hop) and the default
 * agent everywhere else. Tests observe the actual chosen headers through a
 * recording `(url, userAgent)` seam; no live network.
 */
class NytFetchPolicyTest {

    private class FakeConnection(
        override val statusCode: Int,
        override val statusMessage: String = "",
        private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
    ) : FetchConnection {
        override fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun input(): InputStream = ByteArrayInputStream(body)
        override fun close() = Unit
    }

    private val seen = mutableListOf<Pair<String, String>>()

    private fun policy(userAgent: String = "UA-POLICY-1"): NytPolicy =
        NytPolicy(userAgent, true, null, "9.9", "", NytPolicy.OFFICIAL_UPDATE_URL)

    private fun openerFor(
        responses: Map<String, FakeConnection>,
        usePolicy: NytPolicy = policy(),
    ): (String) -> FetchConnection {
        val open: (String, String) -> FetchConnection = { url, userAgent ->
            seen.add(url to userAgent)
            responses[url] ?: throw AssertionError("unexpected URL in test: $url")
        }
        return NytFetchPolicy.opener(usePolicy, open)
    }

    private fun textPage(body: String): FakeConnection =
        FakeConnection(200, "OK", mapOf("Content-Type" to "text/plain"), body.toByteArray())

    private fun redirectTo(location: String): FakeConnection =
        FakeConnection(301, "Moved", mapOf("Location" to location))

    @Test
    fun directNyt_usesPolicyAgent() {
        val url = "https://www.nytimes.com/2026/10/16/world/demo.html"
        val fetcher = ArticleFetcher(openerFor(mapOf(url to textPage("hello"))))
        assertEquals("hello", fetcher.fetch(url).text)
        assertEquals(listOf(url to "UA-POLICY-1"), seen)
    }

    @Test
    fun directOtherHost_usesDefaultAgent() {
        val url = "https://example.com/notes.txt"
        val fetcher = ArticleFetcher(openerFor(mapOf(url to textPage("hello"))))
        fetcher.fetch(url)
        assertEquals(listOf(url to ArticleFetcher.USER_AGENT), seen)
    }

    @Test
    fun redirectIntoNyt_switchesToPolicyAgent() {
        val start = "http://example.com/short"
        val nyt = "https://www.nytimes.com/2026/10/16/world/demo.html"
        val fetcher = ArticleFetcher(
            openerFor(mapOf(start to redirectTo(nyt), nyt to textPage("hello"))),
        )
        fetcher.fetch(start)
        assertEquals(
            listOf(start to ArticleFetcher.USER_AGENT, nyt to "UA-POLICY-1"),
            seen,
        )
    }

    @Test
    fun redirectOutOfNyt_switchesBackToDefault() {
        val start = "https://www.nytimes.com/2026/10/16/world/demo.html"
        val out = "https://example.com/landing.txt"
        val fetcher = ArticleFetcher(
            openerFor(mapOf(start to redirectTo(out), out to textPage("hello"))),
        )
        fetcher.fetch(start)
        assertEquals(
            listOf(start to "UA-POLICY-1", out to ArticleFetcher.USER_AGENT),
            seen,
        )
    }

    @Test
    fun spoofSubdomainSchemePortUserinfo_useDefaultAgent() {
        val urls = listOf(
            "https://nytimes.com.evil.test/article.txt",
            "https://evilnytimes.com/article.txt",
            "https://cooking.nytimes.com/recipe.txt",
            "http://www.nytimes.com/2026/10/16/world/demo.html",
            "https://www.nytimes.com:8443/2026/10/16/world/demo.html",
            "https://user@www.nytimes.com/2026/10/16/world/demo.html",
            "https://www.nytimes.com.@evil.test/article.txt",
        )
        val responses = urls.associateWith { textPage("hello") }
        val fetcher = ArticleFetcher(openerFor(responses))
        urls.forEach { fetcher.fetch(it) }
        assertEquals(urls.map { it to ArticleFetcher.USER_AGENT }, seen)
    }

    @Test
    fun explicitStandardPortAndCase_usePolicyAgent() {
        val urls = listOf(
            "https://www.nytimes.com:443/a.txt",
            "https://nytimes.com/b.txt",
            "https://WWW.NYTIMES.COM/c.txt",
        )
        val fetcher = ArticleFetcher(openerFor(urls.associateWith { textPage("hello") }))
        urls.forEach { fetcher.fetch(it) }
        assertEquals(urls.map { it to "UA-POLICY-1" }, seen)
    }

    @Test
    fun updatedPolicy_affectsNextReading() {
        val url = "https://www.nytimes.com/2026/10/16/world/demo.html"
        val responses = mapOf(url to textPage("hello"))
        ArticleFetcher(openerFor(responses, policy("UA-OLD"))).fetch(url)
        ArticleFetcher(openerFor(responses, policy("UA-NEW"))).fetch(url)
        assertEquals(listOf(url to "UA-OLD", url to "UA-NEW"), seen)
    }

    @Test
    fun xPostOembed_usesDefaultAgent() {
        val post = "https://x.com/UltraLinx/status/2111029595168538686"
        var oembedAgent: String? = null
        val open: (String, String) -> FetchConnection = { url, userAgent ->
            assertTrue("unexpected URL: $url", url.startsWith(ArticleFetcher.OEMBED_BASE))
            oembedAgent = userAgent
            FakeConnection(
                200, "OK", mapOf("Content-Type" to "application/json"),
                ("{\"author_name\":\"Oliur\",\"html\":\"<blockquote><p>Hi.</p></blockquote>\"}")
                    .toByteArray(),
            )
        }
        val doc = ArticleFetcher(NytFetchPolicy.opener(policy(), open)).fetch(post)
        assertEquals("Post by Oliur", doc.title)
        assertEquals(ArticleFetcher.USER_AGENT, oembedAgent)
    }

    @Test
    fun unparseableUrl_usesDefaultAgent() {
        assertEquals(ArticleFetcher.USER_AGENT, NytFetchPolicy.userAgentFor(":::", policy()))
        assertEquals(ArticleFetcher.USER_AGENT, NytFetchPolicy.userAgentFor("", policy()))
    }
}
