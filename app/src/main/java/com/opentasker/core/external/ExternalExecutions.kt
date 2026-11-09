package com.opentasker.core.external

import android.content.Context
import java.util.UUID

/**
 * Process-wide access to the external execution ledger, persisted so a caller can still resolve its
 * execution id after the app's process has been restarted.
 *
 * The receiver (which issues ids) and the service (which advances them) run in the same process but
 * are separate components, so neither can own the state.
 */
object ExternalExecutions {
    private val ledger = ExternalExecutionLedger()
    private var loaded = false

    @Synchronized
    private fun store(context: Context): ExternalExecutionStore {
        val store = ExternalExecutionStore(context)
        if (!loaded) {
            ledger.restore(store.load())
            loaded = true
        }
        return store
    }

    fun accept(
        context: Context,
        taskId: Long,
        taskName: String,
        nowMs: Long = System.currentTimeMillis(),
        executionId: String = UUID.randomUUID().toString(),
        producer: String = "external",
        parentExecutionId: String? = null,
    ): ExternalExecutionAcceptance {
        val store = store(context)
        val acceptance = ledger.acceptOnce(
            executionId = executionId,
            taskId = taskId,
            taskName = taskName,
            nowMs = nowMs,
            producer = producer,
            parentExecutionId = parentExecutionId,
        )
        if (acceptance.isNew) store.save(ledger.snapshot())
        return acceptance
    }

    fun update(
        context: Context,
        executionId: String,
        state: ExternalExecutionState,
        durationMs: Long = 0,
        error: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val store = store(context)
        ledger.update(executionId, state, nowMs, durationMs, error)
        store.save(ledger.snapshot())
    }

    fun get(context: Context, executionId: String): ExternalExecutionRecord? {
        store(context)
        return ledger.get(executionId)
    }

    fun snapshot(context: Context): List<ExternalExecutionRecord> {
        store(context)
        return ledger.snapshot()
    }

    /**
     * Resolves executions that were accepted or in flight when the process died. They can never
     * complete, and leaving them non-terminal would make a caller poll forever.
     */
    fun failInterrupted(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val store = store(context)
        val failed = ledger.failStaleNonTerminal(nowMs, INTERRUPTED_REASON)
        if (failed.isNotEmpty()) store.save(ledger.snapshot())
    }

    const val INTERRUPTED_REASON = "The engine restarted before this run finished."
}
