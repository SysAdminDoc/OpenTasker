package com.opentasker.widget

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.ExecutionAdmissionRegistry
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.ExecutionEnvelope
import com.opentasker.core.engine.logSkippedRun
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.recoveryMessage
import android.os.Handler
import android.os.Looper
import com.opentasker.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskRunActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SHORTCUT
        if (taskId < 0) {
            finishWithMessage(getString(R.string.widget_task_invalid))
            return
        }

        // A widget task can legitimately run for minutes (flow.wait alone allows 30). This
        // activity draws nothing but is a real window, so staying alive for the run left the
        // launcher covered by an invisible, touch-consuming overlay with no progress indication.
        // Hand the work to a detached scope and get out of the way; the result arrives as a toast.
        if (!RunningWidgetTasks.begin(taskId)) {
            finishWithMessage(getString(R.string.widget_task_already_running))
            return
        }
        val appContext = applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        finish()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val message = try {
                val db = OpenTaskerApp_NoHilt.db
                val entity = db.taskDao().getById(taskId)
                if (entity == null) {
                    appContext.getString(R.string.widget_task_not_found)
                } else {
                    val decoded = entity.toDomainDecodeResult()
                    val issue = decoded.issue
                    if (issue != null) {
                        val reason = issue.recoveryMessage()
                        AppLogger.error(TAG, reason)
                        logSkippedRun(db, decoded.value, source, reason)
                        appContext.getString(R.string.widget_task_corrupt, decoded.value.name)
                    } else {
                        val task = decoded.value
                        val result = executeAndLogTask(
                            appContext = applicationContext,
                            db = db,
                            task = task,
                            source = source,
                            execution = ExecutionEnvelope.create(task, source),
                            visibleActivity = true,
                            // Without the shared controller this path gets a private in-memory one
                            // that admits even while the profile is saturated or its circuit is
                            // open, so widget and shortcut taps ignored limits the in-app Run
                            // button respects.
                            admissionController = ExecutionAdmissionRegistry.current(applicationContext),
                        )
                        val status = when {
                            result.held -> R.string.widget_task_result_held
                            result.skippedReason != null -> R.string.widget_task_result_skipped
                            result.report.success -> R.string.widget_task_result_succeeded
                            else -> R.string.widget_task_result_failed
                        }
                        appContext.getString(status, task.name, result.report.durationMs)
                    }
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Task run failed", e)
                appContext.getString(R.string.widget_task_run_failed)
            }
            finally {
                RunningWidgetTasks.finish(taskId)
            }
            mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Two quick taps on a widget used to start two concurrent runs with real side effects. */
    private object RunningWidgetTasks {
        private val running = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

        fun begin(taskId: Long): Boolean = running.add(taskId)

        fun finish(taskId: Long) {
            running.remove(taskId)
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "com.opentasker.widget.TASK_ID"
        const val EXTRA_SOURCE = "com.opentasker.widget.SOURCE"
        const val SOURCE_WIDGET = "Widget"
        const val SOURCE_SHORTCUT = "Shortcut"
        private const val TAG = "TaskRunActivity"
    }
}
