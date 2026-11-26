package app.readly

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.readly.article.ArticleEngine
import app.readly.article.BpcUiState
import app.readly.feeds.FeedLimits
import app.readly.feeds.FeedRefreshScheduler
import app.readly.feeds.FeedRepository
import app.readly.feeds.FeedTimes
import app.readly.feeds.RefreshSchedule
import app.readly.input.pdf.PdfFiles
import app.readly.input.pdf.PdfImportStore
import app.readly.input.pdf.PdfLimits
import app.readly.input.pdf.PdfOcrOptions
import app.readly.input.sites.RuleUpdateStatus
import app.readly.input.sites.SiteUiState
import app.readly.library.ReadingStore
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.playback.ReaderService
import app.readly.playback.ReaderState
import app.readly.text.DateOrder
import app.readly.tts.pocket.PocketTuning
import app.readly.tts.pocket.PocketTuningPrefs
import app.readly.ui.FlowLayout
import app.readly.ui.ListSheet
import app.readly.ui.SettingsSummaries
import app.readly.ui.SettingsWidgets
import app.readly.ui.SettingsWidgets.dp
import app.readly.ui.WebsiteLayout
import java.io.File
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/**
 * Full-screen Settings Hub and 6 focused sub-screens replacing the overcrowded
 * bottom-sheet accordion and moving buried playback/Android Auto settings out
 * of [FeedsActivity].
 */
class SettingsActivity : Activity() {

    enum class Section(val titleRes: Int) {
        VOICES(R.string.settings_voices_title),
        READING(R.string.settings_reading_title),
        WEBSITES(R.string.settings_websites_title),
        ANDROID_AUTO(R.string.settings_auto_title),
        DOCUMENTS(R.string.settings_docs_title),
        STORAGE(R.string.settings_storage_title),
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var content: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var badgeView: TextView
    private var section: Section? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE)
        content = findViewById(R.id.settingsContent)
        titleView = findViewById(R.id.settingsTitle)
        badgeView = findViewById(R.id.settingsBadge)
        section = intent.getStringExtra(EXTRA_SECTION)?.let { name ->
            runCatching { Section.valueOf(name) }.getOrNull()
        }
        findViewById<ImageButton>(R.id.settingsBack).setOnClickListener { closeWithTransition() }
        titleView.setText(section?.titleRes ?: R.string.sheet_title)
        badgeView.visibility = if (section == null) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        render()
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

    private fun render() {
        content.removeAllViews()
        when (section) {
            null -> renderHub()
            Section.VOICES -> renderVoicesSection()
            Section.READING -> renderReadingSection()
            Section.WEBSITES -> renderWebsitesSection()
            Section.ANDROID_AUTO -> renderAndroidAutoSection()
            Section.DOCUMENTS -> renderDocumentsSection()
            Section.STORAGE -> renderStorageSection()
        }
    }

    // ------------------------------------------------------------------
    // Hub
    // ------------------------------------------------------------------

    private fun renderHub() {
        val pack = ReaderService.activePack(this)
        val faPack = ReaderService.packIdForLang(this, "fa")?.let { VoiceCatalog.byId(it) }
        val smartOn = prefs.getBoolean(ReaderService.KEY_SMART_VOICE, true)

        content.addView(buildQuickControlCard(pack, faPack))

        val app = application as ReadlyApp
        val mainName = VoiceCatalog.shortName(pack)
        val faName = faPack?.let { VoiceCatalog.shortName(it) }
        content.addView(
            SettingsWidgets.hubCategoryRow(
                this,
                R.drawable.ic_mic,
                getString(R.string.settings_voices_title),
                SettingsSummaries.voicesSummary(mainName, faName, smartOn),
            ) { open(this, Section.VOICES) }.root,
        )

        val normalize = prefs.getBoolean(ReaderService.KEY_NORMALIZE_TEXT, true)
        val dayFirst = ReaderService.normalizeOptions(this).dateOrder == DateOrder.DAY_FIRST
        val autoUpdates = prefs.getBoolean(ReaderService.KEY_NYT_AUTO_UPDATE, true)
        content.addView(
            SettingsWidgets.hubCategoryRow(
                this,
                R.drawable.ic_text,
                getString(R.string.settings_reading_title),
                SettingsSummaries.readingSummary(normalize, dayFirst, autoUpdates),
            ) { open(this, Section.READING) }.root,
        )

        val siteCount = runCatching { app.feeds.sites().size }.getOrDefault(0)
        val compact = WebsiteLayout.compact(this)
        val tzId = prefs.getString(FeedTimes.KEY_TIME_ZONE, FeedTimes.DEFAULT_TIME_ZONE)
            ?: FeedTimes.DEFAULT_TIME_ZONE
        val tzShort = SettingsSummaries.shortTimeZoneName(tzId, ZoneId.systemDefault().id)
        val schedEnabled = runCatching { FeedRefreshScheduler.read(this).enabled }.getOrDefault(false)
        content.addView(
            SettingsWidgets.hubCategoryRow(
                this,
                R.drawable.ic_globe,
                getString(R.string.settings_websites_title),
                SettingsSummaries.websitesSummary(siteCount, compact, tzShort, schedEnabled),
            ) { open(this, Section.WEBSITES) }.root,
        )

        val playNext = app.feeds.isPlayNextOn()
        val prefetch = prefs.getBoolean(FeedRepository.KEY_PREFETCH, true)
        val pausePrompts = prefs.getBoolean(ReaderService.KEY_PAUSE_FOR_PROMPTS, true)
        content.addView(
            SettingsWidgets.hubCategoryRow(
                this,
                R.drawable.ic_car,
                getString(R.string.settings_auto_title),
                SettingsSummaries.androidAutoSummary(playNext, prefetch, pausePrompts),
            ) { open(this, Section.ANDROID_AUTO) }.root,
        )

        val ocr = runCatching { PdfFiles.ocrOptions(this) }.getOrDefault(PdfOcrOptions())
        content.addView(
            SettingsWidgets.hubCategoryRow(
                this,
                R.drawable.ic_folder_open,
                getString(R.string.settings_docs_title),
                SettingsSummaries.documentsSummary(ocr.enabled, ocr.languages, ocr.maxSide),
            ) { open(this, Section.DOCUMENTS) }.root,
        )

        val storageRow = SettingsWidgets.hubCategoryRow(
            this,
            R.drawable.ic_storage,
            getString(R.string.settings_storage_title),
            getString(R.string.settings_models_loading),
        ) { open(this, Section.STORAGE) }
        content.addView(storageRow.root)

        val subRef = WeakReference(storageRow.summaryView)
        val actRef = WeakReference(this)
        app.ioExecutor.execute {
            val installed = installedPacks()
            val totalMb = installed.sumOf { it.sizeMb }
            val text = SettingsSummaries.storageSummary(totalMb, installed.size)
            runOnUiThread {
                val act = actRef.get() ?: return@runOnUiThread
                val tv = subRef.get() ?: return@runOnUiThread
                if (!act.isDestroyed && !act.isFinishing && tv.isAttachedToWindow) {
                    tv.text = text
                }
            }
        }
    }

    private fun buildQuickControlCard(pack: VoicePack, faPack: VoicePack?): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            setPadding(dp(this@SettingsActivity, 14), dp(this@SettingsActivity, 12), dp(this@SettingsActivity, 14), dp(this@SettingsActivity, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(this@SettingsActivity, 12) }
        }

        // Active voices strip + Voices button
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val voiceLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@SettingsActivity, null, 0, R.style.CardTitle).apply {
                    text = VoiceCatalog.shortName(pack)
                },
            )
            if (faPack != null && faPack.id != pack.id) {
                addView(
                    TextView(this@SettingsActivity, null, 0, R.style.Small).apply {
                        text = getString(R.string.persian_pack_format, VoiceCatalog.shortName(faPack))
                        setTextColor(getColor(R.color.accent))
                    },
                )
            }
        }
        topRow.addView(voiceLabels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(
            Button(this, null, 0, R.style.Btn_Text).apply {
                setText(R.string.change_pack_short)
                setOnClickListener {
                    startActivity(Intent(this@SettingsActivity, VoicesActivity::class.java))
                    overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
                }
            },
        )
        card.addView(topRow)

        // Inline speakers (when pack has multiple speakers)
        if (pack.speakers.size > 1) {
            val speakersFlow = FlowLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(this@SettingsActivity, 8) }
            }
            fun paintSpeakers() {
                speakersFlow.removeAllViews()
                val selected = ReaderService.speakerIdFor(this, pack.id).coerceIn(pack.speakers.indices)
                for (idx in pack.speakers.indices) {
                    val name = ReaderService.speakerShortName(pack, idx)
                    speakersFlow.addView(
                        Button(
                            this,
                            null,
                            0,
                            if (idx == selected) R.style.Chip_Selected else R.style.Chip,
                        ).apply {
                            text = getString(R.string.speaker_chip_format, name)
                            setOnClickListener {
                                ReaderService.setSpeakerId(this@SettingsActivity, pack.id, idx)
                                paintSpeakers()
                                previewSpeaker(pack, idx, name)
                            }
                        },
                    )
                }
            }
            paintSpeakers()
            card.addView(speakersFlow)
        }

        // Reading speed + SeekBar + presets
        val speedHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(this@SettingsActivity, 10) }
        }
        val speedValue = TextView(this, null, 0, R.style.Small).apply {
            setTextColor(getColor(R.color.accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        speedHead.addView(
            TextView(this, null, 0, R.style.Body).apply {
                setText(R.string.settings_speed_title)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        speedHead.addView(speedValue)
        card.addView(speedHead)

        val seek = SeekBar(this).apply {
            max = 15
            progressDrawable = getDrawable(R.drawable.seekbar_progress)
            thumb = getDrawable(R.drawable.seekbar_thumb)
            splitTrack = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(this@SettingsActivity, 4) }
        }
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(this@SettingsActivity, 6) }
        }
        val presetBtns = mutableListOf<Pair<Float, Button>>()
        fun paintSpeed(speed: Float) {
            speedValue.text = SettingsSummaries.formatSpeed(speed)
            for ((preset, btn) in presetBtns) {
                val selected = kotlin.math.abs(preset - speed) < 0.05f
                btn.isSelected = selected
                btn.setBackgroundResource(if (selected) R.drawable.chip_selected_background else R.drawable.chip_background)
                btn.setTextColor(getColor(if (selected) R.color.accent else R.color.text2))
            }
        }
        for ((i, preset) in SettingsSummaries.SPEED_PRESETS.withIndex()) {
            val btn = Button(this, null, 0, R.style.Segment).apply {
                text = SettingsSummaries.formatSpeed(preset)
                setOnClickListener {
                    prefs.edit().putFloat(ReaderService.KEY_SPEED, preset).apply()
                    seek.progress = SettingsSummaries.speedToProgress(preset)
                    paintSpeed(preset)
                }
            }
            presetBtns += preset to btn
            presetRow.addView(
                btn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(this@SettingsActivity, 6)
                },
            )
        }
        val initialSpeed = prefs.getFloat(ReaderService.KEY_SPEED, 1.0f)
        seek.progress = SettingsSummaries.speedToProgress(initialSpeed)
        paintSpeed(initialSpeed)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val s = SettingsSummaries.progressToSpeed(progress)
                paintSpeed(s)
                if (fromUser) prefs.edit().putFloat(ReaderService.KEY_SPEED, s).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        card.addView(seek)
        card.addView(presetRow)
        return card
    }

    private fun previewSpeaker(pack: VoicePack, speakerId: Int, name: String) {
        val store = VoiceModelStore(pack, File(noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
        if (!store.isInstalled()) return
        ReaderService.preview(this, pack.id, speakerId, ReaderService.previewText(this, pack, name))
    }

    // ------------------------------------------------------------------
    // 1. Voices & Languages
    // ------------------------------------------------------------------

    private fun renderVoicesSection() {
        val pack = ReaderService.activePack(this)
        val faPack = ReaderService.packIdForLang(this, "fa")?.let { VoiceCatalog.byId(it) }
        val packsSub = if (faPack != null && faPack.id != pack.id) {
            getString(R.string.settings_browse_voices_sub, VoiceCatalog.shortName(pack), VoiceCatalog.shortName(faPack))
        } else {
            getString(R.string.settings_browse_voices_main_only, VoiceCatalog.shortName(pack))
        }

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_active_packs_section)))
        val packsCard = SettingsWidgets.cardGroup(this)
        packsCard.addView(
            SettingsWidgets.navRow(this, R.drawable.ic_mic, getString(R.string.settings_browse_voices_title), packsSub) {
                startActivity(Intent(this, VoicesActivity::class.java))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
            }.root,
        )
        content.addView(packsCard)

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_language_switch_section)))
        val langCard = SettingsWidgets.cardGroup(this)
        SettingsWidgets.addDividedRow(
            langCard,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.smart_voice_label),
                getString(R.string.smart_voice_help),
                prefs.getBoolean(ReaderService.KEY_SMART_VOICE, true),
            ) { checked -> prefs.edit().putBoolean(ReaderService.KEY_SMART_VOICE, checked).apply() },
        )
        SettingsWidgets.addDividedRow(
            langCard,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.auto_download_short),
                getString(R.string.auto_download_short_help),
                prefs.getBoolean(ReaderService.KEY_AUTO_DOWNLOAD, true),
            ) { checked -> prefs.edit().putBoolean(ReaderService.KEY_AUTO_DOWNLOAD, checked).apply() },
        )
        content.addView(langCard)

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_system_tts_section)))
        val phoneCard = SettingsWidgets.cardGroup(this)
        val phoneStatus = PhoneVoiceSettings.status(this)
        phoneCard.addView(
            SettingsWidgets.actionRow(
                this,
                getString(R.string.phone_voice_label),
                phoneStatus.text,
                getString(if (phoneStatus.isOurs) R.string.phone_voice_change_button else R.string.setup_button),
            ) {
                PhoneVoiceSettings.open(
                    this,
                    if (phoneStatus.isOurs) null else getString(R.string.phone_voice_toast),
                    getString(R.string.phone_voice_fallback_toast),
                )
            }.root,
        )
        content.addView(phoneCard)

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_advanced_section)))
        val pocketDisclosure = SettingsWidgets.disclosureCard(
            this,
            getString(R.string.settings_pocket_tuning_title),
            getString(R.string.settings_pocket_tuning_sub),
        ) { body -> buildPocketFields(body) }
        content.addView(pocketDisclosure.root)
    }

    private fun buildPocketFields(box: LinearLayout) {
        data class Field(
            val key: String,
            val labelRes: Int,
            val helpRes: Int,
            val isFloat: Boolean,
            val shown: (PocketTuning) -> String,
            val updated: (PocketTuning, String) -> PocketTuning?,
        )
        val fields = listOf(
            Field(ReaderService.KEY_POCKET_MAX_TOKENS, R.string.pocket_max_tokens_label, R.string.pocket_max_tokens_help, false, { it.maxTokens.toString() }) { t, s -> s.toIntOrNull()?.let { t.copy(maxTokens = it) } },
            Field(ReaderService.KEY_POCKET_MIN_TOKENS, R.string.pocket_min_tokens_label, R.string.pocket_min_tokens_help, false, { it.minTokens.toString() }) { t, s -> s.toIntOrNull()?.let { t.copy(minTokens = it) } },
            Field(ReaderService.KEY_POCKET_G2P_WINDOW_CHARS, R.string.pocket_g2p_window_label, R.string.pocket_g2p_window_help, false, { it.g2pWindowChars.toString() }) { t, s -> s.toIntOrNull()?.let { t.copy(g2pWindowChars = it) } },
            Field(ReaderService.KEY_POCKET_TEMPERATURE, R.string.pocket_temperature_label, R.string.pocket_temperature_help, true, { it.temperature.toString() }) { t, s -> s.toFloatOrNull()?.let { t.copy(temperature = it) } },
            Field(ReaderService.KEY_POCKET_DECODE_STEPS, R.string.pocket_decode_steps_label, R.string.pocket_decode_steps_help, false, { it.decodeSteps.toString() }) { t, s -> s.toIntOrNull()?.let { t.copy(decodeSteps = it) } },
            Field(ReaderService.KEY_POCKET_EOS_THRESHOLD, R.string.pocket_eos_threshold_label, R.string.pocket_eos_threshold_help, true, { it.eosThreshold.toString() }) { t, s -> s.toFloatOrNull()?.let { t.copy(eosThreshold = it) } },
            Field(ReaderService.KEY_POCKET_FRAMES_AFTER_EOS, R.string.pocket_frames_after_eos_label, R.string.pocket_frames_after_eos_help, false, { it.framesAfterEos.toString() }) { t, s -> s.toIntOrNull()?.let { t.copy(framesAfterEos = it) } },
        )
        var filling = false
        val editors = mutableListOf<Pair<Field, EditText>>()
        val errors = mutableListOf<TextView>()
        fun current(): PocketTuning = PocketTuningPrefs.load(prefs.all).tuning
        fun paint(tuning: PocketTuning) {
            filling = true
            editors.forEach { (f, e) -> e.setText(f.shown(tuning)) }
            errors.forEach { it.visibility = View.GONE }
            filling = false
        }
        for (f in fields) {
            box.addView(
                TextView(this, null, 0, R.style.Body).apply {
                    setText(f.labelRes)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(this@SettingsActivity, 10) }
                },
            )
            val err = TextView(this, null, 0, R.style.Small).apply {
                setTextColor(getColor(R.color.error))
                visibility = View.GONE
            }
            val edit = EditText(this, null, 0, R.style.BoxEdit).apply {
                inputType = if (f.isFloat) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(editable: Editable?) {
                        if (filling) return
                        val candidate = f.updated(current(), editable?.toString().orEmpty())
                        if (candidate == null) {
                            err.setText(R.string.pocket_enter_number)
                            err.visibility = View.VISIBLE
                            return
                        }
                        try {
                            candidate.validate()
                        } catch (e: IllegalArgumentException) {
                            err.text = e.message
                            err.visibility = View.VISIBLE
                            return
                        }
                        val value = PocketTuningPrefs.values(candidate).getValue(f.key)
                        val ed = prefs.edit()
                        when (value) {
                            is Int -> ed.putInt(f.key, value)
                            is Float -> ed.putFloat(f.key, value)
                            else -> ed.putString(f.key, value.toString())
                        }
                        ed.apply()
                        err.visibility = View.GONE
                    }
                })
            }
            box.addView(edit)
            box.addView(TextView(this, null, 0, R.style.Small).apply { setText(f.helpRes) })
            box.addView(err)
            editors += f to edit
            errors += err
        }
        paint(current())
        box.addView(
            Button(this, null, 0, R.style.Btn_Ghost).apply {
                setText(R.string.pocket_reset_defaults)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(this@SettingsActivity, 10) }
                setOnClickListener {
                    val ed = prefs.edit()
                    for ((k, v) in PocketTuningPrefs.values(PocketTuning())) {
                        when (v) {
                            is Int -> ed.putInt(k, v)
                            is Float -> ed.putFloat(k, v)
                            else -> ed.putString(k, v.toString())
                        }
                    }
                    ed.apply()
                    paint(PocketTuning())
                }
            },
        )
    }

    // ------------------------------------------------------------------
    // 2. Reading & Pronunciation
    // ------------------------------------------------------------------

    private fun renderReadingSection() {
        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_text_norm_section)))
        val normCard = SettingsWidgets.cardGroup(this)
        SettingsWidgets.addDividedRow(
            normCard,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.normalize_text_label),
                getString(R.string.normalize_text_help),
                prefs.getBoolean(ReaderService.KEY_NORMALIZE_TEXT, true),
            ) { checked -> prefs.edit().putBoolean(ReaderService.KEY_NORMALIZE_TEXT, checked).apply() },
        )
        SettingsWidgets.addDividedRow(
            normCard,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.date_day_first_label),
                getString(R.string.date_day_first_help),
                ReaderService.normalizeOptions(this).dateOrder == DateOrder.DAY_FIRST,
            ) { checked ->
                val order = if (checked) DateOrder.DAY_FIRST else DateOrder.MONTH_FIRST
                prefs.edit().putString(ReaderService.KEY_DATE_ORDER, order.key).apply()
            },
        )
        SettingsWidgets.addDividedRow(
            normCard,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.respell_names_label),
                getString(R.string.respell_names_help),
                prefs.getBoolean(ReaderService.KEY_RESPELL_NAMES, false),
            ) { checked -> prefs.edit().putBoolean(ReaderService.KEY_RESPELL_NAMES, checked).apply() },
        )
        SettingsWidgets.addDividedRow(
            normCard,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.read_hidden_text_label),
                getString(R.string.read_hidden_text_help),
                prefs.getBoolean(ReaderService.KEY_READ_HIDDEN_TEXT, false),
            ) { checked -> prefs.edit().putBoolean(ReaderService.KEY_READ_HIDDEN_TEXT, checked).apply() },
        )
        content.addView(normCard)

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_extractor_section)))
        val siteCard = SettingsWidgets.cardGroup(this)
        val coord = (application as ReadlyApp).siteRules
        coord.ensureStarted()
        val engine = ArticleEngine.get(this)

        SettingsWidgets.addDividedRow(
            siteCard,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.nyt_auto_label),
                getString(R.string.nyt_auto_help),
                prefs.getBoolean(ReaderService.KEY_NYT_AUTO_UPDATE, true),
            ) { checked -> prefs.edit().putBoolean(ReaderService.KEY_NYT_AUTO_UPDATE, checked).apply() },
        )

        val statusAction = SettingsWidgets.actionRow(
            this,
            getString(R.string.nyt_section_title),
            "",
            getString(R.string.nyt_check_updates),
        ) {}
        val errorView = TextView(this, null, 0, R.style.Small).apply {
            setTextColor(getColor(R.color.error))
            visibility = View.GONE
            setPadding(0, 0, 0, dp(this@SettingsActivity, 10))
        }
        SettingsWidgets.addDividedRow(siteCard, statusAction.root)
        siteCard.addView(errorView)
        content.addView(siteCard)

        val actRef = WeakReference<Activity>(this)
        val btnRef = WeakReference(statusAction.button)
        val subRef = WeakReference(statusAction.subtitleView)
        val errRef = WeakReference(errorView)
        val main = Handler(Looper.getMainLooper())
        val repaint = Runnable {
            val act = actRef.get()
            val btn = btnRef.get()
            val sub = subRef.get()
            val err = errRef.get()
            if (act == null || act.isFinishing || act.isDestroyed || btn == null || sub == null || err == null) {
                return@Runnable
            }
            if (!btn.isAttachedToWindow) return@Runnable
            SiteCardUi.paint(btn, sub, err, act, coord.siteUiState(), engine.bpcUiState())
        }
        val firstSite = coord.siteUiState().also {
            if (it.base.checking) coord.checkUpdates(manual = false) { main.post(repaint) }
        }
        val firstBpc = engine.bpcUiState().also {
            if (it.checking) engine.checkAddonUpdates(manual = false) { main.post(repaint) }
        }
        SiteCardUi.paint(statusAction.button, statusAction.subtitleView, errorView, this, firstSite, firstBpc)
        statusAction.button.setOnClickListener {
            SiteCardUi.paint(
                statusAction.button,
                statusAction.subtitleView,
                errorView,
                this,
                coord.siteUiState().let { it.copy(base = it.base.copy(checking = true)) },
                engine.bpcUiState().copy(checking = true),
            )
            engine.checkAddonUpdates(manual = true) { main.post(repaint) }
            coord.checkUpdates(manual = true) { main.post(repaint) }
        }
    }

    // ------------------------------------------------------------------
    // 3. Websites & Feeds
    // ------------------------------------------------------------------

    private fun renderWebsitesSection() {
        val app = application as ReadlyApp
        val siteCount = runCatching { app.feeds.sites().size }.getOrDefault(0)

        val sourcesCard = SettingsWidgets.cardGroup(this)
        sourcesCard.addView(
            SettingsWidgets.navRow(
                this,
                R.drawable.ic_globe,
                getString(R.string.settings_manage_sites_title),
                getString(R.string.settings_manage_sites_sub, siteCount),
            ) {
                startActivity(Intent(this, FeedsActivity::class.java))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
            }.root,
        )
        content.addView(sourcesCard)

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_display_limits_section)))
        val displayCard = SettingsWidgets.cardGroup(this)

        val compact = WebsiteLayout.compact(this)
        SettingsWidgets.addDividedRow(
            displayCard,
            SettingsWidgets.segmentedRow(
                this,
                getString(R.string.settings_layout_title),
                getString(R.string.compact_websites_help),
                listOf(getString(R.string.settings_layout_tiles), getString(R.string.settings_layout_compact)),
                if (compact) 1 else 0,
            ) { idx ->
                prefs.edit().putBoolean(WebsiteLayout.KEY_COMPACT, idx == 1).apply()
            },
        )

        val zoneIds = listOf(FeedTimes.DEFAULT_TIME_ZONE, FeedTimes.DEVICE_TIME_ZONE) +
            ZoneId.getAvailableZoneIds().sorted().filterNot { it == FeedTimes.DEFAULT_TIME_ZONE }
        val zoneLabels = zoneIds.map { id ->
            when (id) {
                FeedTimes.DEFAULT_TIME_ZONE -> getString(R.string.feed_time_zone_new_york)
                FeedTimes.DEVICE_TIME_ZONE -> getString(R.string.feed_time_zone_device, ZoneId.systemDefault().id)
                else -> id.replace('_', ' ')
            }
        }
        lateinit var tzRow: SettingsWidgets.NavRowHandles
        fun tzSummary(): String {
            val selected = prefs.getString(FeedTimes.KEY_TIME_ZONE, FeedTimes.DEFAULT_TIME_ZONE)
                ?: FeedTimes.DEFAULT_TIME_ZONE
            val idx = zoneIds.indexOf(selected).coerceAtLeast(0)
            return zoneLabels[idx]
        }
        tzRow = SettingsWidgets.navRow(
            this,
            null,
            getString(R.string.feed_time_zone_label),
            tzSummary(),
        ) {
            val selected = prefs.getString(FeedTimes.KEY_TIME_ZONE, FeedTimes.DEFAULT_TIME_ZONE)
                ?: FeedTimes.DEFAULT_TIME_ZONE
            ListSheet(
                this,
                getString(R.string.feed_time_zone_label),
                zoneLabels,
                zoneIds.indexOf(selected).coerceAtLeast(0),
                withSearch = true,
            ) { pickedIdx ->
                prefs.edit().putString(FeedTimes.KEY_TIME_ZONE, zoneIds[pickedIdx]).apply()
                tzRow.subtitleView.text = tzSummary()
            }.show()
        }
        SettingsWidgets.addDividedRow(displayCard, tzRow.root)
        content.addView(displayCard)

        val currentLimits = app.feeds.limits()
        lateinit var limitsDisclosure: SettingsWidgets.DisclosureHandles
        limitsDisclosure = SettingsWidgets.disclosureCard(
            this,
            getString(R.string.settings_limits_title),
            getString(R.string.settings_limits_sub, currentLimits.maxArticles, currentLimits.refreshMinutes),
        ) { box ->
            box.addView(TextView(this, null, 0, R.style.Small).apply { setText(R.string.auto_sites_max_label) })
            val maxEdit = EditText(this, null, 0, R.style.BoxEdit).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(currentLimits.maxArticles.toString())
            }
            box.addView(maxEdit)
            box.addView(
                TextView(this, null, 0, R.style.Small).apply {
                    setText(R.string.auto_sites_refresh_label)
                    setPadding(0, dp(this@SettingsActivity, 8), 0, 0)
                },
            )
            val refreshEdit = EditText(this, null, 0, R.style.BoxEdit).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(currentLimits.refreshMinutes.toString())
            }
            box.addView(refreshEdit)
            val status = TextView(this, null, 0, R.style.Small).apply {
                setPadding(0, dp(this@SettingsActivity, 6), 0, 0)
                visibility = View.GONE
            }
            box.addView(status)
            box.addView(
                Button(this, null, 0, R.style.Btn_Ghost).apply {
                    setText(R.string.auto_sites_save)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(this@SettingsActivity, 8) }
                    setOnClickListener {
                        val max = FeedLimits.parseMaxArticles(maxEdit.text.toString())
                        val refresh = FeedLimits.parseRefreshMinutes(refreshEdit.text.toString())
                        val error = max.exceptionOrNull() ?: refresh.exceptionOrNull()
                        status.visibility = View.VISIBLE
                        if (error != null) {
                            status.text = error.message
                            status.setTextColor(getColor(R.color.error))
                            return@setOnClickListener
                        }
                        val next = FeedLimits(max.getOrThrow(), refresh.getOrThrow())
                        app.feeds.saveLimits(next)
                        status.setText(R.string.auto_sites_saved)
                        status.setTextColor(getColor(R.color.ok))
                        limitsDisclosure.subtitleView.text = getString(
                            R.string.settings_limits_sub,
                            next.maxArticles,
                            next.refreshMinutes,
                        )
                    }
                },
            )
        }
        content.addView(limitsDisclosure.root)

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_schedule_section)))
        content.addView(buildScheduleCard())
    }

    private fun buildScheduleCard(): View {
        val current = FeedRefreshScheduler.read(this)
        val card = SettingsWidgets.cardGroup(this)

        var enabledState = current.enabled
        var wifiState = current.wifiOnly
        var batteryState = current.batteryNotLow
        var chargingState = current.charging

        val intervalEdit = EditText(this, null, 0, R.style.BoxEdit).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.minutes.toString())
        }
        val statusText = TextView(this, null, 0, R.style.Small).apply {
            text = prefs.getString(FeedRefreshScheduler.STATUS, "No scheduled refresh has run yet.").orEmpty()
            setPadding(0, dp(this@SettingsActivity, 6), 0, 0)
        }

        fun saveSchedule(showSuccessToast: Boolean = false) {
            try {
                val interval = intervalEdit.text.toString().toIntOrNull()
                require(interval != null) { "Enter a whole number of minutes." }
                FeedRefreshScheduler.save(
                    this,
                    RefreshSchedule(enabledState, interval, wifiState, batteryState, chargingState),
                )
                statusText.setTextColor(getColor(R.color.text2))
                statusText.text = if (enabledState) {
                    "Schedule saved. Android will refresh when these conditions are met."
                } else {
                    "Background refresh is off. You can still refresh manually."
                }
                if (showSuccessToast) {
                    Toast.makeText(this, R.string.auto_sites_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Readly", "Couldn't save background feed refresh schedule", e)
                statusText.setTextColor(getColor(R.color.error))
                statusText.text = if (e is IllegalArgumentException) e.message else "Couldn't save the schedule. Try again."
            }
        }

        SettingsWidgets.addDividedRow(
            card,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.settings_schedule_enable),
                getString(R.string.settings_schedule_help),
                enabledState,
            ) { checked ->
                enabledState = checked
                saveSchedule()
            },
        )

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(this@SettingsActivity, 10), 0, dp(this@SettingsActivity, 10))
        }
        details.addView(TextView(this, null, 0, R.style.Small).apply { setText(R.string.settings_schedule_interval_label) })
        intervalEdit.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(this@SettingsActivity, 4) }
        details.addView(intervalEdit)

        val condFlow = FlowLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(this@SettingsActivity, 10) }
        }
        fun conditionChip(labelRes: Int, initial: Boolean, onToggle: (Boolean) -> Unit): Button {
            var state = initial
            val chip = Button(this, null, 0, if (state) R.style.Chip_Selected else R.style.Chip).apply {
                text = getString(labelRes)
                setOnClickListener {
                    state = !state
                    setBackgroundResource(if (state) R.drawable.chip_selected_background else R.drawable.chip_background)
                    setTextColor(getColor(if (state) R.color.accent else R.color.text2))
                    onToggle(state)
                    saveSchedule()
                }
            }
            return chip
        }
        condFlow.addView(conditionChip(R.string.settings_cond_wifi, wifiState) { wifiState = it })
        condFlow.addView(conditionChip(R.string.settings_cond_battery, batteryState) { batteryState = it })
        condFlow.addView(conditionChip(R.string.settings_cond_charging, chargingState) { chargingState = it })
        details.addView(condFlow)
        details.addView(statusText)
        details.addView(
            Button(this, null, 0, R.style.Btn_Ghost).apply {
                setText(R.string.settings_schedule_save)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(this@SettingsActivity, 10) }
                setOnClickListener { saveSchedule(showSuccessToast = true) }
            },
        )
        SettingsWidgets.addDividedRow(card, details)
        return card
    }

    // ------------------------------------------------------------------
    // 4. Android Auto & Playback
    // ------------------------------------------------------------------

    private fun renderAndroidAutoSection() {
        val feeds = (application as ReadlyApp).feeds

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_queue_section)))
        val queueCard = SettingsWidgets.cardGroup(this)
        SettingsWidgets.addDividedRow(
            queueCard,
            SettingsWidgets.switchRow(
                this,
                "Play next article",
                getString(R.string.auto_sites_play_next),
                feeds.isPlayNextOn(),
            ) { checked -> feeds.setPlayNext(checked) },
        )
        SettingsWidgets.addDividedRow(
            queueCard,
            SettingsWidgets.switchRow(
                this,
                "Preload next article early",
                getString(R.string.auto_sites_prefetch),
                prefs.getBoolean(FeedRepository.KEY_PREFETCH, true),
            ) { checked -> prefs.edit().putBoolean(FeedRepository.KEY_PREFETCH, checked).apply() },
        )
        SettingsWidgets.addDividedRow(
            queueCard,
            SettingsWidgets.switchRow(
                this,
                "Announce article intro",
                getString(R.string.auto_sites_intro),
                prefs.getBoolean(FeedRepository.KEY_INTRO, true),
            ) { checked -> prefs.edit().putBoolean(FeedRepository.KEY_INTRO, checked).apply() },
        )
        content.addView(queueCard)

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_in_car_section)))
        val carCard = SettingsWidgets.cardGroup(this)
        SettingsWidgets.addDividedRow(
            carCard,
            SettingsWidgets.switchRow(
                this,
                "Show sentence on car screen",
                getString(R.string.auto_sites_show_sentence),
                prefs.getBoolean(ReaderService.KEY_CAR_SENTENCE, true),
            ) { checked -> prefs.edit().putBoolean(ReaderService.KEY_CAR_SENTENCE, checked).apply() },
        )
        SettingsWidgets.addDividedRow(
            carCard,
            SettingsWidgets.switchRow(
                this,
                "Pause for navigation prompts",
                getString(R.string.auto_sites_pause_prompts),
                prefs.getBoolean(ReaderService.KEY_PAUSE_FOR_PROMPTS, true),
            ) { checked -> prefs.edit().putBoolean(ReaderService.KEY_PAUSE_FOR_PROMPTS, checked).apply() },
        )
        SettingsWidgets.addDividedRow(
            carCard,
            SettingsWidgets.switchRow(
                this,
                "Auto-play unheard on connect",
                getString(R.string.auto_sites_auto_play),
                prefs.getBoolean(FeedRepository.KEY_AUTO_PLAY, false),
            ) { checked -> prefs.edit().putBoolean(FeedRepository.KEY_AUTO_PLAY, checked).apply() },
        )
        content.addView(carCard)

        val guide = SettingsWidgets.disclosureCard(
            this,
            getString(R.string.settings_car_setup_title),
            getString(R.string.settings_car_setup_sub),
        ) { body ->
            body.addView(TextView(this, null, 0, R.style.Small).apply { setText(R.string.auto_sites_unknown_sources) })
        }
        content.addView(guide.root)
    }

    // ------------------------------------------------------------------
    // 5. Documents & OCR
    // ------------------------------------------------------------------

    private fun renderDocumentsSection() {
        val defaults = PdfLimits()
        val ocrDefaults = PdfOcrOptions()

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_ocr_section)))
        val ocrCard = SettingsWidgets.cardGroup(this)
        SettingsWidgets.addDividedRow(
            ocrCard,
            SettingsWidgets.switchRow(
                this,
                getString(R.string.pdf_ocr_enabled),
                "Automatically recognize image-based PDF pages on device",
                prefs.getBoolean(PdfFiles.KEY_OCR_ENABLED, ocrDefaults.enabled),
            ) { checked -> prefs.edit().putBoolean(PdfFiles.KEY_OCR_ENABLED, checked).apply() },
        )

        val languages = listOf("auto", "eng", "fas", "eng+fas")
        val langLabels = listOf(
            getString(R.string.settings_ocr_lang_auto),
            getString(R.string.pdf_ocr_language_english),
            getString(R.string.pdf_ocr_language_persian),
            getString(R.string.settings_ocr_lang_both),
        )
        val storedLang = prefs.getString(PdfFiles.KEY_OCR_LANGUAGE, ocrDefaults.languages)
        val langIdx = languages.indexOf(storedLang).also { idx ->
            if (idx < 0) {
                Log.w("Readly", "fallback=true attempted=pdf_ocr_language reason=invalid_saved_language taken=show_default_for_correction")
                Toast.makeText(this, R.string.pdf_settings_invalid, Toast.LENGTH_LONG).show()
            }
        }
        SettingsWidgets.addDividedRow(
            ocrCard,
            SettingsWidgets.segmentedRow(
                this,
                getString(R.string.pdf_ocr_language),
                getString(R.string.pdf_ocr_language_auto),
                langLabels,
                if (langIdx < 0) 0 else langIdx,
            ) { idx -> prefs.edit().putString(PdfFiles.KEY_OCR_LANGUAGE, languages[idx]).apply() },
        )

        val layouts = listOf("auto", "sparse", "block", "line")
        val layoutLabels = listOf(
            getString(R.string.settings_layout_auto),
            getString(R.string.settings_layout_scattered),
            getString(R.string.settings_layout_block),
            getString(R.string.settings_layout_line),
        )
        val storedLayout = prefs.getString(PdfFiles.KEY_IMAGE_LAYOUT, ocrDefaults.imageLayout)
        val layoutIdx = layouts.indexOf(storedLayout).also { idx ->
            if (idx < 0) {
                Log.w("Readly", "fallback=true attempted=image_ocr_layout reason=invalid_saved_layout taken=show_default_for_correction")
                Toast.makeText(this, R.string.pdf_settings_invalid, Toast.LENGTH_LONG).show()
            }
        }
        SettingsWidgets.addDividedRow(
            ocrCard,
            SettingsWidgets.segmentedRow(
                this,
                getString(R.string.image_layout_label),
                "Scattered for signs or menus, Block for dense screenshots",
                layoutLabels,
                if (layoutIdx < 0) 0 else layoutIdx,
            ) { idx -> prefs.edit().putString(PdfFiles.KEY_IMAGE_LAYOUT, layouts[idx]).apply() },
        )
        content.addView(ocrCard)

        val maxMb = prefs.getInt(PdfFiles.KEY_MAX_MB, defaults.maxFileMb)
        val maxChars = prefs.getInt(PdfFiles.KEY_MAX_CHARS, defaults.maxTextChars)
        val maxSide = prefs.getInt(PdfFiles.KEY_OCR_SIDE, ocrDefaults.maxSide)

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_ocr_limits_section)))
        lateinit var limitsDisclosure: SettingsWidgets.DisclosureHandles
        limitsDisclosure = SettingsWidgets.disclosureCard(
            this,
            getString(R.string.settings_ocr_limits_title),
            getString(R.string.settings_ocr_limits_sub, maxMb, SettingsSummaries.formatCharCount(maxChars), maxSide),
        ) { box ->
            fun numberField(labelRes: Int, initial: Int): EditText {
                box.addView(
                    TextView(this, null, 0, R.style.Small).apply {
                        setText(labelRes)
                        setPadding(0, dp(this@SettingsActivity, 8), 0, dp(this@SettingsActivity, 2))
                    },
                )
                return EditText(this, null, 0, R.style.BoxEdit).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(initial.toString())
                    box.addView(this)
                }
            }
            val sizeEdit = numberField(R.string.pdf_max_mb_label, maxMb)
            val charsEdit = numberField(R.string.pdf_max_chars_label, maxChars)
            val sideEdit = numberField(R.string.pdf_ocr_resolution, maxSide)
            val errorText = TextView(this, null, 0, R.style.Small).apply {
                setTextColor(getColor(R.color.error))
                setPadding(0, dp(this@SettingsActivity, 6), 0, 0)
            }
            box.addView(errorText)
            box.addView(
                Button(this, null, 0, R.style.Btn_Ghost).apply {
                    setText(R.string.pdf_save_limits)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(this@SettingsActivity, 6) }
                    setOnClickListener {
                        val mb = sizeEdit.text.toString().toIntOrNull()
                        val chars = charsEdit.text.toString().toIntOrNull()
                        val side = sideEdit.text.toString().toIntOrNull()
                        if (mb == null || chars == null || side == null) {
                            errorText.setText(R.string.pocket_enter_number)
                            return@setOnClickListener
                        }
                        try {
                            PdfLimits(mb, chars).validate()
                            PdfOcrOptions(
                                prefs.getBoolean(PdfFiles.KEY_OCR_ENABLED, ocrDefaults.enabled),
                                prefs.getString(PdfFiles.KEY_OCR_LANGUAGE, ocrDefaults.languages) ?: ocrDefaults.languages,
                                side,
                                prefs.getString(PdfFiles.KEY_IMAGE_LAYOUT, ocrDefaults.imageLayout) ?: ocrDefaults.imageLayout,
                            ).validate()
                        } catch (e: IllegalArgumentException) {
                            Log.e("Readly", "Document import settings validation failed", e)
                            errorText.text = e.message
                            return@setOnClickListener
                        }
                        prefs.edit().putInt(PdfFiles.KEY_MAX_MB, mb)
                            .putInt(PdfFiles.KEY_MAX_CHARS, chars)
                            .putInt(PdfFiles.KEY_OCR_SIDE, side).apply()
                        errorText.text = ""
                        limitsDisclosure.subtitleView.text = getString(
                            R.string.settings_ocr_limits_sub,
                            mb,
                            SettingsSummaries.formatCharCount(chars),
                            side,
                        )
                        Toast.makeText(this@SettingsActivity, R.string.pdf_limits_saved, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            box.addView(
                TextView(this, null, 0, R.style.Small).apply {
                    setText(R.string.pdf_limits_help)
                    setPadding(0, dp(this@SettingsActivity, 10), 0, 0)
                },
            )
        }
        content.addView(limitsDisclosure.root)
    }

    // ------------------------------------------------------------------
    // 6. Storage & Data
    // ------------------------------------------------------------------

    private fun renderStorageSection() {
        val app = application as ReadlyApp

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_models_section)))
        val modelsCard = SettingsWidgets.cardGroup(this)
        val modelsRow = SettingsWidgets.navRow(
            this,
            R.drawable.ic_storage,
            getString(R.string.settings_models_title),
            getString(R.string.settings_models_loading),
        ) {
            startActivity(Intent(this, VoicesActivity::class.java))
            overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
        }
        modelsCard.addView(modelsRow.root)
        content.addView(modelsCard)

        val actRef = WeakReference(this)
        val subRef = WeakReference(modelsRow.subtitleView)
        app.ioExecutor.execute {
            val packs = installedPacks()
            val totalMb = packs.sumOf { it.sizeMb }
            val names = packs.joinToString(" · ") { VoiceCatalog.shortName(it) }
            val summary = if (packs.isEmpty()) {
                getString(R.string.storage_empty)
            } else {
                "$totalMb MB used · $names"
            }
            runOnUiThread {
                val act = actRef.get() ?: return@runOnUiThread
                val tv = subRef.get() ?: return@runOnUiThread
                if (!act.isDestroyed && !act.isFinishing && tv.isAttachedToWindow) {
                    tv.text = summary
                }
            }
        }

        content.addView(SettingsWidgets.sectionLabel(this, getString(R.string.settings_cleanup_section)))
        val cleanCard = SettingsWidgets.cardGroup(this)
        lateinit var clearReadingsRow: SettingsWidgets.ActionRowHandles
        clearReadingsRow = SettingsWidgets.actionRow(
            this,
            getString(R.string.settings_clear_readings_title),
            getString(R.string.settings_clear_readings_sub),
            getString(R.string.settings_clear_readings_btn),
        ) {
            AlertDialog.Builder(this)
                .setTitle("Clear saved readings?")
                .setMessage("Readly saves text locally for Search and Bookmarks. This removes saved readings without bookmarks. Imported documents, feed headlines and bookmarked readings stay available.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Clear") { _, _ ->
                    clearReadingsRow.button.isEnabled = false
                    app.ioExecutor.execute {
                        try {
                            val count = ReadingStore(this).use {
                                it.clearUnbookmarked(ReaderState.snapshot.readingId)
                            }
                            runOnUiThread {
                                if (!isDestroyed) {
                                    clearReadingsRow.button.isEnabled = true
                                    Toast.makeText(this, "$count saved readings cleared", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Readly", "Couldn't clear saved readings", e)
                            runOnUiThread {
                                if (!isDestroyed) {
                                    clearReadingsRow.button.isEnabled = true
                                    Toast.makeText(this, "Couldn't clear saved readings. Try again.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }.show()
        }
        SettingsWidgets.addDividedRow(cleanCard, clearReadingsRow.root)

        SettingsWidgets.addDividedRow(
            cleanCard,
            SettingsWidgets.actionRow(
                this,
                getString(R.string.pdf_imports_clear),
                "Removes completed, failed and cancelled PDFs, pictures & OCR text cache",
                getString(R.string.settings_clear_imports_btn),
                danger = true,
            ) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.pdf_imports_clear_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        app.ioExecutor.execute {
                            try {
                                PdfImportStore(this).use { it.clearFinished() }
                                runOnUiThread {
                                    if (!isDestroyed) {
                                        Toast.makeText(this, R.string.pdf_imports_cleared, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("Readly", "Couldn't clear imported documents", e)
                                runOnUiThread {
                                    if (!isDestroyed) {
                                        Toast.makeText(this, R.string.document_operation_failed, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }.show()
            }.root,
        )
        content.addView(cleanCard)
    }

    private fun installedPacks(): List<VoicePack> {
        val dir = File(noBackupFilesDir, "voices")
        return VoiceCatalog.ALL.filter {
            VoiceModelStore(it, dir, VoiceModelStore::httpUrlOpener).isInstalled()
        }
    }

    companion object {
        private const val EXTRA_SECTION = "section"

        fun open(activity: Activity, section: Section? = null) {
            val intent = Intent(activity, SettingsActivity::class.java)
            if (section != null) intent.putExtra(EXTRA_SECTION, section.name)
            activity.startActivity(intent)
            activity.overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
        }

        internal object SiteCardUi {
            fun paint(
                button: Button,
                statusText: TextView,
                errorView: TextView,
                activity: Activity,
                state: SiteUiState,
                bpc: BpcUiState? = null,
            ) {
                val base = state.base
                val checking = base.checking || bpc?.checking == true
                button.isEnabled = !checking
                button.setText(if (checking) R.string.nyt_checking else R.string.nyt_check_updates)
                statusText.text = siteStatusText(activity, state, bpc)
                val problems = listOfNotNull(
                    bpc?.lastError,
                    base.startupError, base.lastError, base.warning,
                    base.startupNotice, base.coordinatorError,
                ).filter { it.isNotBlank() }
                if (problems.isEmpty()) {
                    errorView.visibility = View.GONE
                } else {
                    errorView.text = problems.joinToString("\n\n")
                    errorView.visibility = View.VISIBLE
                }
            }

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
