package com.opentasker.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExternalExecutionLedgerTest {

    @Test
    fun anAcceptedExecutionIsNotYetTerminal() {
        val ledger = ExternalExecutionLedger()
        val record = ledger.accept("e1", taskId = 7, taskName = "Morning", nowMs = 100)

        assertEquals(ExternalExecutionState.ACCEPTED, record.state)
        assertFalse("acceptance must never read as completion", record.state.isTerminal)
        assertEquals(record, ledger.get("e1"))
    }

    @Test
    fun anExecutionAdvancesToATerminalStateAndStaysThere() {
        val ledger = ExternalExecutionLedger()
        ledger.accept("e1", 7, "Morning", nowMs = 100)
        ledger.update("e1", ExternalExecutionState.RUNNING, nowMs = 110)
        ledger.update("e1", ExternalExecutionState.SUCCEEDED, nowMs = 200, durationMs = 90)

        // A late duplicate notification must not walk a finished run backwards.
        ledger.update("e1", ExternalExecutionState.RUNNING, nowMs = 300)

        val record = requireNotNull(ledger.get("e1"))
        assertEquals(ExternalExecutionState.SUCCEEDED, record.state)
        assertTrue(record.state.isTerminal)
        assertEquals(90, record.durationMs)
    }

    @Test
    fun updatingAnUnknownExecutionIsANoOp() {
        val ledger = ExternalExecutionLedger()
        assertNull(ledger.update("nope", ExternalExecutionState.SUCCEEDED, nowMs = 1))
        assertNull(ledger.get("nope"))
    }

    @Test
    fun theLedgerIsBoundedAndEvictsOldestFirst() {
        val ledger = ExternalExecutionLedger(capacity = 3)
        repeat(5) { index -> ledger.accept("e$index", index.toLong(), "T$index", nowMs = index.toLong()) }

        assertEquals(listOf("e2", "e3", "e4"), ledger.snapshot().map { it.executionId })
        assertNull("a caller that never polls cannot pin the ledger open", ledger.get("e0"))
    }

    @Test
    fun aRunInterruptedByAProcessDeathResolvesToFailedRatherThanPollingForever() {
        val ledger = ExternalExecutionLedger()
        ledger.accept("accepted", 1, "A", nowMs = 10)
        ledger.accept("running", 2, "B", nowMs = 20)
        ledger.update("running", ExternalExecutionState.RUNNING, nowMs = 21)
        ledger.accept("done", 3, "C", nowMs = 30)
        ledger.update("done", ExternalExecutionState.SUCCEEDED, nowMs = 31)

        val failed = ledger.failStaleNonTerminal(nowMs = 99, reason = "engine restarted")

        assertEquals(listOf("accepted", "running"), failed.map { it.executionId })
        assertTrue(failed.all { it.state == ExternalExecutionState.FAILED })
        assertEquals("engine restarted", requireNotNull(ledger.get("accepted")).error)
        // A run that already finished is untouched.
        assertEquals(ExternalExecutionState.SUCCEEDED, requireNotNull(ledger.get("done")).state)
    }

    @Test
    fun restoringKeepsAcceptanceOrderAndReappliesTheCap() {
        val ledger = ExternalExecutionLedger(capacity = 2)
        ledger.restore(
            listOf(
                ExternalExecutionRecord("c", 3, "C", ExternalExecutionState.ACCEPTED, 30, 30),
                ExternalExecutionRecord("a", 1, "A", ExternalExecutionState.SUCCEEDED, 10, 11),
                ExternalExecutionRecord("b", 2, "B", ExternalExecutionState.FAILED, 20, 21),
            ),
        )
        assertEquals(listOf("b", "c"), ledger.snapshot().map { it.executionId })
    }

    @Test
    fun twoDeliveriesOfOneCommandArrivingTogetherStartOneRun() {
        repeat(200) {
            val ledger = ExternalExecutionLedger()
            val startGate = java.util.concurrent.CountDownLatch(1)
            val started = java.util.concurrent.atomic.AtomicInteger()
            val deliveries = List(4) {
                Thread {
                    startGate.await()
                    if (ledger.acceptOnce("same-id", taskId = 7, taskName = "Morning", nowMs = 100).isNew) {
                        started.incrementAndGet()
                    }
                }.apply { start() }
            }
            startGate.countDown()
            deliveries.forEach { it.join() }

            assertEquals(1, started.get())
        }
    }

    @Test
    fun aRepeatedCommandGetsTheFirstDeliverysRecordBack() {
        val ledger = ExternalExecutionLedger()
        val first = ledger.acceptOnce("e1", taskId = 7, taskName = "Morning", nowMs = 100)
        ledger.update("e1", ExternalExecutionState.RUNNING, nowMs = 110)

        val repeat = ledger.acceptOnce("e1", taskId = 9, taskName = "Other", nowMs = 500)

        assertTrue(first.isNew)
        assertFalse(repeat.isNew)
        assertEquals(7, repeat.record.taskId)
        assertEquals(ExternalExecutionState.RUNNING, repeat.record.state)
    }

    @Test
    fun runTaskNeverExecutesTheTaskInsideTheBroadcastWindow() {
        val source = listOf(
            File("src/main/java/com/opentasker/core/external/AutomationTargetReceiver.kt"),
            File("app/src/main/java/com/opentasker/core/external/AutomationTargetReceiver.kt"),
        ).first { it.exists() }.readText()

        assertFalse(
            "RUN_TASK must hand off to the service, never run the task inside goAsync()",
            "executeAndLogTask" in source,
        )
        assertTrue(
            "RUN_TASK must enqueue to the foreground service",
            "AutomationService.ACTION_RUN_EXTERNAL_TASK" in source,
        )
        assertTrue(
            "a legacy caller must get an explicit protocol response",
            "EXTRA_PROTOCOL_VERSION" in source,
        )
    }

    @Test
    fun theQueryExecutionActionIsDeclaredOnTheGuardedReceiver() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

        val receiverBlock = manifest
            .substringAfter("com.opentasker.core.external.AutomationTargetReceiver")
            .substringBefore("</receiver>")
        assertTrue(
            "QUERY_EXECUTION must be filtered on the permission-guarded receiver",
            "com.opentasker.action.QUERY_EXECUTION" in receiverBlock,
        )
        assertTrue(
            "the receiver must keep enforcing its declared filters",
            "enforceIntentFilter" in receiverBlock,
        )
    }
}
