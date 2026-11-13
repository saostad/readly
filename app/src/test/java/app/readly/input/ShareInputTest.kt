package app.readly.input

import app.readly.text.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShareInputTest {

    private fun send(
        type: String? = "text/plain",
        text: String? = null,
        subject: String? = null,
        htmlText: String? = null,
        streamName: String? = null,
        streamMime: String? = null,
        streamText: String? = null,
        streamSizeBytes: Long? = null,
    ): ShareRequest = ShareInput.resolve(
        ShareInput.ACTION_SEND, type, text, subject, htmlText,
        streamName, streamMime, streamText, streamSizeBytes,
    )

    private fun failsWith(block: () -> ShareRequest): String {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message ?: "(no message)"
        }
        fail("expected resolve to fail")
        throw AssertionError("unreachable")
    }

    @Test
    fun resolve_processText_readsAsPlain() {
        val got = ShareInput.resolve(
            ShareInput.ACTION_PROCESS_TEXT, "text/plain", "Selected words to hear.", null, null, null, null, null,
        ) as ShareRequest.Read
        assertEquals("Selected words to hear.", got.text)
        assertEquals(SourceFormat.PLAIN, got.format)
        assertEquals("Selected words to hear.", got.title)
    }

    @Test
    fun resolve_chromeShare_urlOnlyWithSubjectTitle() {
        val got = send(
            text = "https://example.com/riverside-park",
            subject = "City Council Approves Riverside Park Plan",
        ) as ShareRequest.Url
        assertEquals("https://example.com/riverside-park", got.url)
        assertEquals("City Council Approves Riverside Park Plan", got.titleHint)
    }

    @Test
    fun resolve_headlinePlusUrl_usesHeadlineAsTitleHint() {
        val got = send(
            text = "Big news downtown today https://example.com/downtown",
            subject = "Morning digest",
        ) as ShareRequest.Url
        assertEquals("https://example.com/downtown", got.url)
        assertEquals("Big news downtown today", got.titleHint)
    }

    @Test
    fun resolve_urlWithTrailingPunctuation_stripsIt() {
        val got = send(text = "Read this (https://example.com/x).") as ShareRequest.Url
        assertEquals("https://example.com/x", got.url)
        assertEquals("Read this", got.titleHint)
    }

    @Test
    fun resolve_proseWithSeveralUrls_isTextNotLink() {
        val text = "Compare https://a.example/one with https://b.example/two before deciding."
        val got = send(text = text) as ShareRequest.Read
        assertEquals(text, got.text)
        assertEquals(SourceFormat.PLAIN, got.format)
    }

    @Test
    fun resolve_longProseWithOneUrl_isTextNotLink() {
        val text = "This morning I read a long analysis of urban gardening, shared from " +
            "https://example.com/gardening-analysis, and it completely changed how I think " +
            "about soil, seasons, watering schedules and the patience that good tomatoes demand."
        assertTrue(text.length - "https://example.com/gardening-analysis".length > ShareInput.MAX_HEADLINE_CHARS)
        val got = send(text = text) as ShareRequest.Read
        assertEquals(SourceFormat.PLAIN, got.format)
    }

    @Test
    fun resolve_plainText_readsAsPlainWithFirstLineTitle() {
        val got = send(text = "Hello there.\nSecond line here.") as ShareRequest.Read
        assertEquals(SourceFormat.PLAIN, got.format)
        assertEquals("Hello there.", got.title)
    }

    @Test
    fun resolve_htmlText_readsAsHtml() {
        val got = send(
            text = "fallback",
            subject = "A page",
            htmlText = "<p>Hello <b>web</b></p>",
        ) as ShareRequest.Read
        assertEquals("<p>Hello <b>web</b></p>", got.text)
        assertEquals(SourceFormat.HTML, got.format)
        assertEquals("A page", got.title)
    }

    @Test
    fun resolve_markdownFileByName_readsAsMarkdown() {
        val got = send(
            type = "application/octet-stream",
            streamName = "notes.md",
            streamMime = "application/octet-stream",
            streamText = "# Hello",
        ) as ShareRequest.Read
        assertEquals("# Hello", got.text)
        assertEquals(SourceFormat.MARKDOWN, got.format)
        assertEquals("notes", got.title)
    }

    @Test
    fun resolve_htmlFile_readsAsHtml() {
        val got = send(
            streamName = "page.htm",
            streamMime = "text/html",
            streamText = "<p>Hi</p>",
        ) as ShareRequest.Read
        assertEquals(SourceFormat.HTML, got.format)
        assertEquals("page", got.title)
    }

    @Test
    fun resolve_textFile_readsAsPlain() {
        val got = send(
            streamName = "story.txt",
            streamMime = "text/plain",
            streamText = "Once upon a time",
        ) as ShareRequest.Read
        assertEquals(SourceFormat.PLAIN, got.format)
        assertEquals("story", got.title)
    }

    @Test
    fun resolve_archiveFile_failsWithKindMessage() {
        assertEquals(
            "Readly can read pictures, PDF, text, Markdown and HTML files, not application/zip (archive.zip).",
            failsWith {
                send(streamName = "archive.zip", streamMime = "application/zip", streamText = "bogus")
            },
        )
    }

    @Test
    fun resolve_oversizeFile_fails() {
        assertEquals(
            "File is too large to read (over 5 MB): big.md",
            failsWith {
                send(
                    streamName = "big.md",
                    streamMime = "text/markdown",
                    streamText = "cut off",
                    streamSizeBytes = ShareInput.MAX_STREAM_BYTES + 1,
                )
            },
        )
    }

    @Test
    fun resolve_emptyShare_fails() {
        assertEquals(
            "Nothing was shared: the app sent no text, link or file.",
            failsWith { send(text = "  ") },
        )
        assertEquals(
            "Nothing was shared: the app sent no text, link or file.",
            failsWith { send() },
        )
    }

    @Test
    fun resolve_urlWithoutHint_hasNullTitle() {
        val got = send(text = "https://example.com/bare") as ShareRequest.Url
        assertEquals("https://example.com/bare", got.url)
        assertNull(got.titleHint)
    }
}
