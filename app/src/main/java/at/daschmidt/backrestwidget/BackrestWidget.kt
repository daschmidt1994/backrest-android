package at.daschmidt.backrestwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

class BackrestWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
        Refresher.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        Refresher.schedule(context)
        Refresher.refreshSoon(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BackrestWidget::class.java))
            val busy = RemoteViews(context.packageName, R.layout.widget).apply {
                setTextViewText(R.id.widget_subtitle, context.getString(R.string.refreshing))
            }
            manager.partiallyUpdateAppWidget(ids, busy)
            Refresher.refreshSoon(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "at.daschmidt.backrestwidget.REFRESH"
        private const val MAX_ROWS = 8
        private const val STALE_FACTOR = 3

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, BackrestWidget::class.java))
                .forEach { update(context, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val options = manager.getAppWidgetOptions(id)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
            manager.updateAppWidget(id, build(context, compact = height in 1 until 100))
        }

        private fun build(context: Context, compact: Boolean): RemoteViews {
            val settings = Settings(context)
            val snapshot = settings.snapshot
            val error = settings.lastError
            val views = RemoteViews(context.packageName, R.layout.widget)

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val refresh = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, BackrestWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
            views.removeAllViews(R.id.widget_rows)

            if (!settings.configured) {
                views.setInt(R.id.widget_status_dot, "setColorFilter", Format.healthColor(context, Health.NONE))
                views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_not_configured))
                return views
            }

            val offline = error != null
            val health = snapshot?.health ?: Health.NONE
            val stale = snapshot == null || offline &&
                System.currentTimeMillis() - snapshot.fetchedAtMs > settings.intervalMinutes * 60_000L * STALE_FACTOR
            views.setInt(
                R.id.widget_status_dot, "setColorFilter",
                Format.healthColor(context, if (stale) Health.NONE else health)
            )
            views.setTextViewText(
                R.id.widget_title,
                snapshot?.let { Format.overall(it) } ?: context.getString(R.string.app_name)
            )
            val subtitle = when {
                offline && snapshot != null -> "Offline · Stand ${Format.time(context, snapshot.fetchedAtMs)}"
                offline -> error
                else -> "Stand ${Format.time(context, snapshot!!.fetchedAtMs)}"
            }
            views.setTextViewText(R.id.widget_subtitle, subtitle)

            if (compact || snapshot == null) {
                views.setViewVisibility(R.id.widget_rows, View.GONE)
                return views
            }
            views.setViewVisibility(R.id.widget_rows, View.VISIBLE)
            for (plan in snapshot.plans.take(MAX_ROWS)) {
                views.addView(R.id.widget_rows, row(context, plan))
            }
            return views
        }

        private fun row(context: Context, plan: PlanState): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            row.setInt(R.id.row_dot, "setColorFilter", Format.healthColor(context, plan.health))
            row.setTextViewText(R.id.row_name, plan.id)
            val last = plan.lastFinished
            row.setTextViewText(
                R.id.row_last,
                when {
                    plan.running -> "läuft …"
                    last == null -> "noch nie"
                    else -> Format.relative(last.startMs + last.durationMs)
                }
            )
            val next = plan.nextBackupMs
            row.setTextViewText(R.id.row_next, if (next != null) "nächstes ${Format.dayAndTime(context, next)}" else "kein Zeitplan")
            return row
        }
    }
}
