package app.readly.feeds

import app.readly.playback.CarBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLibraryTest {

    @Test
    fun readLater_newestFirstWithoutDuplicates() {
        var list = ReadLater.add(emptyList(), "https://a.example/1", "One", nowMs = 1)
        list = ReadLater.add(list, "https://b.example/2", "", nowMs = 2)
        list = ReadLater.add(list, "https://a.example/1", "One again", nowMs = 3)
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), list.map { it.url })
        assertEquals("b.example", list[1].title)
        assertEquals(list, ReadLater.decode(ReadLater.encode(list)))
        assertEquals("Real title", ReadLater.retitle(list, "https://b.example/2", "Real title")[1].title)
        assertEquals(1, ReadLater.remove(list, "https://a.example/1").size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun readLater_refusesNonLinks() {
        ReadLater.add(emptyList(), "just some words", "", nowMs = 1)
    }

    @Test(expected = org.json.JSONException::class)
    fun readLater_corruptStorageIsAnError() {
        ReadLater.decode("{not json")
    }

    @Test
    fun history_tracksProgressFinishAndCap() {
        var h = ListeningHistory.record(emptyList(), "https://a.example/1", "One", "A", 300, 1_000, nowMs = 1)
        assertTrue(h[0].resumable)
        assertEquals(0.3, h[0].fraction, 1e-9)
        h = ListeningHistory.finish(h, "https://a.example/1", nowMs = 2)
        assertTrue(h[0].finished)
        assertFalse(h[0].resumable)
        // Starting a heard article again keeps it heard; moving into it makes it partial.
        assertTrue(ListeningHistory.record(h, "https://a.example/1", "", "", 0, 1_000, 3)[0].finished)
        assertFalse(ListeningHistory.record(h, "https://a.example/1", "", "", 500, 1_000, 3)[0].finished)
        assertEquals(h, ListeningHistory.decode(ListeningHistory.encode(h)))
        var many = h
        for (i in 0..ListeningHistory.MAX_ENTRIES + 5) {
            many = ListeningHistory.record(many, "https://x.example/$i", "", "", 1, 10, i.toLong())
        }
        assertEquals(ListeningHistory.MAX_ENTRIES, many.size)
        assertEquals("https://x.example/${ListeningHistory.MAX_ENTRIES + 5}", many[0].url)
    }

    @Test
    fun completion_marksForTheCar() {
        assertEquals(0, CarBrowser.completion(null).first)
        val partial = HistoryEntry("u", "t", "s", 250, 1_000, finished = false, updatedAtMs = 1)
        assertEquals(1 to 0.25, CarBrowser.completion(partial))
        assertEquals(2 to 1.0, CarBrowser.completion(partial.copy(finished = true)))
    }

    @Test
    fun latest_sitesTakeTurnsAndStopAtTheLimit() {
        fun list(site: String, n: Int) = (1..n).map { Headline("$site $it", "https://$site.example/$it") }
        val mixed = LatestMix.interleave(listOf("A" to list("a", 3), "B" to list("b", 1), "C" to list("c", 2)), max = 5)
        assertEquals(listOf("a 1", "b 1", "c 1", "a 2", "c 2"), mixed.map { it.headline.title })
        assertEquals(listOf("A", "B", "C", "A", "C"), mixed.map { it.siteLabel })
        val dup = LatestMix.interleave(listOf("A" to list("a", 1), "A2" to list("a", 1)), max = 10)
        assertEquals(1, dup.size)
    }
}
