package app.readly.feeds

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchTest {

    private val bbc = FeedSite("1", "https://www.bbc.co.uk/news", "BBC News")
    private val verge = FeedSite("2", "https://www.theverge.com/")

    private val titles = mapOf(
        bbc to listOf(
            Headline("Budget vote delayed again in parliament", "https://bbc.co.uk/1"),
            Headline("Storm warnings issued for the coast", "https://bbc.co.uk/2"),
        ),
        verge to listOf(Headline("New phone chips run faster and cooler", "https://theverge.com/1")),
    )

    private fun pick(query: String?, sites: List<FeedSite> = listOf(bbc, verge), load: (FeedSite) -> List<Headline> = { titles.getValue(it) }) =
        VoiceSearch.pick(query, sites, load)

    private fun url(p: VoiceSearch.Pick) = (p as VoiceSearch.Pick.Article).headline.url

    @Test
    fun emptyRequest_readsTheFirstSitesNewestArticle() {
        assertEquals("https://bbc.co.uk/1", url(pick("  ")))
        assertEquals("https://bbc.co.uk/1", url(pick(null)))
    }

    @Test
    fun namingASite_readsItsNewestArticle() {
        assertEquals("https://theverge.com/1", url(pick("the verge")))
        assertEquals("https://theverge.com/1", url(pick("theverge")))
        assertEquals("https://bbc.co.uk/1", url(pick("BBC News")))
    }

    @Test
    fun otherWords_matchTheClosestTitle() {
        assertEquals("https://bbc.co.uk/2", url(pick("the storm on the coast")))
        assertEquals("https://theverge.com/1", url(pick("phone chips")))
    }

    @Test
    fun noMatch_failsAndNamesSitesThatCouldntLoad() {
        val result = pick("football scores") { site ->
            if (site == verge) throw IOException("HTTP 503") else titles.getValue(site)
        }
        val message = (result as VoiceSearch.Pick.Failure).message
        assertTrue(message, message.startsWith("Couldn't find \"football scores\""))
        assertTrue(message, message.contains("theverge.com (HTTP 503)"))
    }

    @Test
    fun noSites_saysToAddThem() {
        val result = pick("anything", sites = emptyList())
        assertTrue((result as VoiceSearch.Pick.Failure).message.startsWith("No websites yet"))
    }
}
