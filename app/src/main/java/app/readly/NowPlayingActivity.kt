package app.readly

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import app.readly.model.VoiceCatalog
import app.readly.playback.ReaderService
import app.readly.playback.ReaderSnapshot
import app.readly.playback.ReaderState
import app.readly.playback.ReaderStatus
import app.readly.playback.ReadingProgress
import app.readly.ui.SettingsSheet

/**
 * The Now-playing screen: an art card, the current sentence with its
 * neighbours, progress, a time-left estimate and transport controls. Playback
 * state arrives from [ReaderState]. The FETCHING/LOADING/ERROR/PREVIEWING
 * states show in the art card subtitle, once.
 */
class NowPlayingActivity : Activity() {

    private lateinit var artSrc: TextView
    private lateinit var artTitle: TextView
    private lateinit var sentenceView: TextView
    private lateinit var sentenceBar: SeekBar

    /** True while the finger is on the bar, so sentence updates don't fight the drag. */
    private var dragging = false
    private lateinit var sentenceLabel: TextView
    private lateinit var timeLeft: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var speedChip: Button
    private lateinit var speakerChip: Button
    private lateinit var browserRow: View
    private lateinit var askRow: View
    private lateinit var askDownload: Button
    private lateinit var transportRow: View

    /** True once a non-IDLE state was seen; an IDLE after that closes the screen. */
    private var hasStarted = false

    private val readerListener = ReaderState.Listener { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        artSrc = findViewById(R.id.artSrc)
        artTitle = findViewById(R.id.artTitle)
        sentenceView = findViewById(R.id.sentenceView)
        sentenceBar = findViewById(R.id.sentenceBar)
        sentenceLabel = findViewById(R.id.sentenceLabel)
        timeLeft = findViewById(R.id.timeLeft)
        sentenceBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) showProgressLabels(ReaderState.snapshot, progress - 1)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    val snap = ReaderState.snapshot
                    val target = (bar.progress - 1).coerceIn(0, (snap.total - 1).coerceAtLeast(0))
                    if (snap.total > 0 && target != snap.index) ReaderService.seek(this@NowPlayingActivity, target)
                }
            },
        )
        playPauseButton = findViewById(R.id.npPlayPause)
        speedChip = findViewById(R.id.npSpeedChip)
        speakerChip = findViewById(R.id.npSpeakerChip)
        browserRow = findViewById(R.id.npBrowserRow)
        askRow = findViewById(R.id.npAskRow)
        askDownload = findViewById(R.id.npAskDownload)
        transportRow = findViewById(R.id.npTransportRow)
        askDownload.setOnClickListener { sendAction(ReaderService.ACTION_DOWNLOAD_VOICE) }
        findViewById<Button>(R.id.npAskReadAnyway).setOnClickListener {
            sendAction(ReaderService.ACTION_READ_ANYWAY)
        }

        findViewById<ImageButton>(R.id.closeButton).setOnClickListener { closeWithTransition() }
        findViewById<ImageButton>(R.id.npPrev).setOnClickListener {
            sendAction(ReaderService.ACTION_PREVIOUS)
        }
        playPauseButton.setOnClickListener { sendAction(ReaderService.ACTION_TOGGLE) }
        findViewById<ImageButton>(R.id.npNext).setOnClickListener {
            sendAction(ReaderService.ACTION_NEXT)
        }
        findViewById<Button>(R.id.npStopChip).setOnClickListener {
            sendAction(ReaderService.ACTION_STOP)
        }
        speedChip.setOnClickListener { openSheet() }
        speakerChip.setOnClickListener { openSheet() }
        findViewById<Button>(R.id.npOpenPage).setOnClickListener {
            ReaderState.snapshot.retryUrl?.let {
                startActivity(ArticleActivity.intent(this, it))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
            }
        }

        render(ReaderState.snapshot)
    }

    override fun onStart() {
        super.onStart()
        ReaderState.addListener(readerListener)
    }

    override fun onStop() {
        ReaderState.removeListener(readerListener)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.screen_close_in, R.anim.screen_close_out)
    }

    private fun closeWithTransition() {
        finish()
        overridePendingTransition(R.anim.screen_close_in, R.anim.screen_close_out)
    }

    private fun sendAction(action: String) {
        startService(Intent(this, ReaderService::class.java).setAction(action))
    }

    private fun openSheet() {
        SettingsSheet(this) { render(ReaderState.snapshot) }.show()
    }

    private fun render(snap: ReaderSnapshot) {
        if (snap.status == ReaderStatus.IDLE) {
            // A Stop (here, from Home or from the notification) closes the screen.
            // The guard keeps a freshly opened screen alive until playback starts.
            if (hasStarted) {
                finish()
                return
            }
        } else {
            hasStarted = true
        }
        renderArt(snap)
        renderBrowserRow(snap)
        renderAskRow(snap)
        renderSentence(snap)
        renderProgress(snap)
        playPauseButton.setImageResource(
            if (snap.status == ReaderStatus.PAUSED) R.drawable.ic_play else R.drawable.ic_pause,
        )
        renderChips(snap)
    }

    /** Art card: the title plus one subtitle line for the current state. */
    private fun renderArt(snap: ReaderSnapshot) {
        artTitle.text = snap.title.ifBlank { snap.host.ifBlank { getString(R.string.source_text) } }
        val subtitle = when (snap.status) {
            ReaderStatus.READING, ReaderStatus.PAUSED, ReaderStatus.FINISHED -> {
                val pack = VoiceCatalog.byId(snap.packId) ?: ReaderService.activePack(this)
                "${snap.host.ifBlank { getString(R.string.source_text) }} · " +
                    VoiceCatalog.shortName(pack)
            }
            ReaderStatus.FETCHING -> snap.currentText.ifBlank { getString(R.string.opening_fallback) }
            ReaderStatus.LOADING_VOICE -> getString(R.string.loading_voice)
            ReaderStatus.PREVIEWING -> snap.currentText.ifBlank {
                getString(R.string.previewing_fallback)
            }
            ReaderStatus.ERROR -> snap.errorMessage.orEmpty()
            ReaderStatus.ASK -> snap.notice.orEmpty()
            ReaderStatus.IDLE -> ""
        }
        artSrc.text = subtitle
        artSrc.setTextColor(
            getColor(if (snap.status == ReaderStatus.ERROR) R.color.error else R.color.accent),
        )
    }

    /** The retry button offers the automatic article engine for a failed eligible link only. */
    private fun renderBrowserRow(snap: ReaderSnapshot) {
        browserRow.visibility =
            if (snap.status == ReaderStatus.ERROR && !snap.retryUrl.isNullOrBlank()) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    /**
     * The Ask state (a voice for this language needs downloading) offers Download
     * and Read with the current voice in place of the transport controls, which
     * have nothing to play yet.
     */
    private fun renderAskRow(snap: ReaderSnapshot) {
        val asking = snap.status == ReaderStatus.ASK
        askRow.visibility = if (asking) View.VISIBLE else View.GONE
        // "No Persian voice is available yet." has nothing to download.
        askDownload.visibility = if (asking && snap.askPackId != null) View.VISIBLE else View.GONE
        transportRow.visibility = if (asking) View.GONE else View.VISIBLE
    }

    /** Previous sentence dimmed, the current one highlighted, the next dimmed. */
    private fun renderSentence(snap: ReaderSnapshot) {
        val show = snap.status in arrayOf(
            ReaderStatus.READING,
            ReaderStatus.PAUSED,
            ReaderStatus.FINISHED,
        ) && snap.currentText.isNotBlank()
        sentenceView.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val dim = getColor(R.color.text3)
        val ink = getColor(R.color.text)
        val highlight = getColor(R.color.accentHighlight)
        val builder = SpannableStringBuilder()
        if (snap.prevText.isNotBlank()) {
            val start = builder.length
            builder.append(snap.prevText)
            builder.setSpan(
                ForegroundColorSpan(dim),
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.append("\n\n")
        }
        val start = builder.length
        builder.append(snap.currentText)
        builder.setSpan(
            ForegroundColorSpan(ink),
            start,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            BackgroundColorSpan(highlight),
            start,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (snap.nextText.isNotBlank()) {
            builder.append("\n\n")
            val nextStart = builder.length
            builder.append(snap.nextText)
            builder.setSpan(
                ForegroundColorSpan(dim),
                nextStart,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        sentenceView.text = builder
    }

    private fun renderProgress(snap: ReaderSnapshot) {
        if (snap.total <= 0) {
            sentenceBar.visibility = View.GONE
            sentenceLabel.visibility = View.GONE
            timeLeft.visibility = View.GONE
            return
        }
        sentenceBar.visibility = View.VISIBLE
        sentenceBar.max = snap.total
        sentenceBar.isEnabled = snap.status == ReaderStatus.READING || snap.status == ReaderStatus.PAUSED
        if (!dragging) {
            sentenceBar.progress = (snap.index + 1).coerceIn(0, snap.total)
            showProgressLabels(snap, snap.index)
        }
    }

    /**
     * Left: time played (an estimate from the voice's measured pace) and the sentence;
     * right: the reading's estimated length. Before a sentence has been measured, only
     * the sentence count shows.
     */
    private fun showProgressLabels(snap: ReaderSnapshot, index: Int) {
        val shown = index.coerceIn(0, (snap.total - 1).coerceAtLeast(0))
        sentenceLabel.visibility = View.VISIBLE
        val estimate = if (snap.status == ReaderStatus.READING || snap.status == ReaderStatus.PAUSED) {
            ReadingProgress.estimate(snap)
        } else {
            null
        }
        if (estimate == null) {
            sentenceLabel.text = getString(R.string.sentence_format, shown + 1, snap.total)
            timeLeft.visibility = View.GONE
            return
        }
        // While dragging, the time follows the finger (sentences scaled evenly).
        val positionMs = if (shown == snap.index) {
            estimate.positionMs
        } else {
            estimate.durationMs * shown / snap.total
        }
        sentenceLabel.text = getString(
            R.string.elapsed_format, ReadingProgress.clock(positionMs), shown + 1, snap.total,
        )
        timeLeft.visibility = View.VISIBLE
        timeLeft.text = ReadingProgress.clock(estimate.durationMs)
    }

    private fun renderChips(snap: ReaderSnapshot) {
        val prefs = getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE)
        val speed = prefs.getFloat(ReaderService.KEY_SPEED, 1.0f)
        speedChip.text = getString(
            R.string.speed_value_format,
            String.format(java.util.Locale.US, "%.1f", speed),
        )
        val pack = VoiceCatalog.byId(snap.packId) ?: ReaderService.activePack(this)
        val sid = ReaderService.speakerIdFor(this, pack.id).coerceIn(pack.speakers.indices)
        speakerChip.text = if (pack.speakers.size == 1) {
            pack.voiceName?.substringBefore(" (") ?: ReaderService.speakerShortName(pack, sid)
        } else {
            ReaderService.speakerShortName(pack, sid)
        }
    }
}
