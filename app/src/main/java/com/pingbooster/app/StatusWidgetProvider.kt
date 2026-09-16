package com.pingbooster.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home screen widget: shows the live state and offers a one-tap start / stop,
 * so the booster can be controlled without opening the app.
 */
class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            render(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Booster.ACTION_WIDGET_TOGGLE) {
            Booster.toggle(context)
            refreshAll(context)
        }
        super.onReceive(context, intent)
    }

    companion object {

        /** Redraws every placed widget; called on state changes only. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = try {
                manager.getAppWidgetIds(ComponentName(context, StatusWidgetProvider::class.java))
            } catch (_: Throwable) {
                return
            }
            for (id in ids) {
                render(context, manager, id)
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            val running = Booster.isRunning(context)

            views.setTextViewText(
                R.id.widgetStatus,
                context.getString(if (running) R.string.widget_status_on else R.string.widget_status_off)
            )

            val latency = StatusBus.latencyMs
            val detail = when {
                !running -> context.getString(R.string.widget_detail_idle)
                latency > 0 -> context.getString(R.string.widget_detail_running, latency, StatusBus.checks)
                else -> context.getString(R.string.tile_subtitle_waiting)
            }
            views.setTextViewText(R.id.widgetDetail, detail)

            views.setImageViewResource(
                R.id.widgetDot,
                if (running) R.drawable.dot_online else R.drawable.dot_offline
            )

            val button = R.id.widgetButton
            views.setTextViewText(
                button,
                context.getString(if (running) R.string.action_stop_short else R.string.action_start_short)
            )
            views.setOnClickPendingIntent(button, togglePendingIntent(context))
            views.setOnClickPendingIntent(R.id.widgetRoot, openPendingIntent(context))

            try {
                manager.updateAppWidget(widgetId, views)
            } catch (_: Throwable) {
                // The widget was removed between the id lookup and the update.
            }
        }

        private fun togglePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, StatusWidgetProvider::class.java)
                .setAction(Booster.ACTION_WIDGET_TOGGLE)
            return PendingIntent.getBroadcast(
                context,
                11,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                12,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
