package app.readly.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.readly.R
import app.readly.SettingsActivity
import app.readly.VoicesActivity
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.playback.ReaderService
import java.io.File
import kotlin.math.abs

/**
 * Compact quick-audio bottom sheet used from Now Playing: active voice pack +
 * Change pack link, speaker preview chips, speed slider + presets, and a link
 * to [SettingsActivity] for everything else.
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
        dialog.findViewById<Button>(R.id.sheetAllSettings).setOnClickListener {
            dialog.dismiss()
            SettingsActivity.open(activity)
        }
        fillPersianLine(dialog, pack)
        fillSpeakers(dialog, pack)
        fillSpeed(dialog)

        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        Sheets.applyFullWidth(dialog)
    }

    private fun fillPersianLine(dialog: Dialog, mainPack: VoicePack) {
        val line = dialog.findViewById<TextView>(R.id.sheetPersianLine)
        val fa = ReaderService.packIdForLang(activity, "fa")?.let { VoiceCatalog.byId(it) }
        if (fa != null && fa.id != mainPack.id) {
            line.text = activity.getString(R.string.persian_pack_format, VoiceCatalog.shortName(fa))
            line.visibility = View.VISIBLE
        } else {
            line.visibility = View.GONE
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
        val selected = ReaderService.speakerIdFor(activity, pack.id).coerceIn(pack.speakers.indices)
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

    private fun playPreview(pack: VoicePack, speakerId: Int, name: String) {
        val store = VoiceModelStore(pack, File(activity.noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
        if (!store.isInstalled()) return
        ReaderService.preview(activity, pack.id, speakerId, ReaderService.previewText(activity, pack, name))
    }

    private fun fillSpeed(dialog: Dialog) {
        val value = dialog.findViewById<TextView>(R.id.sheetSpeedValue)
        val seek = dialog.findViewById<SeekBar>(R.id.sheetSpeedSeek)
        val presetRow = dialog.findViewById<LinearLayout>(R.id.sheetSpeedPresets)
        presetRow.removeAllViews()
        val presetBtns = mutableListOf<Pair<Float, Button>>()
        val gap = SettingsWidgets.dp(activity, 6)

        fun paint(speed: Float) {
            value.text = SettingsSummaries.formatSpeed(speed)
            for ((preset, btn) in presetBtns) {
                val active = abs(preset - speed) < 0.05f
                btn.isSelected = active
                btn.setBackgroundResource(if (active) R.drawable.chip_selected_background else R.drawable.chip_background)
                btn.setTextColor(activity.getColor(if (active) R.color.accent else R.color.text2))
            }
        }

        for ((i, preset) in SettingsSummaries.SPEED_PRESETS.withIndex()) {
            val btn = Button(activity, null, 0, R.style.Segment).apply {
                text = SettingsSummaries.formatSpeed(preset)
                setOnClickListener {
                    prefs.edit().putFloat(ReaderService.KEY_SPEED, preset).apply()
                    seek.progress = SettingsSummaries.speedToProgress(preset)
                    paint(preset)
                }
            }
            presetBtns += preset to btn
            presetRow.addView(
                btn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gap
                },
            )
        }

        val speed = prefs.getFloat(ReaderService.KEY_SPEED, 1.0f)
        seek.progress = SettingsSummaries.speedToProgress(speed)
        paint(speed)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val newSpeed = SettingsSummaries.progressToSpeed(progress)
                paint(newSpeed)
                if (fromUser) prefs.edit().putFloat(ReaderService.KEY_SPEED, newSpeed).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }
}
