package com.opentasker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
        // The cached label is only ever written when the widget is configured, so a renamed task
        // showed its old name forever and a deleted one still looked runnable. Re-read the names
        // the widget claims to display; goAsync keeps the receiver alive for the query.
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                refreshCachedNames(appContext, appWidgetIds)
                for (widgetId in appWidgetIds) {
                    updateWidget(appContext, appWidgetManager, widgetId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            editor.remove(keyTaskId(id))
            editor.remove(keyDeletedTaskId(id))
            editor.remove(keyTaskName(id))
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "opentasker_widget_prefs"

        fun keyTaskId(widgetId: Int) = "widget_${widgetId}_task_id"
        fun keyTaskName(widgetId: Int) = "widget_${widgetId}_task_name"
        fun keyDeletedTaskId(widgetId: Int) = "widget_${widgetId}_deleted_task_id"

        /**
         * Asks every task widget to re-read its label. Call after a task rename or delete: nothing
         * else notices, because widgets render from a cache written at configuration time.
         */
        fun requestRefresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, TaskWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }

        private suspend fun refreshCachedNames(context: Context, appWidgetIds: IntArray) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val dao = runCatching { OpenTaskerApp_NoHilt.db.taskDao() }.getOrNull() ?: return
            for (widgetId in appWidgetIds) {
                val taskId = widgetTaskToLookUp(
                    boundTaskId = prefs.getLong(keyTaskId(widgetId), -1L),
                    deletedTaskId = prefs.getLong(keyDeletedTaskId(widgetId), -1L),
                )
                if (taskId < 0) continue
                val name = runCatching { dao.getById(taskId) }.getOrNull()
                    ?.toDomainDecodeResult()
                    ?.value
                    ?.name
                if (name == null) {
                    // The task is gone. Drop the id so the widget stops offering a run that can
                    // only fail, and label it so the tap target explains itself. The id is set
                    // aside because undoing the delete brings the same task back.
                    editor.remove(keyTaskId(widgetId))
                    editor.putLong(keyDeletedTaskId(widgetId), taskId)
                    editor.putString(keyTaskName(widgetId), context.getString(R.string.widget_task_missing))
                } else {
                    editor.putLong(keyTaskId(widgetId), taskId)
                    editor.remove(keyDeletedTaskId(widgetId))
                    editor.putString(keyTaskName(widgetId), name)
                }
            }
            editor.apply()
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val taskId = prefs.getLong(keyTaskId(widgetId), -1L)
            val defaultTaskName = context.getString(R.string.app_name)
            val taskName = prefs.getString(keyTaskName(widgetId), defaultTaskName) ?: defaultTaskName

            val views = RemoteViews(context.packageName, R.layout.widget_task)
            views.setTextViewText(R.id.widget_task_name, taskName)

            val target = if (taskId >= 0) {
                Intent(context, TaskRunActivity::class.java).apply {
                    putExtra(TaskRunActivity.EXTRA_TASK_ID, taskId)
                    putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_WIDGET)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            } else {
                // No task bound: send the tap to configuration rather than leaving a dead widget.
                Intent(context, TaskWidgetConfigActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            manager.updateAppWidget(widgetId, views)
        }
    }
}

/** A widget's current task wins; otherwise the one it lost, in case that task has come back. */
internal fun widgetTaskToLookUp(boundTaskId: Long, deletedTaskId: Long): Long =
    if (boundTaskId >= 0) boundTaskId else deletedTaskId
