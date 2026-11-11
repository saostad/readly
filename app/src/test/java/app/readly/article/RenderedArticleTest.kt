package app.readly.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snapshot-to-speech step shared by the phone's article screen and the car's screenless reader. */
class RenderedArticleTest {

    private val url = "https://news.example/story"
    private val story = "The council voted six to three after a long debate on Tuesday evening. ".repeat(14)
    private val page = "<html><head><title>Story</title>" +
        "<meta property=\"og:title\" content=\"Council Votes\"></head><body>" +
        "<nav><a href=\"/\">Home</a></nav><article><h1>Council Votes</h1><p>$story</p></article></body></html>"

    @Test
    fun article_isReadyWithItsTitleAndNoNotice() {
        val outcome = RenderedArticle.parse(null, page, url, gateFlags = 0, truncated = false, updateWarning = null, readHiddenText = false)
            as RenderedArticle.Outcome.Ready
        assertEquals("Council Votes", outcome.title)
        assertTrue(outcome.text.contains("council voted six to three"))
        assertFalse(outcome.text.contains("Home"))
        assertNull(outcome.notice)
    }

    @Test
    fun gatePromptsTruncationAndUpdateWarnings_areAllSaid() {
        val outcome = RenderedArticle.parse(null, page, url, gateFlags = 2, truncated = true, updateWarning = "Update failed.", readHiddenText = false)
            as RenderedArticle.Outcome.Ready
        val notice = outcome.notice!!
        assertTrue(notice, "possible subscription prompts" in notice)
        assertTrue(notice, "truncated to 5 MB" in notice)
        assertTrue(notice, "Update failed." in notice)
    }

    @Test
    fun pageWithoutText_failsWithAReason() {
        val outcome = RenderedArticle.parse(null, "<html><body></body></html>", url, 0, false, null, readHiddenText = false)
        assertTrue(outcome is RenderedArticle.Outcome.Failed)
        assertTrue((outcome as RenderedArticle.Outcome.Failed).message.isNotBlank())
    }

    @Test
    fun unsafeAddress_failsInsteadOfReading() {
        val outcome = RenderedArticle.parse(null, page, "http://127.0.0.1/story", 0, false, null, readHiddenText = false)
        assertTrue(outcome is RenderedArticle.Outcome.Failed)
    }

    private val pictured = "<html><head><title>Story</title></head><body><article><h1>Council Votes</h1>" +
        "<p>$story</p><p><img src=\"a.jpg\" alt=\"A crowded council chamber\"> " +
        "Read more <span class=\"sr-only\">about the council vote</span> later.</p></article></body></html>"

    @Test
    fun screenTextOnly_skipsImageDescriptionsAndScreenReaderText() {
        val outcome = RenderedArticle.parse(null, pictured, url, 0, false, null, readHiddenText = false)
            as RenderedArticle.Outcome.Ready
        assertTrue(outcome.text.contains("council voted six to three"))
        assertTrue(outcome.text, outcome.text.contains("Read more later."))
        assertFalse(outcome.text, outcome.text.contains("crowded council chamber"))
        assertFalse(outcome.text, outcome.text.contains("about the council vote"))
    }

    @Test
    fun hiddenTextOn_readsImageDescriptionsAndScreenReaderText() {
        val outcome = RenderedArticle.parse(null, pictured, url, 0, false, null, readHiddenText = true)
            as RenderedArticle.Outcome.Ready
        assertTrue(outcome.text, outcome.text.contains("A crowded council chamber"))
        assertTrue(outcome.text, outcome.text.contains("about the council vote"))
    }
}
