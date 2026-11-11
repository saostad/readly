package app.readly.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.readly.PhoneVoiceSettings
import app.readly.R
import app.readly.ReadlyApp
import app.readly.VoicesActivity
import app.readly.article.ArticleEngine
import app.readly.article.BpcUiState
import app.readly.input.sites.RuleUpdateStatus
import app.readly.input.sites.SiteUiState
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.playback.ReaderService
import app.readly.text.DateOrder
import java.io.File
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Voice & speed" bottom sheet: the active pack with a Change-pack link,
 * one tappable speaker chip per speaker (tap selects and previews), speed,
 * the Smart voice and auto-download switches, the phone-voice row and the
 * Persian line. All setting persistence is unchanged; content is rebuilt on
 * every [show] so it never shows stale state.
 */
class SettingsSheet(
    private val activity: Activity,
    private val onDismiss: () -> Unit = {},
) {

    private val prefs: SharedPreferences =
        activity.getSharedPreferences(ReaderService.PREFS_NAME, Activity.MODE_PRIVATE)

    fun show() {
        val pack = ReaderService.activePack(activity)
        val dialog = Dialog(activity, R.style.SheetDialog)
        dialog.asBottomSheet() ?: return
        dialog.setContentView(R.layout.dialog_settings_sheet)

        dialog.findViewById<TextView>(R.id.sheetPackName).text = VoiceCatalog.shortName(pack)
        dialog.findViewById<Button>(R.id.sheetChangePack).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, VoicesActivity::class.java))
            activity.overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
        }
        fillSpeakers(dialog, pack)
        fillSpeed(dialog)
        fillSwitch(
            dialog,
            R.id.sheetSmartSwitch,
            ReaderService.KEY_SMART_VOICE,
        )
        fillSwitch(
            dialog,
            R.id.sheetAutoSwitch,
            ReaderService.KEY_AUTO_DOWNLOAD,
        )
        fillSwitch(dialog, R.id.sheetNormalizeSwitch, ReaderService.KEY_NORMALIZE_TEXT)
        fillSwitch(dialog, R.id.sheetRespellSwitch, ReaderService.KEY_RESPELL_NAMES, default = false)
        fillDayFirstSwitch(dialog)
        fillPhoneRow(dialog)
        fillPersianLine(dialog)
        fillSiteSection(dialog)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        Sheets.applyFullWidth(dialog)
    }

    private fun fillSpeakers(dialog: Dialog, pack: VoicePack) {
        val box = dialog.findViewById<FlowLayout>(R.id.sheetSpeakers)
        val hint = dialog.findViewById<TextView>(R.id.sheetSpeakerHint)
        val single = dialog.findViewById<TextView>(R.id.sheetSingleVoice)
        if (pack.speakers.size == 1) {
            box.visibility = View.GONE
            hint.visibility = View.GONE
            single.visibility = View.VISIBLE
            return
        }
        box.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
        single.visibility = View.GONE
        val selected = ReaderService.speakerIdFor(activity, pack.id)
            .coerceIn(pack.speakers.indices)
        box.removeAllViews()
        for (index in pack.speakers.indices) {
            val name = ReaderService.speakerShortName(pack, index)
            val chip = Button(
                activity,
                null,
                0,
                if (index == selected) R.style.Chip_Selected else R.style.Chip,
            ).apply {
                text = activity.getString(R.string.speaker_chip_format, name)
                setOnClickListener {
                    ReaderService.setSpeakerId(activity, pack.id, index)
                    fillSpeakers(dialog, pack)
                    playPreview(pack, index, name)
                }
            }
            box.addView(chip)
        }
    }

    /** Previews [speakerId] saying its preview sentence (never interrupts reading). */
    private fun playPreview(pack: VoicePack, speakerId: Int, name: String) {
        val store = VoiceModelStore(
            pack,
            File(activity.noBackupFilesDir, "voices"),
            VoiceModelStore::httpUrlOpener,
        )
        if (!store.isInstalled()) return
        ReaderService.preview(
            activity,
            pack.id,
            speakerId,
            ReaderService.previewText(activity, pack, name),
        )
    }

    /** SeekBar 0..15 maps to 0.5×..2.0× in 0.1 steps. */
    private fun progressToSpeed(progress: Int): Float = 0.5f + progress * 0.1f

    private fun speedToProgress(speed: Float): Int =
        ((speed - 0.5f) * 10 + 0.5f).toInt().coerceIn(0, 15)

    private fun fillSpeed(dialog: Dialog) {
        val value = dialog.findViewById<TextView>(R.id.sheetSpeedValue)
        val seek = dialog.findViewById<SeekBar>(R.id.sheetSpeedSeek)
        fun paint(speed: Float) {
            value.text = activity.getString(
                R.string.speed_value_format,
                String.format(Locale.US, "%.1f", speed),
            )
        }
        val speed = prefs.getFloat(ReaderService.KEY_SPEED, 1.0f)
        seek.progress = speedToProgress(speed)
        paint(speed)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val newSpeed = progressToSpeed(progress)
                paint(newSpeed)
                if (fromUser) prefs.edit().putFloat(ReaderService.KEY_SPEED, newSpeed).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun fillSwitch(dialog: Dialog, switchId: Int, key: String, default: Boolean = true) {
        val switch = dialog.findViewById<Switch>(switchId)
        switch.isChecked = prefs.getBoolean(key, default)
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    /** Stores the date order as its [DateOrder.key], like smart-voices' `date_order`. */
    private fun fillDayFirstSwitch(dialog: Dialog) {
        val switch = dialog.findViewById<Switch>(R.id.sheetDayFirstSwitch)
        switch.isChecked = ReaderService.normalizeOptions(activity).dateOrder == DateOrder.DAY_FIRST
        switch.setOnCheckedChangeListener { _, checked ->
            val order = if (checked) DateOrder.DAY_FIRST else DateOrder.MONTH_FIRST
            prefs.edit().putString(ReaderService.KEY_DATE_ORDER, order.key).apply()
        }
    }

    private fun fillPhoneRow(dialog: Dialog) {
        val status = PhoneVoiceSettings.status(activity)
        dialog.findViewById<TextView>(R.id.sheetPhoneStatus).text = status.text
        val button = dialog.findViewById<Button>(R.id.sheetPhoneButton)
        if (status.isOurs) {
            button.setText(R.string.phone_voice_change_button)
            button.setOnClickListener {
                PhoneVoiceSettings.open(
                    activity,
                    null,
                    activity.getString(R.string.phone_voice_fallback_toast),
                )
            }
        } else {
            button.setText(R.string.setup_button)
            button.setOnClickListener {
                PhoneVoiceSettings.open(
                    activity,
                    activity.getString(R.string.phone_voice_toast),
                    activity.getString(R.string.phone_voice_fallback_toast),
                )
            }
        }
    }

    /** `"Persian: <shortName>"` when a Persian voice is set. */
    private fun fillPersianLine(dialog: Dialog) {
        val line = dialog.findViewById<TextView>(R.id.sheetPersianLine)
        val fa = ReaderService.packIdForLang(activity, "fa")?.let { VoiceCatalog.byId(it) }
        if (fa != null) {
            line.text = activity.getString(R.string.persian_pack_format, VoiceCatalog.shortName(fa))
            line.visibility = View.VISIBLE
        } else {
            line.visibility = View.GONE
        }
    }

    /**
     * "Website reading": the shared automatic-updates toggle, a manual update
     * check, and the current article-extension plus reading-fix status. The
     * toggle governs both updaters. The manual check targets the actual
     * add-on (starting the engine when needed) and also refreshes the
     * native/extraction rules kept for compatibility; the two statuses are
     * shown separately, never conflated. Everything here is immediate: both
     * coordinators work on their own threads, and completion callbacks
     * repaint on main only while this dialog is still showing. The retained
     * callbacks capture only the two weak references plus the app-owned
     * coordinators/engine and the main handler — never the dialog, the
     * activity, or this sheet — so a dismiss/rotation retains nothing and
     * never touches dead views. All rendering lives in [SiteSheetUi].
     */
    private fun fillSiteSection(dialog: Dialog) {
        val coord = (activity.application as ReadlyApp).siteRules
        coord.ensureStarted()
        val engine = ArticleEngine.get(activity)
        val auto = dialog.findViewById<Switch>(R.id.sheetNytAutoSwitch)
        auto.isChecked = prefs.getBoolean(ReaderService.KEY_NYT_AUTO_UPDATE, true)
        auto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ReaderService.KEY_NYT_AUTO_UPDATE, checked).apply()
        }
        val dialogRef = WeakReference(dialog)
        val activityRef = WeakReference(activity)
        val main = Handler(Looper.getMainLooper())
        val repaint = Runnable {
            val act = activityRef.get()
            val d = dialogRef.get()
            if (act == null || act.isFinishing || act.isDestroyed) return@Runnable
            if (d == null || !d.isShowing) return@Runnable
            SiteSheetUi.paint(d, act, coord.siteUiState(), engine.bpcUiState())
        }
        val firstBpc = engine.bpcUiState()
        SiteSheetUi.paint(dialog, activity, coord.siteUiState().also { first ->
            if (first.base.checking) {
                // Join the in-flight check so the sheet refreshes when it lands.
                coord.checkUpdates(manual = false) { main.post(repaint) }
            }
        }, firstBpc.also {
            if (it.checking) {
                engine.checkAddonUpdates(manual = false) { main.post(repaint) }
            }
        })
        dialog.findViewById<Button>(R.id.sheetNytCheck).setOnClickListener {
            val act = activityRef.get()
            val d = dialogRef.get()
            if (act != null && d != null) {
                val optimistic = coord.siteUiState()
                val optimisticBpc = engine.bpcUiState()
                SiteSheetUi.paint(
                    d, act,
                    optimistic.copy(base = optimistic.base.copy(checking = true)),
                    optimisticBpc.copy(checking = true),
                )
            }
            // Manual: the actual add-on first (may start the engine), then the
            // compatibility rules. Both repaint when they land.
            engine.checkAddonUpdates(manual = true) { main.post(repaint) }
            coord.checkUpdates(manual = true) { main.post(repaint) }
        }
    }

    companion object {
        /**
         * Static site sheet renderer: every function takes the recovered
         * [Dialog]/[Activity] as parameters and holds no references, so the
         * callbacks in [fillSiteSection] cannot retain the sheet or its
         * activity indirectly.
         */
        internal object SiteSheetUi {
            /** Repaints the check button, status lines and problem text. */
            fun paint(
                dialog: Dialog,
                activity: Activity,
                state: SiteUiState,
                bpc: BpcUiState? = null,
            ) {
                val base = state.base
                val checking = base.checking || bpc?.checking == true
                dialog.findViewById<Button>(R.id.sheetNytCheck).apply {
                    isEnabled = !checking
                    setText(if (checking) R.string.nyt_checking else R.string.nyt_check_updates)
                }
                dialog.findViewById<TextView>(R.id.sheetNytStatus).text =
                    siteStatusText(activity, state, bpc)
                val problems = listOfNotNull(
                    bpc?.lastError,
                    base.startupError, base.lastError, base.warning,
                    base.startupNotice, base.coordinatorError,
                ).filter { it.isNotBlank() }
                val errorView = dialog.findViewById<TextView>(R.id.sheetNytError)
                if (problems.isEmpty()) {
                    errorView.visibility = View.GONE
                } else {
                    errorView.text = problems.joinToString("\n\n")
                    errorView.visibility = View.VISIBLE
                }
            }

            /**
             * Add-on version/check lines first, then the compatibility rules
             * version/source, import summary, and last-check lines. The two
             * updaters are never conflated: the add-on is the real article
             * engine, the rules only feed native/extraction compatibility.
             */
            fun siteStatusText(activity: Activity, state: SiteUiState, bpc: BpcUiState? = null): String {
                val lines = mutableListOf<String>()
                if (bpc != null) {
                    lines.add(
                        if (!bpc.installedVersion.isNullOrBlank()) {
                            activity.getString(R.string.bpc_addon_format, bpc.installedVersion)
                        } else {
                            activity.getString(R.string.bpc_addon_unknown)
                        },
                    )
                    if (bpc.lastCheckMs > 0) {
                        val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(bpc.lastCheckMs))
                        lines.add(activity.getString(R.string.bpc_last_checked_format, whenText))
                    } else {
                        lines.add(activity.getString(R.string.bpc_never_checked))
                    }
                }
                val base = state.base
                if (!base.ready) {
                    lines.add(
                        activity.getString(
                            if (base.startupError != null) R.string.nyt_unavailable else R.string.nyt_starting,
                        ),
                    )
                    return lines.joinToString("\n")
                }
                val source = activity.getString(
                    if (base.origin == RuleUpdateStatus.ORIGIN_CACHE) R.string.nyt_source_downloaded
                    else R.string.nyt_source_bundled,
                )
                lines.add(activity.getString(R.string.nyt_rules_format, base.version ?: "?", source))
                if (state.summary != null) {
                    lines.add(state.summary)
                    lines.add(activity.getString(R.string.sites_imports_partial))
                }
                if (state.legacyOverrideVersion != null) {
                    lines.add(
                        activity.getString(R.string.sites_legacy_format, state.legacyOverrideVersion),
                    )
                }
                if (base.lastCheckMs > 0) {
                    val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(base.lastCheckMs))
                    lines.add(activity.getString(R.string.nyt_last_checked_format, whenText))
                } else {
                    lines.add(activity.getString(R.string.nyt_never_checked))
                }
                return lines.joinToString("\n")
            }
        }
    }
}
