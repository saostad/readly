package app.readly

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.*
import app.readly.input.pdf.DocumentPlayback
import app.readly.library.*
import app.readly.playback.ReaderService
import app.readly.ui.BookmarkDialogs

/** Local library search and saved positions. All database work stays off the UI thread. */
class LibraryActivity : Activity() {
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private var bookmarks = false
    private var generation = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookmarks = savedInstanceState?.getBoolean("bookmarks") ?: intent.getBooleanExtra("bookmarks", false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        root.addView(button("Back") { finish() })
        val tabs = LinearLayout(this)
        tabs.addView(button("Search") { bookmarks = false; load() }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(button("Bookmarks") { bookmarks = true; load() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)
        query = EditText(this).apply {
            hint = "Search articles, documents, history and notes"
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(savedInstanceState?.getString("query").orEmpty())
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { load(); true } else false }
        }
        root.addView(query)
        root.addView(button("Search library") { load() })
        status = TextView(this).apply { setTextColor(getColor(R.color.text2)); setPadding(0, 12, 0, 12) }
        root.addView(status)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    override fun onStart() { super.onStart(); load() }
    override fun onStop() { generation++; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("bookmarks", bookmarks); outState.putString("query", query.text.toString()); super.onSaveInstanceState(outState)
    }
    private fun button(label: String, action: () -> Unit) = Button(this, null, 0, R.style.Btn_Text).apply { text = label; setOnClickListener { action() } }
    private fun load() {
        val version = ++generation
        val text = query.text.toString()
        val showBookmarks = bookmarks
        results.removeAllViews(); status.text = "Loading…"; status.setTextColor(getColor(R.color.text2))
        val app = application as ReadlyApp
        app.ioExecutor.execute {
            try {
                val marks = if (showBookmarks) ReadingStore(this).use { it.bookmarks().filter { b -> LibraryMatch.matches(text, b.title, b.quote, b.note) } } else emptyList()
                val found = if (showBookmarks) emptyList() else LibraryService(this, app.feeds).search(text)
                runOnUiThread {
                    if (version != generation || isDestroyed || isFinishing) return@runOnUiThread
                    status.text = if (showBookmarks) "${marks.size} bookmarks" else "${found.size} results · Searches locally saved headlines and text. Refresh feeds to include newer articles."
                    if (showBookmarks) marks.forEach(::bookmarkRow) else found.forEach(::resultRow)
                    if (results.childCount == 0) status.text = if (showBookmarks) "No bookmarks match. Use Bookmark on the Now playing screen to save a sentence." else "No matches in your local library. Refresh feeds or import a document, then search again."
                }
            } catch (error: Exception) {
                Log.e("Readly", "Library load failed mode=${if (showBookmarks) "bookmarks" else "search"}", error)
                runOnUiThread { if (version == generation && !isDestroyed) {
                    status.text = "Couldn't load the library. Reopen it and try again. Your saved items have not been changed."
                    status.setTextColor(getColor(R.color.error))
                } }
            }
        }
    }
    private fun resultRow(item: LibraryResult) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
        box.addView(TextView(this).apply { text = item.title; textSize = 17f; setTextColor(getColor(R.color.text)) })
        box.addView(TextView(this).apply { text = item.detail; maxLines = 4; setTextColor(getColor(R.color.text2)) })
        box.addView(button("Read") { open(item) })
        results.addView(box)
    }
    private fun open(item: LibraryResult) {
        try {
            when {
                item.readingId != null -> {
                    ReaderService.readSaved(this, item.readingId, item.sentence)
                    startActivity(Intent(this, NowPlayingActivity::class.java))
                }
                item.documentId != null -> DocumentPlayback.read(this, item.documentId)
                item.url != null -> startActivity(ArticleActivity.intent(this, item.url))
            }
        } catch (error: Exception) {
            Log.e("Readly", "Couldn't open library result key=${item.key.substringBefore(':')}", error)
            Toast.makeText(this, "Couldn't open this item. Reopen your library and try again.", Toast.LENGTH_LONG).show()
        }
    }
    private fun bookmarkRow(mark: Bookmark) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
        box.addView(TextView(this).apply { text = "${mark.title} · Sentence ${mark.sentence + 1}"; textSize = 17f; setTextColor(getColor(R.color.text)) })
        box.addView(TextView(this).apply { text = mark.quote; setTextColor(getColor(R.color.text2)) })
        if (mark.note.isNotBlank()) box.addView(TextView(this).apply { text = mark.note; setTextColor(getColor(R.color.accent)) })
        box.addView(button("Read from here") { open(LibraryResult(mark.id, mark.title, "", readingId = mark.readingId, sentence = mark.sentence)) })
        box.addView(button("Edit note") { BookmarkDialogs.edit(this, mark, ::load) })
        box.addView(button("Delete bookmark") {
            AlertDialog.Builder(this).setTitle("Delete bookmark?").setMessage("The saved reading will remain in your library.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    (application as ReadlyApp).ioExecutor.execute {
                        try {
                            ReadingStore(this).use { it.deleteBookmark(mark.id, mark.version) }
                            runOnUiThread { if (!isDestroyed) load() }
                        } catch (error: Exception) {
                            Log.e("Readly", "Couldn't delete bookmark id=${mark.id}", error)
                            runOnUiThread { if (!isDestroyed) Toast.makeText(this, "Couldn't delete this bookmark. Reopen the list and try again.", Toast.LENGTH_LONG).show() }
                        }
                    }
                }.show()
        })
        results.addView(box)
    }
}
