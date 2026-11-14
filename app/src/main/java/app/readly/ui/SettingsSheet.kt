package app.readly.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.readly.feeds.FeedTimes
import app.readly.FeedsActivity
import app.readly.PhoneVoiceSettings
import app.readly.R
import app.readly.ReadlyApp
import app.readly.VoicesActivity
import app.readly.article.ArticleEngine
import app.readly.article.BpcUiState
import app.readly.input.pdf.PdfFiles
import app.readly.input.pdf.PdfLimits
import app.readly.input.pdf.PdfOcrOptions
import app.readly.input.pdf.PdfImportStore
import app.readly.input.sites.RuleUpdateStatus
import app.readly.input.sites.SiteUiState
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.playback.ReaderService
import app.readly.text.DateOrder
import app.readly.tts.pocket.PocketTuning
import app.readly.tts.pocket.PocketTuningPrefs
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
        val sections = listOf(
            R.id.settingsPlaybackToggle to R.id.settingsPlaybackGroup,
            R.id.settingsFeedsToggle to R.id.settingsFeedsGroup,
            R.id.settingsVoicesToggle to R.id.settingsVoicesGroup,
            R.id.settingsDocumentsToggle to R.id.settingsDocumentsGroup,
        )
        for ((button, group) in sections) dialog.findViewById<Button>(button).setOnClickListener {
            val view = dialog.findViewById<View>(group)
            val expand = view.visibility != View.VISIBLE
            sections.forEach { (_, other) -> dialog.findViewById<View>(other).visibility = View.GONE }
            view.visibility = if (expand) View.VISIBLE else View.GONE
        }
        RefreshSettings.fill(activity, dialog.findViewById(R.id.sheetRefreshSchedule))
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
        fillSwitch(dialog, R.id.sheetHiddenTextSwitch, ReaderService.KEY_READ_HIDDEN_TEXT, default = false)
        fillSwitch(dialog, R.id.sheetCompactWebsitesSwitch, WebsiteLayout.KEY_COMPACT, default = WebsiteLayout.DEFAULT_COMPACT)
        fillTimeZone(dialog)
        fillDayFirstSwitch(dialog)
        fillPhoneRow(dialog)
        fillAutoSitesRow(dialog)
        fillPersianLine(dialog)
        fillSiteSection(dialog)
        fillPocketSection(dialog)
        fillPdfSection(dialog)
        dialog.findViewById<LinearLayout>(R.id.sheetPdfBox).addView(Button(activity, null, 0, R.style.Btn_Text).apply {
            text = "Clear saved readings without bookmarks"
            setOnClickListener {
                android.app.AlertDialog.Builder(activity).setTitle("Clear saved readings?")
                    .setMessage("Readly saves text locally for Search and Bookmarks. This removes saved readings without bookmarks. Imported documents, feed headlines and bookmarked readings stay available.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        isEnabled = false
                        (activity.application as ReadlyApp).ioExecutor.execute {
                            try {
                                val count = app.readly.library.ReadingStore(activity).use { it.clearUnbookmarked(app.readly.playback.ReaderState.snapshot.readingId) }
                                activity.runOnUiThread { if (!activity.isDestroyed) {
                                    isEnabled = true
                                    android.widget.Toast.makeText(activity, "$count saved readings cleared", android.widget.Toast.LENGTH_LONG).show()
                                } }
                            } catch (error: Exception) {
                                android.util.Log.e("Readly", "Couldn't clear saved readings", error)
                                activity.runOnUiThread { if (!activity.isDestroyed) {
                                    isEnabled = true
                                    android.widget.Toast.makeText(activity, "Couldn't clear saved readings. Reopen Settings and try again.", android.widget.Toast.LENGTH_LONG).show()
                                } }
                            }
                        }
                    }.show()
            }
        })
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        Sheets.applyFullWidth(dialog)
    }

    private fun fillTimeZone(dialog: Dialog) {
        val ids = listOf(FeedTimes.DEFAULT_TIME_ZONE, FeedTimes.DEVICE_TIME_ZONE) +
            java.time.ZoneId.getAvailableZoneIds().sorted().filterNot { it == FeedTimes.DEFAULT_TIME_ZONE }
        val labels = ids.map {
            when (it) {
                FeedTimes.DEFAULT_TIME_ZONE -> activity.getString(R.string.feed_time_zone_new_york)
                FeedTimes.DEVICE_TIME_ZONE -> activity.getString(R.string.feed_time_zone_device, java.time.ZoneId.systemDefault().id)
                else -> it.replace('_', ' ')
            }
        }
        val spinner = dialog.findViewById<android.widget.Spinner>(R.id.sheetTimeZone)
        spinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
        val selected = prefs.getString(FeedTimes.KEY_TIME_ZONE, FeedTimes.DEFAULT_TIME_ZONE)
        spinner.setSelection(ids.indexOf(selected))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString(FeedTimes.KEY_TIME_ZONE, ids[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun fillPdfSection(dialog: Dialog) {
        val box = dialog.findViewById<LinearLayout>(R.id.sheetPdfBox)
        val defaults = PdfLimits()
        fun editor(label: Int, value: Int): EditText {
            box.addView(TextView(activity, null, 0, R.style.Small).apply { setText(label) })
            return EditText(activity, null, 0, R.style.BoxEdit).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
                box.addView(this)
            }
        }
        val size = editor(R.string.pdf_max_mb_label, prefs.getInt(PdfFiles.KEY_MAX_MB, defaults.maxFileMb))
        val chars = editor(R.string.pdf_max_chars_label, prefs.getInt(PdfFiles.KEY_MAX_CHARS, defaults.maxTextChars))
        val ocrDefaults = PdfOcrOptions()
        val enabled = Switch(activity).apply {
            setText(R.string.pdf_ocr_enabled)
            isChecked = prefs.getBoolean(PdfFiles.KEY_OCR_ENABLED, ocrDefaults.enabled)
        }
        box.addView(enabled)
        box.addView(TextView(activity, null, 0, R.style.Small).apply { setText(R.string.pdf_ocr_language) })
        val languages = listOf("auto", "eng", "fas", "eng+fas")
        val language = android.widget.Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf(activity.getString(R.string.pdf_ocr_language_auto), activity.getString(R.string.pdf_ocr_language_english), activity.getString(R.string.pdf_ocr_language_persian), activity.getString(R.string.pdf_ocr_language_both)))
            val stored = prefs.getString(PdfFiles.KEY_OCR_LANGUAGE, ocrDefaults.languages)
            val storedIndex = languages.indexOf(stored)
            if (storedIndex < 0) {
                android.util.Log.w("Readly", "fallback=true attempted=pdf_ocr_language reason=invalid_saved_language taken=show_default_for_correction")
                android.widget.Toast.makeText(activity, R.string.pdf_settings_invalid, android.widget.Toast.LENGTH_LONG).show()
            }
            setSelection(if (storedIndex < 0) languages.indexOf(ocrDefaults.languages) else storedIndex)
        }
        box.addView(language)
        box.addView(TextView(activity, null, 0, R.style.Small).apply { setText(R.string.image_layout_label) })
        val layouts = listOf("auto", "sparse", "block", "line")
        val layout = android.widget.Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf(activity.getString(R.string.image_layout_auto), activity.getString(R.string.image_layout_sparse),
                    activity.getString(R.string.image_layout_block), activity.getString(R.string.image_layout_line)))
            val index = layouts.indexOf(prefs.getString(PdfFiles.KEY_IMAGE_LAYOUT, ocrDefaults.imageLayout))
            if (index < 0) {
                android.util.Log.w("Readly", "fallback=true attempted=image_ocr_layout reason=invalid_saved_layout taken=show_default_for_correction")
                android.widget.Toast.makeText(activity, R.string.pdf_settings_invalid, android.widget.Toast.LENGTH_LONG).show()
            }
            setSelection(if (index < 0) layouts.indexOf(ocrDefaults.imageLayout) else index)
        }
        box.addView(layout)
        val side = editor(R.string.pdf_ocr_resolution, prefs.getInt(PdfFiles.KEY_OCR_SIDE, ocrDefaults.maxSide))
        val error = TextView(activity).apply { setTextColor(activity.getColor(R.color.error)) }
        box.addView(error)
        box.addView(Button(activity, null, 0, R.style.Btn_Text).apply {
            setText(R.string.pdf_save_limits)
            setOnClickListener {
                val mb = size.text.toString().toIntOrNull()
                val count = chars.text.toString().toIntOrNull()
                val resolution = side.text.toString().toIntOrNull()
                if (mb == null || count == null || resolution == null) {
                    error.setText(R.string.pocket_enter_number)
                    return@setOnClickListener
                }
                val limits = PdfLimits(mb, count)
                try {
                    limits.validate()
                    PdfOcrOptions(enabled.isChecked, languages[language.selectedItemPosition], resolution, layouts[layout.selectedItemPosition]).validate()
                } catch (e: IllegalArgumentException) {
                    android.util.Log.e("Readly", "Document import settings validation failed", e)
                    error.text = e.message
                    return@setOnClickListener
                }
                prefs.edit().putInt(PdfFiles.KEY_MAX_MB, mb).putInt(PdfFiles.KEY_MAX_CHARS, count)
                    .putBoolean(PdfFiles.KEY_OCR_ENABLED, enabled.isChecked)
                    .putString(PdfFiles.KEY_OCR_LANGUAGE, languages[language.selectedItemPosition])
                    .putInt(PdfFiles.KEY_OCR_SIDE, resolution)
                    .putString(PdfFiles.KEY_IMAGE_LAYOUT, layouts[layout.selectedItemPosition]).apply()
                error.text = ""
                android.widget.Toast.makeText(activity, R.string.pdf_limits_saved, android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(Button(activity, null, 0, R.style.Btn_Text).apply {
            setText(R.string.pdf_imports_history)
            setOnClickListener { dialog.dismiss(); showPdfImports() }
        })
        box.addView(Button(activity, null, 0, R.style.Btn_Text).apply {
            setText(R.string.pdf_imports_clear)
            setOnClickListener {
                android.app.AlertDialog.Builder(activity).setMessage(R.string.pdf_imports_clear_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        (activity.application as ReadlyApp).ioExecutor.execute {
                            try {
                                PdfImportStore(activity).use { it.clearFinished() }
                                activity.runOnUiThread { android.widget.Toast.makeText(activity, R.string.pdf_imports_cleared, android.widget.Toast.LENGTH_SHORT).show() }
                            } catch (e: Exception) { pdfError(e) }
                        }
                    }.show()
            }
        })
    }

    private fun pdfError(error: Exception) {
        android.util.Log.e("Readly", "Saved document operation failed", error)
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) android.widget.Toast.makeText(activity,
                if (error is app.readly.input.pdf.PdfImportException) error.message else activity.getString(R.string.document_operation_failed),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showPdfImports() {
        (activity.application as ReadlyApp).ioExecutor.execute {
            try {
                val imports = PdfImportStore(activity).use { it.list() }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (imports.isEmpty()) {
                        android.widget.Toast.makeText(activity, R.string.pdf_imports_empty, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.app.AlertDialog.Builder(activity).setTitle(R.string.pdf_imports_history)
                            .setItems(imports.map { "${it.title} · ${it.status}" }.toTypedArray()) { _, index ->
                                val item = imports[index]
                                if (item.status != "ready") {
                                    android.widget.Toast.makeText(activity, R.string.pdf_imports_unready, android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    app.readly.input.pdf.DocumentPlayback.read(activity, item.id)
                                }
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
            } catch (e: Exception) { pdfError(e) }
        }
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

    private fun fillAutoSitesRow(dialog: Dialog) {
        val status = dialog.findViewById<TextView>(R.id.sheetAutoSitesStatus)
        try {
            val sites = (activity.application as ReadlyApp).feeds.sites()
            status.text = when (sites.size) {
                0 -> activity.getString(R.string.auto_sites_count_none)
                else -> sites.joinToString(", ") { it.label() }
            }
        } catch (e: java.io.IOException) {
            status.text = e.message
            status.setTextColor(activity.getColor(R.color.error))
        }
        dialog.findViewById<Button>(R.id.sheetAutoSitesButton).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, FeedsActivity::class.java))
            activity.overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
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

    /** One editable Pocket tuning value: its prefs key, texts and conversion. */
    private class PocketField(
        val key: String,
        val labelRes: Int,
        val helpRes: Int,
        val isFloat: Boolean,
        val shown: (PocketTuning) -> String,
        val updated: (PocketTuning, String) -> PocketTuning?,
    )

    private val pocketFields = listOf(
        PocketField(
            ReaderService.KEY_POCKET_MAX_TOKENS,
            R.string.pocket_max_tokens_label, R.string.pocket_max_tokens_help, false,
            { it.maxTokens.toString() },
            { tuning, text -> text.toIntOrNull()?.let { tuning.copy(maxTokens = it) } },
        ),
        PocketField(
            ReaderService.KEY_POCKET_MIN_TOKENS,
            R.string.pocket_min_tokens_label, R.string.pocket_min_tokens_help, false,
            { it.minTokens.toString() },
            { tuning, text -> text.toIntOrNull()?.let { tuning.copy(minTokens = it) } },
        ),
        PocketField(
            ReaderService.KEY_POCKET_G2P_WINDOW_CHARS,
            R.string.pocket_g2p_window_label, R.string.pocket_g2p_window_help, false,
            { it.g2pWindowChars.toString() },
            { tuning, text -> text.toIntOrNull()?.let { tuning.copy(g2pWindowChars = it) } },
        ),
        PocketField(
            ReaderService.KEY_POCKET_TEMPERATURE,
            R.string.pocket_temperature_label, R.string.pocket_temperature_help, true,
            { it.temperature.toString() },
            { tuning, text -> text.toFloatOrNull()?.let { tuning.copy(temperature = it) } },
        ),
        PocketField(
            ReaderService.KEY_POCKET_DECODE_STEPS,
            R.string.pocket_decode_steps_label, R.string.pocket_decode_steps_help, false,
            { it.decodeSteps.toString() },
            { tuning, text -> text.toIntOrNull()?.let { tuning.copy(decodeSteps = it) } },
        ),
        PocketField(
            ReaderService.KEY_POCKET_EOS_THRESHOLD,
            R.string.pocket_eos_threshold_label, R.string.pocket_eos_threshold_help, true,
            { it.eosThreshold.toString() },
            { tuning, text -> text.toFloatOrNull()?.let { tuning.copy(eosThreshold = it) } },
        ),
        PocketField(
            ReaderService.KEY_POCKET_FRAMES_AFTER_EOS,
            R.string.pocket_frames_after_eos_label, R.string.pocket_frames_after_eos_help, false,
            { it.framesAfterEos.toString() },
            { tuning, text -> text.toIntOrNull()?.let { tuning.copy(framesAfterEos = it) } },
        ),
    )

    /**
     * "Persian voice (advanced)": one number field per [PocketTuning]
     * value, saved as it is edited when the whole stored set still
     * validates; an out-of-range edit shows the validation message under
     * its field and is not saved. Reset restores all seven defaults.
     */
    private fun fillPocketSection(dialog: Dialog) {
        val box = dialog.findViewById<LinearLayout>(R.id.sheetPocketBox)
        box.removeAllViews()
        var filling = false
        val editors = mutableListOf<Pair<PocketField, EditText>>()
        val errors = mutableListOf<Pair<PocketField, TextView>>()
        fun currentTuning(): PocketTuning = PocketTuningPrefs.load(prefs.all).tuning
        fun paint(tuning: PocketTuning) {
            filling = true
            for ((field, editor) in editors) editor.setText(field.shown(tuning))
            for ((_, error) in errors) error.visibility = View.GONE
            filling = false
        }
        for (field in pocketFields) {
            val label = TextView(activity, null, 0, R.style.CardTitle).apply {
                text = activity.getString(field.labelRes)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (10 * activity.resources.displayMetrics.density).toInt() }
            }
            box.addView(label)
            val error = TextView(activity, null, 0, R.style.Small).apply {
                setTextColor(activity.getColor(R.color.error))
                visibility = View.GONE
            }
            val editor = EditText(activity, null, 0, R.style.BoxEdit).apply {
                inputType = if (field.isFloat) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(editable: Editable?) {
                        if (filling) return
                        val candidate = field.updated(currentTuning(), editable?.toString().orEmpty())
                        if (candidate == null) {
                            error.text = activity.getString(R.string.pocket_enter_number)
                            error.visibility = View.VISIBLE
                            return
                        }
                        try {
                            candidate.validate()
                        } catch (e: IllegalArgumentException) {
                            error.text = e.message
                            error.visibility = View.VISIBLE
                            return
                        }
                        val value: Any = PocketTuningPrefs.values(candidate).getValue(field.key)
                        val edit = prefs.edit()
                        when (value) {
                            is Int -> edit.putInt(field.key, value)
                            is Float -> edit.putFloat(field.key, value)
                            else -> edit.putString(field.key, value.toString())
                        }
                        edit.apply()
                        error.visibility = View.GONE
                    }
                })
            }
            box.addView(editor)
            box.addView(
                TextView(activity, null, 0, R.style.Small).apply {
                    text = activity.getString(field.helpRes)
                },
            )
            box.addView(error)
            editors += field to editor
            errors += field to error
        }
        paint(currentTuning())
        dialog.findViewById<Button>(R.id.sheetPocketReset).setOnClickListener {
            val edit = prefs.edit()
            for ((key, value) in PocketTuningPrefs.values(PocketTuning())) {
                when (value) {
                    is Int -> edit.putInt(key, value)
                    is Float -> edit.putFloat(key, value)
                    else -> edit.putString(key, value.toString())
                }
            }
            edit.apply()
            paint(PocketTuning())
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
