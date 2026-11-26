package app.readly

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.readly.input.pdf.DocumentPlayback
import app.readly.library.Bookmark
import app.readly.library.LibraryMatch
import app.readly.library.LibraryResult
import app.readly.library.LibraryService
import app.readly.library.ReadingStore
import app.readly.playback.ReaderService
import app.readly.ui.BookmarkDialogs
import app.readly.ui.SettingsWidgets.dp

/** Local library search (and fallback bookmarks viewer). All database work stays off the UI thread. */
class LibraryActivity : Activity() {
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private var bookmarks = false
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookmarks = savedInstanceState?.getBoolean("bookmarks") ?: intent.getBooleanExtra("bookmarks", false)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Top app bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(this@LibraryActivity, 56)
            setPadding(dp(this@LibraryActivity, 12), dp(this@LibraryActivity, 6), dp(this@LibraryActivity, 12), dp(this@LibraryActivity, 6))
        }
        bar.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_back)
                setBackgroundResource(R.drawable.icon_circle_background)
                imageTintList = getColorStateList(R.color.text2)
                contentDescription = getString(R.string.back_desc)
                setOnClickListener { closeWithTransition() }
            },
            LinearLayout.LayoutParams(dp(this, 48), dp(this, 48)),
        )
        bar.addView(
            TextView(this, null, 0, R.style.Title).apply {
                setText(if (bookmarks) R.string.library_bookmarks else R.string.search_desc)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(this@LibraryActivity, 8)
            },
        )
        root.addView(bar)
        root.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.line)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
        )

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this@LibraryActivity, 16), dp(this@LibraryActivity, 12), dp(this@LibraryActivity, 16), dp(this@LibraryActivity, 16))
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        query = EditText(this, null, 0, R.style.BoxEdit).apply {
            hint = "Search articles, PDFs, history, and notes"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(savedInstanceState?.getString("query").orEmpty())
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    load()
                    true
                } else {
                    false
                }
            }
        }
        searchRow.addView(query, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(
            Button(this, null, 0, R.style.Btn_Primary).apply {
                setText(R.string.library_search_action)
                setOnClickListener { load() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(this@LibraryActivity, 8)
            },
        )
        body.addView(searchRow)

        status = TextView(this, null, 0, R.style.Small).apply {
            setPadding(0, dp(this@LibraryActivity, 10), 0, dp(this@LibraryActivity, 10))
        }
        body.addView(status)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(
            ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    override fun onStop() {
        generation++
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("bookmarks", bookmarks)
        outState.putString("query", query.text.toString())
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.screen_close_in, R.anim.screen_close_out)
    }

    private fun closeWithTransition() {
        finish()
        overridePendingTransition(R.anim.screen_close_in, R.anim.screen_close_out)
    }

    private fun load() {
        val version = ++generation
        val text = query.text.toString()
        val showBookmarks = bookmarks
        results.removeAllViews()
        status.text = "Loading…"
        status.setTextColor(getColor(R.color.text2))
        val app = application as ReadlyApp
        app.ioExecutor.execute {
            try {
                val marks = if (showBookmarks) {
                    ReadingStore(this).use {
                        it.bookmarks().filter { b -> LibraryMatch.matches(text, b.title, b.quote, b.note) }
                    }
                } else {
                    emptyList()
                }
                val found = if (showBookmarks) emptyList() else LibraryService(this, app.feeds).search(text)
                runOnUiThread {
                    if (version != generation || isDestroyed || isFinishing) return@runOnUiThread
                    status.text = if (showBookmarks) {
                        "${marks.size} bookmarks"
                    } else {
                        "${found.size} results · Searches locally saved headlines, documents & history"
                    }
                    if (showBookmarks) marks.forEach(::bookmarkRow) else found.forEach(::resultRow)
                    if (results.childCount == 0) {
                        status.text = if (showBookmarks) {
                            getString(R.string.library_bookmarks_empty)
                        } else {
                            "No matches in your local library. Refresh feeds or import a document, then search again."
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("Readly", "Library load failed mode=${if (showBookmarks) "bookmarks" else "search"}", error)
                runOnUiThread {
                    if (version == generation && !isDestroyed) {
                        status.text = "Couldn't load the library. Reopen it and try again. Your saved items have not been changed."
                        status.setTextColor(getColor(R.color.error))
                    }
                }
            }
        }
    }

    private fun resultRow(item: LibraryResult) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            setPadding(dp(this@LibraryActivity, 14), dp(this@LibraryActivity, 12), dp(this@LibraryActivity, 14), dp(this@LibraryActivity, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(this@LibraryActivity, 10) }
        }
        box.addView(TextView(this, null, 0, R.style.CardTitle).apply { text = item.title })
        box.addView(
            TextView(this, null, 0, R.style.Small).apply {
                text = item.detail
                maxLines = 4
                setPadding(0, dp(this@LibraryActivity, 4), 0, dp(this@LibraryActivity, 8))
            },
        )
        box.addView(
            Button(this, null, 0, R.style.Btn_Ghost).apply {
                text = "Read aloud"
                setOnClickListener { open(item) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        results.addView(box)
    }

    private fun open(item: LibraryResult, fromBookmark: Boolean = false) {
        try {
            when {
                item.readingId != null -> {
                    ReaderService.readSaved(this, item.readingId, item.sentence, fromBookmark)
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            setPadding(dp(this@LibraryActivity, 14), dp(this@LibraryActivity, 12), dp(this@LibraryActivity, 14), dp(this@LibraryActivity, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(this@LibraryActivity, 10) }
        }
        box.addView(TextView(this, null, 0, R.style.CardTitle).apply { text = "${mark.title} · Sentence ${mark.sentence + 1}" })
        box.addView(
            TextView(this, null, 0, R.style.Small).apply {
                text = mark.quote
                setPadding(0, dp(this@LibraryActivity, 4), 0, dp(this@LibraryActivity, 6))
            },
        )
        if (mark.note.isNotBlank()) {
            box.addView(
                TextView(this, null, 0, R.style.Small).apply {
                    text = mark.note
                    setTextColor(getColor(R.color.accent))
                    setPadding(0, 0, 0, dp(this@LibraryActivity, 6))
                },
            )
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            Button(this, null, 0, R.style.Btn_Text).apply {
                text = "Read"
                setOnClickListener {
                    open(LibraryResult(mark.id, mark.title, "", readingId = mark.readingId, sentence = mark.sentence), fromBookmark = true)
                }
            },
        )
        row.addView(
            Button(this, null, 0, R.style.Btn_Text).apply {
                text = "Edit note"
                setOnClickListener { BookmarkDialogs.edit(this@LibraryActivity, mark, ::load) }
            },
        )
        row.addView(
            Button(this, null, 0, R.style.Btn_TextError).apply {
                text = "Delete"
                setOnClickListener {
                    AlertDialog.Builder(this@LibraryActivity)
                        .setTitle("Delete bookmark?")
                        .setMessage("The saved reading will remain in your library.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            (application as ReadlyApp).ioExecutor.execute {
                                try {
                                    ReadingStore(this@LibraryActivity).use { it.deleteBookmark(mark.id, mark.version) }
                                    runOnUiThread { if (!isDestroyed) load() }
                                } catch (error: Exception) {
                                    Log.e("Readly", "Couldn't delete bookmark id=${mark.id}", error)
                                    runOnUiThread {
                                        if (!isDestroyed) {
                                            Toast.makeText(this@LibraryActivity, "Couldn't delete this bookmark. Try again.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }.show()
                }
            },
        )
        box.addView(row)
        results.addView(box)
    }
}
