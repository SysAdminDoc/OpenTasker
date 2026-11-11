package com.opentasker.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.opentasker.app.OpenTaskerApp_NoHilt
import androidx.core.content.ContextCompat
import com.opentasker.core.capabilities.ProfileTurnOnChecks
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.engine.ExecutionEnvelope
import com.opentasker.core.engine.ExecutionProducer
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

enum class InternalTaskRunSource(
    val wireValue: String,
    val runLogLabel: String,
) {
    LOCALE_PLUGIN("locale_plugin", "Locale plugin"),
    SCENE_OVERLAY("scene_overlay", "Scene overlay"),
    QUICK_SETTINGS_TILE("quick_settings_tile", "Quick Settings tile"),
    APP_FUNCTION("app_function", "App function"),
}

object AutomationTargetContract {
    const val PERMISSION = "com.opentasker.permission.AUTOMATION"

    // Home Assistant Companion notification commands use these field names when a
    // command_broadcast_intent action targets this receiver. They are aliases for the
    // namespaced OpenTasker extras, not replacements, so protocol-v2 callers remain stable.
    const val HOME_ASSISTANT_COMMAND_BROADCAST_INTENT = "command_broadcast_intent"
    const val HOME_ASSISTANT_FIELD_MESSAGE = "message"
    const val HOME_ASSISTANT_FIELD_DATA = "data"
    const val HOME_ASSISTANT_FIELD_INTENT_PACKAGE_NAME = "intent_package_name"
    const val HOME_ASSISTANT_FIELD_INTENT_ACTION = "intent_action"
    const val HOME_ASSISTANT_FIELD_INTENT_EXTRAS = "intent_extras"

    const val ACTION_RUN_TASK = "com.opentasker.action.RUN_TASK"
    const val ACTION_SET_PROFILE_ENABLED = "com.opentasker.action.SET_PROFILE_ENABLED"
    const val ACTION_QUERY_STATUS = "com.opentasker.action.QUERY_STATUS"
    const val ACTION_QUERY_EXECUTION = "com.opentasker.action.QUERY_EXECUTION"

    /**
     * Protocol version a RUN_TASK caller must declare.
     *
     * v1 held the broadcast open with goAsync() until the whole task finished and returned its
     * terminal success. Android expects broadcast work to finish in roughly 10 seconds while a
     * task can wait up to 30 minutes, so a v1 caller was reading a result the system could kill
     * mid-run. v2 validates, enqueues to the foreground service, and returns "accepted" plus an
     * execution id the caller polls with ACTION_QUERY_EXECUTION.
     */
    const val PROTOCOL_VERSION = 2

    const val EXTRA_TASK_ID = "com.opentasker.extra.TASK_ID"
    const val EXTRA_TASK_NAME = "com.opentasker.extra.TASK_NAME"
    const val EXTRA_PROFILE_ID = "com.opentasker.extra.PROFILE_ID"
    const val EXTRA_PROFILE_NAME = "com.opentasker.extra.PROFILE_NAME"
    const val EXTRA_ENABLED = "com.opentasker.extra.ENABLED"
    const val EXTRA_ERROR = "com.opentasker.extra.ERROR"
    const val EXTRA_TASK_SUCCESS = "com.opentasker.extra.TASK_SUCCESS"
    const val EXTRA_TASK_DURATION_MS = "com.opentasker.extra.TASK_DURATION_MS"
    const val EXTRA_PROFILE_FOUND = "com.opentasker.extra.PROFILE_FOUND"
    const val EXTRA_PROFILE_ENABLED = "com.opentasker.extra.PROFILE_ENABLED"
    const val EXTRA_PROFILE_CONTEXT_COUNT = "com.opentasker.extra.PROFILE_CONTEXT_COUNT"
    const val EXTRA_TASK_COUNT = "com.opentasker.extra.TASK_COUNT"
    const val EXTRA_PROFILE_COUNT = "com.opentasker.extra.PROFILE_COUNT"
    const val EXTRA_ENABLED_PROFILE_COUNT = "com.opentasker.extra.ENABLED_PROFILE_COUNT"
    const val EXTRA_PROTOCOL_VERSION = "com.opentasker.extra.PROTOCOL_VERSION"
    const val EXTRA_ACCEPTED = "com.opentasker.extra.ACCEPTED"
    const val EXTRA_EXECUTION_ID = "com.opentasker.extra.EXECUTION_ID"
    const val EXTRA_EXECUTION_STATE = "com.opentasker.extra.EXECUTION_STATE"
    const val EXTRA_EXECUTION_TERMINAL = "com.opentasker.extra.EXECUTION_TERMINAL"
    const val EXTRA_RUN_SOURCE = "com.opentasker.extra.RUN_SOURCE"
    const val EXTRA_PARENT_EXECUTION_ID = "com.opentasker.extra.PARENT_EXECUTION_ID"
    const val EXTRA_EXECUTION_PRODUCER = "com.opentasker.extra.EXECUTION_PRODUCER"

    const val VARIABLE_EXTRA_PREFIX = "com.opentasker.var."
    const val DEFAULT_RUN_SOURCE = "External intent"
    const val MAX_VARIABLE_VALUE_CHARS = 4_096
    const val MAX_SUPPLIED_VARIABLES = 64
    private val variableNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

    fun isValidVariableName(name: String): Boolean = variableNamePattern.matches(name)

    fun variableExtraName(variableName: String): String {
        require(isValidVariableName(variableName)) { "Invalid variable name." }
        return VARIABLE_EXTRA_PREFIX + variableName
    }

    /**
     * The only supported constructor for trusted in-app RUN_TASK broadcasts.
     *
     * Keeping protocol versioning and variable encoding here prevents an internal producer from
     * silently drifting behind the exported receiver contract again.
     */
    fun internalRunTaskIntent(
        context: Context,
        taskId: Long,
        source: InternalTaskRunSource,
        variables: Map<String, String> = emptyMap(),
        executionId: String = UUID.randomUUID().toString(),
        parentExecutionId: String? = null,
    ): Intent {
        require(taskId > 0) { "Task id must be positive." }
        variables.keys.forEach { variableName ->
            require(isValidVariableName(variableName)) { "Invalid variable name: $variableName" }
        }
        return Intent(context, AutomationTargetReceiver::class.java).apply {
            action = ACTION_RUN_TASK
            putExtra(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_RUN_SOURCE, source.wireValue)
            putExtra(EXTRA_EXECUTION_ID, executionId)
            putExtra(EXTRA_EXECUTION_PRODUCER, source.wireValue)
            parentExecutionId?.let { putExtra(EXTRA_PARENT_EXECUTION_ID, it) }
            variables.entries
                .sortedBy { it.key }
                .take(MAX_SUPPLIED_VARIABLES)
                .forEach { (name, value) ->
                    putExtra(variableExtraName(name), value.take(MAX_VARIABLE_VALUE_CHARS))
                }
        }
    }

    /** Maps only known in-app source tokens/labels; callers cannot inject arbitrary run-log text. */
    fun runSourceLabel(rawSource: String?): String =
        InternalTaskRunSource.entries
            .firstOrNull { it.wireValue == rawSource || it.runLogLabel == rawSource }
            ?.runLogLabel
            ?: DEFAULT_RUN_SOURCE

    /**
     * Extracts only the bounded, string-valued variable extras accepted by RUN_TASK. Keeping this
     * parser beside the public contract lets tests and future producers exercise the same boundary
     * without invoking a receiver or touching the database.
     */
    fun extractVariableExtras(extras: Bundle?): Map<String, String> {
        if (extras == null) return emptyMap()
        return extractVariableExtras(extras.keySet(), extras::getString)
    }

    /**
     * Reads only the variable keys. Bundle.getString on an extra that isn't a string logs a
     * ClassCastException stack trace, and every v2 request carries the int protocol version.
     */
    internal fun extractVariableExtras(keys: Set<String>, read: (String) -> String?): Map<String, String> =
        extractVariableExtras(keys.filter { it.startsWith(VARIABLE_EXTRA_PREFIX) }.associateWith(read))

    internal fun extractVariableExtras(values: Map<String, String?>): Map<String, String> = values.keys
            .asSequence()
            .filter { it.startsWith(VARIABLE_EXTRA_PREFIX) }
            .sorted()
            .mapNotNull { key ->
                val name = key.removePrefix(VARIABLE_EXTRA_PREFIX)
                if (!isValidVariableName(name)) return@mapNotNull null
                val value = values[key] ?: return@mapNotNull null
                name to value.take(MAX_VARIABLE_VALUE_CHARS)
            }
            .take(MAX_SUPPLIED_VARIABLES)
            .toMap()
}

class AutomationTargetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Read before goAsync(), which detaches the pending result and makes this read false.
        // Scene buttons, widgets and the tile send plain broadcasts, and setting a result on one
        // logs an error per fire; only ordered senders like the Locale plugin host get the result.
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        requestScope.launch {
            val response = runCatching {
                when (intent.action) {
                    AutomationTargetContract.ACTION_RUN_TASK -> runTask(context.applicationContext, intent)
                    AutomationTargetContract.ACTION_SET_PROFILE_ENABLED -> setProfileEnabled(context.applicationContext, intent)
                    AutomationTargetContract.ACTION_QUERY_STATUS -> queryStatus(intent)
                    AutomationTargetContract.ACTION_QUERY_EXECUTION -> queryExecution(context.applicationContext, intent)
                    else -> failure("Unsupported action: ${intent.action}")
                }
            }.getOrElse { failure(it.message ?: "Automation target request failed") }
            try {
                if (ordered) {
                    pending.setResultCode(response.resultCode)
                    pending.setResultExtras(response.extras)
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to publish automation target result", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Validates and hands the run to the foreground service, then returns immediately.
     *
     * The receiver never waits for the task: a run that outlives the broadcast window would be
     * killed mid-task with no run-log entry, and returning its "success" from inside that window
     * meant returning a result that had not happened yet.
     */
    private suspend fun runTask(appContext: Context, intent: Intent): TargetResponse {
        val requestedVersion = intent.getIntExtra(AutomationTargetContract.EXTRA_PROTOCOL_VERSION, 0)
        if (requestedVersion != AutomationTargetContract.PROTOCOL_VERSION) {
            return failure(
                "RUN_TASK requires ${AutomationTargetContract.EXTRA_PROTOCOL_VERSION}=" +
                    "${AutomationTargetContract.PROTOCOL_VERSION}. Runs are asynchronous: the reply " +
                    "carries an execution id to poll with ${AutomationTargetContract.ACTION_QUERY_EXECUTION}, " +
                    "not a task result.",
                extras = Bundle().apply {
                    putInt(AutomationTargetContract.EXTRA_PROTOCOL_VERSION, AutomationTargetContract.PROTOCOL_VERSION)
                },
            )
        }

        val task = resolveTask(intent)
            ?: return failure("Task not found. Provide ${AutomationTargetContract.EXTRA_TASK_ID} or ${AutomationTargetContract.EXTRA_TASK_NAME}.")

        val suppliedVariables = AutomationTargetContract.extractVariableExtras(intent.extras)
        val runSource = AutomationTargetContract.runSourceLabel(
            intent.getStringExtra(AutomationTargetContract.EXTRA_RUN_SOURCE),
        )
        val requestedExecutionId = intent
            .getStringExtra(AutomationTargetContract.EXTRA_EXECUTION_ID)
            ?.trim()
            ?.takeIf(ExecutionEnvelope::isValidExecutionId)
        val requestedParentExecutionId = intent
            .getStringExtra(AutomationTargetContract.EXTRA_PARENT_EXECUTION_ID)
            ?.trim()
            ?.takeIf(ExecutionEnvelope::isValidExecutionId)
        val producer = intent.getStringExtra(AutomationTargetContract.EXTRA_EXECUTION_PRODUCER)
            ?.trim()
            ?.takeIf { value -> ExecutionProducer.entries.any { it.wireValue == value } }
            ?: InternalTaskRunSource.entries
                .firstOrNull { it.wireValue == intent.getStringExtra(AutomationTargetContract.EXTRA_RUN_SOURCE) }
                ?.wireValue
            ?: ExecutionProducer.fromSource(runSource).wireValue
        val acceptance = ExternalExecutions.accept(
            context = appContext,
            taskId = task.id,
            taskName = task.name,
            executionId = requestedExecutionId ?: UUID.randomUUID().toString(),
            producer = producer,
            parentExecutionId = requestedParentExecutionId,
        )

        // Re-delivery of a command with the same id is an acknowledgement, not a second run. The
        // ledger answers that in the step that records the id: two copies arriving together used
        // to both start the service, and the engine then failed the id on the second copy while
        // the first was still running.
        if (!acceptance.isNew) return acceptedResponse(acceptance.record)
        val executionId = acceptance.record.executionId

        val serviceIntent = Intent(appContext, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RUN_EXTERNAL_TASK
            putExtra(AutomationTargetContract.EXTRA_EXECUTION_ID, executionId)
            putExtra(AutomationTargetContract.EXTRA_TASK_ID, task.id)
            putExtra(AutomationTargetContract.EXTRA_RUN_SOURCE, runSource)
            putExtra(AutomationTargetContract.EXTRA_EXECUTION_PRODUCER, producer)
            requestedParentExecutionId?.let { putExtra(AutomationTargetContract.EXTRA_PARENT_EXECUTION_ID, it) }
            suppliedVariables.forEach { (name, value) ->
                putExtra(AutomationTargetContract.variableExtraName(name), value)
            }
        }
        return try {
            ContextCompat.startForegroundService(appContext, serviceIntent)
            acceptedResponse(requireNotNull(ExternalExecutions.get(appContext, executionId)))
        } catch (e: Exception) {
            ExternalExecutions.update(
                appContext,
                executionId,
                ExternalExecutionState.FAILED,
                error = "Engine service could not be started",
            )
            failure("Engine service could not be started: ${e.message}")
        }
    }

    private fun queryExecution(appContext: Context, intent: Intent): TargetResponse {
        val executionId = intent.getStringExtra(AutomationTargetContract.EXTRA_EXECUTION_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return failure("Provide ${AutomationTargetContract.EXTRA_EXECUTION_ID}.")

        val record = ExternalExecutions.get(appContext, executionId)
        val state = record?.state ?: ExternalExecutionState.UNKNOWN
        return TargetResponse(
            // An unknown id is a caller error, not a task failure; a still-running execution is a
            // valid answer, so only a genuinely failed run reports CANCELED.
            if (state == ExternalExecutionState.UNKNOWN || state == ExternalExecutionState.FAILED) {
                Activity.RESULT_CANCELED
            } else {
                Activity.RESULT_OK
            },
            Bundle().apply {
                putInt(AutomationTargetContract.EXTRA_PROTOCOL_VERSION, AutomationTargetContract.PROTOCOL_VERSION)
                putString(AutomationTargetContract.EXTRA_EXECUTION_ID, executionId)
                putString(AutomationTargetContract.EXTRA_EXECUTION_STATE, state.name)
                putBoolean(AutomationTargetContract.EXTRA_EXECUTION_TERMINAL, state.isTerminal)
                record?.parentExecutionId?.let {
                    putString(AutomationTargetContract.EXTRA_PARENT_EXECUTION_ID, it)
                }
                record?.producer?.let {
                    putString(AutomationTargetContract.EXTRA_EXECUTION_PRODUCER, it)
                }
                record?.let {
                    putLong(AutomationTargetContract.EXTRA_TASK_ID, it.taskId)
                    putLong(AutomationTargetContract.EXTRA_TASK_DURATION_MS, it.durationMs)
                    putBoolean(
                        AutomationTargetContract.EXTRA_TASK_SUCCESS,
                        it.state == ExternalExecutionState.SUCCEEDED,
                    )
                    it.error?.let { error -> putString(AutomationTargetContract.EXTRA_ERROR, error) }
                }
                if (record == null) {
                    putString(
                        AutomationTargetContract.EXTRA_ERROR,
                        "Unknown execution id (never issued, or aged out of the retained results).",
                    )
                }
            },
        )
    }

    private suspend fun setProfileEnabled(appContext: Context, intent: Intent): TargetResponse {
        val db = OpenTaskerApp_NoHilt.db
        val profile = resolveProfile(intent)
            ?: return failure("Profile not found. Provide ${AutomationTargetContract.EXTRA_PROFILE_ID} or ${AutomationTargetContract.EXTRA_PROFILE_NAME}.")
        val enabled = intent.getBooleanExtra(AutomationTargetContract.EXTRA_ENABLED, profile.enabled)
        if (enabled && profile.requiresRiskAcknowledgement) {
            return failure("Imported profile requires in-app power review before its first enable.")
        }
        // Turning a profile on passes what the app checks from its list. Only the enabled column is
        // written: a whole-row write lost edits saved in the meantime and brought back a profile
        // deleted in the meantime.
        ProfileTurnOnChecks.switchSaved(db, profile.id, enabled, Settings.System.canWrite(appContext))
            ?.let { return failure(it.replaceFirstChar(Char::uppercaseChar)) }
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, true)
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_ENABLED, enabled)
            },
        )
    }

    private suspend fun queryStatus(intent: Intent): TargetResponse {
        val db = OpenTaskerApp_NoHilt.db
        val profile = resolveProfile(intent)
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                putInt(AutomationTargetContract.EXTRA_TASK_COUNT, db.taskDao().countAll())
                putInt(AutomationTargetContract.EXTRA_PROFILE_COUNT, db.profileDao().countAll())
                putInt(
                    AutomationTargetContract.EXTRA_ENABLED_PROFILE_COUNT,
                    db.profileDao().countEnabled(),
                )
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, profile != null)
                profile?.let {
                    putBoolean(AutomationTargetContract.EXTRA_PROFILE_ENABLED, it.enabled)
                    putInt(AutomationTargetContract.EXTRA_PROFILE_CONTEXT_COUNT, it.contexts.size)
                }
            },
        )
    }

    private suspend fun resolveTask(intent: Intent) =
        intent.getLongExtra(AutomationTargetContract.EXTRA_TASK_ID, 0L)
            .takeIf { it > 0 }
            ?.let { OpenTaskerApp_NoHilt.db.taskDao().getById(it)?.toDomain() }
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_TASK_NAME)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { name -> OpenTaskerApp_NoHilt.db.taskDao().getByNameIgnoreCase(name)?.toDomain() }

    private suspend fun resolveProfile(intent: Intent) =
        intent.getLongExtra(AutomationTargetContract.EXTRA_PROFILE_ID, 0L)
            .takeIf { it > 0 }
            ?.let { id -> OpenTaskerApp_NoHilt.db.profileDao().getById(id) }
            ?.toDomain()
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_PROFILE_NAME)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { name -> OpenTaskerApp_NoHilt.db.profileDao().getByNameIgnoreCase(name)?.toDomain() }

    private fun failure(message: String, extras: Bundle = Bundle()): TargetResponse {
        AppLogger.warn(TAG, message)
        return TargetResponse(
            Activity.RESULT_CANCELED,
            extras.apply { putString(AutomationTargetContract.EXTRA_ERROR, message) },
        )
    }

    private fun acceptedResponse(record: ExternalExecutionRecord): TargetResponse = TargetResponse(
        Activity.RESULT_OK,
        Bundle().apply {
            putInt(AutomationTargetContract.EXTRA_PROTOCOL_VERSION, AutomationTargetContract.PROTOCOL_VERSION)
            putBoolean(AutomationTargetContract.EXTRA_ACCEPTED, true)
            putString(AutomationTargetContract.EXTRA_EXECUTION_ID, record.executionId)
            putString(AutomationTargetContract.EXTRA_EXECUTION_STATE, record.state.name)
            putBoolean(AutomationTargetContract.EXTRA_EXECUTION_TERMINAL, record.state.isTerminal)
            record.parentExecutionId?.let {
                putString(AutomationTargetContract.EXTRA_PARENT_EXECUTION_ID, it)
            }
            putString(AutomationTargetContract.EXTRA_EXECUTION_PRODUCER, record.producer)
        },
    )

    companion object {
        private const val TAG = "AutomationTargetReceiver"

        /**
         * One scope for every broadcast rather than a fresh one per request.
         *
         * A `BroadcastReceiver` instance is discarded as soon as [onReceive] returns, so a scope
         * created there is unreachable and unmanaged for the whole `goAsync()` window. A supervisor
         * scope on the process keeps one failing request from cancelling the next.
         */
        private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

private data class TargetResponse(
    val resultCode: Int,
    val extras: Bundle,
)
