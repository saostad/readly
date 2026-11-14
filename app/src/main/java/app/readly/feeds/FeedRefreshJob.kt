package app.readly.feeds

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import app.readly.ReadlyApp
import app.readly.playback.ReaderService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Android owns timing and restores this persisted job after a reboot. No wake locks or polling. */
data class RefreshSchedule(val enabled: Boolean = false, val minutes: Int = 60, val wifiOnly: Boolean = true, val batteryNotLow: Boolean = true, val charging: Boolean = false) {
    fun validate() { require(minutes in 15..1440) { "Background refresh must be between 15 and 1440 minutes." } }
}
object FeedRefreshScheduler {
    private const val JOB_ID = 0x524646 // Stable Android job identity.
    const val STATUS = "scheduledRefreshStatus"
    fun read(context: Context): RefreshSchedule {
        val p = context.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        return RefreshSchedule(p.getBoolean("refreshEnabled", false), p.getInt("refreshInterval", 60), p.getBoolean("refreshWifi", true), p.getBoolean("refreshBattery", true), p.getBoolean("refreshCharging", false)).also { it.validate() }
    }
    fun save(context: Context, settings: RefreshSchedule) {
        settings.validate()
        apply(context, settings)
        context.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("refreshEnabled", settings.enabled).putInt("refreshInterval", settings.minutes)
            .putBoolean("refreshWifi", settings.wifiOnly).putBoolean("refreshBattery", settings.batteryNotLow)
            .putBoolean("refreshCharging", settings.charging).apply()
    }
    fun ensure(context: Context) = apply(context, read(context))
    private fun apply(context: Context, s: RefreshSchedule) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!s.enabled) { scheduler.cancel(JOB_ID); return }
        val signature = "${s.minutes}/${s.wifiOnly}/${s.batteryNotLow}/${s.charging}"
        if (scheduler.getPendingJob(JOB_ID)?.extras?.getString("settings") == signature) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, FeedRefreshJob::class.java))
            .setPeriodic(s.minutes * 60_000L).setPersisted(true)
            .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(s.batteryNotLow).setRequiresCharging(s.charging)
            .setExtras(PersistableBundle().apply { putString("settings", signature) }).build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android couldn't schedule feed refresh. Try saving the schedule again." }
    }
}
class FeedRefreshJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var stopped = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        val cancelled = AtomicBoolean(false)
        stopped = cancelled
        status("Scheduled feed refresh is running…")
        task = executor.submit {
            try {
                val feeds = (application as ReadlyApp).feeds
                val failed = mutableListOf<String>()
                val sites = feeds.sites()
                if (sites.isEmpty()) { status("No websites configured. Add a website in Feeds settings to enable scheduled updates."); return@submit }
                for (site in sites) {
                    if (cancelled.get()) return@submit
                    try {
                        if (feeds.headlines(site, force = true).stale) failed += site.label()
                    } catch (error: Exception) {
                        Log.e("Readly", "Scheduled feed refresh failed site=${site.id}", error)
                        feeds.setRefreshError(site.id, "Scheduled refresh failed. Open the feed and tap Refresh to retry.")
                        failed += site.label()
                    }
                }
                if (!cancelled.get()) {
                    val whenText = feeds.updatedTime(System.currentTimeMillis())
                    status(if (failed.isEmpty()) "Scheduled refresh completed: $whenText" else "Scheduled refresh failed for ${failed.joinToString(", ")}: $whenText. Previous headlines were kept where available.")
                    feeds.notifyItemsChanged()
                }
            } catch (error: Exception) {
                Log.e("Readly", "Scheduled refresh couldn't load the feed list", error)
                if (!cancelled.get()) status("Scheduled refresh failed. Open Feeds settings and check your websites.")
            } finally {
                if (!cancelled.get()) jobFinished(params, false)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true)
        task?.cancel(true)
        Log.w("Readly", "fallback=true attempted=scheduled-feed-refresh reason=android-stopped-job taken=next-periodic-run")
        status("Android stopped the scheduled refresh. If background refresh is enabled, the next run will follow your network and battery settings.")
        return false
    }
    private fun status(message: String) {
        getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE).edit().putString(FeedRefreshScheduler.STATUS, message).apply()
        (application as ReadlyApp).feeds.notifyItemsChanged()
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
