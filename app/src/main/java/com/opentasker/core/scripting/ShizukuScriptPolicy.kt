package com.opentasker.core.scripting

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal data class ShizukuScriptInvocation(
    val path: String,
    val argumentText: String?,
    val timeoutMs: Long,
)

internal data class ShizukuScriptRequest(
    val path: String,
    val expectedSha256: String,
    val arguments: List<String>,
    val timeoutMs: Long,
)

/**
 * What the user service reports. [status] is separate from [exitCode] because a script may
 * legitimately exit with any code, including the 124 to 127 the fixed commands use for refusals.
 */
internal data class ShizukuScriptReply(
    val status: Int,
    val exitCode: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutBytes: Int = 0,
    val stderrBytes: Int = 0,
) {
    companion object {
        /** A refusal or failure, with the explanation (or, on a mismatch, the actual hash) in stderr. */
        fun refused(status: Int, detail: String = ""): ShizukuScriptReply =
            ShizukuScriptReply(status = status, stderr = detail)
    }
}

internal sealed interface ShizukuScriptVerdict {
    /** [text] decodes exactly the bytes that were hashed, so nothing unverified can be run. */
    data class Approved(val text: String) : ShizukuScriptVerdict
    data class Mismatch(val actualSha256: String) : ShizukuScriptVerdict
    data object TooLarge : ShizukuScriptVerdict
    data object NotText : ShizukuScriptVerdict
}

internal object ShizukuScriptPolicy {
    const val ACTION_ID = "script.shizuku.run"
    const val MAX_SCRIPT_BYTES = 64 * 1024
    const val MAX_OUTPUT_BYTES = 32 * 1024

    const val STATUS_RAN = 0
    const val STATUS_INVALID_REQUEST = 1
    const val STATUS_UNREADABLE = 2
    const val STATUS_TOO_LARGE = 3
    const val STATUS_NOT_TEXT = 4
    const val STATUS_HASH_MISMATCH = 5
    const val STATUS_TIMED_OUT = 6
    const val STATUS_FAILED = 7

    /** Absolute with no `.`, `..` or empty segment, so the approved path is the path that is read. */
    fun normalizePath(raw: String): String? {
        val path = raw.trim()
        if (!path.startsWith('/') || path.length == 1) return null
        if (TermuxScriptPolicy.utf8Size(path) > TermuxScriptPolicy.MAX_PATH_BYTES) return null
        if (path.any(Char::isISOControl)) return null
        val intact = path.substring(1).split('/').none { it.isBlank() || it == "." || it == ".." }
        return if (intact) path else null
    }

    fun verify(content: ByteArray, expectedSha256: String): ShizukuScriptVerdict {
        if (content.size > MAX_SCRIPT_BYTES) return ShizukuScriptVerdict.TooLarge
        val actual = TermuxScriptPolicy.hash(content)
        if (actual != expectedSha256) return ShizukuScriptVerdict.Mismatch(actual)
        val text = String(content, Charsets.UTF_8)
        // `sh -c` takes text, so bytes that do not survive decoding would run as something else.
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(content) || text.indexOf(Char(0)) >= 0) {
            return ShizukuScriptVerdict.NotText
        }
        return ShizukuScriptVerdict.Approved(text)
    }

    /**
     * The text is the shell's program and the path is only its `$0`, so the file is not opened a
     * second time. Arguments stay separate argv entries and are never added to the program.
     */
    fun command(text: String, path: String, arguments: List<String>): List<String> =
        listOf("/system/bin/sh", "-c", text, path) + arguments
}

/**
 * Keeps the first [cap] bytes and goes on reading after that, so a chatty script can't stall on a
 * full pipe. Readable while the drain is still going, because a child the script left running can
 * hold the pipe open long after the script itself has exited.
 */
internal class BoundedCapture(private val cap: Int = ShizukuScriptPolicy.MAX_OUTPUT_BYTES) {
    private val kept = ByteArrayOutputStream()
    private var total = 0L

    fun drain(stream: InputStream) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                append(buffer, count)
            }
        } catch (_: IOException) {
            // The process was destroyed underneath the read. What arrived before that still counts.
        }
    }

    @Synchronized
    private fun append(buffer: ByteArray, count: Int) {
        total += count
        val room = cap - kept.size()
        if (room > 0) kept.write(buffer, 0, minOf(room, count))
    }

    @Synchronized
    fun text(): String = kept.toString(Charsets.UTF_8.name())

    @Synchronized
    fun totalBytes(): Int = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Runs inside the Shizuku process, as the shell user. It re-checks everything the app already
 * checked, because a rule that only lives on the calling side is not a boundary, then reads the
 * file once and runs the bytes it hashed. Replacing the file after the check changes nothing.
 */
internal object ShizukuScriptExecutor {
    private const val STREAM_JOIN_TIMEOUT_MILLIS = 1_000L

    fun run(
        path: String,
        expectedSha256: String,
        arguments: List<String>,
        timeoutMs: Long,
        start: (List<String>) -> Process,
    ): ShizukuScriptReply {
        val approvedPath = ShizukuScriptPolicy.normalizePath(path)
            ?: return ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_INVALID_REQUEST, "Invalid script path")
        val hash = TermuxScriptPolicy.normalizeHash(expectedSha256)
            ?: return ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_INVALID_REQUEST, "Invalid script hash")
        if (timeoutMs !in TermuxScriptPolicy.MIN_TIMEOUT_MS..TermuxScriptPolicy.MAX_TIMEOUT_MS) {
            return ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_INVALID_REQUEST, "Timeout out of range")
        }
        if (arguments.size > TermuxScriptPolicy.MAX_ARGUMENTS) {
            return ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_INVALID_REQUEST, "Too many arguments")
        }

        val content = try {
            readAtMostOverLimit(File(approvedPath))
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_UNREADABLE)

        return when (val verdict = ShizukuScriptPolicy.verify(content, hash)) {
            ShizukuScriptVerdict.TooLarge -> ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_TOO_LARGE)
            ShizukuScriptVerdict.NotText -> ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_NOT_TEXT)
            is ShizukuScriptVerdict.Mismatch ->
                ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_HASH_MISMATCH, verdict.actualSha256)
            is ShizukuScriptVerdict.Approved ->
                execute(ShizukuScriptPolicy.command(verdict.text, approvedPath, arguments), timeoutMs, start)
        }
    }

    /** Null when it isn't a regular file, which also keeps a FIFO from blocking the binder thread. */
    private fun readAtMostOverLimit(file: File): ByteArray? {
        if (!file.isFile) return null
        return FileInputStream(file).use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (output.size() <= ShizukuScriptPolicy.MAX_SCRIPT_BYTES) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun execute(
        command: List<String>,
        timeoutMs: Long,
        start: (List<String>) -> Process,
    ): ShizukuScriptReply {
        val process = try {
            start(command)
        } catch (error: IOException) {
            return ShizukuScriptReply.refused(
                ShizukuScriptPolicy.STATUS_FAILED,
                error.message ?: "The shell could not be started",
            )
        }
        val stdout = BoundedCapture()
        val stderr = BoundedCapture()
        val readers = listOf(
            drainThread("opentasker-shizuku-script-stdout") { stdout.drain(process.inputStream) },
            drainThread("opentasker-shizuku-script-stderr") { stderr.drain(process.errorStream) },
        )
        // A script that reads stdin gets end of file rather than waiting out its whole timeout.
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // Already closed because the process has exited.
        }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        readers.forEach { it.join(STREAM_JOIN_TIMEOUT_MILLIS) }
        return ShizukuScriptReply(
            status = if (finished) ShizukuScriptPolicy.STATUS_RAN else ShizukuScriptPolicy.STATUS_TIMED_OUT,
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            stdoutBytes = stdout.totalBytes(),
            stderrBytes = stderr.totalBytes(),
        )
    }

    private fun drainThread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply {
            isDaemon = true
            start()
        }
}

internal enum class ShizukuScriptRejectionReason {
    NOT_READY,
    INVALID_INPUT,
    NOT_APPROVED,
    RATE_LIMITED,
    HASH_MISMATCH,
    UNREADABLE,
    TOO_LARGE,
    NOT_TEXT,
    TIMED_OUT,
    SERVICE_FAILED,
}

internal sealed interface ShizukuScriptExecutionResult {
    data class Rejected(
        val reason: ShizukuScriptRejectionReason,
        val message: String,
    ) : ShizukuScriptExecutionResult

    /** The script ran. A non-zero [ShizukuScriptReply.exitCode] is still a completed run. */
    data class Completed(
        val reply: ShizukuScriptReply,
        val approvedHash: String,
    ) : ShizukuScriptExecutionResult
}

internal class ShizukuScriptCoordinator(
    private val limiter: TermuxDispatchLimiter = ShizukuScriptDispatch.limiter,
) {
    fun execute(
        ready: Boolean,
        invocation: ShizukuScriptInvocation,
        approvedHashFor: (String) -> String?,
        runner: (ShizukuScriptRequest) -> ShizukuScriptReply,
    ): ShizukuScriptExecutionResult {
        if (!ready) {
            return rejected(
                ShizukuScriptRejectionReason.NOT_READY,
                "Shizuku isn't ready. Start it, allow OpenTasker in it, and turn on power mode from its row in Setup",
            )
        }
        val path = ShizukuScriptPolicy.normalizePath(invocation.path)
            ?: return rejected(
                ShizukuScriptRejectionReason.INVALID_INPUT,
                "Script path must be absolute, with no '.' or '..' segments",
            )
        if (invocation.timeoutMs !in TermuxScriptPolicy.MIN_TIMEOUT_MS..TermuxScriptPolicy.MAX_TIMEOUT_MS) {
            return rejected(
                ShizukuScriptRejectionReason.INVALID_INPUT,
                "Timeout must be between ${TermuxScriptPolicy.MIN_TIMEOUT_MS} and " +
                    "${TermuxScriptPolicy.MAX_TIMEOUT_MS} ms",
            )
        }
        val arguments = TermuxScriptPolicy.parseArguments(invocation.argumentText)
            ?: return rejected(
                ShizukuScriptRejectionReason.INVALID_INPUT,
                "Arguments have an unclosed quote or exceed the size limit",
            )
        val expectedHash = approvedHashFor(path)?.let(TermuxScriptPolicy::normalizeHash)
            ?: return rejected(
                ShizukuScriptRejectionReason.NOT_APPROVED,
                "Script is not on the approved Shizuku script list. Add its path and SHA-256 under More > Settings",
            )
        if (!limiter.tryAcquire(path)) {
            return rejected(
                ShizukuScriptRejectionReason.RATE_LIMITED,
                "Script is rate-limited. Wait before running it again",
            )
        }

        val reply = runner(ShizukuScriptRequest(path, expectedHash, arguments, invocation.timeoutMs))
        return when (reply.status) {
            ShizukuScriptPolicy.STATUS_RAN -> ShizukuScriptExecutionResult.Completed(reply, expectedHash)
            ShizukuScriptPolicy.STATUS_HASH_MISMATCH -> rejected(
                ShizukuScriptRejectionReason.HASH_MISMATCH,
                "Hash mismatch for $path: approved $expectedHash, the file has ${reply.stderr}. Nothing ran",
            )
            ShizukuScriptPolicy.STATUS_UNREADABLE -> rejected(
                ShizukuScriptRejectionReason.UNREADABLE,
                "The shell user can't read $path. Keep the script in /data/local/tmp or shared storage",
            )
            ShizukuScriptPolicy.STATUS_TOO_LARGE -> rejected(
                ShizukuScriptRejectionReason.TOO_LARGE,
                "Script is larger than ${ShizukuScriptPolicy.MAX_SCRIPT_BYTES / 1024} KB",
            )
            ShizukuScriptPolicy.STATUS_NOT_TEXT -> rejected(
                ShizukuScriptRejectionReason.NOT_TEXT,
                "Script isn't plain UTF-8 text, so it was not run",
            )
            ShizukuScriptPolicy.STATUS_TIMED_OUT -> rejected(
                ShizukuScriptRejectionReason.TIMED_OUT,
                "Script was stopped after ${invocation.timeoutMs} ms",
            )
            ShizukuScriptPolicy.STATUS_INVALID_REQUEST -> rejected(
                ShizukuScriptRejectionReason.INVALID_INPUT,
                "The Shizuku service refused the request: ${reply.stderr}",
            )
            else -> rejected(
                ShizukuScriptRejectionReason.SERVICE_FAILED,
                reply.stderr.ifBlank { "The Shizuku service could not run the script" },
            )
        }
    }

    private fun rejected(reason: ShizukuScriptRejectionReason, message: String) =
        ShizukuScriptExecutionResult.Rejected(reason, message)
}

internal object ShizukuScriptDispatch {
    val limiter = TermuxDispatchLimiter()
}
