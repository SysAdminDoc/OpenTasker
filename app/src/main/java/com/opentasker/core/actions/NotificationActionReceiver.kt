package com.opentasker.core.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.platform.AppVisibilityTracker

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIFICATION_BUTTON) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 }
        val legacyTaskName = intent.getStringExtra(EXTRA_TASK_NAME)
        if (taskId == null && legacyTaskName == null) return
        val buttonLabel = intent.getStringExtra(EXTRA_BUTTON_LABEL)
            ?: legacyTaskName
            ?: "Task ${taskId ?: "unknown"}"
        // Before Android 12 a notification tap lets the app open a screen for a few seconds.
        if (Build.VERSION.SDK_INT < 31) AppVisibilityTracker.noteUserPresence()

        // Hand the run to the already-foreground AutomationService and return immediately: the
        // receiver's ~10 s window is far too short for tasks that can wait up to 30 minutes, and a
        // receiver killed mid-task would lose the run with no run-log entry.
        val serviceIntent = Intent(context.applicationContext, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RUN_NOTIFICATION_TASK
            if (taskId != null) putExtra(EXTRA_TASK_ID, taskId)
            if (legacyTaskName != null) putExtra(EXTRA_TASK_NAME, legacyTaskName)
            putExtra(EXTRA_BUTTON_LABEL, buttonLabel)
        }
        try {
            ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Notification button '$buttonLabel' could not start the engine service", e)
        }
    }

    companion object {
        const val ACTION_NOTIFICATION_BUTTON = "com.opentasker.action.NOTIFICATION_BUTTON"
        const val EXTRA_TASK_ID = "com.opentasker.extra.TASK_ID"
        /** Compatibility only for PendingIntents created before immutable ID bindings shipped. */
        const val EXTRA_TASK_NAME = "com.opentasker.extra.TASK_NAME"
        const val EXTRA_BUTTON_LABEL = "com.opentasker.extra.BUTTON_LABEL"
        const val SOURCE = "Notification action"
        private const val TAG = "OpenTasker"
    }
}
