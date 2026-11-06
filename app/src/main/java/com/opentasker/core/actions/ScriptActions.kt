package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.power.ShizukuShellRunner
import com.opentasker.core.scripting.ScriptAllowlistStore
import com.opentasker.core.scripting.ShizukuScriptCoordinator
import com.opentasker.core.scripting.ShizukuScriptExecutionResult
import com.opentasker.core.scripting.ShizukuScriptInvocation
import com.opentasker.core.scripting.ShizukuScriptPolicy
import com.opentasker.core.scripting.TermuxCommandBroker
import com.opentasker.core.scripting.TermuxScriptBackend
import com.opentasker.core.scripting.TermuxScriptCoordinator
import com.opentasker.core.scripting.TermuxScriptExecutionResult
import com.opentasker.core.scripting.TermuxScriptInvocation
import com.opentasker.core.scripting.TermuxScriptPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

class TermuxScriptAction : DeclaredAction(ActionCatalog.require(TermuxScriptBackend.ACTION_ID)) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val executable = args["executable"]?.trim()
            ?: return ActionResult.Failure("Missing 'executable' argument")
        if (executable.isBlank()) return ActionResult.Failure("Executable path is blank")

        val timeoutMs = TermuxScriptPolicy.parseTimeout(args["timeoutMs"])
            ?: return ActionResult.Failure("Timeout must be a whole number of milliseconds")
        val capturePrefix = args["capturePrefix"]?.trim()?.ifBlank { null }
        if (capturePrefix != null && !TermuxScriptPolicy.isValidCapturePrefix(capturePrefix)) {
            return ActionResult.Failure("Output variable prefix is invalid")
        }

        val invocation = TermuxScriptInvocation(
            executable = executable,
            argumentText = args["arguments"],
            workingDirectory = args["workingDirectory"],
            stdin = args["stdin"],
            timeoutMs = timeoutMs,
        )
        val readiness = TermuxScriptBackend.isDispatchReady(ctx.app)
        val coordinator = TermuxScriptCoordinator()

        return try {
            val execution = coordinator.execute(
                ready = readiness,
                invocation = invocation,
                approvedHashFor = { normalizedExecutable ->
                    ScriptAllowlistStore.termux(ctx.app).expectedHash(normalizedExecutable)
                },
                commandRunner = { request -> TermuxCommandBroker.execute(ctx.app, request) },
            )
            when (execution) {
                is TermuxScriptExecutionResult.Rejected -> {
                    ctx.logger("Termux script rejected (${execution.reason}): ${execution.message}")
                    ActionResult.Failure(execution.message)
                }
                is TermuxScriptExecutionResult.Completed -> completeExecution(ctx, capturePrefix, execution)
            }
        } catch (_: TimeoutCancellationException) {
            ctx.logger("Termux script timed out; stdout=<redacted> stderr=<redacted>")
            ActionResult.Failure("Termux command timed out")
        } catch (_: SecurityException) {
            ActionResult.Failure("Termux RUN_COMMAND permission was denied")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogger.error("TermuxScriptAction", "Dispatch failed (${error.javaClass.simpleName}); output redacted")
            ActionResult.Failure("Termux dispatch failed (${error.javaClass.simpleName})")
        }
    }

    internal fun completeExecution(
        ctx: ActionContext,
        capturePrefix: String?,
        execution: TermuxScriptExecutionResult.Completed,
    ): ActionResult {
        val result = execution.command
        if (capturePrefix != null) {
            ctx.variables.set("${capturePrefix}_stdout", result.stdout)
            ctx.variables.set("${capturePrefix}_stderr", result.stderr)
            ctx.variables.set("${capturePrefix}_exit_code", result.exitCode.toString())
            ctx.variables.set("${capturePrefix}_stdout_length", result.stdoutOriginalLength.toString())
            ctx.variables.set("${capturePrefix}_stderr_length", result.stderrOriginalLength.toString())
        }
        ctx.logger(
            "Termux script completed: hash=${execution.approvedHash}; err=${result.errorCode ?: "none"}; " +
                "exit=${result.exitCode}; " +
                "stdout=<redacted:${TermuxScriptPolicy.utf8Size(result.stdout)}B>; " +
                "stderr=<redacted:${TermuxScriptPolicy.utf8Size(result.stderr)}B>",
        )
        return when {
            !result.termuxSucceeded -> ActionResult.Failure(
                buildString {
                    append("Termux could not execute the approved script")
                    result.errorCode?.let { append(" (error ").append(it).append(')') }
                    if (result.errorMessage.isNotEmpty()) append(": ").append(result.errorMessage)
                },
            )
            result.exitCode != 0 -> ActionResult.Failure("Termux script exited with code ${result.exitCode}")
            else -> ActionResult.Success
        }
    }
}

/**
 * Runs the user's own approved script as the Android shell user. The elevated actions in
 * [ShizukuPowerBackend.elevatedActionIds] each map to fixed commands, and this one deliberately
 * stays out of that set: what it runs is decided by the hash allowlist in Settings.
 */
class ShizukuScriptAction : DeclaredAction(ActionCatalog.require(ShizukuScriptPolicy.ACTION_ID)) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"]?.trim().orEmpty()
        if (path.isEmpty()) return ActionResult.Failure("Missing 'path' argument")

        val timeoutMs = TermuxScriptPolicy.parseTimeout(args["timeoutMs"])
            ?: return ActionResult.Failure("Timeout must be a whole number of milliseconds")
        val capturePrefix = args["capturePrefix"]?.trim()?.ifBlank { null }
        if (capturePrefix != null && !TermuxScriptPolicy.isValidCapturePrefix(capturePrefix)) {
            return ActionResult.Failure("Output variable prefix is invalid")
        }

        // Binding the service, reading the allowlist and the script itself all block.
        val execution = withContext(Dispatchers.IO) {
            ShizukuScriptCoordinator().execute(
                ready = ShizukuPowerBackend.isReady(),
                invocation = ShizukuScriptInvocation(path, args["arguments"], timeoutMs),
                approvedHashFor = { approvedPath -> ScriptAllowlistStore.shizuku(ctx.app).expectedHash(approvedPath) },
                runner = ShizukuShellRunner::runScript,
            )
        }
        return when (execution) {
            is ShizukuScriptExecutionResult.Rejected -> {
                ctx.logger("Shizuku script rejected (${execution.reason}): ${execution.message}")
                ActionResult.Failure(execution.message)
            }
            is ShizukuScriptExecutionResult.Completed -> completeExecution(ctx, capturePrefix, execution)
        }
    }

    internal fun completeExecution(
        ctx: ActionContext,
        capturePrefix: String?,
        execution: ShizukuScriptExecutionResult.Completed,
    ): ActionResult {
        val reply = execution.reply
        if (capturePrefix != null) {
            ctx.variables.set("${capturePrefix}_stdout", reply.stdout)
            ctx.variables.set("${capturePrefix}_stderr", reply.stderr)
            ctx.variables.set("${capturePrefix}_exit_code", reply.exitCode.toString())
            ctx.variables.set("${capturePrefix}_stdout_length", reply.stdoutBytes.toString())
            ctx.variables.set("${capturePrefix}_stderr_length", reply.stderrBytes.toString())
        }
        ctx.logger(
            "Shizuku script completed: hash=${execution.approvedHash}; exit=${reply.exitCode}; " +
                "stdout=<redacted:${reply.stdoutBytes}B>; stderr=<redacted:${reply.stderrBytes}B>",
        )
        return if (reply.exitCode == 0) {
            ActionResult.Success
        } else {
            ActionResult.Failure("Shizuku script exited with code ${reply.exitCode}")
        }
    }
}
