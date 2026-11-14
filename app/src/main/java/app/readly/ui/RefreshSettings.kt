package app.readly.ui

import android.app.Activity
import android.text.InputType
import android.util.Log
import android.widget.*
import app.readly.R
import app.readly.feeds.FeedRefreshScheduler
import app.readly.feeds.RefreshSchedule

object RefreshSettings {
    fun fill(activity: Activity, box: LinearLayout) {
        val current = FeedRefreshScheduler.read(activity)
        fun label(value: String): TextView = TextView(activity).apply { text = value; setTextColor(activity.getColor(R.color.text2)); box.addView(this) }
        label("Scheduled feed refresh")
        fun toggle(value: String, checked: Boolean) = Switch(activity).apply { text = value; isChecked = checked; box.addView(this) }
        val enabled = toggle("Refresh feeds in the background", current.enabled)
        label("Refresh interval in minutes (15–1440)")
        val minutes = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(current.minutes.toString()); box.addView(this) }
        val wifi = toggle("Wi-Fi / unmetered connections only", current.wifiOnly)
        val battery = toggle("Only when battery is not low", current.batteryNotLow)
        val charging = toggle("Only while charging", current.charging)
        label("Android chooses the exact time and may delay refresh to save battery. Downloads include headlines, not full articles or audio.")
        val status = label(activity.getSharedPreferences(app.readly.playback.ReaderService.PREFS_NAME, Activity.MODE_PRIVATE).getString(FeedRefreshScheduler.STATUS, "No scheduled refresh has run yet.").orEmpty())
        box.addView(Button(activity, null, 0, R.style.Btn_Text).apply {
            text = "Save refresh schedule"
            setOnClickListener {
                try {
                    val interval = minutes.text.toString().toIntOrNull()
                    require(interval != null) { "Enter a whole number of minutes." }
                    FeedRefreshScheduler.save(activity, RefreshSchedule(enabled.isChecked, interval, wifi.isChecked, battery.isChecked, charging.isChecked))
                    status.setTextColor(activity.getColor(R.color.text2))
                    status.text = if (enabled.isChecked) "Schedule saved. Android will refresh when these conditions are met." else "Background refresh is off. You can still refresh manually."
                } catch (error: Exception) {
                    Log.e("Readly", "Couldn't save the background feed refresh schedule", error)
                    status.setTextColor(activity.getColor(R.color.error))
                    status.text = if (error is IllegalArgumentException) error.message else "Couldn't save the schedule. Try again."
                }
            }
        })
    }
}
