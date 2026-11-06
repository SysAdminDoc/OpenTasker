package com.opentasker.core.power

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.opentasker.app.BuildConfig
import com.opentasker.core.scripting.ShizukuScriptPolicy
import com.opentasker.core.scripting.ShizukuScriptReply
import com.opentasker.core.scripting.ShizukuScriptRequest
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Command policy and transport boundary for the Shizuku user service.
 *
 * The host process sends only an exact command from [ShizukuCommandPolicy] to a separately started
 * user service. An ordinary app process is never used as a fallback.
 */
object ShizukuShellRunner {
    private const val USER_SERVICE_TAG = "opentasker-power"
    private const val USER_SERVICE_VERSION = 2
    private const val CONNECTION_TIMEOUT_SECONDS = 2L
    private const val POWER_MODE_OFF = "Shizuku power mode is off, so nothing ran. Turn it on from its row in Setup."

    private val lock = Any()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) {
                remoteService = IShizukuCommandService.Stub.asInterface(service)
                binding = false
                bindingLatch?.countDown()
                bindingLatch = null
            }
            // The first bind can outlast the wait in remoteServiceForUse, which leaves Setup
            // showing "cannot reach its helper service" for a transport that is now up.
            ShizukuPowerBackend.notifyStatusChanged()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            invalidateRemoteService()
        }

        override fun onBindingDied(name: ComponentName) {
            invalidateRemoteService()
        }

        override fun onNullBinding(name: ComponentName) {
            invalidateRemoteService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        invalidateRemoteService()
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remoteService: IShizukuCommandService? = null

    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var binding = false
    private var bindingLatch: CountDownLatch? = null
    private var binderListenerRegistered = false

    /** Called once after the application context is available. */
    fun initialize(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!binderListenerRegistered) {
                runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
                binderListenerRegistered = true
            }
        }
    }

    fun execute(actionId: String, variantIndex: Int = 0): ShellResult {
        val command = ShizukuCommandPolicy.command(actionId, variantIndex)
            ?: return if (!ShizukuCommandPolicy.isAllowed(actionId)) {
                ShellResult.Failure("Action '$actionId' is not in the Shizuku allowlist")
            } else {
                ShellResult.Failure("Invalid variant index $variantIndex for action '$actionId'")
            }
        if (ShizukuPowerBackend.killSwitchEnabled) {
            return ShellResult.Failure(POWER_MODE_OFF)
        }
        val service = remoteServiceForUse()
            ?: return unavailable()
        val exitCode = IntArray(1)
        return runCatching {
            val output = service.execute(actionId, command.toTypedArray(), exitCode)
            if (exitCode[0] == 0) {
                ShellResult.Success(output.orEmpty(), exitCode[0])
            } else {
                ShellResult.Failure(output.takeUnless(String::isNullOrBlank) ?: "Command exited with code ${exitCode[0]}")
            }
        }.getOrElse { error ->
            invalidateRemoteService()
            ShellResult.Failure(error.message ?: "Shizuku user service disconnected")
        }
    }

    /** Runs the fixed screenshot command and lets the privileged process write only app storage. */
    fun captureScreenshot(path: String): ShellResult {
        if (ShizukuPowerBackend.killSwitchEnabled) {
            return ShellResult.Failure(POWER_MODE_OFF)
        }
        if (!isSafeScreenshotPath(path)) {
            return ShellResult.Failure("Screenshot path must stay inside OpenTasker's app-specific external storage")
        }
        val service = remoteServiceForUse()
            ?: return unavailable()
        return runCatching {
            val exitCode = service.captureScreenshot("screenshot.take", path)
            if (exitCode == 0) {
                ShellResult.Success(path, exitCode)
            } else {
                ShellResult.Failure("Screenshot command exited with code $exitCode")
            }
        }.getOrElse { error ->
            invalidateRemoteService()
            ShellResult.Failure(error.message ?: "Shizuku user service disconnected")
        }
    }

    /** Blocks for as long as the script runs, so this belongs on a background thread. */
    internal fun runScript(request: ShizukuScriptRequest): ShizukuScriptReply {
        if (ShizukuPowerBackend.killSwitchEnabled) {
            return ShizukuScriptReply.refused(ShizukuScriptPolicy.STATUS_FAILED, POWER_MODE_OFF)
        }
        val service = remoteServiceForUse()
            ?: return ShizukuScriptReply.refused(
                ShizukuScriptPolicy.STATUS_FAILED,
                (unavailable() as ShellResult.Failure).reason,
            )
        val outcome = IntArray(4)
        val stderr = arrayOfNulls<String>(1)
        return runCatching {
            val stdout = service.runScript(
                request.path,
                request.expectedSha256,
                request.arguments.toTypedArray(),
                request.timeoutMs,
                outcome,
                stderr,
            )
            ShizukuScriptReply(
                status = outcome[0],
                exitCode = outcome[1],
                stdout = stdout.orEmpty(),
                stderr = stderr[0].orEmpty(),
                stdoutBytes = outcome[2],
                stderrBytes = outcome[3],
            )
        }.getOrElse { error ->
            invalidateRemoteService()
            ShizukuScriptReply.refused(
                ShizukuScriptPolicy.STATUS_FAILED,
                error.message ?: "Shizuku user service disconnected",
            )
        }
    }

    fun isAllowed(actionId: String): Boolean = ShizukuCommandPolicy.isAllowed(actionId)

    fun allowedVariantCount(actionId: String): Int = ShizukuCommandPolicy.variantCount(actionId)

    fun hasPrivilegedTransport(): Boolean =
        !ShizukuPowerBackend.killSwitchEnabled && remoteServiceForUse() != null

    /** Unbinds the user service and asks Shizuku to destroy its process. */
    fun shutdown() {
        synchronized(lock) {
            val args = serviceArgs
            serviceArgs = null
            remoteService = null
            binding = false
            bindingLatch?.countDown()
            bindingLatch = null
            if (args != null) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
            if (binderListenerRegistered) {
                runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
                binderListenerRegistered = false
            }
        }
    }

    private fun remoteServiceForUse(): IShizukuCommandService? {
        if (!isShizukuReady()) return null
        var latch: CountDownLatch? = null
        synchronized(lock) {
            remoteService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            val context = appContext ?: return null
            latch = if (binding) {
                bindingLatch
            } else {
                val newLatch = CountDownLatch(1)
                binding = true
                bindingLatch = newLatch
                val args = userServiceArgs(context)
                serviceArgs = args
                runCatching { Shizuku.bindUserService(args, connection) }
                    .onFailure {
                        binding = false
                        bindingLatch = null
                        newLatch.countDown()
                    }
                newLatch
            }
        }
        latch?.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return synchronized(lock) {
            remoteService?.takeIf { it.asBinder().isBinderAlive }
        }
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context, ShizukuCommandUserService::class.java),
        )
            .tag(USER_SERVICE_TAG)
            .version(USER_SERVICE_VERSION)
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .processNameSuffix("power")

    private fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun isSafeScreenshotPath(path: String): Boolean = runCatching {
        val root = appContext?.getExternalFilesDir(null)?.canonicalFile ?: return@runCatching false
        val target = File(path).canonicalFile
        target.path.startsWith(root.path + File.separator)
    }.getOrDefault(false)

    private fun invalidateRemoteService() {
        synchronized(lock) {
            remoteService = null
            binding = false
            bindingLatch?.countDown()
            bindingLatch = null
        }
    }

    /** The only outcome without a transport: ordinary app processes are never used as a fallback. */
    private fun unavailable(): ShellResult =
        ShellResult.Failure(
            "Can't reach Shizuku, so nothing ran. Start Shizuku and allow OpenTasker in it, then check its row in Setup.",
        )
}

internal object ShizukuCommandPolicy {
    private val commands: Map<String, List<List<String>>> = mapOf(
        // Writing airplane_mode_on only flips the switch on screen: no broadcast goes out, so the
        // modem and Wi-Fi stay on. This command is the route that tells the radios.
        "airplane.toggle" to listOf(
            listOf("cmd", "connectivity", "airplane-mode", "enable"),
            listOf("cmd", "connectivity", "airplane-mode", "disable"),
        ),
        "mobile.toggle" to listOf(
            listOf("svc", "data", "enable"),
            listOf("svc", "data", "disable"),
        ),
        // Writing the key succeeds on builds that ignore it, so the action reads the value back
        // rather than trusting a zero exit code.
        "aod.set" to listOf(
            listOf("settings", "put", "secure", "doze_always_on", "1"),
            listOf("settings", "put", "secure", "doze_always_on", "0"),
        ),
        "screenshot.take" to listOf(
            listOf("screencap", "-p"),
        ),
        "reboot" to listOf(
            listOf("svc", "power", "reboot", "false"),
        ),
        "screen.off" to listOf(
            listOf("input", "keyevent", "223"),
        ),
        "wake" to listOf(
            listOf("input", "keyevent", "224"),
        ),
    )

    fun command(actionId: String, variantIndex: Int): List<String>? =
        commands[actionId]?.getOrNull(variantIndex)

    fun isAllowed(actionId: String): Boolean = actionId in commands

    fun isExact(actionId: String, argv: List<String>): Boolean = argv in (commands[actionId].orEmpty())

    fun variantCount(actionId: String): Int = commands[actionId]?.size ?: 0
}

sealed interface ShellResult {
    data class Success(val output: String, val exitCode: Int) : ShellResult
    data class Failure(val reason: String) : ShellResult
}
