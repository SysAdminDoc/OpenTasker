package com.opentasker.core.scripting

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShizukuScriptPolicyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val script = "echo hello\n"
    private val scriptHash = TermuxScriptPolicy.hash(script.toByteArray())

    // --- Paths ---

    @Test
    fun onlyAnAbsolutePathWithNoTraversalIsAccepted() {
        assertEquals("/data/local/tmp/a.sh", ShizukuScriptPolicy.normalizePath("  /data/local/tmp/a.sh "))
        assertEquals("/sdcard/My Scripts/a.sh", ShizukuScriptPolicy.normalizePath("/sdcard/My Scripts/a.sh"))
        listOf(
            "",
            "/",
            "a.sh",
            "./a.sh",
            "~/a.sh",
            "/data/local/tmp/../../etc/hosts",
            "/data/local/tmp/./a.sh",
            "/data//local/a.sh",
            "/data/local/tmp/",
            "/data/local/\nsneaky.sh",
            "/" + "a".repeat(TermuxScriptPolicy.MAX_PATH_BYTES + 1),
        ).forEach { path ->
            assertNull("'$path' must be refused", ShizukuScriptPolicy.normalizePath(path))
        }
    }

    // --- Content ---

    @Test
    fun verifiedContentDecodesToExactlyTheBytesThatWereHashed() {
        assertEquals(
            ShizukuScriptVerdict.Approved(script),
            ShizukuScriptPolicy.verify(script.toByteArray(), scriptHash),
        )
    }

    @Test
    fun aChangedScriptReportsTheHashItActuallyHas() {
        val changed = "rm -rf /sdcard\n".toByteArray()

        assertEquals(
            ShizukuScriptVerdict.Mismatch(TermuxScriptPolicy.hash(changed)),
            ShizukuScriptPolicy.verify(changed, scriptHash),
        )
    }

    @Test
    fun aScriptOverTheLimitIsRefusedBeforeHashing() {
        val tooBig = ByteArray(ShizukuScriptPolicy.MAX_SCRIPT_BYTES + 1) { 'a'.code.toByte() }

        assertEquals(ShizukuScriptVerdict.TooLarge, ShizukuScriptPolicy.verify(tooBig, TermuxScriptPolicy.hash(tooBig)))
    }

    @Test
    fun bytesThatWouldNotSurviveDecodingAreRefusedEvenWithTheRightHash() {
        val invalidUtf8 = byteArrayOf('e'.code.toByte(), 0xC3.toByte(), 'x'.code.toByte())
        val embeddedNul = byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())

        assertEquals(
            ShizukuScriptVerdict.NotText,
            ShizukuScriptPolicy.verify(invalidUtf8, TermuxScriptPolicy.hash(invalidUtf8)),
        )
        assertEquals(
            ShizukuScriptVerdict.NotText,
            ShizukuScriptPolicy.verify(embeddedNul, TermuxScriptPolicy.hash(embeddedNul)),
        )
    }

    @Test
    fun argumentsStaySeparateFromTheProgramSoTheyCannotBecomeCode() {
        val hostile = "; rm -rf /sdcard"

        val command = ShizukuScriptPolicy.command(script, "/data/local/tmp/a.sh", listOf(hostile, "two"))

        assertEquals(
            listOf("/system/bin/sh", "-c", script, "/data/local/tmp/a.sh", hostile, "two"),
            command,
        )
    }

    // --- Output capture ---

    @Test
    fun captureKeepsTheFirstBytesAndKeepsDrainingAfterTheCap() {
        val total = 1024 * 1024
        val stream = CountingStream(total)
        val capture = BoundedCapture(cap = 16)

        capture.drain(stream)

        assertEquals("A chatty script must not stall on a full pipe", total, stream.served)
        assertEquals(16, capture.text().length)
        assertEquals("The real size is still reported", total, capture.totalBytes())
    }

    // --- The privileged side ---

    @Test
    fun theExecutorRunsTheBytesItHashedAndReportsOutputAndExitCode() {
        val file = scriptFile(script)
        val started = mutableListOf<List<String>>()

        val reply = ShizukuScriptExecutor.run(file.absolutePath.unixPath(), scriptHash, listOf("arg"), 5_000) { command ->
            started += command
            FakeProcess(stdout = "out".byteInputStream(), stderr = "err".byteInputStream(), exit = 3)
        }

        assertEquals(ShizukuScriptPolicy.STATUS_RAN, reply.status)
        assertEquals("A script may legitimately exit non-zero", 3, reply.exitCode)
        assertEquals("out", reply.stdout)
        assertEquals("err", reply.stderr)
        assertEquals(3, reply.stdoutBytes)
        assertEquals(3, reply.stderrBytes)
        assertEquals(script, started.single()[2])
        assertEquals("arg", started.single().last())
    }

    @Test
    fun aScriptReplacedSinceApprovalNeverStarts() {
        val replaced = "rm -rf /sdcard\n"
        val file = scriptFile(replaced)

        val reply = ShizukuScriptExecutor.run(file.absolutePath.unixPath(), scriptHash, emptyList(), 5_000) {
            fail("A script that fails its hash check must not start")
            throw AssertionError()
        }

        assertEquals(ShizukuScriptPolicy.STATUS_HASH_MISMATCH, reply.status)
        assertEquals(TermuxScriptPolicy.hash(replaced.toByteArray()), reply.stderr)
    }

    @Test
    fun aMissingFileOrADirectoryIsUnreadableNotACrash() {
        listOf(
            File(folder.root, "missing.sh").absolutePath.unixPath(),
            folder.root.absolutePath.unixPath(),
        ).forEach { path ->
            val reply = ShizukuScriptExecutor.run(path, scriptHash, emptyList(), 5_000) {
                fail("Nothing readable, so nothing to start")
                throw AssertionError()
            }
            assertEquals(path, ShizukuScriptPolicy.STATUS_UNREADABLE, reply.status)
        }
    }

    @Test
    fun theServiceRefusesWhatTheAppShouldHaveRefusedItself() {
        fun refusal(path: String, hash: String, timeout: Long, arguments: List<String> = emptyList()): Int =
            ShizukuScriptExecutor.run(path, hash, arguments, timeout) {
                fail("A refused request must not start")
                throw AssertionError()
            }.status

        val bad = ShizukuScriptPolicy.STATUS_INVALID_REQUEST
        assertEquals(bad, refusal("/a/../b.sh", scriptHash, 5_000))
        assertEquals(bad, refusal("/a/b.sh", "short", 5_000))
        assertEquals(bad, refusal("/a/b.sh", scriptHash, 1))
        assertEquals(bad, refusal("/a/b.sh", scriptHash, 120_001))
        assertEquals(
            bad,
            refusal("/a/b.sh", scriptHash, 5_000, List(TermuxScriptPolicy.MAX_ARGUMENTS + 1) { "x" }),
        )
    }

    @Test
    fun aScriptThatOutlivesItsTimeoutIsDestroyedAndKeepsItsPartialOutput() {
        val file = scriptFile(script)
        val process = FakeProcess(stdout = "partial".byteInputStream(), stderr = emptyStream(), exit = null)

        val reply = ShizukuScriptExecutor.run(file.absolutePath.unixPath(), scriptHash, emptyList(), 1_000) { process }

        assertEquals(ShizukuScriptPolicy.STATUS_TIMED_OUT, reply.status)
        assertTrue(process.destroyed)
        assertEquals("partial", reply.stdout)
    }

    @Test
    fun aBackgroundChildHoldingThePipeOpenDoesNotLoseTheOutputOrHangTheCall() {
        val file = scriptFile(script)
        val release = CountDownLatch(1)
        val heldOpen = HeldOpenStream("started\n".toByteArray(), release)
        try {
            val reply = ShizukuScriptExecutor.run(file.absolutePath.unixPath(), scriptHash, emptyList(), 5_000) {
                FakeProcess(stdout = heldOpen, stderr = emptyStream(), exit = 0)
            }

            assertEquals(ShizukuScriptPolicy.STATUS_RAN, reply.status)
            assertEquals(0, reply.exitCode)
            assertEquals("started\n", reply.stdout)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aScriptThatReadsStdinGetsEndOfFile() {
        val file = scriptFile(script)
        val process = FakeProcess(stdout = emptyStream(), stderr = emptyStream(), exit = 0)

        ShizukuScriptExecutor.run(file.absolutePath.unixPath(), scriptHash, emptyList(), 5_000) { process }

        assertTrue(process.stdinClosed)
    }

    // --- The app side ---

    @Test
    fun nothingReachesTheServiceWhenShizukuIsNotReadyOrTheKillSwitchIsOn() {
        val result = coordinator().execute(
            ready = false,
            invocation = invocation(),
            approvedHashFor = { scriptHash },
            runner = { failRunner() },
        )

        assertRejected(ShizukuScriptRejectionReason.NOT_READY, result)
    }

    @Test
    fun aBadRequestIsRefusedBeforeTheAllowlistOrTheService() {
        assertRejected(
            ShizukuScriptRejectionReason.INVALID_INPUT,
            run(invocation(path = "relative.sh")),
        )
        assertRejected(
            ShizukuScriptRejectionReason.INVALID_INPUT,
            run(invocation(timeoutMs = TermuxScriptPolicy.MAX_TIMEOUT_MS + 1)),
        )
        assertRejected(
            ShizukuScriptRejectionReason.INVALID_INPUT,
            run(invocation(arguments = "unclosed \"quote")),
        )
    }

    @Test
    fun aScriptThatIsNotApprovedNeverReachesTheService() {
        val result = coordinator().execute(true, invocation(), { null }, { failRunner() })

        assertRejected(ShizukuScriptRejectionReason.NOT_APPROVED, result)
    }

    @Test
    fun aSecondRunWithinTheSameSecondIsRateLimited() {
        val coordinator = coordinator()
        val ran = ShizukuScriptReply(status = ShizukuScriptPolicy.STATUS_RAN, exitCode = 0)

        val first = coordinator.execute(true, invocation(), { scriptHash }, { ran })
        val second = coordinator.execute(true, invocation(), { scriptHash }, { failRunner() })

        assertTrue(first is ShizukuScriptExecutionResult.Completed)
        assertRejected(ShizukuScriptRejectionReason.RATE_LIMITED, second)
    }

    @Test
    fun theServiceReceivesTheParsedArgumentsAndTheApprovedHash() {
        val received = mutableListOf<ShizukuScriptRequest>()

        coordinator().execute(
            ready = true,
            invocation = invocation(arguments = "one \"two words\" 'three'"),
            approvedHashFor = { "  ${scriptHash.uppercase()} " },
            runner = { request ->
                received += request
                ShizukuScriptReply(status = ShizukuScriptPolicy.STATUS_RAN, exitCode = 0)
            },
        )

        val request = received.single()
        assertEquals("/data/local/tmp/a.sh", request.path)
        assertEquals(scriptHash, request.expectedSha256)
        assertEquals(listOf("one", "two words", "three"), request.arguments)
    }

    @Test
    fun aMismatchNamesBothHashesSoTheUserCanCompareThem() {
        val actual = "b".repeat(64)

        val result = coordinator().execute(true, invocation(), { scriptHash }, {
            ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_HASH_MISMATCH, actual)
        })

        assertRejected(ShizukuScriptRejectionReason.HASH_MISMATCH, result)
        val message = (result as ShizukuScriptExecutionResult.Rejected).message
        assertTrue(message, scriptHash in message && actual in message)
    }

    @Test
    fun anExitCodeThatLooksLikeARefusalIsStillACompletedRun() {
        listOf(124, 125, 126, 127).forEach { code ->
            val result = coordinator().execute(true, invocation(), { scriptHash }, {
                ShizukuScriptReply(status = ShizukuScriptPolicy.STATUS_RAN, exitCode = code)
            })

            assertEquals(code, (result as ShizukuScriptExecutionResult.Completed).reply.exitCode)
        }
    }

    @Test
    fun everyServiceRefusalHasItsOwnReason() {
        val expected = mapOf(
            ShizukuScriptPolicy.STATUS_UNREADABLE to ShizukuScriptRejectionReason.UNREADABLE,
            ShizukuScriptPolicy.STATUS_TOO_LARGE to ShizukuScriptRejectionReason.TOO_LARGE,
            ShizukuScriptPolicy.STATUS_NOT_TEXT to ShizukuScriptRejectionReason.NOT_TEXT,
            ShizukuScriptPolicy.STATUS_TIMED_OUT to ShizukuScriptRejectionReason.TIMED_OUT,
            ShizukuScriptPolicy.STATUS_INVALID_REQUEST to ShizukuScriptRejectionReason.INVALID_INPUT,
            ShizukuScriptPolicy.STATUS_FAILED to ShizukuScriptRejectionReason.SERVICE_FAILED,
        )

        expected.forEach { (status, reason) ->
            val result = coordinator().execute(true, invocation(), { scriptHash }, {
                ShizukuScriptReply.refused(status)
            })
            assertRejected(reason, result)
            assertFalse((result as ShizukuScriptExecutionResult.Rejected).message.isBlank())
        }
    }

    // --- Helpers ---

    private fun coordinator() = ShizukuScriptCoordinator(TermuxDispatchLimiter())

    private fun run(invocation: ShizukuScriptInvocation) =
        coordinator().execute(true, invocation, { scriptHash }, { failRunner() })

    private fun invocation(
        path: String = "/data/local/tmp/a.sh",
        arguments: String? = null,
        timeoutMs: Long = TermuxScriptPolicy.DEFAULT_TIMEOUT_MS,
    ) = ShizukuScriptInvocation(path, arguments, timeoutMs)

    private fun failRunner(): ShizukuScriptReply {
        fail("This request must not reach the Shizuku service")
        throw AssertionError()
    }

    private fun assertRejected(reason: ShizukuScriptRejectionReason, result: ShizukuScriptExecutionResult) {
        assertTrue("Expected a rejection, got $result", result is ShizukuScriptExecutionResult.Rejected)
        assertEquals(reason, (result as ShizukuScriptExecutionResult.Rejected).reason)
    }

    private fun scriptFile(content: String): File =
        folder.newFile().apply { writeBytes(content.toByteArray()) }

    /** The path rules are for the device's filesystem, so Windows test paths need their drive gone. */
    private fun String.unixPath(): String {
        val forward = replace(File.separatorChar, '/')
        return if (forward.length > 2 && forward[1] == ':') forward.substring(2) else forward
    }

    private fun emptyStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    private class CountingStream(private val total: Int) : InputStream() {
        var served = 0
        override fun read(): Int = if (served >= total) -1 else 'x'.code.also { served++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= total) return -1
            val count = minOf(len, total - served)
            b.fill('x'.code.toByte(), off, off + count)
            served += count
            return count
        }
    }

    /** Hands over its bytes and then blocks, like a pipe a background child still holds open. */
    private class HeldOpenStream(private val first: ByteArray, private val release: CountDownLatch) : InputStream() {
        private var delivered = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!delivered) {
                delivered = true
                first.copyInto(b, off)
                return first.size
            }
            release.await()
            return -1
        }
    }

    private class FakeProcess(
        private val stdout: InputStream,
        private val stderr: InputStream,
        private val exit: Int?,
    ) : Process() {
        var destroyed = false
        var stdinClosed = false

        override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                stdinClosed = true
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = exit ?: throw UnsupportedOperationException()
        override fun exitValue(): Int = exit ?: throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
        }
    }
}
