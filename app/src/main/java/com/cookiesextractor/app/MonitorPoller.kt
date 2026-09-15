package com.cookiesextractor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * One fetch-diff-notify cycle of the monitor (COK-26) plus its JobScheduler wiring. The
 * gateway document is fetched with the user's bearer token, [MonitorAlerts] decides what
 * changed, and transitions surface as native notifications. Everything the job needs is
 * read from [MonitorRepository] at execution time, so config changes require only a
 * reschedule.
 */
object MonitorPoller {

    const val CHANNEL_ID = "monitor-alerts"

    // Check-now (activity thread) and the scheduled job can overlap; the whole
    // fetch-diff-persist cycle is serialized or last-writer-wins loses transitions.
    private val cycleLock = Any()
    const val EXTRA_NAVIGATE_URL = "monitor_navigate_url"
    private const val JOB_ID = 20260926

    /** Runs one cycle on the calling thread (the job thread or the Check-now worker). */
    fun runOnce(context: Context): String {
        val repo = MonitorRepository(context)
        return runCycle(context, repo, repo.gatewayUrl, repo.token)
    }

    /**
     * Same cycle with explicit credentials: Check-now probes what is on screen WITHOUT
     * persisting it, so Cancel still cancels edits and the scheduled job keeps its own
     * configuration until Save.
     */
    fun runOnce(context: Context, gatewayUrl: String?, token: String?): String =
        runCycle(context, MonitorRepository(context), gatewayUrl?.takeIf { it.isNotBlank() }, token?.takeIf { it.isNotBlank() })

    private fun runCycle(context: Context, repo: MonitorRepository, gatewayUrl: String?, token: String?): String = synchronized(cycleLock) {
        fun status(message: String): String {
            repo.lastResult = message
            return message
        }
        val url = gatewayUrl
            ?: return status(context.getString(R.string.monitor_status_unconfigured))
        val tok = token
            ?: return status(context.getString(R.string.monitor_status_unconfigured))
        val body = try {
            fetch(url, tok)
        } catch (e: Exception) {
            return status(context.getString(R.string.monitor_status_fetch_failed, e.javaClass.simpleName))
        } ?: return status(context.getString(R.string.monitor_status_fetch_failed, "empty"))
        val alerts = MonitorAlerts.parseState(body)
            ?: return status(context.getString(R.string.monitor_status_bad_document))

        val seen = repo.seenStates().toMutableMap()
        val canNotify = context.getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() == true
        var notified = 0
        var cleared = 0
        for (decision in MonitorAlerts.diff(alerts, seen)) {
            when (decision) {
                is MonitorAlerts.Decision.Notify -> {
                    notifyExpired(context, decision.alert)
                    // if notifications are suppressed (permission denied), do NOT record
                    // the transition: the alert must re-fire once the user grants it
                    if (canNotify) {
                        seen[decision.alert.service] = decision.alert.state
                        notified++
                    }
                }
                is MonitorAlerts.Decision.Clear -> {
                    clearNotification(context, decision.service)
                    seen[decision.service] = MonitorAlerts.STATE_OK
                    cleared++
                }
                MonitorAlerts.Decision.None -> {}
            }
        }
        repo.setSeenStates(seen)
        return status(context.getString(R.string.monitor_status_checked_fmt, alerts.size, notified, cleared))
    }

    /** (Re)schedules the periodic job for the configured period; call on config changes. */
    fun schedule(context: Context, periodMinutes: Int) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val periodMs = periodMinutes.coerceIn(
            MonitorRepository.MIN_PERIOD_MINUTES,
            MonitorRepository.MAX_PERIOD_MINUTES,
        ) * 60_000L
        val job = JobInfo.Builder(
            JOB_ID,
            android.content.ComponentName(context, MonitorJobService::class.java),
        )
            .setPeriodic(periodMs)
            .setPersisted(true)
            // no point waking the app for a fetch that can only fail offline
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    private fun fetch(url: String, token: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            // a redirect would re-send the bearer token to a different host: fail instead
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (connection.responseCode !in 200..299) {
                // drain the error body so the connection returns to the pool cleanly
                runCatching { connection.errorStream?.close() }
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }.ifBlank { null }
        } finally {
            connection.disconnect()
        }
    }

    private fun notifyExpired(context: Context, alert: MonitorAlerts.Alert) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.monitor_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        // requestCode must differ per service: Intents equal for filterEquals share one
        // PendingIntent and FLAG_UPDATE_CURRENT would retarget every earlier notification
        val tap = PendingIntent.getActivity(
            context,
            alert.service.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_NAVIGATE_URL, alert.url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.monitor_notification_title))
            .setContentText(context.getString(R.string.monitor_notification_text, alert.service))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        // tagged per service so a recovery cancels exactly its own notification
        manager.notify(alert.service, NOTIFICATION_BASE_ID, notification)
    }

    private fun clearNotification(context: Context, service: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(service, NOTIFICATION_BASE_ID)
    }

    private const val NOTIFICATION_BASE_ID = 26_000
}

/**
 * The JobScheduler entry point: delegates the whole cycle to [MonitorPoller] on the job
 * thread. Configuration is read from [MonitorRepository] at run time; a finished job
 * reports success and lets the scheduler re-fire it after the configured period.
 */
class MonitorJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!MonitorRepository(this).enabled) {
            // prefs/job divergence (e.g. process death between disable and cancel): stop
            // the zombie instead of waking the app every period to read one pref
            MonitorPoller.cancel(this)
            return false
        }
        Thread {
            val status = MonitorPoller.runOnce(this)
            Log.i(TAG, "monitor cycle: $status")
            jobFinished(params, false)
        }.start()
        return true // work continues on the thread above
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private companion object {
        const val TAG = "CookieExtractor"
    }
}
