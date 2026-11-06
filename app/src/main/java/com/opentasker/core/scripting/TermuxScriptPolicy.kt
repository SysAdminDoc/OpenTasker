package com.opentasker.core.scripting

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap

internal data class TermuxScriptInvocation(
    val executable: String,
    val argumentText: String?,
    val workingDirectory: String?,
    val stdin: String?,
    val timeoutMs: Long,
)

internal data class PreparedTermuxScript(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String?,
    val stdin: String?,
    val timeoutMs: Long,
)

internal data class TermuxCommandRequest(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val stdin: String? = null,
    val timeoutMs: Long,
)

internal data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val stdoutOriginalLength: Int,
    val stderrOriginalLength: Int,
    /** Termux's `err` value, or null when Termux delivered no result bundle at all. */
    val errorCode: Int?,
    /** Termux's own `errmsg`, bounded and sanitized. Never carries script output. */
    val errorMessage: String = "",
) {
    /** False only when Termux never returned a result bundle for the command. */
    val delivered: Boolean get() = errorCode != null

    /**
     * Termux reports "no internal error" as `Activity.RESULT_OK` (-1), not 0, so a command that ran
     * cleanly arrives with `err = -1`. A bundle that omits the key is read as 0 and also counts as
     * success, which keeps older Termux builds working.
     */
    val termuxSucceeded: Boolean get() = errorCode == ERROR_CODE_OK || errorCode == ERROR_CODE_ABSENT

    companion object {
        /** `Activity.RESULT_OK`, the value Termux's `Errno.ERRNO_SUCCESS` carries. */
        const val ERROR_CODE_OK = -1
        const val ERROR_CODE_ABSENT = 0
    }
}

internal enum class TermuxScriptRejectionReason {
    PERMISSION_DENIED,
    INVALID_INPUT,
    NOT_APPROVED,
    RATE_LIMITED,
    HASH_CHECK_FAILED,
    HASH_MISMATCH,
    OUTPUT_TOO_LARGE,
}

internal sealed interface TermuxScriptExecutionResult {
    data class Completed(
        val command: TermuxCommandResult,
        val approvedHash: String,
    ) : TermuxScriptExecutionResult

    data class Rejected(
        val reason: TermuxScriptRejectionReason,
        val message: String,
    ) : TermuxScriptExecutionResult
}

/** Why a hash preflight did or did not produce a SHA-256 for the approved script. */
internal sealed interface TermuxHashCheckResult {
    data class Verified(val hash: String) : TermuxHashCheckResult

    /** Termux never returned a result bundle for the command. */
    data object NotDelivered : TermuxHashCheckResult

    /** Termux itself refused or failed the command, before any shell ran. */
    data class TermuxError(val errorCode: Int, val errorMessage: String) : TermuxHashCheckResult

    /** The shell ran and exited non-zero, so the script is missing or sha256sum is unavailable. */
    data class CommandFailed(val exitCode: Int) : TermuxHashCheckResult

    /** The command succeeded but its output held no readable hash, or exceeded the capture limit. */
    data object Unreadable : TermuxHashCheckResult
}

internal sealed interface TermuxPreparationResult {
    data class Ready(val script: PreparedTermuxScript) : TermuxPreparationResult
    data class Invalid(val message: String) : TermuxPreparationResult
}

internal sealed interface TermuxVerifiedCommandResult {
    data class Verified(val command: TermuxCommandResult) : TermuxVerifiedCommandResult
    data object HashMismatch : TermuxVerifiedCommandResult
    data object HashCheckFailed : TermuxVerifiedCommandResult
}

internal object TermuxScriptPolicy {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MIN_TIMEOUT_MS = 1_000L
    const val MAX_TIMEOUT_MS = 120_000L
    const val MAX_OUTPUT_BYTES = 32 * 1024
    const val MAX_STDIN_BYTES = 32 * 1024
    const val MAX_ARGUMENT_BYTES = 4 * 1024
    const val MAX_ARGUMENT_TOTAL_BYTES = 32 * 1024
    const val MAX_ARGUMENTS = 128
    const val MAX_PATH_BYTES = 4 * 1024
    const val MAX_CAPTURE_PREFIX_LENGTH = 64
    const val HASH_OUTPUT_LIMIT_BYTES = 4 * 1024
    const val HASH_TIMEOUT_MS = 10_000L
    const val MAX_ERROR_MESSAGE_LENGTH = 200
    const val HASH_EXECUTABLE = "\$PREFIX/bin/sh"

    private const val HASH_SCRIPT =
        "case \"\$1\" in '~/'*) p=\"\$HOME/\${1#\\~/}\";; *) exit 64;; esac; sha256sum -- \"\$p\""
    private const val VERIFIED_EXECUTION_SCRIPT =
        "case \"\$2\" in '~/'*) p=\"\$HOME/\${2#\\~/}\";; *) printf '__OPENTASKER_HASH_ERROR__\\n'; exit 64;; esac; " +
            "actual=\$(sha256sum -- \"\$p\" 2>/dev/null) || { printf '__OPENTASKER_HASH_ERROR__\\n'; exit 65; }; " +
            "actual=\"\${actual%% *}\"; " +
            "if [ \"\$actual\" != \"\$1\" ]; then printf '__OPENTASKER_HASH_MISMATCH__\\n'; exit 66; fi; " +
            "printf '__OPENTASKER_HASH_OK__%s\\n' \"\$actual\"; shift 2; exec \"\$p\" \"\$@\""
    private const val HASH_MISMATCH_MARKER = "__OPENTASKER_HASH_MISMATCH__\n"
    private const val HASH_ERROR_MARKER = "__OPENTASKER_HASH_ERROR__\n"
    private const val HASH_OK_PREFIX = "__OPENTASKER_HASH_OK__"
    private val hashRegex = Regex("^[0-9a-fA-F]{64}(?=\\s|$)")
    private val capturePrefixRegex = Regex("^%?[A-Za-z][A-Za-z0-9_]{0,62}$")

    fun prepare(invocation: TermuxScriptInvocation): TermuxPreparationResult {
        val executable = normalizeExecutable(invocation.executable)
            ?: return TermuxPreparationResult.Invalid(
                "Executable must be inside ${TermuxScriptBackend.SCRIPT_DIRECTORY} without traversal or control characters",
            )
        val arguments = parseArguments(invocation.argumentText)
            ?: return TermuxPreparationResult.Invalid("Arguments are malformed or exceed the bounded argument limits")
        val workingDirectory = invocation.workingDirectory?.trim()?.ifBlank { null }
        if (workingDirectory != null && !isBoundedPath(workingDirectory)) {
            return TermuxPreparationResult.Invalid("Working directory is malformed or exceeds the path limit")
        }
        val stdin = invocation.stdin?.takeIf { it.isNotEmpty() }
        if (stdin != null && utf8Size(stdin) > MAX_STDIN_BYTES) {
            return TermuxPreparationResult.Invalid("Standard input exceeds the 32 KB limit")
        }
        if (invocation.timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            return TermuxPreparationResult.Invalid("Timeout must be between 1000 and 120000 milliseconds")
        }
        return TermuxPreparationResult.Ready(
            PreparedTermuxScript(
                executable = executable,
                arguments = arguments,
                workingDirectory = workingDirectory,
                stdin = stdin,
                timeoutMs = invocation.timeoutMs,
            ),
        )
    }

    fun normalizeExecutable(path: String): String? {
        val normalized = path.trim()
        val prefix = "${TermuxScriptBackend.SCRIPT_DIRECTORY}/"
        if (!normalized.startsWith(prefix) || normalized.length == prefix.length) return null
        if (!isBoundedPath(normalized)) return null
        val relativeSegments = normalized.removePrefix(prefix).split('/')
        if (relativeSegments.any { it.isBlank() || it == "." || it == ".." }) return null
        return normalized
    }

    fun normalizeHash(hash: String): String? =
        hash.trim().lowercase().takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }

    fun isValidCapturePrefix(prefix: String): Boolean =
        prefix.length <= MAX_CAPTURE_PREFIX_LENGTH && capturePrefixRegex.matches(prefix)

    fun hashCheckRequest(script: PreparedTermuxScript): TermuxCommandRequest =
        TermuxCommandRequest(
            executable = HASH_EXECUTABLE,
            arguments = listOf("-c", HASH_SCRIPT, "opentasker-hash", script.executable),
            timeoutMs = minOf(script.timeoutMs, HASH_TIMEOUT_MS),
        )

    fun parseHashResult(result: TermuxCommandResult): TermuxHashCheckResult {
        if (!result.delivered) return TermuxHashCheckResult.NotDelivered
        if (!result.termuxSucceeded) {
            return TermuxHashCheckResult.TermuxError(checkNotNull(result.errorCode), result.errorMessage)
        }
        if (result.exitCode != 0) return TermuxHashCheckResult.CommandFailed(result.exitCode)
        if (!isOutputWithinLimit(result, HASH_OUTPUT_LIMIT_BYTES)) return TermuxHashCheckResult.Unreadable
        val hash = hashRegex.find(result.stdout)?.value?.lowercase()
            ?: return TermuxHashCheckResult.Unreadable
        return TermuxHashCheckResult.Verified(hash)
    }

    /** Bounds and flattens Termux's own error text so it can be shown without leaking or wrapping. */
    fun sanitizeErrorMessage(raw: String?): String {
        val flattened = raw.orEmpty().map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (flattened.length <= MAX_ERROR_MESSAGE_LENGTH) {
            flattened
        } else {
            flattened.take(MAX_ERROR_MESSAGE_LENGTH - 1) + "…"
        }
    }

    fun verifiedExecutionRequest(script: PreparedTermuxScript, expectedHash: String): TermuxCommandRequest =
        TermuxCommandRequest(
            executable = HASH_EXECUTABLE,
            arguments = listOf(
                "-c",
                VERIFIED_EXECUTION_SCRIPT,
                "opentasker-run",
                expectedHash,
                script.executable,
            ) + script.arguments,
            workingDirectory = script.workingDirectory,
            stdin = script.stdin,
            timeoutMs = script.timeoutMs,
        )

    fun unwrapVerifiedResult(result: TermuxCommandResult, expectedHash: String): TermuxVerifiedCommandResult {
        if (result.stdout.startsWith(HASH_MISMATCH_MARKER)) return TermuxVerifiedCommandResult.HashMismatch
        if (result.stdout.startsWith(HASH_ERROR_MARKER)) return TermuxVerifiedCommandResult.HashCheckFailed
        val marker = verificationSuccessMarker(expectedHash)
        if (!result.stdout.startsWith(marker) || result.stdoutOriginalLength < marker.length) {
            return TermuxVerifiedCommandResult.HashCheckFailed
        }
        return TermuxVerifiedCommandResult.Verified(
            result.copy(
                stdout = result.stdout.removePrefix(marker),
                stdoutOriginalLength = result.stdoutOriginalLength - marker.length,
            ),
        )
    }

    internal fun verificationSuccessMarker(expectedHash: String): String = "$HASH_OK_PREFIX$expectedHash\n"

    internal fun verificationMismatchMarker(): String = HASH_MISMATCH_MARKER

    fun isOutputWithinLimit(result: TermuxCommandResult, limitBytes: Int = MAX_OUTPUT_BYTES): Boolean =
        result.stdoutOriginalLength in 0..limitBytes &&
            result.stderrOriginalLength in 0..limitBytes &&
            utf8Size(result.stdout) <= limitBytes &&
            utf8Size(result.stderr) <= limitBytes

    fun parseTimeout(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        return if (value.isEmpty()) DEFAULT_TIMEOUT_MS else value.toLongOrNull()
    }

    fun hash(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString("") { "%02x".format(it) }

    fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private fun isBoundedPath(path: String): Boolean =
        path.isNotBlank() && utf8Size(path) <= MAX_PATH_BYTES && path.none(Char::isISOControl)

    fun parseArguments(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return emptyList()
        if (utf8Size(raw) > MAX_ARGUMENT_TOTAL_BYTES) return null

        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        var tokenStarted = false

        fun finishToken(): Boolean {
            if (!tokenStarted) return true
            val token = current.toString()
            if (utf8Size(token) > MAX_ARGUMENT_BYTES || arguments.size >= MAX_ARGUMENTS) return false
            arguments += token
            current.clear()
            tokenStarted = false
            return true
        }

        raw.forEach { char ->
            if (escaping) {
                current.append(char)
                escaping = false
                tokenStarted = true
            } else if (char == '\\' && quote != '\'') {
                escaping = true
                tokenStarted = true
            } else if ((char == '\'' || char == '"')) {
                if (quote == null) {
                    quote = char
                    tokenStarted = true
                } else if (quote == char) {
                    quote = null
                } else {
                    current.append(char)
                }
            } else if (char.isWhitespace() && quote == null) {
                if (!finishToken()) return null
            } else {
                current.append(char)
                tokenStarted = true
            }
        }
        if (escaping || quote != null || !finishToken()) return null
        return arguments
    }
}

internal class TermuxScriptCoordinator(
    private val limiter: TermuxDispatchLimiter = TermuxScriptDispatch.limiter,
) {
    suspend fun execute(
        ready: Boolean,
        invocation: TermuxScriptInvocation,
        approvedHashFor: (String) -> String?,
        commandRunner: suspend (TermuxCommandRequest) -> TermuxCommandResult,
    ): TermuxScriptExecutionResult {
        if (!ready) {
            return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.PERMISSION_DENIED,
                "Termux RUN_COMMAND permission is not ready",
            )
        }
        val script = when (val prepared = TermuxScriptPolicy.prepare(invocation)) {
            is TermuxPreparationResult.Invalid -> {
                return TermuxScriptExecutionResult.Rejected(
                    TermuxScriptRejectionReason.INVALID_INPUT,
                    prepared.message,
                )
            }
            is TermuxPreparationResult.Ready -> prepared.script
        }
        val expectedHash = approvedHashFor(script.executable)?.let(TermuxScriptPolicy::normalizeHash)
            ?: return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.NOT_APPROVED,
                "Script is not in the approved-script allowlist",
            )
        if (!limiter.tryAcquire(script.executable)) {
            return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.RATE_LIMITED,
                "Script is rate-limited. Wait before re-dispatching",
            )
        }

        val actualHash = when (
            val check = TermuxScriptPolicy.parseHashResult(commandRunner(TermuxScriptPolicy.hashCheckRequest(script)))
        ) {
            is TermuxHashCheckResult.Verified -> check.hash
            TermuxHashCheckResult.NotDelivered -> return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.HASH_CHECK_FAILED,
                "Termux returned no result for the hash check. Set allow-external-apps=true in " +
                    "~/.termux/termux.properties and reload Termux settings",
            )
            is TermuxHashCheckResult.TermuxError -> return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.HASH_CHECK_FAILED,
                buildString {
                    append("Termux error ").append(check.errorCode)
                    if (check.errorMessage.isNotEmpty()) append(": ").append(check.errorMessage)
                    append(" while checking the script hash")
                },
            )
            is TermuxHashCheckResult.CommandFailed -> return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.HASH_CHECK_FAILED,
                "The hash check exited with code ${check.exitCode}. Confirm ${script.executable} exists " +
                    "and that sha256sum is installed in Termux",
            )
            TermuxHashCheckResult.Unreadable -> return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.HASH_CHECK_FAILED,
                "Termux returned no readable SHA-256 for the script",
            )
        }
        if (actualHash != expectedHash) {
            return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.HASH_MISMATCH,
                "Hash mismatch: expected $expectedHash, Termux computed $actualHash",
            )
        }

        val result = when (
            val verified = TermuxScriptPolicy.unwrapVerifiedResult(
                commandRunner(TermuxScriptPolicy.verifiedExecutionRequest(script, expectedHash)),
                expectedHash,
            )
        ) {
            TermuxVerifiedCommandResult.HashMismatch -> {
                return TermuxScriptExecutionResult.Rejected(
                    TermuxScriptRejectionReason.HASH_MISMATCH,
                    "Script changed after its preflight hash check and was not executed",
                )
            }
            TermuxVerifiedCommandResult.HashCheckFailed -> {
                return TermuxScriptExecutionResult.Rejected(
                    TermuxScriptRejectionReason.HASH_CHECK_FAILED,
                    "Termux could not re-verify the approved script before execution",
                )
            }
            is TermuxVerifiedCommandResult.Verified -> verified.command
        }
        if (!TermuxScriptPolicy.isOutputWithinLimit(result)) {
            return TermuxScriptExecutionResult.Rejected(
                TermuxScriptRejectionReason.OUTPUT_TOO_LARGE,
                "Termux output exceeds the 32 KB per-stream capture limit",
            )
        }
        return TermuxScriptExecutionResult.Completed(result, expectedHash)
    }
}

internal class TermuxDispatchLimiter(
    private val maxEntries: Int = 128,
    private val minimumIntervalMs: Long = 1_000L,
    private val retentionMs: Long = 10 * 60 * 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dispatchTimes = LinkedHashMap<String, Long>(16, 0.75f, true)

    @Synchronized
    fun tryAcquire(executable: String): Boolean {
        val now = clock()
        dispatchTimes.entries.removeIf { now - it.value >= retentionMs }
        val lastDispatch = dispatchTimes[executable]
        if (lastDispatch != null && now - lastDispatch < minimumIntervalMs) return false
        dispatchTimes[executable] = now
        while (dispatchTimes.size > maxEntries) {
            dispatchTimes.entries.iterator().run {
                next()
                remove()
            }
        }
        return true
    }

    @Synchronized
    internal fun size(): Int = dispatchTimes.size
}

internal object TermuxScriptDispatch {
    internal val limiter = TermuxDispatchLimiter()

    fun hashScript(content: ByteArray): String = TermuxScriptPolicy.hash(content)
}
