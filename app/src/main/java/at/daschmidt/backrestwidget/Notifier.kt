package at.daschmidt.backrestwidget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Kinds of problems the app can notify about. Each has its own switch in the
 * settings and its own Android channel, so it can also be muted system-side.
 */
enum class Alert(val channelId: String, val channelName: Int, val settingLabel: Int, val defaultOn: Boolean) {
    BACKUP_FAILED("failures", R.string.channel_failures, R.string.alert_backup_failed, true),
    BACKUP_WARNING("warnings", R.string.channel_warnings, R.string.alert_backup_warning, false),
    TASK_FAILED("tasks", R.string.channel_tasks, R.string.alert_task_failed, true),
    UNREACHABLE("connection", R.string.channel_connection, R.string.alert_unreachable, true),
    REMOTE_DISCONNECTED("remotes", R.string.channel_remotes, R.string.alert_remote_disconnected, true),
}

/** Decisions about what to notify, free of Android APIs so they can be unit tested. */
object AlertRules {
    /** Older events are not notified, e.g. right after installing or re-enabling a switch. */
    const val MAX_AGE_MS = 24 * 3600_000L

    /** A remote is reported once it has been silent this long (reconnects after a restart are quick). */
    const val REMOTE_SILENCE_MS = 15 * 60_000L

    /** The newest finished backup of [plan] if it has [status] and was not notified yet. */
    fun newBackup(plan: PlanState, status: OpStatus, notifiedStartMs: Long, nowMs: Long): BackupEntry? {
        val last = plan.lastFinished ?: return null
        if (last.status != status || last.startMs <= notifiedStartMs) return null
        return last.takeIf { nowMs - it.startMs <= MAX_AGE_MS }
    }

    /** Failed tasks newer than [notifiedId], skipping old ones. */
    fun newTasks(tasks: List<FailedTask>, notifiedId: Long, nowMs: Long): List<FailedTask> =
        tasks.filter { it.id > notifiedId && nowMs - it.startMs <= MAX_AGE_MS }

    /** Whether this run of failed refreshes should be notified now. */
    fun unreachable(failedRefreshes: Int, threshold: Int, alreadyNotified: Boolean): Boolean =
        !alreadyNotified && failedRefreshes >= threshold

    /** Remotes that count as disconnected: not connected and without a recent heartbeat. */
    fun disconnected(remotes: List<RemoteState>, nowMs: Long): List<RemoteState> = remotes.filter {
        !it.connected && (it.lastHeartbeatMs == null || nowMs - it.lastHeartbeatMs > REMOTE_SILENCE_MS)
    }
}

object Notifier {

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(Alert.entries.map {
            NotificationChannel(it.channelId, context.getString(it.channelName), NotificationManager.IMPORTANCE_DEFAULT)
        })
    }

    /** After a successful refresh: backups, other tasks and remote instances. */
    fun afterSuccess(context: Context, settings: Settings, snapshot: Snapshot, tasks: List<FailedTask>) {
        val now = System.currentTimeMillis()
        settings.failedRefreshes = 0
        if (settings.unreachableNotified) {
            settings.unreachableNotified = false
            cancel(context, Alert.UNREACHABLE, 0)
        }

        for (plan in snapshot.plans) {
            AlertRules.newBackup(plan, OpStatus.ERROR, settings.notifiedFailure(plan.key), now)?.let {
                settings.setNotifiedFailure(plan.key, it.startMs)
                notify(context, settings, Alert.BACKUP_FAILED, plan.key.hashCode(),
                    context.getString(R.string.notify_failed_title, planLabel(plan)),
                    context.getString(R.string.notify_failed_text, Format.dateTime(context, it.startMs)))
            }
            AlertRules.newBackup(plan, OpStatus.WARNING, settings.notifiedWarning(plan.key), now)?.let {
                settings.setNotifiedWarning(plan.key, it.startMs)
                notify(context, settings, Alert.BACKUP_WARNING, plan.key.hashCode(),
                    context.getString(R.string.notify_warning_title, planLabel(plan)),
                    context.getString(R.string.notify_failed_text, Format.dateTime(context, it.startMs)))
            }
        }

        val newTasks = AlertRules.newTasks(tasks, settings.notifiedTaskId, now)
        for (task in newTasks) {
            val where = if (task.instance.isNotEmpty() && snapshot.remotes.any { it.instance == task.instance }) {
                " (${task.instance})"
            } else ""
            notify(context, settings, Alert.TASK_FAILED, task.id.hashCode(),
                context.getString(R.string.notify_task_title, task.title + where),
                task.message ?: context.getString(R.string.notify_failed_text, Format.dateTime(context, task.startMs)))
        }
        tasks.maxOfOrNull { it.id }?.let { if (it > settings.notifiedTaskId) settings.notifiedTaskId = it }

        val down = AlertRules.disconnected(snapshot.remotes, now).map { it.instance }.toSet()
        val notified = settings.notifiedRemotes
        for (instance in down - notified) {
            val remote = snapshot.remotes.first { it.instance == instance }
            notify(context, settings, Alert.REMOTE_DISCONNECTED, instance.hashCode(),
                context.getString(R.string.notify_remote_title, instance),
                remote.lastHeartbeatMs?.let { context.getString(R.string.notify_remote_text, Format.dateTime(context, it)) }
                    ?: Format.connectionLabel(remote.state))
        }
        for (instance in notified - down) cancel(context, Alert.REMOTE_DISCONNECTED, instance.hashCode())
        settings.notifiedRemotes = down
    }

    /** After a failed refresh: count it and report once the threshold is reached. */
    fun afterFailure(context: Context, settings: Settings, message: String) {
        val failed = settings.failedRefreshes + 1
        settings.failedRefreshes = failed
        if (!AlertRules.unreachable(failed, settings.unreachableThreshold, settings.unreachableNotified)) return
        settings.unreachableNotified = true
        notify(context, settings, Alert.UNREACHABLE, 0,
            context.getString(R.string.notify_unreachable_title),
            context.getString(R.string.notify_unreachable_text, failed, message))
    }

    /** Sends one notification on every channel whose switch is on; returns how many were sent. */
    fun sendTest(context: Context, settings: Settings): Int {
        val enabled = Alert.entries.filter { settings.alertEnabled(it) }
        for (alert in enabled) {
            notify(context, settings, alert, TEST_ID,
                context.getString(R.string.notify_test_title, context.getString(alert.channelName)),
                context.getString(R.string.notify_test_text))
        }
        return if (canPost(context)) enabled.size else 0
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun planLabel(plan: PlanState) = if (plan.instance != null) "${plan.id} (${plan.instance})" else plan.id

    private fun notify(context: Context, settings: Settings, alert: Alert, id: Int, title: String, text: String) {
        if (!settings.alertEnabled(alert) || !canPost(context)) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, alert.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(alert.channelId, id, notification)
    }

    private fun cancel(context: Context, alert: Alert, id: Int) =
        context.getSystemService(NotificationManager::class.java).cancel(alert.channelId, id)

    private const val TEST_ID = -1
}
