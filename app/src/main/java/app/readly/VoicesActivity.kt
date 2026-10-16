package app.readly

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
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
import java.io.File
import java.io.IOException

/**
 * One card per [VoiceFamily]: pick a variant chip (and a Piper voice), play its
 * sample clip, download it or switch to it. A storage list at the bottom shows
 * what's installed. Downloads reuse the single app-wide [InstallTask], so only
 * one install runs at a time no matter which screen started it.
 */
class VoicesActivity : Activity(), InstallTask.Listener {
    private lateinit var cardsBox: LinearLayout
    private lateinit var prefs: SharedPreferences
    private val cards = mutableListOf<Card>()
    private lateinit var storageTotal: TextView
    private lateinit var storageRows: LinearLayout
    private lateinit var storageNote: TextView
    private val samples = SamplePlayer()
    private var playingPackId: String? = null
    private var task: InstallTask? = null

    /** Download/sample errors by pack id; each card shows its selected pack's error. */
    private val cardErrors = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voices)
        cardsBox = findViewById(R.id.cardsContainer)
        prefs = getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE)
        for (info in VoiceCatalog.FAMILIES) {
            val card = Card(info)
            cards.add(card)
            cardsBox.addView(card.root)
        }
        val storageTitle = TextView(this).apply {
            text = getString(R.string.storage_title)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        storageTotal = TextView(this)
        storageRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        storageNote = TextView(this)
        cardsBox.addView(storageTitle)
        cardsBox.addView(storageTotal)
        cardsBox.addView(storageRows)
        cardsBox.addView(storageNote)
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
        super.onStop()
    }

    override fun onDestroy() {
        samples.release()
        task?.let { if (it.listener === this) it.listener = null }
        super.onDestroy()
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
        /** Default: the active pack if it's in this family, else the first downloaded pack, else the first pack. */
        var selectedPackId: String =
            defaultIn(VoiceCatalog.packsFor(info.family)).id
        var piperVoice: String? = VoiceCatalog.byId(selectedPackId)?.voiceName

        val root: LinearLayout = LinearLayout(this@VoicesActivity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.voice_card_background)
            val p = dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        val badge: TextView = TextView(this@VoicesActivity).apply {
            setText(R.string.in_use_badge)
            visibility = View.GONE
        }
        val chips: RadioGroup = RadioGroup(this@VoicesActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        var piperSpinner: Spinner? = null
        val statusText = TextView(this@VoicesActivity)
        val sampleButton = Button(this@VoicesActivity).apply {
            setText(R.string.sample_button)
            setOnClickListener {
                VoiceCatalog.byId(selectedPackId)?.let { toggleSample(it) }
            }
        }
        val mainButton = Button(this@VoicesActivity).apply {
            setOnClickListener { onMainButton() }
        }
        val noteText = TextView(this@VoicesActivity).apply {
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
            max = 100
            visibility = View.GONE
        }
        val progressLabel = TextView(this@VoicesActivity).apply {
            visibility = View.GONE
        }
        val errorText = TextView(this@VoicesActivity).apply {
            setTextColor(getColor(android.R.color.holo_red_dark))
            visibility = View.GONE
        }

        init {
            val titleRow = LinearLayout(this@VoicesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val title = TextView(this@VoicesActivity).apply {
                text = info.title
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleRow.addView(title)
            titleRow.addView(badge)
            root.addView(titleRow)
            root.addView(
                TextView(this@VoicesActivity).apply { text = info.tagline },
            )
            if (info.family == VoiceFamily.PIPER) {
                val row = LinearLayout(this@VoicesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                row.addView(
                    TextView(this@VoicesActivity).apply {
                        setText(R.string.voice_label)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { rightMargin = dp(8) }
                    },
                )
                val spinner = Spinner(this@VoicesActivity)
                val adapter = ArrayAdapter(
                    this@VoicesActivity,
                    android.R.layout.simple_spinner_item,
                    VoiceCatalog.piperVoices(),
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                spinner.setSelection(
                    VoiceCatalog.piperVoices().indexOf(piperVoice).coerceAtLeast(0),
                )
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val voice = VoiceCatalog.piperVoices()[position]
                        if (voice == piperVoice) return
                        piperVoice = voice
                        if (chipPacks().none { it.id == selectedPackId }) {
                            selectedPackId = defaultIn(chipPacks()).id
                        }
                        rebuildChips()
                        update()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>) = Unit
                }
                piperSpinner = spinner
                row.addView(spinner)
                root.addView(row)
            }
            root.addView(
                HorizontalScrollView(this@VoicesActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(8)
                        bottomMargin = dp(4)
                    }
                },
            )
            root.addView(statusText)
            val actionRow = LinearLayout(this@VoicesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            actionRow.addView(sampleButton)
            actionRow.addView(
                View(this@VoicesActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                },
            )
            actionRow.addView(mainButton)
            root.addView(actionRow)
            root.addView(noteText)
            root.addView(progressBar)
            root.addView(progressLabel)
            root.addView(errorText)
            rebuildChips()
        }

        /** Packs behind this card's chips: the family, or the selected Piper voice. */
        fun chipPacks(): List<VoicePack> {
            val packs = VoiceCatalog.packsFor(info.family)
            return if (info.family == VoiceFamily.PIPER) {
                packs.filter { it.voiceName == piperVoice }
            } else {
                packs
            }
        }

        fun rebuildChips() {
            chips.setOnCheckedChangeListener(null)
            chips.removeAllViews()
            for (pack in chipPacks()) {
                val chip = RadioButton(this@VoicesActivity).apply {
                    id = View.generateViewId()
                    tag = pack.id
                    text = getString(R.string.chip_format, pack.variant, pack.sizeMb)
                    buttonDrawable = null
                    setBackgroundResource(R.drawable.chip_background)
                    setTextColor(getColorStateList(R.color.chip_text))
                    val h = dp(12)
                    val v = dp(8)
                    setPadding(h, v, h, v)
                    layoutParams = RadioGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        rightMargin = dp(8)
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    }
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

        /** Switches this card's selection to [pack] (and the Piper voice if needed). */
        fun selectPack(pack: VoicePack) {
            if (pack.family != info.family) return
            if (info.family == VoiceFamily.PIPER && pack.voiceName != piperVoice) {
                piperVoice = pack.voiceName
                piperSpinner?.setSelection(
                    VoiceCatalog.piperVoices().indexOf(pack.voiceName).coerceAtLeast(0),
                )
                rebuildChips()
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
            badge.visibility =
                if (active != null && active.family == info.family && isInstalled(active)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            statusText.text = "${pack.rtfNote} · ${getString(when {
                inUse -> R.string.status_in_use
                installed -> R.string.status_downloaded
                else -> R.string.status_not_downloaded
            })}"
            sampleButton.setText(
                if (playingPackId == pack.id) R.string.stop_sample_button
                else R.string.sample_button,
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
                installed -> {
                    mainButton.setText(R.string.use_button)
                    mainButton.isEnabled = true
                }
                else -> {
                    mainButton.text = getString(R.string.download_variant_format, pack.sizeMb)
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
        for (card in cards) card.update()
        refreshStorage()
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
            }
            row.addView(
                TextView(this).apply {
                    text = "${VoiceCatalog.shortName(pack)} · ${pack.sizeMb} MB"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            if (pack.id == activeId) {
                row.addView(
                    TextView(this).apply { setText(R.string.storage_in_use) },
                )
            } else {
                row.addView(
                    Button(this).apply {
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
            installed.size == 1 && installed[0].id == activeId -> {
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
            refresh()
            return
        }
        cardErrors.remove(pack.id)
        val reason = samples.play(
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
        if (reason != null) {
            sampleError(pack, reason)
        } else {
            // play() stops any previous clip first, so this one is now the only one.
            playingPackId = pack.id
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

    private fun usePack(pack: VoicePack) {
        prefs.edit().putString(ReaderService.KEY_ACTIVE_PACK, pack.id).apply()
        ReaderService.preload(this)
        Toast.makeText(
            this,
            getString(R.string.use_voice_toast_format, pack.displayName),
            Toast.LENGTH_LONG,
        ).show()
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
