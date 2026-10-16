package app.readly

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.readly.model.InstallStage
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceFamily
import app.readly.model.VoiceFamilyInfo
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.playback.ReaderService
import app.readly.playback.ReaderState
import app.readly.playback.ReaderStatus
import app.readly.tts.ReadlyTtsService
import app.readly.ui.ListSheet
import java.io.File
import java.io.IOException

/**
 * One card per [VoiceFamily]: pick a variant segment (and a Piper language and
 * voice), play its sample clip, download it or switch to it. A storage list at
 * the bottom shows what's installed. Downloads reuse the single app-wide
 * [InstallTask], so only one install runs at a time no matter which screen
 * started it.
 */
class VoicesActivity : Activity(), InstallTask.Listener {
    companion object {
        /** Preselect extras from the Smart voice Ask notification. */
        const val EXTRA_PRESELECT_FAMILY = "preselectFamily"
        const val EXTRA_PRESELECT_LANG = "preselectLang"
        const val EXTRA_PRESELECT_VOICE = "preselectVoice"
        const val EXTRA_PRESELECT_PACK = "preselectPack"
    }

    private lateinit var cardsBox: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var systemSpinner: Spinner
    /** Pack id per system spinner row; null is the automatic entry. */
    private var systemRows: List<String?> = listOf(null)
    private val cards = mutableListOf<Card>()
    private lateinit var storageTotal: TextView
    private lateinit var storageRows: LinearLayout
    private lateinit var storageNote: TextView
    private val samples = SamplePlayer()
    private var playingPackId: String? = null
    private var sampleLoading = false
    private var task: InstallTask? = null

    /** Download/sample errors by pack id; each card shows its selected pack's error. */
    private val cardErrors = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voices)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { closeWithTransition() }
        cardsBox = findViewById(R.id.cardsContainer)
        prefs = getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE)
        addSystemSection()
        for (info in VoiceCatalog.FAMILIES) {
            val card = Card(info)
            cards.add(card)
            cardsBox.addView(card.root)
        }
        cardsBox.addView(
            TextView(this, null, 0, R.style.CardTitle).apply {
                text = getString(R.string.storage_title)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
            },
        )
        storageTotal = TextView(this, null, 0, R.style.Small)
        storageRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        storageNote = TextView(this, null, 0, R.style.Small)
        cardsBox.addView(storageTotal)
        cardsBox.addView(storageRows)
        cardsBox.addView(storageNote)
        applyPreselect()
    }

    /** Honors the Smart voice Ask notification's preselect extras, if present. */
    private fun applyPreselect() {
        val packId = intent.getStringExtra(EXTRA_PRESELECT_PACK)
        val pack = packId?.let { VoiceCatalog.byId(it) }
        if (pack != null) {
            cardForPack(pack.id).selectPack(pack)
            return
        }
        val family = intent.getStringExtra(EXTRA_PRESELECT_FAMILY)?.let {
            try {
                VoiceFamily.valueOf(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: return
        val card = cards.firstOrNull { it.info.family == family } ?: return
        if (family == VoiceFamily.PIPER) {
            intent.getStringExtra(EXTRA_PRESELECT_LANG)?.let { lang ->
                if (lang in VoiceCatalog.piperLangs()) card.piperLang = lang
            }
            intent.getStringExtra(EXTRA_PRESELECT_VOICE)?.let { voice ->
                val lang = card.piperLang ?: return@let
                if (voice in VoiceCatalog.piperVoices(lang)) card.piperVoice = voice
            }
            card.rebuildPickerChips()
            card.rebuildSegments()
            card.selectedPackId = card.chipPacks().firstOrNull()?.id ?: card.selectedPackId
            // Re-check the segment for the new selection.
            for (i in 0 until card.chips.childCount) {
                val chip = card.chips.getChildAt(i) as RadioButton
                if (chip.tag == card.selectedPackId) {
                    card.chips.check(chip.id)
                    break
                }
            }
        }
        card.update()
    }

    override fun onResume() {
        super.onResume()
        val running = (application as ReadlyApp).installTask
        if (running != null && !running.isDone) {
            task = running
            running.listener = this
        } else {
            task = null
        }
        refresh()
        task?.let { updateProgress(it.lastStage, it.lastDoneBytes, it.lastTotalBytes) }
    }

    override fun onPause() {
        task?.let { if (it.listener === this) it.listener = null }
        super.onPause()
    }

    override fun onStop() {
        samples.release()
        playingPackId = null
        sampleLoading = false
        super.onStop()
    }

    override fun onDestroy() {
        samples.release()
        task?.let { if (it.listener === this) it.listener = null }
        super.onDestroy()
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

    private fun voicesDir(): File = File(noBackupFilesDir, "voices")

    private fun isInstalled(pack: VoicePack): Boolean =
        VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener).isInstalled()

    private fun cardForPack(id: String): Card {
        val family = VoiceCatalog.byId(id)?.family ?: return cards.first()
        return cards.first { it.info.family == family }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------
    // Family cards
    // ------------------------------------------------------------------

    private inner class Card(val info: VoiceFamilyInfo) {
        /** Piper language; default is the active pack's, else the most recently picked. */
        var piperLang: String? =
            if (info.family == VoiceFamily.PIPER) defaultPiperLang() else null

        /** Default voice: the default pack's voice within [piperLang]. */
        var piperVoice: String? = piperLang?.let { defaultIn(langPacks(it)).voiceName }

        /** Default: the active pack if it's in this family, else the first downloaded pack, else the first pack. */
        var selectedPackId: String =
            defaultIn(if (info.family == VoiceFamily.PIPER) chipPacks() else VoiceCatalog.packsFor(info.family)).id

        val root: LinearLayout = LinearLayout(this@VoicesActivity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            val p = dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        val badge: TextView = TextView(this@VoicesActivity).apply {
            setText(R.string.in_use_badge_caps)
            setTextColor(getColor(R.color.ok))
            setBackgroundResource(R.drawable.badge_background)
            textSize = 10.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val h = dp(8)
            val v = dp(4)
            setPadding(h, v, h, v)
            visibility = View.GONE
        }
        val chips: RadioGroup = RadioGroup(this@VoicesActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            setBackgroundResource(R.drawable.segmented_track)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        }
        var langChip: Button? = null
        var voiceChip: Button? = null
        val statusText = TextView(this@VoicesActivity, null, 0, R.style.Small)
        val sampleButton = Button(this@VoicesActivity, null, 0, R.style.Btn_Ghost).apply {
            setText(R.string.sample_button)
            setOnClickListener {
                VoiceCatalog.byId(selectedPackId)?.let { toggleSample(it) }
            }
        }
        val mainButton = Button(this@VoicesActivity, null, 0, R.style.Btn_Primary).apply {
            setOnClickListener { onMainButton() }
        }
        val noteText = TextView(this@VoicesActivity, null, 0, R.style.Small).apply {
            setText(R.string.another_downloading_note)
            visibility = View.GONE
        }
        val progressBar = ProgressBar(
            this@VoicesActivity, null, android.R.attr.progressBarStyleHorizontal,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            progressDrawable = getDrawable(R.drawable.seekbar_progress)
            minimumHeight = dp(4)
            max = 100
            visibility = View.GONE
        }
        val progressLabel = TextView(this@VoicesActivity, null, 0, R.style.Small).apply {
            visibility = View.GONE
        }
        val errorText = TextView(this@VoicesActivity).apply {
            setTextColor(getColor(R.color.error))
            visibility = View.GONE
        }

        init {
            val titleRow = LinearLayout(this@VoicesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val title = TextView(this@VoicesActivity, null, 0, R.style.CardTitle).apply {
                text = info.title
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleRow.addView(title)
            titleRow.addView(badge)
            root.addView(titleRow)
            root.addView(
                TextView(this@VoicesActivity, null, 0, R.style.Small).apply { text = info.tagline },
            )
            if (info.family == VoiceFamily.PIPER) {
                root.addView(
                    LinearLayout(this@VoicesActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
                        langChip = Button(this@VoicesActivity, null, 0, R.style.Chip).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                            setCompoundDrawablesWithIntrinsicBounds(
                                R.drawable.ic_globe,
                                0,
                                R.drawable.ic_chevron_down,
                                0,
                            )
                            compoundDrawablePadding = dp(6)
                            setOnClickListener { pickLanguage() }
                        }
                        addView(langChip)
                        voiceChip = Button(this@VoicesActivity, null, 0, R.style.Chip).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply { leftMargin = dp(8) }
                            setCompoundDrawablesWithIntrinsicBounds(
                                0,
                                0,
                                R.drawable.ic_chevron_down,
                                0,
                            )
                            compoundDrawablePadding = dp(6)
                            setOnClickListener { pickVoice() }
                        }
                        addView(voiceChip)
                    },
                )
                rebuildPickerChips()
            }
            root.addView(chips)
            root.addView(statusText)
            val actionRow = LinearLayout(this@VoicesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            }
            val sampleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            sampleButton.layoutParams = sampleParams
            actionRow.addView(sampleButton)
            val mainParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.3f,
            ).apply { leftMargin = dp(8) }
            mainButton.layoutParams = mainParams
            actionRow.addView(mainButton)
            root.addView(actionRow)
            root.addView(noteText)
            root.addView(progressBar)
            root.addView(progressLabel)
            root.addView(errorText)
            rebuildSegments()
        }

        /** Packs behind this card's segments: the family, or the selected Piper voice. */
        fun chipPacks(): List<VoicePack> {
            val packs = VoiceCatalog.packsFor(info.family)
            return if (info.family == VoiceFamily.PIPER) {
                packs.filter { it.lang == piperLang && it.voiceName == piperVoice }
            } else {
                packs
            }
        }

        /** Repaints the language/voice picker chips from the current selection. */
        fun rebuildPickerChips() {
            val lang = piperLang ?: return
            langChip?.text = VoiceCatalog.langDisplay(lang)
            voiceChip?.text = piperVoice
        }

        /** Opens the language list sheet (with search, the list is long). */
        private fun pickLanguage() {
            val langs = VoiceCatalog.piperLangs()
            ListSheet(
                this@VoicesActivity,
                getString(R.string.lang_sheet_title),
                langs.map { VoiceCatalog.langDisplay(it) },
                langs.indexOf(piperLang).coerceAtLeast(0),
                withSearch = true,
            ) { position ->
                val lang = langs[position]
                if (lang == piperLang) return@ListSheet
                piperLang = lang
                prefs.edit().putString(ReaderService.KEY_PIPER_LANG, lang).apply()
                piperVoice = defaultIn(langPacks(lang)).voiceName
                selectedPackId = defaultIn(chipPacks()).id
                rebuildPickerChips()
                rebuildSegments()
                update()
            }.show()
        }

        /** Opens the voice list sheet; a newly picked voice introduces itself. */
        private fun pickVoice() {
            val lang = piperLang ?: return
            val voices = VoiceCatalog.piperVoices(lang)
            ListSheet(
                this@VoicesActivity,
                getString(R.string.voice_sheet_title),
                voices,
                voices.indexOf(piperVoice).coerceAtLeast(0),
                withSearch = false,
            ) { position ->
                val voice = voices[position]
                if (voice == piperVoice) return@ListSheet
                piperVoice = voice
                if (chipPacks().none { it.id == selectedPackId }) {
                    selectedPackId = defaultIn(chipPacks()).id
                }
                rebuildPickerChips()
                rebuildSegments()
                update()
                // A newly picked voice introduces itself, unless that would talk
                // over the reader.
                if (ReaderState.snapshot.status == ReaderStatus.READING) {
                    Toast.makeText(
                        this@VoicesActivity,
                        R.string.preview_busy,
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    VoiceCatalog.byId(selectedPackId)?.let { toggleSample(it) }
                }
            }.show()
        }

        fun rebuildSegments() {
            chips.setOnCheckedChangeListener(null)
            chips.removeAllViews()
            for (pack in chipPacks()) {
                val chip = RadioButton(this@VoicesActivity, null, 0, R.style.Segment).apply {
                    id = View.generateViewId()
                    tag = pack.id
                    text = getString(R.string.chip_format, pack.variant, pack.sizeMb)
                    buttonDrawable = null
                    layoutParams = RadioGroup.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                }
                chips.addView(chip)
                if (pack.id == selectedPackId) chips.check(chip.id)
            }
            chips.setOnCheckedChangeListener { group, checkedId ->
                val id = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
                selectedPackId = id
                update()
            }
        }

        /** Switches this card's selection to [pack] (and the Piper language/voice if needed). */
        fun selectPack(pack: VoicePack) {
            if (pack.family != info.family) return
            if (info.family == VoiceFamily.PIPER &&
                (pack.lang != piperLang || pack.voiceName != piperVoice)
            ) {
                piperLang = pack.lang
                piperVoice = pack.voiceName
                rebuildPickerChips()
                rebuildSegments()
            }
            selectedPackId = pack.id
            for (i in 0 until chips.childCount) {
                val chip = chips.getChildAt(i) as RadioButton
                if (chip.tag == pack.id) {
                    chips.check(chip.id)
                    break
                }
            }
            update()
        }

        fun setProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
            progressBar.progress =
                if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100) else 0
            val sizeMb = VoiceCatalog.byId(task?.packId ?: selectedPackId)?.sizeMb ?: 0
            progressLabel.text = when (stage) {
                InstallStage.DOWNLOADING ->
                    getString(R.string.downloading_format, bytesDone / 1_000_000, sizeMb)
                InstallStage.VERIFYING -> getString(R.string.verifying_label)
            }
        }

        /** Repaints this card from installed/active/installing state. Selection is kept. */
        fun update() {
            val activeId = ReaderService.activePackId(this@VoicesActivity)
            val active = VoiceCatalog.byId(activeId)
            val running = (application as ReadlyApp).installTask
            val runningId = if (running != null && !running.isDone) running.packId else null
            val pack = VoiceCatalog.byId(selectedPackId) ?: return
            val installed = isInstalled(pack)
            val inUse = installed && pack.id == activeId
            val langUse = if (installed && !inUse) {
                langAssignment(pack)?.let { ReaderService.langName(it) }
            } else {
                null
            }
            val mainHere = active != null && active.family == info.family && isInstalled(active)
            val langHereName = if (mainHere) {
                null
            } else {
                listOf("fa", "ar").firstNotNullOfOrNull { bucket ->
                    val assigned = ReaderService.packIdForLang(this@VoicesActivity, bucket)
                        ?.let { VoiceCatalog.byId(it) }
                    if (assigned != null && assigned.family == info.family && isInstalled(assigned)) {
                        ReaderService.langName(bucket)
                    } else {
                        null
                    }
                }
            }
            root.setBackgroundResource(
                if (mainHere) R.drawable.card_active_background else R.drawable.card_background,
            )
            when {
                mainHere -> {
                    badge.setText(R.string.in_use_badge_caps)
                    badge.visibility = View.VISIBLE
                }
                langHereName != null -> {
                    badge.text = getString(R.string.in_use_for_format, langHereName)
                    badge.visibility = View.VISIBLE
                }
                else -> badge.visibility = View.GONE
            }
            statusText.text = "${pack.rtfNote} · ${when {
                inUse -> getString(R.string.status_in_use)
                langUse != null -> getString(R.string.in_use_for_format, langUse)
                installed -> getString(R.string.status_downloaded)
                else -> getString(R.string.status_not_downloaded)
            }}"
            sampleButton.setText(
                when {
                    playingPackId != pack.id -> R.string.sample_button
                    sampleLoading -> R.string.sample_loading
                    else -> R.string.stop_sample_button
                },
            )
            when {
                runningId == pack.id -> {
                    mainButton.setText(R.string.cancel_button)
                    mainButton.isEnabled = true
                }
                inUse -> {
                    mainButton.setText(R.string.in_use_badge)
                    mainButton.isEnabled = false
                }
                langUse != null -> {
                    mainButton.text = getString(R.string.in_use_for_format, langUse)
                    mainButton.isEnabled = false
                }
                installed -> {
                    mainButton.setText(R.string.use_button)
                    mainButton.isEnabled = true
                }
                else -> {
                    mainButton.text = getString(R.string.download_main_format, pack.sizeMb)
                    mainButton.isEnabled = runningId == null
                }
            }
            noteText.visibility =
                if (!installed && runningId != null && runningId != pack.id) View.VISIBLE else View.GONE
            val runningHere =
                runningId != null && VoiceCatalog.byId(runningId)?.family == info.family
            progressBar.visibility = if (runningHere) View.VISIBLE else View.GONE
            progressLabel.visibility = if (runningHere) View.VISIBLE else View.GONE
            val error = cardErrors[pack.id]
            if (error != null) {
                errorText.text = error
                errorText.visibility = View.VISIBLE
            } else {
                errorText.visibility = View.GONE
            }
        }

        private fun onMainButton() {
            val pack = VoiceCatalog.byId(selectedPackId) ?: return
            val running = (application as ReadlyApp).installTask
            val runningId = if (running != null && !running.isDone) running.packId else null
            val activeId = ReaderService.activePackId(this@VoicesActivity)
            when {
                runningId == pack.id -> task?.cancel()
                isInstalled(pack) && pack.id != activeId -> usePack(pack)
                !isInstalled(pack) -> startInstall(pack)
            }
        }
    }

    /** Default Piper language: the active pack's, else the most recently picked. */
    private fun defaultPiperLang(): String {
        val active = VoiceCatalog.byId(ReaderService.activePackId(this))
        if (active?.family == VoiceFamily.PIPER && active.lang != null) return active.lang
        val recent = prefs.getString(ReaderService.KEY_PIPER_LANG, null)
        if (recent != null && recent in VoiceCatalog.piperLangs()) return recent
        return VoiceCatalog.piperLangs().first()
    }

    private fun langPacks(lang: String): List<VoicePack> =
        VoiceCatalog.packsFor(VoiceFamily.PIPER).filter { it.lang == lang }

    /**
     * Default selection within [packs]: the active pack if present, else the first
     * downloaded pack, else the first pack.
     */
    private fun defaultIn(packs: List<VoicePack>): VoicePack {
        val activeId = ReaderService.activePackId(this)
        packs.find { it.id == activeId }?.let { return it }
        packs.firstOrNull { isInstalled(it) }?.let { return it }
        return packs.first()
    }

    /** Re-reads installed/active/installing state from disk and repaints every card. */
    private fun refresh() {
        refreshSystemSection()
        for (card in cards) card.update()
        refreshStorage()
    }

    // ------------------------------------------------------------------
    // System voice (the phone's text-to-speech engine)
    // ------------------------------------------------------------------

    private fun addSystemSection() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            val p = dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        card.addView(
            TextView(this, null, 0, R.style.CardTitle).apply {
                text = getString(R.string.system_voice_title)
            },
        )
        card.addView(
            TextView(this, null, 0, R.style.Small).apply {
                text = getString(R.string.system_voice_help)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(8)
                }
            },
        )
        systemSpinner = Spinner(this)
        systemSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long,
            ) {
                // Programmatic rebuilds below re-fire with the stored value; only a
                // real change writes.
                val picked = systemRows.getOrNull(position)
                if (picked != ReadlyTtsService.systemPackId(this@VoicesActivity)) {
                    val edit = prefs.edit()
                    if (picked == null) {
                        edit.remove(ReadlyTtsService.KEY_SYSTEM_PACK)
                    } else {
                        edit.putString(ReadlyTtsService.KEY_SYSTEM_PACK, picked)
                    }
                    edit.apply()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        card.addView(systemSpinner)
        card.addView(
            Button(this, null, 0, R.style.Btn_Ghost).apply {
                text = getString(R.string.system_voice_settings_button)
                setOnClickListener { openTtsSettings() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            },
        )
        cardsBox.addView(card)
        refreshSystemSection()
    }

    /** Rebuilds the system spinner from the installed packs, keeping the setting. */
    private fun refreshSystemSection() {
        val installed = VoiceCatalog.ALL.filter { isInstalled(it) }
        systemRows = listOf(null) + installed.map { it.id }
        val items = listOf(getString(R.string.system_voice_automatic)) +
            installed.map { VoiceCatalog.shortName(it) }
        systemSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            items,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        systemSpinner.setSelection(
            systemRows.indexOf(ReadlyTtsService.systemPackId(this)).coerceAtLeast(0),
        )
    }

    private fun openTtsSettings() {
        PhoneVoiceSettings.open(this, null, getString(R.string.tts_settings_path))
    }

    private fun refreshStorage() {
        val activeId = ReaderService.activePackId(this)
        val installed = VoiceCatalog.ALL.filter { isInstalled(it) }
        storageTotal.text =
            getString(R.string.storage_total_format, installed.sumOf { it.sizeMb })
        storageRows.removeAllViews()
        for (pack in installed) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this, null, 0, R.style.Small).apply {
                    text = "${VoiceCatalog.shortName(pack)} · ${pack.sizeMb} MB"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            val langUse = langAssignment(pack)
            if (pack.id == activeId) {
                row.addView(
                    TextView(this, null, 0, R.style.Small).apply {
                        setText(R.string.storage_in_use)
                    },
                )
            } else if (langUse != null) {
                row.addView(
                    TextView(this, null, 0, R.style.Small).apply {
                        text = getString(
                            R.string.storage_in_use_for_format,
                            ReaderService.langName(langUse),
                        )
                    },
                )
            } else {
                row.addView(
                    Button(this, null, 0, R.style.Btn_TextError).apply {
                        setText(R.string.delete_button)
                        setOnClickListener { confirmDelete(pack) }
                    },
                )
            }
            storageRows.addView(row)
        }
        when {
            installed.isEmpty() -> {
                storageNote.setText(R.string.storage_empty)
                storageNote.visibility = View.VISIBLE
            }
            installed.none { it.id != activeId && langAssignment(it) == null } -> {
                storageNote.setText(R.string.storage_only_active)
                storageNote.visibility = View.VISIBLE
            }
            else -> storageNote.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------
    // Sample clips
    // ------------------------------------------------------------------

    private fun toggleSample(pack: VoicePack) {
        if (playingPackId == pack.id) {
            samples.stop()
            playingPackId = null
            sampleLoading = false
            refresh()
            return
        }
        cardErrors.remove(pack.id)
        val url = pack.sampleUrl
        val reason = if (url != null) {
            samples.playUrl(
                url,
                onPrepared = {
                    sampleLoading = false
                    refresh()
                },
                onDone = {
                    playingPackId = null
                    sampleLoading = false
                    refresh()
                },
                onAsyncError = { asyncReason ->
                    playingPackId = null
                    sampleLoading = false
                    sampleError(pack, asyncReason)
                },
            )
        } else {
            samples.play(
                this,
                pack.sampleRes,
                onDone = {
                    playingPackId = null
                    refresh()
                },
                onAsyncError = { asyncReason ->
                    playingPackId = null
                    sampleError(pack, asyncReason)
                },
            )
        }
        if (reason != null) {
            sampleError(pack, reason)
        } else {
            // play()/playUrl() stop any previous clip first, so this one is now the only one.
            playingPackId = pack.id
            sampleLoading = url != null
        }
        refresh()
    }

    private fun sampleError(pack: VoicePack, reason: String) {
        val message = getString(R.string.sample_error_format, reason)
        Log.e("Readly", "$message (${pack.id})")
        cardErrors[pack.id] = message
        cardForPack(pack.id).selectPack(pack)
        refresh()
    }

    // ------------------------------------------------------------------
    // Install (shares the single app-wide InstallTask with the setup screen)
    // ------------------------------------------------------------------

    private fun startInstall(pack: VoicePack) {
        val app = application as ReadlyApp
        if (app.installTask?.isDone == false) return
        cardErrors.remove(pack.id)
        val store = VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener)
        val newTask = InstallTask(store) { finished ->
            if (app.installTask === finished) app.installTask = null
        }
        task = newTask
        newTask.listener = this
        app.installTask = newTask
        refresh()
        cardForPack(pack.id).setProgress(InstallStage.DOWNLOADING, 0, pack.sizeBytes)
        app.ioExecutor.execute { newTask.run() }
    }

    override fun onProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        updateProgress(stage, bytesDone, bytesTotal)
    }

    private fun updateProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        val id = task?.packId ?: return
        cardForPack(id).setProgress(stage, bytesDone, bytesTotal)
    }

    override fun onDone() {
        val id = task?.packId
        task = null
        if (id != null) {
            Log.i("Readly", "Voice install complete ($id)")
            cardErrors.remove(id)
            VoiceCatalog.byId(id)?.let { cardForPack(it.id).selectPack(it) }
        }
        refresh()
        if (id != null && !isFinishing && !isDestroyed) {
            val pack = VoiceCatalog.byId(id) ?: return
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.use_now_format, pack.displayName))
                .setPositiveButton(android.R.string.yes) { _, _ -> usePack(pack) }
                .setNegativeButton(android.R.string.no, null)
                .show()
        }
    }

    override fun onCancelled() {
        Log.i("Readly", "Voice install cancelled by user")
        task = null
        refresh()
    }

    override fun onError(e: Exception) {
        val id = task?.packId
        task = null
        val message = getString(
            R.string.error_format,
            e.javaClass.name,
            e.message ?: "(no details)",
        )
        Log.e("Readly", "Voice download failed", e)
        if (id != null) {
            cardErrors[id] = message
            VoiceCatalog.byId(id)?.let { cardForPack(it.id).selectPack(it) }
        }
        refresh()
    }

    // ------------------------------------------------------------------
    // Switch + delete
    // ------------------------------------------------------------------

    /**
     * Language bucket this pack is assigned to (`"fa"`/`"ar"`), or null when it
     * is the main voice or not assigned. Main takes precedence.
     */
    private fun langAssignment(pack: VoicePack): String? {
        if (pack.id == ReaderService.activePackId(this)) return null
        val bucket = ReaderService.bucketForPack(pack) ?: return null
        return if (ReaderService.packIdForLang(this, bucket) == pack.id) bucket else null
    }

    private fun usePack(pack: VoicePack) {
        val bucket = ReaderService.bucketForPack(pack)
        if (bucket != null) {
            ReaderService.setPackIdForLang(this, bucket, pack.id)
            Toast.makeText(
                this,
                getString(
                    R.string.use_lang_voice_toast_format,
                    ReaderService.langName(bucket),
                    pack.displayName,
                ),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            prefs.edit().putString(ReaderService.KEY_ACTIVE_PACK, pack.id).apply()
            ReaderService.preload(this)
            Toast.makeText(
                this,
                getString(R.string.use_voice_toast_format, pack.displayName),
                Toast.LENGTH_LONG,
            ).show()
        }
        refresh()
    }

    private fun confirmDelete(pack: VoicePack) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_pack_confirm_format, pack.displayName, pack.sizeMb))
            .setPositiveButton(R.string.delete_button) { _, _ -> deletePack(pack) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePack(pack: VoicePack) {
        cardErrors.remove(pack.id)
        // Safe on Linux: files a still-loaded engine has open or mmap'd stay valid
        // until it closes them. The preload on switch closes the old engine anyway,
        // and the in-use pack has no Delete button at all.
        try {
            VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener).delete()
            if (File(voicesDir(), pack.topDir).exists()) {
                throw IOException("files could not be removed")
            }
        } catch (e: Exception) {
            val message = getString(
                R.string.delete_error_format,
                pack.displayName,
                e.javaClass.name,
                e.message ?: "(no details)",
            )
            Log.e("Readly", message, e)
            cardErrors[pack.id] = message
            cardForPack(pack.id).selectPack(pack)
        }
        refresh()
    }
}
