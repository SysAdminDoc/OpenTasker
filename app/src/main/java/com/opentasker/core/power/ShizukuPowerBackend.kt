package com.opentasker.core.power

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.opentasker.core.logging.AppLogger
import androidx.core.content.edit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuPowerBackend {
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val SETUP_URL = "https://shizuku.rikka.app/guide/setup/"
    private const val TAG = "ShizukuPowerBackend"
    private const val PREFERENCES = "shizuku-power"
    private const val KEY_KILL_SWITCH = "kill-switch-enabled"

    val elevatedActionIds: Set<String> = setOf(
        "airplane.toggle",
        "mobile.toggle",
        "aod.set",
        "screenshot.take",
        "reboot",
        "screen.off",
        "wake",
    )

    /** Defaults on so a process restart never enables privileged behavior before preferences load. */
    @Volatile
    var killSwitchEnabled: Boolean = true
        internal set

    private val statusChangeEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Fires when the binder arrives or dies, or a permission answer lands, so Setup can re-read. */
    val statusChanges: SharedFlow<Unit> = statusChangeEvents.asSharedFlow()

    private val permissionResultEvents = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The answer to [requestPermission]: true when granted. */
    val permissionResults: SharedFlow<Boolean> = permissionResultEvents.asSharedFlow()

    private val listenersRegistered = AtomicBoolean(false)
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { notifyStatusChanged() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { notifyStatusChanged() }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        permissionResultEvents.tryEmit(grantResult == PackageManager.PERMISSION_GRANTED)
        notifyStatusChanged()
    }

    fun initialize(context: Context) {
        ShizukuShellRunner.initialize(context)
        killSwitchEnabled = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_KILL_SWITCH, true)
        if (listenersRegistered.compareAndSet(false, true)) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
            }.onFailure { AppLogger.error(TAG, "Failed to register Shizuku listeners", it) }
        }
    }

    internal fun notifyStatusChanged() {
        statusChangeEvents.tryEmit(Unit)
    }

    fun shutdown() {
        ShizukuShellRunner.shutdown()
    }

    fun setKillSwitchEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_KILL_SWITCH, enabled) }
        killSwitchEnabled = enabled
        if (enabled) {
            ShizukuShellRunner.shutdown()
        } else {
            ShizukuShellRunner.initialize(context)
        }
    }

    fun inspect(context: Context): ShizukuPowerStatus = statusFor(
        managerInstalled = isPackageInstalled(context, MANAGER_PACKAGE),
        killSwitchEnabled = killSwitchEnabled,
        serviceRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
        permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false),
        privilegedTransportAvailable = ShizukuShellRunner.hasPrivilegedTransport(),
    )

    internal fun statusFor(
        managerInstalled: Boolean,
        killSwitchEnabled: Boolean = false,
        serviceRunning: Boolean = false,
        permissionGranted: Boolean = false,
        privilegedTransportAvailable: Boolean = false,
    ): ShizukuPowerStatus = when {
        // A live binder outranks the package check: Sui has no manager app, and forks can ship
        // under their own package name.
        !managerInstalled && !serviceRunning -> ShizukuPowerStatus(
            state = ShizukuPowerState.NotInstalled,
            summary = "Shizuku is not running and no Shizuku manager is installed.",
        )
        killSwitchEnabled -> ShizukuPowerStatus(
            state = ShizukuPowerState.Disabled,
            summary = "Shizuku power mode is off. Turn it on here to use elevated actions.",
        )
        !serviceRunning -> ShizukuPowerStatus(
            state = ShizukuPowerState.ManagerInstalled,
            summary = "Shizuku manager is installed but the service is not running.",
        )
        !permissionGranted -> ShizukuPowerStatus(
            state = ShizukuPowerState.PermissionNeeded,
            summary = "Shizuku is running but OpenTasker needs permission.",
        )
        !privilegedTransportAvailable -> ShizukuPowerStatus(
            state = ShizukuPowerState.BackendUnavailable,
            summary = "Shizuku permission is granted, but this build has no privileged user-service transport. " +
                "Elevated actions cannot run until the transport is available.",
        )
        else -> ShizukuPowerStatus(
            state = ShizukuPowerState.Ready,
            summary = "Shizuku is active, permission is granted, and a privileged transport is available.",
        )
    }

    fun hintForAction(actionId: String): ShizukuActionHint? =
        if (actionId in elevatedActionIds) {
            ShizukuActionHint(
                actionId = actionId,
                message = "This action requires Shizuku permission and its privileged user-service transport.",
            )
        } else {
            null
        }

    fun isReady(): Boolean =
        !killSwitchEnabled &&
            ShizukuShellRunner.hasPrivilegedTransport() &&
            runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission(requestCode: Int): Boolean =
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { AppLogger.error(TAG, "Failed to request Shizuku permission", it) }
            .isSuccess

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess
}

data class ShizukuPowerStatus(
    val state: ShizukuPowerState,
    val summary: String,
) {
    val managerInstalled: Boolean
        get() = state != ShizukuPowerState.NotInstalled

    val isReady: Boolean
        get() = state == ShizukuPowerState.Ready
}

enum class ShizukuPowerState {
    NotInstalled,
    ManagerInstalled,
    PermissionNeeded,
    BackendUnavailable,
    Ready,
    Disabled,
}

data class ShizukuActionHint(
    val actionId: String,
    val message: String,
)
