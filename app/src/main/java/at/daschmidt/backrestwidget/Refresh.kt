package at.daschmidt.backrestwidget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object Refresher {
    private const val PERIODIC = "refresh_periodic"
    private const val ONE_SHOT = "refresh_now"
    private const val CHANNEL = "failures"
    private const val NOTIFY_MAX_AGE_MS = 24 * 3600_000L

    /** Fetch the dashboard, cache it, notify about new failures and redraw widgets. Blocking. */
    fun refresh(context: Context): Result<Snapshot> {
        val settings = Settings(context)
        val now = System.currentTimeMillis()
        val result = runCatching {
            Snapshot.fromDashboard(BackrestClient(settings).summaryDashboard(), now)
        }.recoverCatching { e ->
            throw if (e is SocketTimeoutException) BackrestException("Zeitüberschreitung") else e
        }
        settings.lastAttemptMs = now
        result.onSuccess {
            settings.snapshot = it
            settings.lastError = null
            notifyFailures(context, settings, it)
        }.onFailure {
            settings.lastError = it.message ?: it.javaClass.simpleName
        }
        BackrestWidget.updateAll(context)
        return result
    }

    fun schedule(context: Context) {
        val settings = Settings(context)
        val wm = WorkManager.getInstance(context)
        if (!settings.configured) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(settings.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Refresh as soon as possible, e.g. from the widget's refresh button. */
    fun refreshSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL, context.getString(R.string.channel_failures), NotificationManager.IMPORTANCE_DEFAULT
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyFailures(context: Context, settings: Settings, snapshot: Snapshot) {
        if (!settings.notifyOnFailure) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        for (plan in snapshot.plans) {
            val last = plan.lastFinished ?: continue
            if (last.status != OpStatus.ERROR) continue
            if (last.startMs <= settings.notifiedFailure(plan.id)) continue
            settings.setNotifiedFailure(plan.id, last.startMs)
            if (System.currentTimeMillis() - last.startMs > NOTIFY_MAX_AGE_MS) continue
            val notification = android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notify_failed_title, plan.id))
                .setContentText(context.getString(R.string.notify_failed_text, Format.dateTime(context, last.startMs)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(plan.id.hashCode(), notification)
        }
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Refresher.refresh(applicationContext)
        // Failures are shown in the widget; the periodic schedule retries anyway.
        Result.success()
    }
}
