package app.readly.feeds

import app.readly.playback.CarBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CarQueueTest {

    private val entries = (1..3).map { CarEntry("site-1", Headline("Story $it", "https://news.example/$it"), "News") }

    @Test
    fun at_findsTheArticleAndMovesWithinTheList() {
        val queue = CarQueue.at("News", entries, "https://news.example/2")!!
        assertEquals("Story 2", queue.current.headline.title)
        assertTrue(queue.hasNext)
        assertTrue(queue.hasPrevious)
        assertEquals("Story 3", queue.moveBy(1)!!.current.headline.title)
        assertEquals("Story 1", queue.moveBy(-1)!!.current.headline.title)
        assertEquals("Story 3", queue.jumpTo(2)!!.current.headline.title)
        assertNull(queue.jumpTo(3))
    }

    @Test
    fun ends_stopMoving() {
        val first = CarQueue.at("News", entries, "https://news.example/1")!!
        assertFalse(first.hasPrevious)
        assertNull(first.moveBy(-1))
        val last = first.moveBy(2)!!
        assertFalse(last.hasNext)
        assertNull(last.moveBy(1))
    }

    @Test
    fun unknownArticle_hasNoQueue() {
        assertNull(CarQueue.at("News", entries, "https://news.example/other"))
    }

    @Test
    fun shuffle_keepsEveryArticleOnceInANewOrder() {
        val many = (1..20).map { CarEntry("site-1", Headline("Story $it", "https://news.example/$it"), "News") }
        val queue = CarQueue.shuffled("News", many, Random(7))!!
        assertEquals(0, queue.index)
        assertEquals(many.toSet(), queue.entries.toSet())
        assertEquals(many.size, queue.entries.size)
        assertNotEquals(many, queue.entries)
        assertNull(CarQueue.shuffled("News", emptyList(), Random(7)))
    }

    @Test
    fun mediaIds_carryTheListAndAnAddressWithPipes() {
        val url = "https://news.example/a?x=1|2"
        assertEquals("site-1" to url, CarBrowser.parseArticle(CarBrowser.articleId("site-1", url)))
        assertEquals(CarBrowser.LATEST to url, CarBrowser.parseArticle(CarBrowser.articleId(CarBrowser.LATEST, url)))
        assertNull(CarBrowser.parseArticle("article:https://news.example/old-style"))
        assertNull(CarBrowser.parseArticle("site:site-1"))
        assertNull(CarBrowser.parseArticle(null))
        assertEquals(CarBrowser.SAVED, CarBrowser.parseShuffle("shuffle:saved"))
        assertNull(CarBrowser.parseShuffle("shuffle:"))
        assertEquals("site:site-1", CarBrowser.parentOf("site-1"))
        assertEquals(CarBrowser.RECENTS, CarBrowser.parentOf(CarBrowser.RECENTS))
    }
}
