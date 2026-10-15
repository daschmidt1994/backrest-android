package at.daschmidt.backrestwidget

import android.content.Context
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
    private const val WHILE_RUNNING = "refresh_running"
    private const val RUNNING_INTERVAL_S = 60L

    /** Fetch the dashboard, cache it, notify about new problems and redraw widgets. Blocking. */
    fun refresh(context: Context): Result<Snapshot> {
        val settings = Settings(context)
        val now = System.currentTimeMillis()
        var tasks = emptyList<FailedTask>()
        val result = runCatching {
            val client = BackrestClient(settings)
            val dashboard = client.summaryDashboard()
            // Progress is optional: older servers or hiccups must not break the overview.
            val operations = runCatching { client.recentOperations() }.getOrNull()
            // Multihost: client instances of this server, e.g. VPS backed up via Backrest.
            val remotes = runCatching { client.peerStates().mapNotNull { RemoteState.from(it) } }
                .getOrElse { if (it is AuthException) throw it else settings.snapshot?.remotes.orEmpty() }
            val remoteOperations = remotes.flatMap { remote -> remote.planIds.map { remote.instance to it } }
                .mapNotNull { (instance, planId) ->
                    runCatching { "$instance/$planId" to client.planOperations(instance, planId) }.getOrNull()
                }.toMap()
            tasks = FailedTask.fromOperations(operations, *remoteOperations.values.toTypedArray())
            Snapshot.fromDashboard(dashboard, now, operations, remotes, remoteOperations)
        }.recoverCatching { e ->
            throw if (e is SocketTimeoutException) BackrestException("Zeitüberschreitung") else e
        }
        settings.lastAttemptMs = now
        result.onSuccess {
            settings.snapshot = it
            settings.lastError = null
            Notifier.afterSuccess(context, settings, it, tasks)
            if (it.running.isNotEmpty()) followUpWhileRunning(context)
        }.onFailure {
            val message = it.message ?: it.javaClass.simpleName
            settings.lastError = message
            Notifier.afterFailure(context, settings, message)
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

    /** Keep the widget's progress roughly current while something runs; stops once idle. */
    private fun followUpWhileRunning(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(RUNNING_INTERVAL_S, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WHILE_RUNNING, ExistingWorkPolicy.REPLACE, request)
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Refresher.refresh(applicationContext)
        // Failures are shown in the widget; the periodic schedule retries anyway.
        Result.success()
    }
}
