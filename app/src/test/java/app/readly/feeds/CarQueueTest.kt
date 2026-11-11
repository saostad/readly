package app.readly.feeds

import app.readly.playback.CarBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarQueueTest {

    private val site = FeedSite("site-1", "https://news.example/")
    private val headlines = (1..3).map { Headline("Story $it", "https://news.example/$it") }

    @Test
    fun at_findsTheArticleAndMovesWithinTheList() {
        val queue = CarQueue.at(site, headlines, "https://news.example/2")!!
        assertEquals("Story 2", queue.current.title)
        assertTrue(queue.hasNext)
        assertTrue(queue.hasPrevious)
        assertEquals("Story 3", queue.moveBy(1)!!.current.title)
        assertEquals("Story 1", queue.moveBy(-1)!!.current.title)
    }

    @Test
    fun ends_stopMoving() {
        val first = CarQueue.at(site, headlines, "https://news.example/1")!!
        assertFalse(first.hasPrevious)
        assertNull(first.moveBy(-1))
        val last = first.moveBy(2)!!
        assertFalse(last.hasNext)
        assertNull(last.moveBy(1))
    }

    @Test
    fun unknownArticle_hasNoQueue() {
        assertNull(CarQueue.at(site, headlines, "https://news.example/other"))
    }

    @Test
    fun articleIds_carryTheSiteAndAnAddressWithPipes() {
        val url = "https://news.example/a?x=1|2"
        val id = CarBrowser.articleId(site.id, url)
        assertEquals(site.id to url, CarBrowser.parseArticle(id))
        assertNull(CarBrowser.parseArticle("article:https://news.example/old-style"))
        assertNull(CarBrowser.parseArticle("site:site-1"))
        assertNull(CarBrowser.parseArticle(null))
    }
}
