package com.opentasker.core.actions

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.annotation.RequiresApi
import com.opentasker.core.apps.PackageNamePolicy
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.platform.BackgroundActivityStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AppArchiveAction(
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
) : DeclaredAction(ActionCatalog.require("app.archive")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        PackageArchiveActionSupport.run(ctx, args, PackageArchiveMode.ARCHIVE, sdkInt())
}

class AppUnarchiveAction(
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
) : DeclaredAction(ActionCatalog.require("app.unarchive")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        PackageArchiveActionSupport.run(ctx, args, PackageArchiveMode.UNARCHIVE, sdkInt())
}

internal enum class PackageArchiveMode(val verb: String) {
    ARCHIVE("archive"),
    UNARCHIVE("unarchive"),
}

internal object PackageArchiveActionSupport {
    suspend fun run(
        ctx: ActionContext,
        args: Map<String, String>,
        mode: PackageArchiveMode,
        sdkInt: Int,
    ): ActionResult {
        if (sdkInt < ANDROID_15_API) {
            return ActionResult.Failure("${mode.verb} requires Android 15 (API 35) or newer")
        }
        val packageName = args["package"]?.trim().orEmpty()
        if (!PackageNamePolicy.isValid(packageName)) {
            return ActionResult.Failure("invalid package name")
        }
        if (packageName == ctx.app.packageName) {
            return ActionResult.Failure("refusing to ${mode.verb} OpenTasker itself")
        }
        // The sdkInt above is injected so the version gate stays unit-testable on the JVM, where
        // Build.VERSION.SDK_INT is always 0. This is the platform check lint can actually prove
        // before the API 35 requestArchive/requestUnarchive call below.
        if (Build.VERSION.SDK_INT < ANDROID_15_API) {
            return ActionResult.Failure("${mode.verb} requires Android 15 (API 35) or newer")
        }

        val operationId = UUID.randomUUID().toString()
        val result = CompletableDeferred<PackageArchiveStatus>()
        PackageArchiveOperations.register(operationId, result)
        return try {
            val statusReceiver = statusReceiver(ctx.app, operationId, mode)
            request(ctx.app.packageManager.packageInstaller, packageName, mode, statusReceiver.intentSender)
            val status = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { result.await() }
                ?: return ActionResult.Failure("${mode.verb} request timed out")
            if (status.accepted) {
                ctx.logger("Package ${mode.verb} request accepted: $packageName")
                ActionResult.Success
            } else {
                ActionResult.Failure("${mode.verb} failed: ${status.detail}")
            }
        } catch (error: SecurityException) {
            ActionResult.Failure("${mode.verb} permission was denied", error)
        } catch (error: PackageManager.NameNotFoundException) {
            ActionResult.Failure("package is not available for ${mode.verb}", error)
        } catch (error: IOException) {
            ActionResult.Failure("${mode.verb} request could not be created", error)
        } catch (error: RuntimeException) {
            ActionResult.Failure("${mode.verb} request failed: ${error.message}", error)
        } finally {
            PackageArchiveOperations.remove(operationId)
        }
    }

    private fun statusReceiver(context: Context, operationId: String, mode: PackageArchiveMode): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            operationId.hashCode(),
            Intent(context, PackageArchiveStatusReceiver::class.java).apply {
                action = PackageArchiveStatusReceiver.ACTION_STATUS
                data = Uri.parse("opentasker://package-archive/$operationId")
                putExtra(PackageArchiveStatusReceiver.EXTRA_OPERATION_ID, operationId)
                putExtra(PackageArchiveStatusReceiver.EXTRA_MODE, mode.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )

    @RequiresApi(35)
    private fun request(
        installer: PackageInstaller,
        packageName: String,
        mode: PackageArchiveMode,
        statusReceiver: android.content.IntentSender,
    ) {
        when (mode) {
            PackageArchiveMode.ARCHIVE -> installer.requestArchive(packageName, statusReceiver)
            PackageArchiveMode.UNARCHIVE -> installer.requestUnarchive(packageName, statusReceiver)
        }
    }

    private fun pendingIntentMutabilityFlags(): Int =
        if (Build.VERSION.SDK_INT >= 31) {
            // PackageInstaller fills status extras into this callback IntentSender.
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }

    private const val ANDROID_15_API = 35
    private const val REQUEST_TIMEOUT_MS = 60_000L
}

/**
 * True when Android is asking the user to approve the request rather than reporting an outcome.
 * Archive uses the generic installer status; unarchive has its own dedicated code.
 */
internal fun Int.needsUserConfirmation(mode: String): Boolean =
    if (mode == PackageArchiveMode.UNARCHIVE.name) {
        this == PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED || this == PackageInstaller.STATUS_PENDING_USER_ACTION
    } else {
        this == PackageInstaller.STATUS_PENDING_USER_ACTION
    }

internal fun Intent.confirmationIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
    }

internal data class PackageArchiveStatus(val accepted: Boolean, val detail: String)

internal object PackageArchiveOperations {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<PackageArchiveStatus>>()

    fun register(operationId: String, result: CompletableDeferred<PackageArchiveStatus>) {
        pending[operationId] = result
    }

    fun complete(operationId: String, status: PackageArchiveStatus) {
        pending.remove(operationId)?.complete(status)
    }

    fun remove(operationId: String) {
        pending.remove(operationId)
    }
}

class PackageArchiveStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID) ?: return
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val status = if (mode == PackageArchiveMode.UNARCHIVE.name &&
            intent.hasExtra(PackageInstaller.EXTRA_UNARCHIVE_STATUS)
        ) {
            intent.getIntExtra(PackageInstaller.EXTRA_UNARCHIVE_STATUS, PackageInstaller.UNARCHIVAL_GENERIC_ERROR)
        } else {
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        }

        // OpenTasker is not the installer of record, so this is the *expected* first answer to
        // every request: Android hands back a confirmation to show rather than a result. Treating
        // it as a terminal failure ("status=-1") is why the action could never succeed. The
        // operation stays pending here - the real outcome arrives as a second broadcast.
        if (status.needsUserConfirmation(mode)) {
            val confirmation = intent.confirmationIntent()
            if (confirmation == null) {
                PackageArchiveOperations.complete(
                    operationId,
                    PackageArchiveStatus(false, "Android asked for confirmation but supplied no screen to show"),
                )
                return
            }
            // A dropped screen would leave the operation waiting on a choice nobody was shown.
            if (BackgroundActivityStart.likelyBlocked(context)) {
                PackageArchiveOperations.complete(
                    operationId,
                    PackageArchiveStatus(false, BackgroundActivityStart.failureMessage("the confirmation screen")),
                )
                return
            }
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirmation) }.onFailure {
                PackageArchiveOperations.complete(
                    operationId,
                    PackageArchiveStatus(false, "the confirmation screen could not be opened; open OpenTasker and retry"),
                )
            }
            return
        }
        val accepted = if (mode == PackageArchiveMode.UNARCHIVE.name) {
            status == PackageInstaller.UNARCHIVAL_OK
        } else {
            status == PackageInstaller.STATUS_SUCCESS
        }
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf(String::isNotBlank)
            ?: if (accepted) "request accepted" else "status=$status"
        PackageArchiveOperations.complete(operationId, PackageArchiveStatus(accepted, detail))
    }

    companion object {
        const val ACTION_STATUS = "com.opentasker.action.PACKAGE_ARCHIVE_STATUS"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val EXTRA_MODE = "mode"
    }
}
