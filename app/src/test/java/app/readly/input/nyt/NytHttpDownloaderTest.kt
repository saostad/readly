package app.readly.input.nyt

import app.readly.input.FetchConnection
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NytHttpDownloaderTest {

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

    private val official = NytPolicy.OFFICIAL_UPDATE_URL
    private val requested = mutableListOf<String>()

    private fun downloaderFor(responses: Map<String, FakeConnection>): NytHttpDownloader {
        requested.clear()
        return NytHttpDownloader { url ->
            requested.add(url)
            responses[url] ?: throw IOException("unexpected URL in test: $url")
        }
    }

    private fun downloadFailsWith(downloader: NytHttpDownloader, url: String = official): String {
        try {
            downloader.download(url)
        } catch (e: IOException) {
            return e.message ?: "(no message)"
        }
        fail("expected download of $url to fail")
        throw AssertionError("unreachable")
    }

    @Test
    fun success_returnsBody() {
        val downloader = downloaderFor(mapOf(official to FakeConnection(200, "OK", body = "ZIP".toByteArray())))
        assertEquals("ZIP", String(downloader.download(official), Charsets.UTF_8))
        assertEquals(listOf(official), requested)
    }

    @Test
    fun http404_namesCodeAndMessage() {
        val downloader = downloaderFor(mapOf(official to FakeConnection(404, "Not Found")))
        assertEquals(
            "NYT rule update failed: HTTP 404 Not Found",
            downloadFailsWith(downloader),
        )
    }

    @Test
    fun http500_emptyMessage() {
        val downloader = downloaderFor(mapOf(official to FakeConnection(500)))
        assertEquals("NYT rule update failed: HTTP 500", downloadFailsWith(downloader))
    }

    @Test
    fun relativeRedirect_followed() {
        val next = "https://gitflic.ru/project/magnolia1234/other?file=x.zip"
        val downloader = downloaderFor(
            mapOf(
                official to FakeConnection(302, "Found", mapOf("Location" to "/project/magnolia1234/other?file=x.zip")),
                next to FakeConnection(200, "OK", body = "ZIP".toByteArray()),
            ),
        )
        assertEquals("ZIP", String(downloader.download(official), Charsets.UTF_8))
        assertEquals(listOf(official, next), requested)
    }

    @Test
    fun absoluteSameHostRedirect_followed() {
        val next = "https://gitflic.ru/other.zip"
        val downloader = downloaderFor(
            mapOf(
                official to FakeConnection(301, "Moved", mapOf("Location" to next)),
                next to FakeConnection(200, "OK", body = "ZIP".toByteArray()),
            ),
        )
        downloader.download(official)
        assertEquals(listOf(official, next), requested)
    }

    @Test
    fun offOriginRedirect_rejected() {
        val downloader = downloaderFor(
            mapOf(official to FakeConnection(302, "Found", mapOf("Location" to "https://evil.example/x.zip"))),
        )
        val message = downloadFailsWith(downloader)
        assertTrue("message was: $message", "off update host" in message && "evil.example" in message)
    }

    @Test
    fun httpDowngrade_rejected() {
        val downloader = downloaderFor(
            mapOf(official to FakeConnection(302, "Found", mapOf("Location" to "http://gitflic.ru/x.zip"))),
        )
        assertTrue(downloadFailsWith(downloader).contains("non-HTTPS"))
    }

    @Test
    fun credentialsRedirect_rejected() {
        val downloader = downloaderFor(
            mapOf(official to FakeConnection(302, "Found", mapOf("Location" to "https://user@gitflic.ru/x.zip"))),
        )
        assertTrue(downloadFailsWith(downloader).contains("credentials"))
    }

    @Test
    fun oddPortRedirect_rejected() {
        val downloader = downloaderFor(
            mapOf(official to FakeConnection(302, "Found", mapOf("Location" to "https://gitflic.ru:8443/x.zip"))),
        )
        val message = downloadFailsWith(downloader)
        assertTrue("message was: $message", "odd port" in message && "8443" in message)
    }

    @Test
    fun explicit443Redirect_allowed() {
        val next = "https://gitflic.ru:443/x.zip"
        val downloader = downloaderFor(
            mapOf(
                official to FakeConnection(302, "Found", mapOf("Location" to next)),
                next to FakeConnection(200, "OK", body = "ZIP".toByteArray()),
            ),
        )
        downloader.download(official)
        assertEquals(listOf(official, next), requested)
    }

    @Test
    fun hostCaseRedirect_allowed() {
        val next = "https://GITFLIC.RU/x.zip"
        val downloader = downloaderFor(
            mapOf(
                official to FakeConnection(302, "Found", mapOf("Location" to next)),
                next to FakeConnection(200, "OK", body = "ZIP".toByteArray()),
            ),
        )
        downloader.download(official)
        assertEquals(listOf(official, next), requested)
    }

    @Test
    fun redirectLoop_rejected() {
        val downloader = downloaderFor(
            mapOf(official to FakeConnection(302, "Found", mapOf("Location" to official))),
        )
        val message = downloadFailsWith(downloader)
        assertTrue("message was: $message", "too many redirects" in message)
        assertEquals(NytHttpDownloader.MAX_REDIRECTS + 1, requested.size)
    }

    @Test
    fun redirectWithoutLocation_rejected() {
        val downloader = downloaderFor(mapOf(official to FakeConnection(301, "Moved")))
        assertTrue(downloadFailsWith(downloader).contains("no Location header"))
    }

    @Test
    fun badRedirectLocation_rejected() {
        val downloader = downloaderFor(
            mapOf(official to FakeConnection(302, "Found", mapOf("Location" to "http://[::1"))),
        )
        assertTrue(downloadFailsWith(downloader).contains("bad redirect"))
    }

    @Test
    fun oversizeBody_rejected() {
        val big = ByteArray((NytRuleParser.MAX_COMPRESSED_BYTES + 1).toInt())
        val downloader = downloaderFor(mapOf(official to FakeConnection(200, "OK", body = big)))
        assertTrue(downloadFailsWith(downloader).contains("over 8 MiB"))
    }

    @Test
    fun wrongStartUrl_refused() {
        val downloader = downloaderFor(emptyMap())
        val message = downloadFailsWith(downloader, "https://evil.example/x.zip")
        assertTrue("message was: $message", "refused unexpected URL" in message)
        assertTrue(requested.isEmpty())
    }
}
