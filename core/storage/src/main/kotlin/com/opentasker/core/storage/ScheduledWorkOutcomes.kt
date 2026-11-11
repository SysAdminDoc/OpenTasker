package com.opentasker.core.storage

import android.content.Context
import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException

/**
 * Per-worker record of how each scheduled job last ended.
 *
 * Android 16 ties JobScheduler and WorkManager quotas to the app standby bucket, so a user who
 * rarely opens OpenTasker can drop into `rare` or `restricted` and watch their automations stop
 * with nothing to look at. WorkManager keeps a stop reason on the live [WorkInfo], but only for
 * work it still tracks and with no timestamp, so each worker writes its own outcome here instead.
 */
enum class ScheduledWorkerId(val key: String) {
    ENGINE_WATCHDOG("engine_watchdog"),
    RUN_LOG_PRUNE("run_log_prune"),
    CONFIGURATION_SNAPSHOT("configuration_snapshot"),
    UPDATE_CHECK("update_check"),
    TEMPORARY_STATE_REVERT("temporary_state_revert"),
    HEALTH_CONNECT_POLL("health_connect_poll"),
    ;

    companion object {
        fun fromKey(key: String): ScheduledWorkerId? = entries.firstOrNull { it.key == key }
    }
}

/** How a worker run ended. [STOPPED] is the one the platform, not the app, decided. */
enum class ScheduledWorkOutcomeKind { COMPLETED, RETRYING, FAILED, STOPPED }

data class ScheduledWorkOutcome(
    val worker: ScheduledWorkerId,
    val kind: ScheduledWorkOutcomeKind,
    /** A [WorkInfo] `STOP_REASON_*` value; [WorkInfo.STOP_REASON_NOT_STOPPED] unless [kind] is STOPPED. */
    val stopReason: Int,
    val timestampMillis: Long,
    /**
     * The most recent platform stop for this worker, when it is not [kind] itself.
     *
     * One row covers a worker class, and the temporary-state revert runs one instance per state
     * key, so a later instance finishing normally would otherwise erase the stop that is the whole
     * point of the row.
     */
    val lastStop: ScheduledWorkOutcome? = null,
)

fun encodeScheduledWorkOutcome(outcome: ScheduledWorkOutcome): String =
    "${outcome.kind.name}|${outcome.stopReason}|${outcome.timestampMillis}"

/** Returns null for anything this version did not write, so an old or corrupt row reads as absent. */
fun decodeScheduledWorkOutcome(worker: ScheduledWorkerId, raw: String?): ScheduledWorkOutcome? {
    val parts = raw?.split('|') ?: return null
    if (parts.size != 3) return null
    val kind = ScheduledWorkOutcomeKind.entries.firstOrNull { it.name == parts[0] } ?: return null
    val stopReason = parts[1].toIntOrNull() ?: return null
    val timestampMillis = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
    return ScheduledWorkOutcome(worker, kind, stopReason, timestampMillis)
}

// Every Result subclass is restricted to the androidx.work library group, so naming one in an `is`
// check fails lint. Comparing against the class of what the public factories return says the same
// thing, and unlike equality it still holds for a failure that carries output data.
fun scheduledWorkOutcomeKind(result: ListenableWorker.Result): ScheduledWorkOutcomeKind = when (result.javaClass) {
    ListenableWorker.Result.failure().javaClass -> ScheduledWorkOutcomeKind.FAILED
    ListenableWorker.Result.retry().javaClass -> ScheduledWorkOutcomeKind.RETRYING
    else -> ScheduledWorkOutcomeKind.COMPLETED
}

class ScheduledWorkOutcomeStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(
        worker: ScheduledWorkerId,
        kind: ScheduledWorkOutcomeKind,
        stopReason: Int = WorkInfo.STOP_REASON_NOT_STOPPED,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val outcome = ScheduledWorkOutcome(worker, kind, stopReason, nowMillis)
        prefs.edit().putString(worker.key, encodeScheduledWorkOutcome(outcome)).apply()
    }

    /**
     * Written from `onStopped`, which can be the last code that runs before the process goes away,
     * so this commits synchronously rather than deferring the write.
     */
    fun recordStopped(worker: ScheduledWorkerId, stopReason: Int, nowMillis: Long = System.currentTimeMillis()) {
        // Below API 31 the platform does not tell WorkManager why, and reporting "not stopped" for
        // a worker that was demonstrably stopped would be a lie.
        val reason = if (stopReason == WorkInfo.STOP_REASON_NOT_STOPPED) WorkInfo.STOP_REASON_UNKNOWN else stopReason
        val outcome = ScheduledWorkOutcome(worker, ScheduledWorkOutcomeKind.STOPPED, reason, nowMillis)
        val encoded = encodeScheduledWorkOutcome(outcome)
        prefs.edit()
            .putString(worker.key, encoded)
            .putString(stopKey(worker), encoded)
            .commit()
    }

    fun read(): List<ScheduledWorkOutcome> = ScheduledWorkerId.entries.mapNotNull { worker ->
        val latest = decodeScheduledWorkOutcome(worker, prefs.getString(worker.key, null)) ?: return@mapNotNull null
        val stop = decodeScheduledWorkOutcome(worker, prefs.getString(stopKey(worker), null))
        if (stop == null || stop == latest) latest else latest.copy(lastStop = stop)
    }

    private companion object {
        const val PREFS_NAME = "scheduled_work_outcomes"

        fun stopKey(worker: ScheduledWorkerId): String = worker.key + ".lastStop"
    }
}

/**
 * Runs [block] and records how it ended.
 *
 * `CoroutineWorker.onStopped` is final, so a platform stop arrives here as cancellation of the
 * worker's coroutine. [ListenableWorker.isStopped] separates that from an ordinary cancellation,
 * and the platform stop reason carries why, on the versions of Android that report one.
 */
suspend fun ListenableWorker.recordingOutcome(
    worker: ScheduledWorkerId,
    block: suspend () -> ListenableWorker.Result,
): ListenableWorker.Result {
    val store = ScheduledWorkOutcomeStore(applicationContext)
    val result = try {
        block()
    } catch (error: CancellationException) {
        if (isStopped) store.recordStopped(worker, platformStopReason())
        throw error
    } catch (error: Throwable) {
        // A platform stop can surface as something other than cancellation when teardown makes an
        // in-flight platform call throw. Recording that as a crash is the confusion this exists to
        // remove, so isStopped decides here too.
        if (isStopped) {
            store.recordStopped(worker, platformStopReason())
        } else {
            store.record(worker, ScheduledWorkOutcomeKind.FAILED)
        }
        throw error
    }
    if (isStopped) {
        store.recordStopped(worker, platformStopReason())
    } else {
        store.record(worker, scheduledWorkOutcomeKind(result))
    }
    return result
}

/** Android only reports a stop reason from API 31; before that the honest answer is "unknown". */
private fun ListenableWorker.platformStopReason(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason else WorkInfo.STOP_REASON_UNKNOWN
