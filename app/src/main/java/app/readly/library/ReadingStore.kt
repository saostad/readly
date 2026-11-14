package app.readly.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.readly.feeds.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Private durable library shared by playback, browsing, scheduled jobs and search. */
class ReadingStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "reading-library.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE feeds (site TEXT PRIMARY KEY, url TEXT NOT NULL, mode TEXT NOT NULL, max_articles INTEGER NOT NULL, fetched INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE readings (id TEXT PRIMARY KEY, title TEXT NOT NULL, host TEXT NOT NULL, language TEXT NOT NULL, url TEXT NOT NULL, words INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE reading_sentences (reading TEXT NOT NULL REFERENCES readings(id), position INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(reading,position))")
        db.execSQL("CREATE INDEX readings_url ON readings(url)")
        db.execSQL("CREATE TABLE bookmarks (id TEXT PRIMARY KEY, reading TEXT NOT NULL REFERENCES readings(id), sentence INTEGER NOT NULL, note TEXT NOT NULL, version INTEGER NOT NULL, created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No library migration from $oldVersion to $newVersion")

    fun saveFeed(site: FeedSite, max: Int, loaded: LoadedHeadlines) {
        require(!loaded.stale) { "An unsuccessful refresh cannot replace saved headlines." }
        val list = loaded.list
        val payload = JSONObject().put("title", list.siteTitle).put("source", list.source.name)
            .put("feedUrl", list.feedUrl).put("notice", list.notice)
            .put("headlines", JSONArray().apply { list.headlines.forEach { put(JSONObject().put("title", it.title).put("url", it.url).put("published", it.published)) } })
        writableDatabase.insertWithOnConflict("feeds", null, ContentValues().apply {
            put("site", site.id); put("url", site.url); put("mode", site.mode.key); put("max_articles", max)
            put("fetched", loaded.fetchedAtMs); put("payload", payload.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Couldn't save feed headlines." } }
    }
    fun loadFeed(site: FeedSite, max: Int): LoadedHeadlines? = readableDatabase.query("feeds", arrayOf("fetched", "payload", "max_articles"),
        "site=? AND url=? AND mode=?", arrayOf(site.id, site.url, site.mode.key), null, null, null).use { c ->
        if (!c.moveToFirst()) return@use null
        val o = JSONObject(c.getString(1)); val a = o.getJSONArray("headlines")
        if (max > c.getInt(2) && a.length() >= c.getInt(2)) return@use null
        val list = HeadlineList(o.getString("title"), (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Headline(it.getString("title"), it.getString("url"), if (it.isNull("published")) null else it.getString("published"))
        } }, HeadlineSource.valueOf(o.getString("source")), if (o.isNull("feedUrl")) null else o.getString("feedUrl"), if (o.isNull("notice")) null else o.getString("notice"))
        LoadedHeadlines(list, c.getLong(0), false, listOfNotNull(list.notice))
    }
    fun removeFeed(siteId: String) { writableDatabase.delete("feeds", "site=?", arrayOf(siteId)) }

    fun saveReading(title: String, host: String, language: String, url: String, sentences: List<String>): String {
        require(sentences.isNotEmpty() && sentences.all { it.isNotBlank() })
        val id = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("readings", null, ContentValues().apply {
                put("id", id); put("title", title); put("host", host); put("language", language); put("url", url)
                put("created", System.currentTimeMillis()); put("words", sentences.sumOf { it.split(Regex("\\s+")).size })
            })
            sentences.forEachIndexed { index, text -> db.insertOrThrow("reading_sentences", null, ContentValues().apply {
                put("reading", id); put("position", index); put("text", text)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }
    private fun sentences(id: String): List<String> = readableDatabase.query("reading_sentences", arrayOf("text"), "reading=?", arrayOf(id), null, null, "position ASC").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    fun reading(id: String): SavedReading = readableDatabase.query("readings", arrayOf("title", "host", "language", "url"), "id=?", arrayOf(id), null, null, null).use { c ->
        check(c.moveToFirst()) { "This saved reading is no longer available." }
        SavedReading(id, c.getString(0), c.getString(1), c.getString(2), c.getString(3), sentences(id))
    }
    fun facts(): Map<String, ArticleFacts> = readableDatabase.query("readings", arrayOf("url", "language", "words"), "url != ''", null, null, null, "created ASC").use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), ArticleFacts(c.getString(1), c.getInt(2))) }
    }
    fun addBookmark(readingId: String, sentence: Int, note: String): String {
        val db = writableDatabase
        db.beginTransaction()
        try {
            BookmarkRules.validate(reading(readingId), sentence)
            val id = UUID.randomUUID().toString()
            db.insertOrThrow("bookmarks", null, ContentValues().apply {
                put("id", id); put("reading", readingId); put("sentence", sentence); put("note", note.trim()); put("version", 1); put("created", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }
    fun bookmarks(): List<Bookmark> = readableDatabase.rawQuery("SELECT b.id,b.reading,r.title,b.sentence,s.text,b.note,b.version FROM bookmarks b JOIN readings r ON r.id=b.reading JOIN reading_sentences s ON s.reading=b.reading AND s.position=b.sentence ORDER BY b.created DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Bookmark(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getInt(6))) }
    }
    fun updateBookmark(id: String, expectedVersion: Int, note: String) {
        val count = writableDatabase.update("bookmarks", ContentValues().apply { put("note", note.trim()); put("version", expectedVersion + 1) }, "id=? AND version=?", arrayOf(id, expectedVersion.toString()))
        check(count == 1) { "This bookmark changed. Reopen it before saving your note." }
    }
    fun deleteBookmark(id: String, expectedVersion: Int) {
        check(writableDatabase.delete("bookmarks", "id=? AND version=?", arrayOf(id, expectedVersion.toString())) == 1) { "This bookmark changed. Reopen the list and try again." }
    }
    fun clearUnbookmarked(keepReadingId: String = ""): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("reading_sentences", "reading != ? AND reading NOT IN (SELECT reading FROM bookmarks)", arrayOf(keepReadingId))
            val removed = db.delete("readings", "id != ? AND id NOT IN (SELECT reading FROM bookmarks)", arrayOf(keepReadingId))
            db.setTransactionSuccessful()
            return removed
        } finally { db.endTransaction() }
    }

    fun searchReadings(query: String): List<LibraryResult> = readableDatabase.query("readings", arrayOf("id", "title", "host"), null, null, null, null, "created DESC").use { c ->
        buildList {
            while (c.moveToNext()) {
                val sentences = sentences(c.getString(0))
                if (LibraryMatch.matches(query, c.getString(1), c.getString(2), sentences.joinToString("\n"))) {
                    val index = sentences.indexOfFirst { LibraryMatch.matches(query, it) }.coerceAtLeast(0)
                    add(LibraryResult("reading:${c.getString(0)}", c.getString(1), "Saved reading · ${sentences[index]}", readingId = c.getString(0), sentence = index))
                }
            }
        }
    }
}
