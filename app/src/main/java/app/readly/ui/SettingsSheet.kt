package app.readly.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.readly.PhoneVoiceSettings
import app.readly.R
import app.readly.VoicesActivity
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.playback.ReaderService
import java.io.File
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
        val dialog = Dialog(activity)
        dialog.asBottomSheet() ?: return
        dialog.setContentView(R.layout.dialog_settings_sheet)

        dialog.findViewById<TextView>(R.id.sheetPackName).text = VoiceCatalog.shortName(pack)
        dialog.findViewById<Button>(R.id.sheetChangePack).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, VoicesActivity::class.java))
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
        fillPhoneRow(dialog)
        fillPersianLine(dialog)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
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

    private fun fillSwitch(dialog: Dialog, switchId: Int, key: String) {
        val switch = dialog.findViewById<Switch>(switchId)
        switch.isChecked = prefs.getBoolean(key, true)
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
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
}
