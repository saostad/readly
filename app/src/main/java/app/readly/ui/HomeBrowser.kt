package app.readly.ui

import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.browse.MediaBrowser
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaController
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import app.readly.R
import app.readly.ReadlyApp
import app.readly.playback.CarBrowser
import app.readly.playback.ReaderService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone's home screen in the Android Auto layout: the same tabs (Websites as
 * tiles, Latest, Saved, Recents), rows, heard marks and Shuffle rows as the car.
 * It is a [MediaBrowser] client of [ReaderService], so the lists come from the very
 * [CarBrowser] the car uses and a tap plays through the same `playFromMediaId`.
 */
class HomeBrowser(
    private val activity: Activity,
    private val tabRow: LinearLayout,
    private val header: View,
    back: ImageButton,
    private val headerTitle: TextView,
    private val content: LinearLayout,
    private val onManageWebsites: () -> Unit,
) {
    private companion object {
        const val TAG = "Readly"
        const val PREFS = "home_ui"
        const val KEY_TAB = "tab"

        // Android Auto's own extras on each row (the same keys CarBrowser sets).
        const val PLAYBACK_STATUS = "android.media.extra.PLAYBACK_STATUS"
        const val STATUS_PARTIALLY_PLAYED = 1
        const val STATUS_FULLY_PLAYED = 2
        const val COMPLETION_PERCENTAGE = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"

        /** Layout breakpoint: tablets and unfolded phones get a third tile column. */
        const val WIDE_SCREEN_DP = 600

        const val UNDO_TAG = "undo"
    }

    /** One screen of the browser: a list id and the title shown above it. */
    private data class Level(val id: String, val title: String)

    /** The last article action, offered as Undo at the top of the list it changed. */
    private class Undo(val listId: String, val message: String, val revert: () -> Unit)

    private val feeds get() = (activity.application as ReadlyApp).feeds
    private var undo: Undo? = null

    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
    private val artExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "readly-home-art") }
    private val artCache = LruCache<String, Bitmap>(24)

    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var tabs: List<MediaItem> = emptyList()
    private var currentTab: String? = null

    /** Folders opened inside the current tab (a website's articles), deepest last. */
    private val stack = mutableListOf<Level>()
    private var subscribed: String? = null

    init {
        back.setOnClickListener { onBack() }
    }

    private val subscription = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>) {
            if (parentId == browser?.root) {
                showTabs(children)
            } else if (parentId == subscribed) {
                render(parentId, children)
            }
        }

        override fun onError(parentId: String) {
            Log.e(TAG, "Home list $parentId failed to load")
            if (parentId == subscribed || parentId == browser?.root) {
                showMessage(activity.getString(R.string.home_list_failed), error = true)
            }
        }
    }

    private val connection = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val b = browser ?: return
            controller = MediaController(activity, b.sessionToken)
            b.subscribe(b.root, subscription)
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "Home couldn't connect to the reader's media browser")
            showMessage(activity.getString(R.string.home_connect_failed), error = true)
        }

        override fun onConnectionSuspended() {
            Log.w(TAG, "Home lost the reader's media browser; reconnecting on the next start")
            controller = null
        }
    }

    fun start() {
        if (browser != null) return
        if (tabs.isEmpty()) showMessage(activity.getString(R.string.home_connecting), error = false)
        browser = MediaBrowser(activity, ComponentName(activity, ReaderService::class.java), connection, null)
            .also { it.connect() }
    }

    fun stop() {
        val b = browser ?: return
        if (b.isConnected) {
            subscribed?.let { b.unsubscribe(it) }
            b.unsubscribe(b.root)
        }
        b.disconnect()
        browser = null
        controller = null
        subscribed = null
    }

    fun close() {
        stop()
        artExecutor.shutdownNow()
    }

    /** Goes up out of a website's list; false when already at a tab. */
    fun onBack(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        open(stack.lastOrNull()?.id ?: currentTab ?: return false)
        return true
    }

    // ------------------------------------------------------------ tabs ---

    private fun showTabs(items: List<MediaItem>) {
        val firstTime = tabs.isEmpty()
        tabs = items
        val saved = prefs.getString(KEY_TAB, null)
        if (currentTab == null || items.none { it.mediaId == currentTab }) {
            currentTab = items.firstOrNull { it.mediaId == saved }?.mediaId ?: items.firstOrNull()?.mediaId
        }
        drawTabs()
        if (firstTime || subscribed == null) open(stack.lastOrNull()?.id ?: currentTab ?: return)
    }

    private fun drawTabs() {
        tabRow.removeAllViews()
        for (tab in tabs) {
            val selected = tab.mediaId == currentTab
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                foreground = activity.getDrawable(selectableBackground())
                contentDescription = tab.description.title
                setOnClickListener { selectTab(tab.mediaId) }
            }
            cell.addView(
                TextView(activity).apply {
                    text = tab.description.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(activity.getColor(if (selected) R.color.text else R.color.text3))
                    typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    maxLines = 1
                },
            )
            cell.addView(
                View(activity).apply {
                    background = activity.getDrawable(R.drawable.tab_indicator)
                    visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(3))
                },
            )
            tabRow.addView(cell)
        }
    }

    private fun selectTab(id: String?) {
        id ?: return
        // Tapping the open tab again reloads it (and retries after an error).
        currentTab = id
        stack.clear()
        prefs.edit().putString(KEY_TAB, id).apply()
        drawTabs()
        open(id)
    }

    /** Subscribes to list [id], replacing the shown one. */
    private fun open(id: String) {
        val b = browser?.takeIf { it.isConnected } ?: return
        subscribed?.let { b.unsubscribe(it) }
        subscribed = id
        if (undo?.listId != id) undo = null
        val level = stack.lastOrNull()
        header.visibility = if (level != null) View.VISIBLE else View.GONE
        headerTitle.text = level?.title.orEmpty()
        showMessage(activity.getString(R.string.home_connecting), error = false)
        b.subscribe(id, subscription)
    }

    // ------------------------------------------------------------ lists ---

    private fun render(parentId: String, items: List<MediaItem>) {
        content.removeAllViews()
        undo?.takeIf { it.listId == parentId }?.let { content.addView(undoBar(it)) }
        val tiles = items.filter { it.isBrowsable && it.description.iconUri != null }
        val rows = items - tiles.toSet()
        rows.forEach { content.addView(row(it)) }
        val websites = parentId == CarBrowser.WEBSITES
        if (tiles.isNotEmpty() || websites) {
            grid(tiles, withAddTile = websites)
        }
    }

    private fun grid(items: List<MediaItem>, withAddTile: Boolean) {
        val columns = if (activity.resources.configuration.screenWidthDp >= WIDE_SCREEN_DP) 3 else 2
        val cells: List<View> = items.map { tile(it) } + listOfNotNull(if (withAddTile) addTile() else null)
        cells.chunked(columns).forEach { chunk ->
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(14) }
            }
            chunk.forEachIndexed { i, cell ->
                cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) marginStart = dp(12) }
                line.addView(cell)
            }
            repeat(columns - chunk.size) {
                line.addView(Space(activity), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(12) })
            }
            content.addView(line)
        }
    }

    private fun tile(item: MediaItem): View {
        val art = SquareFrame(activity).apply {
            background = activity.getDrawable(R.drawable.tile_background)
            clipToOutline = true
        }
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        art.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        item.description.iconUri?.let { loadArt(it, image) }
        return tileFrame(art, item.description.title, item.description.subtitle) { openFolder(item) }
    }

    private fun addTile(): View {
        val art = SquareFrame(activity).apply { background = activity.getDrawable(R.drawable.card_background) }
        art.addView(
            ImageView(activity).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = activity.getColorStateList(R.color.accent)
                scaleType = ImageView.ScaleType.CENTER
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return tileFrame(
            art,
            activity.getString(R.string.home_add_website),
            activity.getString(R.string.home_add_website_sub),
        ) { onManageWebsites() }
    }

    private fun tileFrame(art: View, title: CharSequence?, subtitle: CharSequence?, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            foreground = activity.getDrawable(selectableBackground())
            contentDescription = title
            setOnClickListener { onClick() }
            addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text(title, 14f, R.color.text, bold = true, lines = 1).apply { setPadding(dp(2), dp(8), dp(2), 0) })
            if (!subtitle.isNullOrBlank()) {
                addView(text(subtitle, 12f, R.color.text2, lines = 1).apply { setPadding(dp(2), dp(2), dp(2), 0) })
            }
        }

    private fun row(item: MediaItem): View {
        val id = item.mediaId.orEmpty()
        val notice = CarBrowser.isNotice(id)
        val extras = item.description.extras
        val status = extras?.getInt(PLAYBACK_STATUS, 0) ?: 0
        val heard = status == STATUS_FULLY_PLAYED
        val shuffle = CarBrowser.parseShuffle(id) != null
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = when {
            shuffle -> R.drawable.ic_shuffle
            item.isPlayable && heard -> R.drawable.ic_check
            item.isPlayable -> R.drawable.ic_play
            else -> null
        }
        if (icon != null) {
            line.addView(
                ImageView(activity).apply {
                    setImageResource(icon)
                    background = activity.getDrawable(R.drawable.icon_circle_background)
                    imageTintList = activity.getColorStateList(if (heard) R.color.text3 else R.color.accent)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                },
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) },
            )
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleColor = when {
            CarBrowser.isRetry(id) -> R.color.accent
            heard -> R.color.text2
            else -> R.color.text
        }
        texts.addView(text(item.description.title, 15f, titleColor, bold = shuffle, lines = 2))
        item.description.subtitle?.takeIf { it.isNotBlank() }?.let {
            texts.addView(text(it, 12.5f, R.color.text3, lines = 2).apply { setPadding(0, dp(3), 0, 0) })
        }
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val article = CarBrowser.parseArticle(id)
        if (article != null) {
            val more = ImageButton(activity).apply {
                setImageResource(R.drawable.ic_more)
                background = activity.getDrawable(selectableBackground())
                imageTintList = activity.getColorStateList(R.color.text3)
                contentDescription = activity.getString(R.string.item_more_desc)
                setOnClickListener { showMenu(it, item, article.first, article.second) }
            }
            line.addView(more, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
            outer.setOnLongClickListener {
                showMenu(more, item, article.first, article.second)
                true
            }
        }
        outer.addView(line)
        if (status == STATUS_PARTIALLY_PLAYED) {
            val fraction = extras?.getDouble(COMPLETION_PERCENTAGE, 0.0) ?: 0.0
            outer.addView(
                ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = (fraction * 100).toInt().coerceIn(0, 100)
                    progressTintList = activity.getColorStateList(R.color.accent)
                    progressBackgroundTintList = activity.getColorStateList(R.color.surface3)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                    topMargin = dp(8)
                    marginStart = if (icon != null) dp(48) else 0
                },
            )
        }
        val websitesNotice = notice && currentTab == CarBrowser.WEBSITES && stack.isEmpty()
        if (!notice || websitesNotice) {
            outer.isClickable = true
            outer.isFocusable = true
            outer.foreground = activity.getDrawable(selectableBackground())
            outer.setOnClickListener {
                when {
                    websitesNotice -> onManageWebsites()
                    item.isPlayable -> play(id)
                    else -> openFolder(item)
                }
            }
        }
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(outer)
        wrapper.addView(
            View(activity).apply { setBackgroundColor(activity.getColor(R.color.line)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
        )
        return wrapper
    }

    private fun openFolder(item: MediaItem) {
        val id = item.mediaId ?: return
        if (CarBrowser.isRetry(id)) {
            // A retry reloads the list in place, under the same heading.
            open(id)
            return
        }
        stack += Level(id, item.description.title?.toString().orEmpty())
        open(id)
    }

    // ------------------------------------------------------- article menu ---

    private fun showMenu(anchor: View, item: MediaItem, listId: String, url: String) {
        val heard = item.description.extras?.getInt(PLAYBACK_STATUS, 0) == STATUS_FULLY_PLAYED
        val title = item.description.title?.toString().orEmpty()
        val site = item.description.extras?.getString(CarBrowser.EXTRA_SITE_LABEL).orEmpty()
        val menu = PopupMenu(activity, anchor)
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        fun add(label: Int, action: () -> Unit) {
            menu.menu.add(0, actions.size, actions.size, label)
            actions += label to action
        }
        if (heard) {
            add(R.string.item_mark_unheard) {
                val previous = feeds.forgetHistory(url)
                offerUndo(R.string.item_done_unheard) { feeds.restoreHistory(url, previous) }
            }
        } else {
            add(R.string.item_mark_heard) {
                val previous = feeds.markHeard(url, title, site)
                offerUndo(R.string.item_done_heard) { feeds.restoreHistory(url, previous) }
            }
        }
        when (listId) {
            CarBrowser.SAVED -> add(R.string.item_remove_saved) {
                val removed = feeds.takeSaved(url)
                if (removed != null) offerUndo(R.string.item_done_removed_saved) { feeds.restoreSaved(removed) }
            }
            CarBrowser.RECENTS, CarBrowser.RECENT_ROOT -> {
                add(R.string.item_save) { save(url, title) }
                add(R.string.item_remove_recent) {
                    val removed = feeds.forgetHistory(url)
                    if (removed != null) offerUndo(R.string.item_done_removed_recent) { feeds.restoreHistory(url, removed) }
                }
            }
            else -> {
                add(R.string.item_save) { save(url, title) }
                add(R.string.item_hide) {
                    feeds.hide(url)
                    offerUndo(R.string.item_done_hidden) { feeds.unhide(url) }
                }
            }
        }
        menu.setOnMenuItemClickListener { picked ->
            val (label, action) = actions[picked.itemId]
            runAction(label, listId, action)
            true
        }
        menu.show()
    }

    private fun save(url: String, title: String) {
        feeds.addSaved(url, title)
        Toast.makeText(activity, R.string.item_done_saved, Toast.LENGTH_SHORT).show()
    }

    /** Runs a menu action; a failure is reported and shown, never left looking done. */
    private fun runAction(label: Int, listId: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Log.e(TAG, "Article action \"${activity.getString(label)}\" failed in list $listId", e)
            val safe = (e as? java.io.IOException)?.message ?: (e as? IllegalArgumentException)?.message
            Toast.makeText(activity, safe ?: activity.getString(R.string.item_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun offerUndo(message: Int, revert: () -> Unit) {
        val listId = subscribed ?: return
        undo = Undo(listId, activity.getString(message), revert)
        // The list reloads on its own (the change notifies it); this shows Undo meanwhile.
        if (content.childCount > 0 && content.getChildAt(0).tag != UNDO_TAG) {
            content.addView(undoBar(undo!!), 0)
        }
    }

    private fun undoBar(pending: Undo): View = LinearLayout(activity).apply {
        tag = UNDO_TAG
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = activity.getDrawable(R.drawable.card_background)
        setPadding(dp(14), dp(6), dp(6), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
        addView(text(pending.message, 13.5f, R.color.text, lines = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            android.widget.Button(activity, null, 0, R.style.Btn_Text).apply {
                text = activity.getString(R.string.item_undo)
                setOnClickListener {
                    undo = null
                    val bar = parent as View
                    (bar.parent as? ViewGroup)?.removeView(bar)
                    runAction(R.string.item_undo, pending.listId, pending.revert)
                }
            },
        )
    }

    private fun play(mediaId: String) {
        val controls = controller?.transportControls
        if (controls == null) {
            Log.e(TAG, "Home tapped $mediaId before the media session was ready")
            Toast.makeText(activity, R.string.home_play_failed, Toast.LENGTH_LONG).show()
            return
        }
        controls.playFromMediaId(mediaId, null)
    }

    private fun showMessage(message: String, error: Boolean) {
        content.removeAllViews()
        content.addView(
            text(message, 13.5f, if (error) R.color.error else R.color.text2, lines = 6).apply {
                setPadding(0, dp(16), 0, dp(16))
            },
        )
    }

    // ------------------------------------------------------------ art ---

    private fun loadArt(uri: Uri, into: ImageView) {
        val key = uri.toString()
        artCache.get(key)?.let {
            into.setImageBitmap(it)
            return
        }
        into.tag = key
        artExecutor.execute {
            val bitmap = try {
                activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null.also {
                    Log.w(TAG, "fallback=true attempted=site-artwork reason=\"${e.javaClass.simpleName}\" taken=plain-tile")
                }
            }
            main.post {
                if (bitmap != null) artCache.put(key, bitmap)
                if (into.tag == key && bitmap != null) into.setImageBitmap(bitmap)
            }
        }
    }

    // ------------------------------------------------------------ views ---

    private fun text(value: CharSequence?, sp: Float, color: Int, bold: Boolean = false, lines: Int): TextView =
        TextView(activity).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(activity.getColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun selectableBackground(): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    /** A frame as tall as it is wide, for the website tiles. */
    private class SquareFrame(context: android.content.Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }
}
