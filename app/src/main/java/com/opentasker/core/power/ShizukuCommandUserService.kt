package com.opentasker.core.power

import android.os.Parcel
import com.opentasker.core.scripting.ShizukuScriptExecutor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The Binder code Shizuku's server ends a user service with. The library keeps its constant
 * (`ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy`) to its own group, so the number lives
 * here and a test holds it to the library's value. An AIDL method pinned with `= 16777114` lands
 * on the same code, because Binder codes start one above AIDL's ids.
 */
internal const val SHIZUKU_DESTROY_TRANSACTION = 16777115

internal fun isShizukuDestroyTransaction(code: Int): Boolean = code == SHIZUKU_DESTROY_TRANSACTION

/**
 * Runs the commands approved by [ShizukuCommandPolicy] and the user's approved scripts in the
 * Shizuku shell/root process.
 *
 * This class is instantiated by Shizuku, not Android's app process. It deliberately has no
 * application fallback. [execute] takes only exact allowlisted commands. [runScript] is the one
 * route to arbitrary shell text, and it runs only a file whose bytes match the hash the host
 * sends, so the allowlist the user manages in Settings is what stands behind it.
 */
class ShizukuCommandUserService : IShizukuCommandService.Stub() {
    private val runningScripts = ConcurrentHashMap.newKeySet<Process>()

    override fun execute(actionId: String, argv: Array<out String>, exitCode: IntArray): String {
        if (!ShizukuCommandPolicy.isExact(actionId, argv.toList())) {
            setExitCode(exitCode, EXIT_REJECTED)
            return "Command rejected by the Shizuku service allowlist"
        }
        return runCommand(argv.toList(), exitCode)
    }

    /**
     * Android's per-app external files directory for this package, derived here from the fixed
     * platform layout: the user service runs as shell and has no Context to ask.
     */
    private fun isWithinAppExternalFiles(target: File): Boolean = runCatching {
        val canonical = target.canonicalFile.path
        APP_EXTERNAL_FILES_ROOTS.any { root ->
            canonical.startsWith(root + File.separator)
        }
    }.getOrDefault(false)

    override fun captureScreenshot(actionId: String, path: String): Int {
        val command = ShizukuCommandPolicy.command(actionId, 0)
        if (command == null || command != listOf("screencap", "-p")) return EXIT_REJECTED
        val output = File(path)
        if (!output.isAbsolute || output.parentFile?.isDirectory != true) return EXIT_INVALID_ARGUMENT
        // This process holds shell privileges, so it re-derives the allowed destination rather
        // than trusting the unprivileged caller's. The host checks the same thing, but a check
        // that lives only on the calling side is a convention, not a boundary - and execute()
        // already re-validates its argv here for exactly that reason.
        if (!isWithinAppExternalFiles(output)) return EXIT_INVALID_ARGUMENT

        return runCatching {
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            var copyFailure: Throwable? = null
            val outputThread = Thread({
                runCatching {
                    process.inputStream.use { source ->
                        FileOutputStream(output).use { destination -> source.copyTo(destination) }
                    }
                }.onFailure { copyFailure = it }
            }, "opentasker-shizuku-screenshot")
            val errorThread = Thread({ readBounded(process.errorStream) }, "opentasker-shizuku-screenshot-error")
            outputThread.start()
            errorThread.start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outputThread.interrupt()
                errorThread.interrupt()
                outputThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                errorThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                EXIT_TIMEOUT
            } else {
                outputThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                errorThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                when {
                    copyFailure != null -> EXIT_EXECUTION_ERROR
                    process.exitValue() != 0 -> process.exitValue()
                    !output.isFile || output.length() == 0L -> EXIT_EXECUTION_ERROR
                    else -> 0
                }
            }
        }.getOrElse { EXIT_EXECUTION_ERROR }
    }

    override fun runScript(
        path: String,
        expectedSha256: String,
        args: Array<out String>,
        timeoutMs: Long,
        outcome: IntArray,
        stderr: Array<String?>,
    ): String {
        var started: Process? = null
        val reply = try {
            ShizukuScriptExecutor.run(path, expectedSha256, args.toList(), timeoutMs) { command ->
                Runtime.getRuntime().exec(command.toTypedArray()).also {
                    started = it
                    runningScripts += it
                }
            }
        } finally {
            started?.let(runningScripts::remove)
        }
        if (outcome.size >= 4) {
            outcome[0] = reply.status
            outcome[1] = reply.exitCode
            outcome[2] = reply.stdoutBytes
            outcome[3] = reply.stderrBytes
        }
        if (stderr.isNotEmpty()) stderr[0] = reply.stderr
        return reply.stdout
    }

    /** Starting or stopping tethering is a framework call, so no shell command goes through [execute]. */
    override fun setWifiHotspot(enabled: Boolean): Int = ShizukuTethering.set(enabled)

    override fun readWifiHotspot(): Int = ShizukuTethering.read()

    override fun destroy() {
        // A script's timeout is enforced from this process, so its shell can't outlive it.
        runningScripts.forEach { it.destroyForcibly() }
        System.exit(0)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (isShizukuDestroyTransaction(code)) {
            destroy()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun runCommand(command: List<String>, exitCode: IntArray): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread({ stdout = readBounded(process.inputStream) }, "opentasker-shizuku-stdout")
            val stderrThread = Thread({ stderr = readBounded(process.errorStream) }, "opentasker-shizuku-stderr")
            stdoutThread.start()
            stderrThread.start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutThread.interrupt()
                stderrThread.interrupt()
                stdoutThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                stderrThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                setExitCode(exitCode, EXIT_TIMEOUT)
                "Command timed out"
            } else {
                stdoutThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                stderrThread.join(STREAM_JOIN_TIMEOUT_MILLIS)
                val result = process.exitValue()
                setExitCode(exitCode, result)
                if (result == 0) stdout else stderr.ifBlank { stdout }
            }
        }.getOrElse { error ->
            setExitCode(exitCode, EXIT_EXECUTION_ERROR)
            error.message ?: "Shizuku command execution failed"
        }
    }

    private fun readBounded(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var remaining = MAX_OUTPUT_BYTES
        while (remaining > 0) {
            val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        stream.close()
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    private fun setExitCode(exitCode: IntArray, value: Int) {
        if (exitCode.isNotEmpty()) exitCode[0] = value
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 10L
        const val STREAM_JOIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_OUTPUT_BYTES = 8 * 1024
        /** `/sdcard/Android/data/<pkg>/files`, plus the raw storage paths that resolve to it. */
        private val APP_EXTERNAL_FILES_ROOTS = listOf(
            "/storage/emulated/0/Android/data/com.opentasker.app/files",
            "/sdcard/Android/data/com.opentasker.app/files",
        )
        const val EXIT_REJECTED = 126
        const val EXIT_INVALID_ARGUMENT = 125
        const val EXIT_TIMEOUT = 124
        const val EXIT_EXECUTION_ERROR = 127
    }
}
