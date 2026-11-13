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
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import app.readly.input.pdf.DocumentPlayback
import app.readly.input.pdf.PdfImportStore
import app.readly.R
import app.readly.ReadlyApp
import app.readly.playback.BrowseRefreshRequests
import app.readly.playback.CarBrowser
import app.readly.playback.ReaderService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone's compact browser: fixed navigation, website rows and a document Library.
 * Article lists, heard marks and Shuffle actions are shared with the car.
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
    private val onNavigate: () -> Unit = {},
    private val onRefreshingChanged: (Boolean) -> Unit = {},
    private val onManageWebsites: () -> Unit,
) {
    private companion object {
        const val TAG = "Readly"
        const val PREFS = "home_ui"
        const val KEY_TAB = "tab"
        const val KEY_LIBRARY_DOCUMENTS = "libraryDocuments"

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
    private var lastItems: List<MediaItem> = emptyList()
    private var libraryDocuments = prefs.getBoolean(KEY_LIBRARY_DOCUMENTS, true)
    private var renderGeneration = 0
    private var artworkNoticeShown = false
    private var activeRequestAt: Long? = null
    private var refreshing = false

    init {
        back.setOnClickListener { onBack() }
    }

    private val subscription = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>) =
            received(parentId, children, null)

        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>, options: Bundle) =
            received(parentId, children, options.requestTime())

        override fun onError(parentId: String) = listFailed(parentId, null)
        override fun onError(parentId: String, options: Bundle) = listFailed(parentId, options.requestTime())
    }

    private fun Bundle.requestTime(): Long? =
        if (containsKey(BrowseRefreshRequests.EXTRA_REQUEST_AT)) getLong(BrowseRefreshRequests.EXTRA_REQUEST_AT) else null

    private fun received(parentId: String, children: MutableList<MediaItem>, requestedAt: Long?) {
        if (parentId == browser?.root) showTabs(children)
        else if (parentId == subscribed && requestedAt == activeRequestAt) {
            render(parentId, children)
            if (parentId != CarBrowser.SAVED || !libraryDocuments) finishRefresh()
        }
    }

    private fun listFailed(parentId: String, requestedAt: Long?) {
        if (parentId == browser?.root || (parentId == subscribed && requestedAt == activeRequestAt)) {
            Log.e(TAG, "Home list $parentId failed to load or refresh")
            finishRefresh()
            showMessage(activity.getString(R.string.feed_refresh_failed), error = true)
        }
    }

    private fun finishRefresh() {
        refreshing = false
        onRefreshingChanged(false)
    }

    /** A user-requested reload bypasses feed TTL without changing this screen or playback. */
    fun refresh() {
        if (refreshing) return
        val parent = subscribed
        if (parent == null || browser?.isConnected != true) {
            Log.e(TAG, "Feed refresh requested before the media browser was connected")
            Toast.makeText(activity, R.string.home_connect_failed, Toast.LENGTH_LONG).show()
            finishRefresh()
            return
        }
        refreshing = true
        onRefreshingChanged(true)
        if (parent == CarBrowser.SAVED && libraryDocuments) render(parent, lastItems)
        else {
            browser?.unsubscribe(parent)
            subscribe(parent, force = true)
        }
    }

    private fun subscribe(parent: String, force: Boolean) {
        val b = browser ?: return
        activeRequestAt = if (force) SystemClock.elapsedRealtimeNanos() else null
        try {
            val at = activeRequestAt
            if (at == null) b.subscribe(parent, subscription)
            else b.subscribe(parent, Bundle().apply { putLong(BrowseRefreshRequests.EXTRA_REQUEST_AT, at) }, subscription)
        } catch (error: Exception) {
            Log.e(TAG, "Couldn't subscribe to feed list parent=$parent", error)
            finishRefresh()
            showMessage(activity.getString(R.string.feed_refresh_failed), error = true)
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
            finishRefresh()
            showMessage(activity.getString(R.string.home_connect_failed), error = true)
        }

        override fun onConnectionSuspended() {
            Log.w(TAG, "Home lost the reader's media browser; reconnecting on the next start")
            controller = null
            finishRefresh()
            showMessage(activity.getString(R.string.home_connect_failed), error = true)
        }
    }

    fun start() {
        if (browser != null) return
        if (tabs.isEmpty()) showMessage(activity.getString(R.string.home_connecting), error = false)
        browser = MediaBrowser(activity, ComponentName(activity, ReaderService::class.java), connection, null)
            .also { it.connect() }
    }

    fun stop() {
        finishRefresh()
        activeRequestAt = null
        val b = browser ?: return
        if (b.isConnected) {
            subscribed?.let { b.unsubscribe(it) }
            b.unsubscribe(b.root)
        }
        b.disconnect()
        browser = null
        controller = null
        subscribed = null
        renderGeneration++
    }

    fun close() {
        stop()
        artExecutor.shutdownNow()
    }

    /** Goes up out of a website's list; false when already at a tab. */
    fun onBack(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        onNavigate()
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
            val label = if (tab.mediaId == CarBrowser.SAVED) activity.getString(R.string.library_title) else tab.description.title
            val icon = when (tab.mediaId) {
                CarBrowser.WEBSITES -> R.drawable.ic_globe
                CarBrowser.LATEST -> R.drawable.ic_latest
                CarBrowser.SAVED -> R.drawable.ic_library
                else -> R.drawable.ic_recent
            }
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(60)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                isSelected = selected
                foreground = activity.getDrawable(selectableBackground())
                contentDescription = label
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { selectTab(tab.mediaId) }
            }
            cell.addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = activity.getColorStateList(if (selected) R.color.accent else R.color.text3)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            cell.addView(text(label, 11.5f, if (selected) R.color.text else R.color.text3, bold = selected, lines = 1)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
            tabRow.addView(cell)
        }
    }

    fun refreshLayout() {
        subscribed?.let { open(it) }
    }

    private fun selectTab(id: String?) {
        id ?: return
        // Tapping the open tab again reloads it (and retries after an error).
        currentTab = id
        onNavigate()
        stack.clear()
        prefs.edit().putString(KEY_TAB, id).apply()
        drawTabs()
        open(id)
    }

    /** Subscribes to list [id], replacing the shown one. */
    private fun open(id: String) {
        finishRefresh()
        val b = browser?.takeIf { it.isConnected } ?: return
        val parent = BrowseRefreshRequests.parent(id)
        subscribed?.let { b.unsubscribe(it) }
        subscribed = parent
        renderGeneration++
        if (undo?.listId != parent) undo = null
        val level = stack.lastOrNull()
        header.visibility = if (level != null) View.VISIBLE else View.GONE
        headerTitle.text = level?.title.orEmpty()
        showMessage(activity.getString(R.string.home_connecting), error = false)
        val force = parent != id
        if (force) {
            refreshing = true
            onRefreshingChanged(true)
        }
        subscribe(parent, force)
    }

    // ------------------------------------------------------------ lists ---

    private fun render(parentId: String, items: List<MediaItem>) {
        val generation = ++renderGeneration
        lastItems = items
        content.removeAllViews()
        undo?.takeIf { it.listId == parentId }?.let { content.addView(undoBar(it)) }
        if (parentId == CarBrowser.SAVED) {
            libraryFilters()
            if (libraryDocuments) {
                renderDocuments(generation)
                return
            }
        }
        if (parentId == CarBrowser.WEBSITES) {
            content.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(activity.getString(R.string.websites_heading), 18f, R.color.text, bold = true, lines = 1),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(activity, null, 0, R.style.Btn_Text).apply {
                    setText(R.string.home_add_website)
                    minimumHeight = dp(48)
                    setOnClickListener { onManageWebsites() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) })
            })
        }
        val compact = WebsiteLayout.compact(activity)
        val tiles = if (compact) emptyList() else items.filter { it.isBrowsable && it.description.iconUri != null }
        (items - tiles.toSet()).forEach { content.addView(row(it)) }
        val websites = parentId == CarBrowser.WEBSITES
        if (tiles.isNotEmpty() || (websites && !compact)) grid(tiles)
    }

    private fun libraryFilters() {
        content.addView(text(activity.getString(R.string.library_title), 18f, R.color.text, bold = true, lines = 1)
            .apply { setPadding(0, dp(4), 0, dp(8)) })
        val filters = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((documents, label) in listOf(true to R.string.library_documents, false to R.string.library_articles)) {
            filters.addView(Button(activity, null, 0, R.style.Btn_Text).apply {
                setText(label)
                minimumHeight = dp(48)
                isSelected = libraryDocuments == documents
                setTextColor(activity.getColor(if (isSelected) R.color.accent else R.color.text2))
                setOnClickListener {
                    finishRefresh()
                    libraryDocuments = documents
                    prefs.edit().putBoolean(KEY_LIBRARY_DOCUMENTS, documents).apply()
                    onNavigate()
                    subscribed?.let { render(it, lastItems) }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(filters)
    }

    private fun renderDocuments(generation: Int) {
        val loading = text(activity.getString(R.string.library_loading), 13f, R.color.text2, lines = 2)
        content.addView(loading)
        (activity.application as ReadlyApp).ioExecutor.execute {
            try {
                val imports = PdfImportStore(activity).use { it.list() }
                main.post {
                    if (generation == renderGeneration && !activity.isDestroyed && !activity.isFinishing) {
                        content.removeView(loading)
                        if (imports.isEmpty()) content.addView(text(activity.getString(R.string.library_empty), 14f, R.color.text2, lines = 4)
                            .apply { setPadding(0, dp(16), 0, dp(16)) })
                        for (item in imports) content.addView(documentRow(item))
                        finishRefresh()
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Couldn't load the document library", error)
                main.post {
                    if (generation == renderGeneration && !activity.isDestroyed && !activity.isFinishing) {
                        loading.text = activity.getString(R.string.document_operation_failed)
                        loading.setTextColor(activity.getColor(R.color.error))
                        finishRefresh()
                    }
                }
            }
        }
    }

    private fun documentRow(item: PdfImportStore.Import): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        isFocusable = true
        foreground = activity.getDrawable(selectableBackground())
        addView(ImageView(activity).apply {
            setImageResource(if (item.kind == "image") R.drawable.ic_image else R.drawable.ic_text)
            imageTintList = activity.getColorStateList(R.color.accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(item.title, 15f, R.color.text, bold = true, lines = 2))
        val state = activity.getString(when (item.status) {
            "ready" -> R.string.library_ready
            "failed" -> R.string.library_failed
            "cancelled" -> R.string.library_cancelled
            else -> R.string.library_processing
        })
        val kind = activity.getString(if (item.kind == "image") R.string.library_picture else R.string.library_pdf)
        labels.addView(text("$kind · $state", 12f, if (item.status == "failed") R.color.error else R.color.text2, lines = 1))
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { DocumentPlayback.read(activity, item.id) }
    }

    private fun grid(items: List<MediaItem>) {
        val columns = if (activity.resources.configuration.screenWidthDp >= WIDE_SCREEN_DP) 3 else 2
        val cells: List<View> = items.map { tile(it) }
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
            minimumHeight = dp(64)
            setPadding(0, dp(8), 0, dp(8))
        }
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val siteArt = item.isBrowsable && item.description.iconUri != null
        val icon = when {
            shuffle -> R.drawable.ic_shuffle
            item.isPlayable && heard -> R.drawable.ic_check
            item.isPlayable -> R.drawable.ic_play
            else -> null
        }
        if (siteArt) {
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = activity.getDrawable(R.drawable.tile_background)
                clipToOutline = true
            }
            line.addView(image, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            loadArt(item.description.iconUri!!, image)
        } else if (icon != null) {
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
            texts.addView(text(it, 12.5f, R.color.text3, lines = 1).apply { setPadding(0, dp(3), 0, 0) })
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
        if (!notice) {
            outer.isClickable = true
            outer.isFocusable = true
            outer.foreground = activity.getDrawable(selectableBackground())
            outer.setOnClickListener {
                when {
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
        onNavigate()
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
                Log.e(TAG, "Couldn't load website artwork", e)
                null
            }
            if (bitmap == null) Log.w(TAG, "fallback=true attempted=site-artwork reason=unreadable_image taken=generic_icon")
            main.post {
                if (bitmap != null) artCache.put(key, bitmap)
                if (into.tag == key) {
                    if (bitmap != null) into.setImageBitmap(bitmap)
                    else {
                        into.setImageResource(R.drawable.ic_globe)
                        into.contentDescription = activity.getString(R.string.website_artwork_unavailable)
                        if (!artworkNoticeShown && !activity.isFinishing && !activity.isDestroyed) {
                            artworkNoticeShown = true
                            Toast.makeText(activity, R.string.website_artwork_unavailable, Toast.LENGTH_LONG).show()
                        }
                    }
                }
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
