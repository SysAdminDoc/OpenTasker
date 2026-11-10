package com.opentasker.core.scripting

import com.opentasker.core.apps.InstalledSigners
import com.opentasker.core.apps.PackageSignatureSource
import com.opentasker.core.apps.TargetSignerGuard
import com.opentasker.core.apps.TargetSignerMismatchException
import com.opentasker.core.apps.TargetSignerPin
import com.opentasker.core.apps.TargetSignerPinSource
import com.opentasker.core.apps.TargetSignerPins
import com.opentasker.core.apps.signerMismatchMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The Termux dispatch site refuses a Termux whose signing certificate changed, before RUN_COMMAND is built or started. */
class TermuxCommandBrokerSignerPinTest {
    private val termux = TermuxScriptBackend.TERMUX_PACKAGE
    private val request = TermuxCommandRequest(executable = "/data/data/com.termux/files/home/run.sh", timeoutMs = 5_000)
    private val result = TermuxCommandResult(
        stdout = "",
        stderr = "",
        exitCode = 0,
        stdoutOriginalLength = 0,
        stderrOriginalLength = 0,
        errorCode = TermuxCommandResult.ERROR_CODE_ABSENT,
    )
    private var dispatched = 0

    @Test
    fun mismatchedTermuxIsSentNothing() {
        val guard = guard(installed = "b".repeat(64))

        val thrown = assertThrows(TargetSignerMismatchException::class.java) {
            runBlocking { TermuxCommandBroker.execute(guard, request) { dispatched++; result } }
        }

        assertEquals(termux, thrown.packageName)
        assertEquals(signerMismatchMessage(termux), thrown.message)
        assertEquals("startForegroundService must never run for a refused Termux", 0, dispatched)
    }

    @Test
    fun matchingTermuxIsDispatched() {
        val guard = guard(installed = "a".repeat(64))

        val returned = runBlocking { TermuxCommandBroker.execute(guard, request) { dispatched++; result } }

        assertEquals(result, returned)
        assertEquals(1, dispatched)
    }

    private fun guard(installed: String): TargetSignerGuard {
        val pins = object : TargetSignerPins {
            private val pin = TargetSignerPin(termux, listOf("a".repeat(64)), TargetSignerPinSource.APPROVED, 1L)
            override fun get(packageName: String): TargetSignerPin? = pin.takeIf { packageName == termux }
            override fun put(pin: TargetSignerPin) = error("No pin may change here")
            override fun remove(packageName: String) = error("No pin may change here")
            override fun all(): List<TargetSignerPin> = listOf(pin)
        }
        val source = PackageSignatureSource { packageName ->
            InstalledSigners(current = listOf(installed)).takeIf { packageName == termux }
        }
        return TargetSignerGuard(source, pins)
    }
}
