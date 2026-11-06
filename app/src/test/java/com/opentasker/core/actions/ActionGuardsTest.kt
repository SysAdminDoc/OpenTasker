package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.scripting.ShizukuScriptExecutionResult
import com.opentasker.core.scripting.ShizukuScriptPolicy
import com.opentasker.core.scripting.ShizukuScriptReply
import com.opentasker.core.scripting.TermuxCommandResult
import com.opentasker.core.scripting.TermuxScriptExecutionResult
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGuardsTest {

    private fun ctx() = ActionContext(ContextWrapper(null), VariableStore())
    private fun ctx(filesDir: File, variables: VariableStore = VariableStore()) =
        ActionContext(
            object : ContextWrapper(null) {
                override fun getFilesDir(): File = filesDir
            },
            variables,
        )

    @Test
    fun httpPostRejectsOversizedBody() = runBlocking {
        val action = HttpPostAction()
        val bigBody = "x".repeat(1_048_577)
        val result = action.run(ctx(), mapOf("url" to "https://example.com", "data" to bigBody))
        assertTrue("oversized POST body should fail", result is ActionResult.Failure)
        assertTrue(
            "failure message mentions limit",
            (result as ActionResult.Failure).message.contains("KB limit")
        )
    }

    @Test
    fun httpPostRejectsOversizedLegacyBodyKey() = runBlocking {
        val action = HttpPostAction()
        val bigBody = "x".repeat(1_048_577)
        val result = action.run(ctx(), mapOf("url" to "https://example.com", "body" to bigBody))
        assertTrue("oversized legacy POST body should fail", result is ActionResult.Failure)
        assertTrue(
            "failure message mentions limit",
            (result as ActionResult.Failure).message.contains("KB limit")
        )
    }

    @Test
    fun httpPostAcceptsBodyAtLimit() = runBlocking {
        val action = HttpPostAction()
        val bodyAtLimit = "x".repeat(1_048_576)
        val result = action.run(ctx(), mapOf("url" to "https://example.com", "data" to bodyAtLimit))
        // Will fail with network error (not body-size rejection) since we have no server
        if (result is ActionResult.Failure) {
            assertTrue(
                "should not reject body at limit",
                !result.message.contains("KB limit")
            )
        }
    }

    @Test
    fun openUrlAllowedSchemesAreRestricted() {
        val allowed = OpenUrlAction.allowedSchemes()
        assertTrue("https must be allowed", "https" in allowed)
        assertTrue("http must be allowed", "http" in allowed)
        assertTrue("javascript must NOT be allowed", "javascript" !in allowed)
        assertTrue("intent must NOT be allowed", "intent" !in allowed)
        assertTrue("file must NOT be allowed", "file" !in allowed)
        assertTrue("content must NOT be allowed", "content" !in allowed)
    }

    @Test
    fun openUrlMissingUrlFails() = runBlocking {
        val action = OpenUrlAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing url should fail", result is ActionResult.Failure)
        assertEquals("missing url", (result as ActionResult.Failure).message)
    }

    @Test
    fun waitRejectsExcessiveDuration() = runBlocking {
        val action = WaitAction()
        val result = action.run(ctx(), mapOf("millis" to "1800001"))
        assertTrue("excessive wait should fail", result is ActionResult.Failure)
        assertTrue(
            "failure mentions maximum",
            (result as ActionResult.Failure).message.contains("maximum")
        )
    }

    @Test
    fun waitMissingDurationFails() = runBlocking {
        val action = WaitAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing wait duration should fail", result is ActionResult.Failure)
        assertEquals("missing millis", (result as ActionResult.Failure).message)
    }

    @Test
    fun waitRejectsMalformedDuration() = runBlocking {
        val action = WaitAction()
        val result = action.run(ctx(), mapOf("millis" to "soon"))
        assertTrue("malformed wait duration should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid millis"))
    }

    @Test
    fun waitRejectsNegativeDuration() = runBlocking {
        val action = WaitAction()
        val result = action.run(ctx(), mapOf("millis" to "-1"))
        assertTrue("negative wait duration should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("non-negative"))
    }

    @Test
    fun waitAcceptsDurationAtLimit() = runBlocking {
        val action = WaitAction()
        // 0ms wait: effectively a no-op but should succeed
        val result = action.run(ctx(), mapOf("millis" to "0"))
        assertTrue("zero wait should succeed", result is ActionResult.Success)
    }

    @Test
    fun httpGetMissingUrlFails() = runBlocking {
        val action = HttpGetAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing url should fail", result is ActionResult.Failure)
        assertEquals("missing url", (result as ActionResult.Failure).message)
    }

    @Test
    fun httpPostMissingUrlFails() = runBlocking {
        val action = HttpPostAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing url should fail", result is ActionResult.Failure)
        assertEquals("missing url", (result as ActionResult.Failure).message)
    }

    @Test
    fun downloadMissingUrlFails() = runBlocking {
        val action = DownloadAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing url should fail", result is ActionResult.Failure)
        assertEquals("missing url", (result as ActionResult.Failure).message)
    }

    @Test
    fun downloadMissingPathFails() = runBlocking {
        val action = DownloadAction()
        val result = action.run(ctx(), mapOf("url" to "https://example.com/file"))
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    @Test
    fun downloadRejectsInvalidMaxBytesBeforeReadingNetwork() = runBlocking {
        val filesDir = Files.createTempDirectory("opentasker-download-max").toFile()
        try {
            val result = DownloadAction().run(
                ctx(filesDir),
                mapOf(
                    "url" to "https://example.com/file",
                    "path" to "out.txt",
                    "max_bytes" to "-1",
                ),
            )

            assertTrue("invalid max_bytes should fail", result is ActionResult.Failure)
            assertEquals("max_bytes must be a positive integer", (result as ActionResult.Failure).message)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun downloadKeepsExistingFileWhenResponseExceedsLimit() = runBlocking {
        val server = fixedResponseServer("oversized")
        val filesDir = Files.createTempDirectory("opentasker-download-atomic").toFile()
        try {
            server.start()
            val destination = File(filesDir, "downloads/existing.txt").apply {
                parentFile?.mkdirs()
                writeText("original")
            }

            val result = DownloadAction().run(
                ctx(filesDir),
                mapOf(
                    "url" to "http://127.0.0.1:${server.address.port}/file",
                    "allow_http" to "true",
                    "path" to "existing.txt",
                    "max_bytes" to "4",
                ),
            )

            assertTrue("oversized download should fail", result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).message.contains("4 byte limit"))
            assertEquals("original", destination.readText())
            assertTrue(
                "partial temp files should be removed",
                destination.parentFile?.listFiles()?.none { it.name.endsWith(".part") } ?: true,
            )
        } finally {
            server.stop(0)
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun pingRejectsInvalidHost() = runBlocking {
        val action = PingAction()
        val result = action.run(ctx(), mapOf("host" to "evil; rm -rf /"))
        assertTrue("malicious host should fail", result is ActionResult.Failure)
        assertEquals("invalid host", (result as ActionResult.Failure).message)
    }

    @Test
    fun pingMissingHostFails() = runBlocking {
        val action = PingAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing host should fail", result is ActionResult.Failure)
        assertEquals("missing host", (result as ActionResult.Failure).message)
    }

    @Test
    fun httpPolicyBlocksNonHttpSchemes() = runBlocking {
        val action = HttpGetAction()
        val result = action.run(ctx(), mapOf("url" to "ftp://example.com"))
        assertTrue("ftp scheme should fail", result is ActionResult.Failure)
        assertTrue(
            "failure mentions unsupported protocol",
            (result as ActionResult.Failure).message.contains("unsupported protocol")
        )
    }

    @Test
    fun wolParsesValidMac() {
        val mac = WakeOnLanAction.parseMac("AA:BB:CC:DD:EE:FF")
        assertTrue("valid MAC should parse", mac != null)
        assertEquals(6, mac!!.size)
    }

    @Test
    fun wolRejectsInvalidMac() {
        assertTrue("short MAC should be rejected", WakeOnLanAction.parseMac("AA:BB") == null)
        assertTrue("non-hex should be rejected", WakeOnLanAction.parseMac("GG:HH:II:JJ:KK:LL") == null)
        assertTrue("empty should be rejected", WakeOnLanAction.parseMac("") == null)
        assertTrue("mixed separators should be rejected", WakeOnLanAction.parseMac("AA:BB-CC:DD-EE:FF") == null)
    }

    @Test
    fun wolAcceptsHyphenSeparatedMac() {
        val mac = WakeOnLanAction.parseMac("AA-BB-CC-DD-EE-FF")
        assertTrue("hyphen-separated MAC should parse", mac != null)
        assertEquals(6, mac!!.size)
    }

    @Test
    fun wolBuildsMagicPacket() {
        val mac = WakeOnLanAction.parseMac("AA:BB:CC:DD:EE:FF")!!
        val packet = WakeOnLanAction.buildMagicPacket(mac)
        assertEquals(102, packet.size)
        // First 6 bytes are 0xFF
        for (i in 0..5) assertEquals(0xFF.toByte(), packet[i])
        // Next 16 repetitions of the MAC
        for (rep in 0..15) {
            for (b in 0..5) {
                assertEquals(mac[b], packet[6 + rep * 6 + b])
            }
        }
    }

    @Test
    fun wolMissingMacFails() = runBlocking {
        val action = WakeOnLanAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing mac should fail", result is ActionResult.Failure)
        assertEquals("missing mac", (result as ActionResult.Failure).message)
    }

    @Test
    fun wolInvalidMacFails() = runBlocking {
        val action = WakeOnLanAction()
        val result = action.run(ctx(), mapOf("mac" to "not-a-mac"))
        assertTrue("invalid mac should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid MAC"))
    }

    @Test
    fun httpPolicyBlocksPlaintextWithoutOptIn() = runBlocking {
        val action = HttpGetAction()
        val result = action.run(ctx(), mapOf("url" to "http://192.168.1.1/api"))
        assertTrue("plaintext HTTP should fail without opt-in", result is ActionResult.Failure)
        assertTrue(
            "failure mentions allow_http",
            (result as ActionResult.Failure).message.contains("allow_http")
        )
    }

    @Test
    fun urlLocalNetworkDetectionCoversHttpsPrivateTargets() {
        assertTrue(urlTargetsLocalNetwork(URL("https://127.0.0.1/api")))
        assertTrue(urlTargetsLocalNetwork(URL("https://192.168.1.10/api")))
        assertTrue(urlTargetsLocalNetwork(URL("https://169.254.1.2/api")))
    }

    @Test
    fun urlLocalNetworkDetectionLeavesPublicHttpsAlone() {
        assertTrue(!urlTargetsLocalNetwork(URL("https://8.8.8.8/dns-query")))
    }

    @Test
    fun torchToggleNeedsKnownCurrentState() {
        assertEquals(true, TorchAction.targetStateFor("on", null))
        assertEquals(false, TorchAction.targetStateFor("off", null))
        assertEquals(false, TorchAction.targetStateFor("toggle", true))
        assertEquals(true, TorchAction.targetStateFor("toggle", false))
        assertEquals(null, TorchAction.targetStateFor("toggle", null))
        assertEquals(null, TorchAction.targetStateFor("invalid", true))
    }

    // --- SayAction guards ---

    @Test
    fun sayMissingTextFails() = runBlocking {
        val action = SayAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing text should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("missing text"))
    }

    @Test
    fun sayRejectsOversizedText() = runBlocking {
        val action = SayAction()
        val bigText = "x".repeat(4001)
        val result = action.run(ctx(), mapOf("text" to bigText))
        assertTrue("oversized text should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("character limit"))
    }

    // --- OpenUrl expanded scheme validation ---

    @Test
    fun openUrlAllowsTelAndMailtoSchemes() {
        val allowed = OpenUrlAction.allowedSchemes()
        assertTrue("tel must be allowed", "tel" in allowed)
        assertTrue("mailto must be allowed", "mailto" in allowed)
        assertTrue("geo must be allowed", "geo" in allowed)
    }

    @Test
    fun openUrlBlocksDataAndBlobSchemes() {
        val allowed = OpenUrlAction.allowedSchemes()
        assertTrue("data must NOT be allowed", "data" !in allowed)
        assertTrue("blob must NOT be allowed", "blob" !in allowed)
    }

    // --- ReadFileAction guards ---

    @Test
    fun readFileMissingPathFails() = runBlocking {
        val action = ReadFileAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    // --- WriteFileAction guards ---

    @Test
    fun writeFileMissingPathFails() = runBlocking {
        val action = WriteFileAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    @Test
    fun writeFileRejectsOversizedContentBeforeCreatingFile() = runBlocking {
        val filesDir = Files.createTempDirectory("opentasker-file-write-limit").toFile()
        try {
            val result = WriteFileAction().run(
                ctx(filesDir),
                mapOf("path" to "big.txt", "text" to "x".repeat(1_048_577)),
            )

            assertTrue("oversized write should fail", result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).message.contains("write limit"))
            assertTrue(!File(filesDir, "user_files/big.txt").exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    // --- PlaySoundAction guards ---

    @Test
    fun playSoundMissingPathFails() = runBlocking {
        val action = PlaySoundAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    // --- LaunchAppAction guards ---

    @Test
    fun launchAppMissingPackageFails() = runBlocking {
        val action = LaunchAppAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing package should fail", result is ActionResult.Failure)
    }

    // --- SetVariable guards ---

    @Test
    fun setVariableMissingNameFails() = runBlocking {
        val action = SetVariableAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing name should fail", result is ActionResult.Failure)
        assertEquals("missing name", (result as ActionResult.Failure).message)
    }

    // --- TermuxScriptAction fail-closed ---

    @Test
    fun termuxScriptFailsClosed() = runBlocking {
        val action = TermuxScriptAction()
        val result = action.run(ctx(), mapOf("executable" to "/bin/sh"))
        assertTrue("Termux script should fail closed without Termux installed", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("not ready"))
    }

    @Test
    fun termuxScriptMapsBoundedResultsWithoutLoggingTheirContents() {
        val variables = VariableStore()
        val logs = mutableListOf<String>()
        val context = ActionContext(ContextWrapper(null), variables, logger = logs::add)
        val result = TermuxScriptAction().completeExecution(
            context,
            "%script",
            TermuxScriptExecutionResult.Completed(
                command = TermuxCommandResult(
                    stdout = "private stdout",
                    stderr = "private stderr",
                    exitCode = 0,
                    stdoutOriginalLength = 14,
                    stderrOriginalLength = 14,
                    errorCode = 0,
                ),
                approvedHash = "a".repeat(64),
            ),
        )

        assertEquals(ActionResult.Success, result)
        assertEquals("private stdout", variables.get("%script_stdout"))
        assertEquals("private stderr", variables.get("%script_stderr"))
        assertEquals("0", variables.get("%script_exit_code"))
        assertTrue(logs.single().contains("stdout=<redacted:14B>"))
        assertTrue("stdout content leaked into logs", logs.none { "private stdout" in it })
        assertTrue("stderr content leaked into logs", logs.none { "private stderr" in it })
    }

    @Test
    fun termuxScriptTreatsTheResultOkErrorCodeAsASuccessfulRun() {
        val variables = VariableStore()
        val context = ActionContext(ContextWrapper(null), variables, logger = {})
        val completed = TermuxScriptExecutionResult.Completed(
            command = TermuxCommandResult(
                stdout = "",
                stderr = "",
                exitCode = 0,
                stdoutOriginalLength = 0,
                stderrOriginalLength = 0,
                errorCode = TermuxCommandResult.ERROR_CODE_OK,
            ),
            approvedHash = "a".repeat(64),
        )

        assertEquals(ActionResult.Success, TermuxScriptAction().completeExecution(context, null, completed))

        val failed = completed.copy(command = completed.command.copy(errorCode = 150))
        val result = TermuxScriptAction().completeExecution(context, null, failed)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("could not execute"))
    }

    // --- ShizukuScriptAction fail-closed ---

    @Test
    fun shizukuScriptNeedsAPathAndFailsClosedUntilShizukuIsReady() = runBlocking {
        val missing = ShizukuScriptAction().run(ctx(), emptyMap())
        assertTrue((missing as ActionResult.Failure).message.contains("path"))

        val result = ShizukuScriptAction().run(ctx(), mapOf("path" to "/data/local/tmp/a.sh"))
        assertTrue("A script must not run without Shizuku", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("not ready"))
    }

    @Test
    fun shizukuScriptMapsBoundedResultsWithoutLoggingTheirContents() {
        val variables = VariableStore()
        val logs = mutableListOf<String>()
        val context = ActionContext(ContextWrapper(null), variables, logger = logs::add)
        val result = ShizukuScriptAction().completeExecution(
            context,
            "%script",
            ShizukuScriptExecutionResult.Completed(
                reply = ShizukuScriptReply(
                    status = ShizukuScriptPolicy.STATUS_RAN,
                    exitCode = 0,
                    stdout = "private stdout",
                    stderr = "private stderr",
                    stdoutBytes = 90_000,
                    stderrBytes = 14,
                ),
                approvedHash = "a".repeat(64),
            ),
        )

        assertEquals(ActionResult.Success, result)
        assertEquals("private stdout", variables.get("%script_stdout"))
        assertEquals("private stderr", variables.get("%script_stderr"))
        assertEquals("0", variables.get("%script_exit_code"))
        assertEquals("The real size is reported even past the capture cap", "90000", variables.get("%script_stdout_length"))
        assertTrue(logs.single().contains("stdout=<redacted:90000B>"))
        assertTrue("stdout content leaked into logs", logs.none { "private stdout" in it })
        assertTrue("stderr content leaked into logs", logs.none { "private stderr" in it })
    }

    @Test
    fun shizukuScriptWithANonZeroExitFailsButKeepsItsOutput() {
        val variables = VariableStore()
        val context = ActionContext(ContextWrapper(null), variables, logger = {})
        val result = ShizukuScriptAction().completeExecution(
            context,
            "%script",
            ShizukuScriptExecutionResult.Completed(
                reply = ShizukuScriptReply(status = ShizukuScriptPolicy.STATUS_RAN, exitCode = 2, stderr = "nope"),
                approvedHash = "a".repeat(64),
            ),
        )

        assertTrue((result as ActionResult.Failure).message.contains("code 2"))
        assertEquals("2", variables.get("%script_exit_code"))
        assertEquals("nope", variables.get("%script_stderr"))
    }

    // --- VolumeAction guards ---

    @Test
    fun volumeMissingLevelFails() = runBlocking {
        val action = VolumeAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing level should fail", result is ActionResult.Failure)
        assertEquals("missing level", (result as ActionResult.Failure).message)
    }

    @Test
    fun vibrateMissingDurationFails() = runBlocking {
        val action = VibrateAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing vibration duration should fail", result is ActionResult.Failure)
        assertEquals("missing millis", (result as ActionResult.Failure).message)
    }

    @Test
    fun vibrateRejectsMalformedDuration() = runBlocking {
        val action = VibrateAction()
        val result = action.run(ctx(), mapOf("millis" to "long"))
        assertTrue("malformed vibration duration should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid millis"))
    }

    @Test
    fun vibrateRejectsOutOfRangeDuration() = runBlocking {
        val action = VibrateAction()
        val result = action.run(ctx(), mapOf("millis" to "0"))
        assertTrue("out-of-range vibration duration should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("between"))
    }

    // --- LaunchIntentAction guards ---

    @Test
    fun launchIntentMissingPackageFails() = runBlocking {
        val action = LaunchIntentAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing package should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("package"))
    }

    // --- LogAction always succeeds ---

    @Test
    fun logActionSucceedsWithEmptyMessage() = runBlocking {
        val action = LogAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("log with no message should succeed", result is ActionResult.Success)
    }

    // --- TaskerUnsupportedAction fail-closed ---

    @Test
    fun taskerUnsupportedActionFailsClosed() = runBlocking {
        val action = TaskerUnsupportedAction()
        val result = action.run(ctx(), mapOf("taskerCode" to "999"))
        assertTrue("unsupported action should fail", result is ActionResult.Failure)
    }

    // --- File action guards ---

    @Test
    fun appendFileMissingPathFails() = runBlocking {
        val action = AppendFileAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    @Test
    fun appendFileRejectsGrowthBeyondLimitAndPreservesContent() = runBlocking {
        val filesDir = Files.createTempDirectory("opentasker-file-append-limit").toFile()
        try {
            val file = File(filesDir, "user_files/log.txt").apply {
                parentFile?.mkdirs()
                writeText("x".repeat(1_048_575))
            }

            val result = AppendFileAction().run(
                ctx(filesDir),
                mapOf("path" to "log.txt", "text" to "yy"),
            )

            assertTrue("oversized append should fail", result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).message.contains("file limit"))
            assertEquals(1_048_575, file.length())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun deleteFileMissingPathFails() = runBlocking {
        val action = DeleteFileAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    @Test
    fun listFilesMissingPathFails() = runBlocking {
        val action = ListFilesAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    @Test
    fun listFilesAppliesFilenamePatternDeterministically() = runBlocking {
        val filesDir = Files.createTempDirectory("opentasker-file-list").toFile()
        try {
            val userFiles = File(filesDir, "user_files/reports").apply { mkdirs() }
            File(userFiles, "zeta.log").writeText("skip")
            File(userFiles, "alpha.txt").writeText("one")
            File(userFiles, "Beta.txt").writeText("two")
            val variables = VariableStore()

            val result = ListFilesAction().run(
                ctx(filesDir, variables),
                mapOf("path" to "reports", "pattern" to "*.txt", "var" to "matches"),
            )

            assertTrue("patterned list should succeed", result is ActionResult.Success)
            assertEquals("alpha.txt\nBeta.txt", variables.get("matches"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun listFilesRejectsPathLikePattern() = runBlocking {
        val filesDir = Files.createTempDirectory("opentasker-file-list-pattern").toFile()
        try {
            File(filesDir, "user_files/reports").mkdirs()

            val result = ListFilesAction().run(
                ctx(filesDir),
                mapOf("path" to "reports", "pattern" to "../*.txt"),
            )

            assertTrue("path-like pattern should fail", result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).message.contains("pattern"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    // --- Settings honest-failure guards ---

    @Test
    fun brightnessMissingLevelFails() = runBlocking {
        val action = BrightnessAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing brightness should fail", result is ActionResult.Failure)
        assertEquals("missing brightness", (result as ActionResult.Failure).message)
    }

    @Test
    fun screenTimeoutMissingDurationFails() = runBlocking {
        val action = ScreenTimeoutAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing screen timeout should fail", result is ActionResult.Failure)
        assertEquals("missing millis", (result as ActionResult.Failure).message)
    }

    @Test
    fun screenTimeoutRejectsMalformedDuration() = runBlocking {
        val action = ScreenTimeoutAction()
        val result = action.run(ctx(), mapOf("millis" to "later"))
        assertTrue("malformed screen timeout should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid millis"))
    }

    @Test
    fun screenTimeoutRejectsOutOfRangeDuration() = runBlocking {
        val action = ScreenTimeoutAction()
        val result = action.run(ctx(), mapOf("millis" to "-1"))
        assertTrue("out-of-range screen timeout should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("between"))
    }

    @Test
    fun airplaneModeFailsClosedWithoutShizuku() = runBlocking {
        val action = AirplaneModeAction()
        val result = action.run(ctx(), mapOf("state" to "on"))
        assertTrue("airplane mode should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("Shizuku"))
    }

    @Test
    fun mobileDataFailsClosedWithoutShizuku() = runBlocking {
        val action = MobileDataAction()
        val result = action.run(ctx(), mapOf("state" to "on"))
        assertTrue("mobile data should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("Shizuku"))
    }

    @Test
    fun tileStateMissingStateFails() = runBlocking {
        val action = TileStateAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing state should fail", result is ActionResult.Failure)
        assertEquals("missing state argument", (result as ActionResult.Failure).message)
    }

    @Test
    fun tileStateInvalidStateFails() = runBlocking {
        val action = TileStateAction()
        val result = action.run(ctx(), mapOf("state" to "maybe"))
        assertTrue("invalid tile state should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid state"))
    }

    // --- App honest-failure guards ---

    @Test
    fun killAppAlwaysFailsHonestly() = runBlocking {
        val action = KillAppAction()
        val result = action.run(ctx(), mapOf("package" to "com.example"))
        assertTrue("kill app should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("not supported"))
    }

    // --- Persist variable action ---

    @Test
    fun persistVariableMissingNameFails() = runBlocking {
        val action = PersistVariableAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing name should fail", result is ActionResult.Failure)
        assertEquals("missing name", (result as ActionResult.Failure).message)
    }

    @Test
    fun persistVariableUnsetSourceFails() = runBlocking {
        val action = PersistVariableAction()
        val result = action.run(ctx(), mapOf("name" to "unset_var"))
        assertTrue("unset variable should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("not set"))
    }

    @Test
    fun persistVariableCopiesLocalToGlobal() = runBlocking {
        val variables = VariableStore()
        variables.pushScope()
        variables.set("counter", "42")
        val context = ActionContext(ContextWrapper(null), variables)
        val action = PersistVariableAction()
        val result = action.run(context, mapOf("name" to "counter"))
        assertTrue("persist should succeed", result is ActionResult.Success)
        assertEquals("42", variables.get("Counter"))
    }

    @Test
    fun persistVariableUsesExplicitGlobalName() = runBlocking {
        val variables = VariableStore()
        variables.pushScope()
        variables.set("temp", "hello")
        val context = ActionContext(ContextWrapper(null), variables)
        val action = PersistVariableAction()
        val result = action.run(context, mapOf("name" to "temp", "global_name" to "GREETING"))
        assertTrue("persist should succeed", result is ActionResult.Success)
        assertEquals("hello", variables.get("GREETING"))
    }

    @Test
    fun persistVariablePromotesExplicitLowercaseTargetAndRoundTrips() = runBlocking {
        val variables = VariableStore()
        variables.pushScope()
        variables.set("temp", "hello")

        val result = PersistVariableAction().run(
            ActionContext(ContextWrapper(null), variables),
            mapOf("name" to "%temp", "global_name" to "%greeting"),
        )
        variables.popScope()

        assertTrue("persist should succeed", result is ActionResult.Success)
        assertEquals("hello", variables.globalSnapshot()["Greeting"])
        assertEquals("hello", variables.get("%Greeting"))
    }

    @Test
    fun persistVariableRejectsInvalidTargetInsteadOfSilentlyWritingLocal() = runBlocking {
        val variables = VariableStore()
        variables.pushScope()
        variables.set("temp", "hello")

        val result = PersistVariableAction().run(
            ActionContext(ContextWrapper(null), variables),
            mapOf("name" to "temp", "global_name" to "bad name"),
        )

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid global variable name"))
    }

    // --- Notification channel resolution ---

    @Test
    fun notificationChannelResolvesKnownKeys() {
        val quiet = NotificationChannels.resolve("quiet")
        assertEquals("opentasker.quiet", quiet.id)

        val default = NotificationChannels.resolve("default")
        assertEquals("opentasker.actions", default.id)

        val urgent = NotificationChannels.resolve("urgent")
        assertEquals("opentasker.urgent", urgent.id)
    }

    @Test
    fun notificationChannelFallsBackToDefault() {
        val unknown = NotificationChannels.resolve("nonexistent")
        assertEquals("opentasker.actions", unknown.id)
    }

    @Test
    fun notificationChannelTrimsAndLowercases() {
        val padded = NotificationChannels.resolve("  URGENT  ")
        assertEquals("opentasker.urgent", padded.id)
    }

    // --- Local network permission guard (pre-API-37 path) ---

    @Test
    fun localNetworkPermissionPassesBelowApi37() {
        val result = checkLocalNetworkPermission(ctx())
        assertEquals("pre-API-37 should not block", null, result)
    }

    @Test
    fun pingGuardsLocalNetworkPermission() = runBlocking {
        val action = PingAction()
        val result = action.run(ctx(), mapOf("host" to "192.168.1.1"))
        assertTrue(
            "ping on pre-API-37 should not fail with permission error",
            result !is ActionResult.Failure || !result.message.contains("ACCESS_LOCAL_NETWORK")
        )
    }

    @Test
    fun wolGuardsLocalNetworkPermission() = runBlocking {
        val action = WakeOnLanAction()
        val result = action.run(ctx(), mapOf("mac" to "AA:BB:CC:DD:EE:FF"))
        assertTrue(
            "WoL on pre-API-37 should not fail with permission error",
            result !is ActionResult.Failure || !result.message.contains("ACCESS_LOCAL_NETWORK")
        )
    }

    private fun fixedResponseServer(body: String): HttpServer {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/file") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(bytes)
                }
            }
        }
    }
}
