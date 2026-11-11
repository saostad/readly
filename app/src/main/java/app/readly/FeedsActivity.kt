package app.readly

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.readly.feeds.FeedLimits
import app.readly.feeds.FeedRepository
import app.readly.feeds.FeedSite
import app.readly.feeds.FindMode
import app.readly.feeds.HeadlineSource
import app.readly.feeds.LoadedHeadlines
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Settings → Android Auto websites: add and remove the sites shown in the car, choose
 * how each one's articles are found, preview the titles the car will show (tap one to
 * read it on the phone), and set the list limits.
 */
class FeedsActivity : Activity() {

    private lateinit var feeds: FeedRepository
    private lateinit var sitesBox: LinearLayout
    private lateinit var addError: TextView
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "readly-feeds-ui") }

    /** Site ids whose preview is open, so a redraw keeps them open. */
    private val openPreviews = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feeds)
        feeds = (application as ReadlyApp).feeds
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { closeWithTransition() }
        sitesBox = findViewById(R.id.sitesContainer)
        addError = findViewById(R.id.addError)
        val urlEdit = findViewById<EditText>(R.id.siteUrl)
        val nameEdit = findViewById<EditText>(R.id.siteName)
        findViewById<Button>(R.id.addSite).setOnClickListener {
            try {
                val site = feeds.add(urlEdit.text.toString(), nameEdit.text.toString())
                urlEdit.text.clear()
                nameEdit.text.clear()
                addError.visibility = View.GONE
                // Show the new site's titles right away, so a site without articles is obvious.
                openPreviews += site.id
                renderSites()
            } catch (e: IllegalArgumentException) {
                showAddError(e.message ?: "That website couldn't be added.")
            } catch (e: java.io.IOException) {
                Log.e("Readly", "Adding an Android Auto website failed", e)
                showAddError(e.message ?: "That website couldn't be added.")
            }
        }
        setupLimits()
        renderSites()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
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

    private fun showAddError(message: String) {
        addError.text = message
        addError.visibility = View.VISIBLE
    }

    private fun setupLimits() {
        val maxEdit = findViewById<EditText>(R.id.maxArticles)
        val refreshEdit = findViewById<EditText>(R.id.refreshMinutes)
        val status = findViewById<TextView>(R.id.limitsStatus)
        // Saved at once (no Save tap); the car's "Play next" button changes the same setting.
        findViewById<CheckBox>(R.id.playNext).apply {
            isChecked = feeds.isPlayNextOn()
            setOnCheckedChangeListener { _, checked -> feeds.setPlayNext(checked) }
        }
        val limits = feeds.limits()
        maxEdit.setText(limits.maxArticles.toString())
        refreshEdit.setText(limits.refreshMinutes.toString())
        findViewById<Button>(R.id.saveLimits).setOnClickListener {
            val max = FeedLimits.parseMaxArticles(maxEdit.text.toString())
            val refresh = FeedLimits.parseRefreshMinutes(refreshEdit.text.toString())
            val error = max.exceptionOrNull() ?: refresh.exceptionOrNull()
            status.visibility = View.VISIBLE
            if (error != null) {
                status.text = error.message
                status.setTextColor(getColor(R.color.error))
                return@setOnClickListener
            }
            feeds.saveLimits(FeedLimits(max.getOrThrow(), refresh.getOrThrow()))
            status.setText(R.string.auto_sites_saved)
            status.setTextColor(getColor(R.color.text2))
        }
    }

    private fun renderSites() {
        sitesBox.removeAllViews()
        val sites = try {
            feeds.sites()
        } catch (e: java.io.IOException) {
            sitesBox.addView(small(e.message ?: "Your websites couldn't be read.", error = true))
            return
        }
        if (sites.isEmpty()) {
            sitesBox.addView(small(getString(R.string.auto_sites_empty)))
            return
        }
        for (site in sites) sitesBox.addView(siteRow(site))
    }

    private fun siteRow(site: FeedSite): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            setPadding(dp(14), dp(12), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        row.addView(TextView(this, null, 0, R.style.CardTitle).apply { text = site.label() })
        row.addView(small(site.url))
        val previewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previewButton = textButton(
            getString(if (site.id in openPreviews) R.string.auto_sites_hide else R.string.auto_sites_preview),
        )
        previewButton.setOnClickListener {
            if (site.id in openPreviews) openPreviews -= site.id else openPreviews += site.id
            renderSites()
        }
        val modeButton = textButton(
            getString(if (site.mode == FindMode.AUTO) R.string.auto_sites_mode_auto else R.string.auto_sites_mode_links),
        )
        modeButton.setOnClickListener {
            val next = if (site.mode == FindMode.AUTO) FindMode.PAGE_LINKS else FindMode.AUTO
            change { feeds.setMode(site.id, next) }
        }
        val removeButton = Button(this, null, 0, R.style.Btn_TextError).apply {
            text = getString(R.string.auto_sites_remove)
            setOnClickListener {
                openPreviews -= site.id
                change { feeds.remove(site.id) }
            }
        }
        buttons.addView(previewButton)
        buttons.addView(modeButton)
        buttons.addView(removeButton)
        row.addView(buttons)
        row.addView(previewBox)
        if (site.id in openPreviews) loadPreview(site, previewBox)
        return row
    }

    private fun loadPreview(site: FeedSite, box: LinearLayout) {
        box.removeAllViews()
        box.addView(small(getString(R.string.auto_sites_loading)))
        executor.execute {
            val result = try {
                Result.success(feeds.headlines(site))
            } catch (e: Exception) {
                Log.e("Readly", "Previewing Android Auto titles for ${site.url} (site=${site.id}) failed", e)
                Result.failure(e)
            }
            main.post {
                if (isDestroyed || !box.isAttachedToWindow) return@post
                box.removeAllViews()
                result.fold(
                    onSuccess = { showPreview(it, box) },
                    onFailure = { box.addView(small(it.message ?: it.javaClass.simpleName, error = true)) },
                )
            }
        }
    }

    private fun showPreview(loaded: LoadedHeadlines, box: LinearLayout) {
        val source = when (loaded.list.source) {
            HeadlineSource.FEED -> "From this feed"
            HeadlineSource.LINKED_FEED -> "From the site's feed: ${loaded.list.feedUrl}"
            HeadlineSource.PAGE_LINKS -> "From the page's links"
        }
        box.addView(small("$source · ${loaded.list.headlines.size} articles"))
        for (notice in loaded.notices) box.addView(small(notice, error = loaded.stale))
        for (headline in loaded.list.headlines) {
            box.addView(
                TextView(this, null, 0, R.style.Body).apply {
                    text = headline.title
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(R.drawable.btn_text)
                    // Reads on the phone with the full article engine.
                    setOnClickListener {
                        try {
                            startActivity(ArticleActivity.intent(this@FeedsActivity, headline.url))
                        } catch (e: Exception) {
                            Log.e("Readly", "Opening ${headline.url} from the Android Auto preview failed", e)
                            Toast.makeText(this@FeedsActivity, "Couldn't open that article.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }
    }

    /** Applies a change to the saved list, reporting a list that can't be read. */
    private fun change(block: () -> Unit) {
        try {
            block()
        } catch (e: java.io.IOException) {
            Log.e("Readly", "Changing the Android Auto websites failed", e)
            Toast.makeText(this, e.message ?: "Your websites couldn't be changed.", Toast.LENGTH_LONG).show()
        }
        renderSites()
    }

    private fun textButton(label: String): Button =
        Button(this, null, 0, R.style.Btn_Text).apply { text = label }

    private fun small(text: String, error: Boolean = false): TextView =
        TextView(this, null, 0, R.style.Small).apply {
            this.text = text
            setPadding(0, dp(4), 0, dp(2))
            if (error) setTextColor(getColor(R.color.error))
        }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()
}
