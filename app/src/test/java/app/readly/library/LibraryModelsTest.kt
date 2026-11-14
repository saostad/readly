package app.readly.library

import app.readly.feeds.CarEntry
import app.readly.feeds.Headline
import org.junit.Assert.*
import org.junit.Test

class LibraryModelsTest {
    private fun entry(url: String, title: String, date: String? = null, site: String = "News") = CarEntry("site", Headline(title, url, date), site)
    private val a = entry("a", "Zulu", "2026-11-14T03:00:00Z")
    private val b = entry("b", "Alpha", "Fri, 13 Nov 2026 23:00:00 -0500", "World")
    private val unknown = entry("c", "Beta")
    private val facts = mapOf("a" to ArticleFacts("en", 360), "b" to ArticleFacts("fa", 180))

    @Test fun newestSortUsesActualInstantAcrossOffsetsAndPlacesUnknownLast() {
        assertEquals(listOf(b, a, unknown), ArticleFilter(sort = ArticleSort.NEWEST).apply(listOf(unknown, a, b), emptySet(), facts))
        assertEquals(listOf(a, b, unknown), ArticleFilter(sort = ArticleSort.OLDEST).apply(listOf(unknown, b, a), emptySet(), facts))
    }
    @Test fun unreadSiteLanguageAndDurationCombineWithoutGuessingUnknownMetadata() {
        val filter = ArticleFilter(unheard = true, site = "News", language = "en", maxMinutes = 2)
        assertEquals(listOf(a), filter.apply(listOf(a, b, unknown), emptySet(), facts))
        assertTrue(filter.apply(listOf(a, b, unknown), setOf("a"), facts).isEmpty())
        assertEquals(listOf(b), ArticleFilter(maxMinutes = 1).apply(listOf(a, b, unknown), emptySet(), facts))
        assertEquals(listOf(a), ArticleFilter(maxMinutes = 1, wordsPerMinute = 360, language = "en").apply(listOf(a, b, unknown), emptySet(), facts))
    }
    @Test fun titleSiteAndDurationOrdersAreStableAndDoNotMutateInput() {
        val input = listOf(a, b, unknown)
        assertEquals(input, ArticleFilter().apply(input, emptySet(), facts))
        assertEquals(listOf(b, unknown, a), ArticleFilter(sort = ArticleSort.TITLE).apply(input, emptySet(), facts))
        assertEquals(listOf(a, unknown, b), ArticleFilter(sort = ArticleSort.SITE).apply(input, emptySet(), facts))
        assertEquals(listOf(b, a, unknown), ArticleFilter(sort = ArticleSort.SHORTEST).apply(input, emptySet(), facts))
        assertEquals(listOf(a, b, unknown), input)
    }
    @Test fun durationDoesNotOverflowAndInvalidSettingsAreRejected() {
        assertEquals(listOf(a, b), ArticleFilter(maxMinutes = Int.MAX_VALUE).apply(listOf(a, b, unknown), emptySet(), facts))
        for (bad in listOf(ArticleFilter(wordsPerMinute = 0), ArticleFilter(maxMinutes = -1))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        }
    }
    @Test fun searchMatchesAllWordsAcrossFieldsAndTreatsSqlCharactersLiterally() {
        assertTrue(LibraryMatch.matches(" NEWS   park ", "News today", "A riverside park"))
        assertTrue(LibraryMatch.matches("خبر فارسی", "خبر", "فارسی"))
        assertFalse(LibraryMatch.matches("park missing", "A riverside park"))
        assertFalse(LibraryMatch.matches("%", "everything"))
        assertTrue(LibraryMatch.matches("%", "10% heard"))
        assertTrue(LibraryMatch.matches("", "All results"))
    }
    @Test fun bookmarkPositionsReferToExactSavedSentences() {
        val reading = SavedReading("id", "Title", "", "en", "", listOf("First sentence.", "Second sentence."))
        BookmarkRules.validate(reading, 1)
        assertThrows(IllegalArgumentException::class.java) { BookmarkRules.validate(reading, -1) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkRules.validate(reading, 2) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkRules.validate(reading.copy(sentences = emptyList()), 0) }
    }
}
