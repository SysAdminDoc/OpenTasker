package com.opentasker.core.plugins.locale

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.external.InternalTaskRunSource
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.platform.ForeignParcels

/**
 * Exposes OpenTasker as a Locale-compatible setting plugin so Tasker, MacroDroid,
 * and other Locale hosts can invoke approved OpenTasker tasks.
 *
 * Protocol:
 * - Host starts [LocaleSettingEditActivity] with ACTION_EDIT_SETTING
 * - User picks a task, activity returns a bundle with taskId + taskName
 * - Host fires [LocaleSettingFireReceiver] with ACTION_FIRE_SETTING and the saved bundle
 * - Receiver dispatches the task through the existing automation pipeline
 */
object LocalePluginTarget {
    const val BUNDLE_KEY_TASK_ID = "com.opentasker.locale.TASK_ID"
    const val BUNDLE_KEY_TASK_NAME = "com.opentasker.locale.TASK_NAME"
    const val BUNDLE_KEY_GRANT = "com.opentasker.locale.GRANT"
    private const val TAG = "LocalePluginTarget"

    fun buildResultBundle(taskId: Long, taskName: String, grant: String): Bundle =
        Bundle().apply {
            putLong(BUNDLE_KEY_TASK_ID, taskId)
            putString(BUNDLE_KEY_TASK_NAME, taskName)
            putString(BUNDLE_KEY_GRANT, grant)
        }

    fun parseTaskId(bundle: Bundle?): Long? {
        if (bundle == null) return null
        val id = bundle.getLong(BUNDLE_KEY_TASK_ID, -1L)
        return if (id > 0) id else null
    }

    fun parseTaskName(bundle: Bundle?): String? =
        bundle?.getString(BUNDLE_KEY_TASK_NAME)?.ifBlank { null }

    fun parseGrant(bundle: Bundle?): String? =
        bundle?.getString(BUNDLE_KEY_GRANT)?.ifBlank { null }
}

class LocaleSettingFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalePluginContract.ACTION_FIRE_SETTING) return

        // Exported, so the sender chooses what is in this bundle. Reading a value unparcels it,
        // and a class this process does not have throws where an uncaught exception would take
        // down the automation engine. A bundle that cannot be read is not a bundle we can trust,
        // so it is discarded rather than partially parsed.
        ForeignParcels.guard(intent)
        val bundle = runCatching {
            intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
        }.getOrElse { error ->
            AppLogger.warn("LocaleSettingFireReceiver", "Discarded an unreadable Locale bundle", error)
            return
        }
        val taskId = runCatching { LocalePluginTarget.parseTaskId(bundle) }.getOrElse { error ->
            AppLogger.warn("LocaleSettingFireReceiver", "Discarded an unreadable Locale bundle", error)
            return
        }
        val taskName = runCatching { LocalePluginTarget.parseTaskName(bundle) }.getOrNull() ?: "unknown"
        val grant = runCatching { LocalePluginTarget.parseGrant(bundle) }.getOrNull()

        if (taskId == null) {
            AppLogger.warn("LocaleSettingFireReceiver", "Missing or invalid task ID in Locale bundle")
            return
        }

        if (!LocaleGrantStore(context).isValid(grant, taskId)) {
            AppLogger.warn(
                "LocaleSettingFireReceiver",
                "Rejected Locale fire for taskId=$taskId: missing, forged, mutated, or revoked grant",
            )
            return
        }

        AppLogger.info("LocaleSettingFireReceiver", "Locale fire: taskId=$taskId name=$taskName")

        val runIntent = AutomationTargetContract.internalRunTaskIntent(
            context = context,
            taskId = taskId,
            source = InternalTaskRunSource.LOCALE_PLUGIN,
        )
        context.sendOrderedBroadcast(runIntent, AutomationTargetContract.PERMISSION)
    }
}
