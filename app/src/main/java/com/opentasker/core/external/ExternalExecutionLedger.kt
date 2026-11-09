package com.opentasker.core.external

import android.content.Context

/** Terminal and non-terminal states an externally requested run can be in. */
enum class ExternalExecutionState {
    /** Validated and handed to the engine. Not a claim that the task has run. */
    ACCEPTED,

    /** The engine has started the run. */
    RUNNING,

    SUCCEEDED,
    FAILED,
    /** Admission rejected the trigger; the Run Log retains it for a user-initiated replay. */
    HELD,

    /** The id is unknown here: never issued, or evicted after the ledger's retention. */
    UNKNOWN,
    ;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == HELD
}

data class ExternalExecutionRecord(
    val executionId: String,
    val taskId: Long,
    val taskName: String,
    val state: ExternalExecutionState,
    val acceptedAtMs: Long,
    val updatedAtMs: Long,
    val durationMs: Long = 0,
    val error: String? = null,
    val producer: String = "external",
    val parentExecutionId: String? = null,
)

data class ExternalExecutionAcceptance(val record: ExternalExecutionRecord, val isNew: Boolean)

/**
 * Bounded, ordered execution ledger.
 *
 * Pure so the eviction and state-transition rules are testable without Android. Records are kept
 * in acceptance order and the oldest are evicted past [capacity]; a caller that never queries its
 * execution therefore cannot grow this without bound.
 */
class ExternalExecutionLedger(private val capacity: Int = DEFAULT_CAPACITY) {
    private val records = LinkedHashMap<String, ExternalExecutionRecord>()

    fun accept(
        executionId: String,
        taskId: Long,
        taskName: String,
        nowMs: Long,
        producer: String = "external",
        parentExecutionId: String? = null,
    ): ExternalExecutionRecord =
        acceptOnce(executionId, taskId, taskName, nowMs, producer, parentExecutionId).record

    /**
     * Records the execution unless its id is already known, and says which of the two happened.
     * A caller that looked the id up first and accepted afterwards let two deliveries of one
     * command both read "new".
     */
    fun acceptOnce(
        executionId: String,
        taskId: Long,
        taskName: String,
        nowMs: Long,
        producer: String = "external",
        parentExecutionId: String? = null,
    ): ExternalExecutionAcceptance =
        synchronized(records) {
            records[executionId]?.let { return@synchronized ExternalExecutionAcceptance(it, isNew = false) }
            val record = ExternalExecutionRecord(
                executionId = executionId,
                taskId = taskId,
                taskName = taskName,
                state = ExternalExecutionState.ACCEPTED,
                acceptedAtMs = nowMs,
                updatedAtMs = nowMs,
                producer = producer,
                parentExecutionId = parentExecutionId,
            )
            records[executionId] = record
            evictOverflow()
            ExternalExecutionAcceptance(record, isNew = true)
        }

    /**
     * Advances an execution. A terminal state is never overwritten, so a late duplicate
     * notification cannot walk a finished run back to RUNNING.
     */
    fun update(
        executionId: String,
        state: ExternalExecutionState,
        nowMs: Long,
        durationMs: Long = 0,
        error: String? = null,
    ): ExternalExecutionRecord? = synchronized(records) {
        val existing = records[executionId] ?: return null
        if (existing.state.isTerminal) return existing
        val updated = existing.copy(
            state = state,
            updatedAtMs = nowMs,
            durationMs = if (durationMs > 0) durationMs else existing.durationMs,
            error = error ?: existing.error,
        )
        records[executionId] = updated
        updated
    }

    fun get(executionId: String): ExternalExecutionRecord? = synchronized(records) { records[executionId] }

    fun snapshot(): List<ExternalExecutionRecord> = synchronized(records) { records.values.toList() }

    fun restore(from: List<ExternalExecutionRecord>) = synchronized(records) {
        records.clear()
        from.sortedBy { it.acceptedAtMs }.forEach { records[it.executionId] = it }
        evictOverflow()
    }

    /**
     * Marks non-terminal records as failed. Called on engine start: a run that was accepted or
     * in flight when the process died can never complete, and leaving it ACCEPTED forever would
     * make a caller poll indefinitely.
     */
    fun failStaleNonTerminal(nowMs: Long, reason: String): List<ExternalExecutionRecord> =
        synchronized(records) {
            records.values
                .filterNot { it.state.isTerminal }
                .map { stale ->
                    val failed = stale.copy(
                        state = ExternalExecutionState.FAILED,
                        updatedAtMs = nowMs,
                        error = reason,
                    )
                    records[stale.executionId] = failed
                    failed
                }
        }

    private fun evictOverflow() {
        while (records.size > capacity) {
            val oldest = records.keys.firstOrNull() ?: return
            records.remove(oldest)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

/**
 * SharedPreferences-backed persistence for [ExternalExecutionLedger], so a caller can still query
 * its execution id after the app's process has been restarted.
 */
class ExternalExecutionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<ExternalExecutionRecord> = prefs.getStringSet(KEY_RECORDS, emptySet())
        .orEmpty()
        .mapNotNull(::decode)

    fun save(records: List<ExternalExecutionRecord>) {
        prefs.edit().putStringSet(KEY_RECORDS, records.map(::encode).toSet()).apply()
    }

    private fun encode(record: ExternalExecutionRecord): String = listOf(
        record.executionId,
        record.taskId.toString(),
        record.taskName.sanitize(),
        record.state.name,
        record.acceptedAtMs.toString(),
        record.updatedAtMs.toString(),
        record.durationMs.toString(),
        record.error.orEmpty().sanitize(),
        record.producer.sanitize(),
        record.parentExecutionId.orEmpty().sanitize(),
    ).joinToString(FIELD_SEPARATOR)

    private fun decode(raw: String): ExternalExecutionRecord? {
        val parts = raw.split(FIELD_SEPARATOR)
        if (parts.size < FIELD_COUNT) return null
        return ExternalExecutionRecord(
            executionId = parts[0].takeIf { it.isNotBlank() } ?: return null,
            taskId = parts[1].toLongOrNull() ?: return null,
            taskName = parts[2],
            state = runCatching { ExternalExecutionState.valueOf(parts[3]) }.getOrNull() ?: return null,
            acceptedAtMs = parts[4].toLongOrNull() ?: return null,
            updatedAtMs = parts[5].toLongOrNull() ?: return null,
            durationMs = parts[6].toLongOrNull() ?: 0,
            error = parts[7].takeIf { it.isNotBlank() },
            producer = parts.getOrNull(8)?.takeIf { it.isNotBlank() } ?: "external",
            parentExecutionId = parts.getOrNull(9)?.takeIf { it.isNotBlank() },
        )
    }

    private fun String.sanitize(): String = replace(FIELD_SEPARATOR, " ").take(MAX_FIELD_CHARS)

    companion object {
        private const val PREFS_NAME = "opentasker_external_executions"
        private const val KEY_RECORDS = "records"
        private const val FIELD_SEPARATOR = "\u001F"
        private const val FIELD_COUNT = 8
        private const val MAX_FIELD_CHARS = 256
    }
}
