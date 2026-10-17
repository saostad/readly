package app.readly.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pasted-input dispatch: links open the article engine, prose is read aloud. */
class ArticleRouteTest {

    @Test
    fun `single url opens the article engine`() {
        val decision = ArticleRoute.routeInput("https://www.forbes.com/sites/x/article/")!!
        assertTrue(decision is ArticleRoute.Decision.Article)
        assertEquals(
            "https://www.forbes.com/sites/x/article/",
            (decision as ArticleRoute.Decision.Article).url,
        )
    }

    @Test
    fun `headline plus link opens the article engine`() {
        val decision = ArticleRoute.routeInput(
            "Interesting tax story https://www.forbes.com/sites/x/article/",
        )!!
        assertTrue(decision is ArticleRoute.Decision.Article)
    }

    @Test
    fun `long prose mentioning a link stays prose`() {
        val prose = "I read this yesterday and thought about it for a long time. ".repeat(5) +
            "https://www.forbes.com/sites/x/article/ and then some more thoughts after."
        val decision = ArticleRoute.routeInput(prose)!!
        assertTrue(decision is ArticleRoute.Decision.Prose)
    }

    @Test
    fun `plain prose stays prose with a first-line title`() {
        val decision = ArticleRoute.routeInput("Hello world\nSecond line")!!
        val prose = decision as ArticleRoute.Decision.Prose
        assertEquals("Hello world", prose.title)
        assertEquals("Hello world\nSecond line", prose.text)
    }

    @Test
    fun `two links stay prose`() {
        val decision = ArticleRoute.routeInput(
            "https://a.example.com/1 https://b.example.com/2",
        )!!
        assertTrue(decision is ArticleRoute.Decision.Prose)
    }

    @Test
    fun `blank input returns null`() {
        assertNull(ArticleRoute.routeInput("   "))
    }
}
