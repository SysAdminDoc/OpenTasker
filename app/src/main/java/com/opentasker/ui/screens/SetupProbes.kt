package com.opentasker.ui.screens

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the platform checks behind Setup and Settings one at a time, so a check that throws costs
 * its own row, not the screen.
 *
 * Issue #20: on Android 8 to 12, listing paired devices threw IllegalStateException on any phone
 * that has the companion-device feature (every Galaxy; the stock emulator doesn't). The call ran
 * in an unguarded IO coroutine that both screens share, so opening either one crashed the app.
 * The manifest fix removes that throw; this keeps the next OEM surprise to one row.
 */
internal class SetupProbes(private val onFailure: (name: String, error: Throwable) -> Unit) {
    private val failed = linkedSetOf<String>()

    /** Names of the checks that threw, in the order they ran. */
    val failures: Set<String> get() = failed

    /** [probe]'s answer, or [fallback] when it throws. Cancellation still propagates. */
    fun <T> read(name: String, fallback: T, probe: () -> T): T = try {
        probe()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: RuntimeException) {
        failedWith(name, error, fallback)
    } catch (error: LinkageError) {
        // An API this build of Android doesn't have after all: NoSuchMethodError or
        // NoClassDefFoundError from an old or trimmed OEM image. Still one row, not the screen.
        failedWith(name, error, fallback)
    }

    private fun <T> failedWith(name: String, error: Throwable, fallback: T): T {
        failed += name
        onFailure(name, error)
        return fallback
    }

    /** Whether something is granted, or null when the check itself failed. */
    fun granted(name: String, probe: () -> Boolean): Boolean? = read(name, null, probe)
}

/** How a Setup row reads. [UNAVAILABLE] is a check that threw, which is not the same as missing. */
internal enum class SetupRowStatus { READY, DETECTED, OPTIONAL, NEEDS_SETUP, UNAVAILABLE }

internal fun PermissionSetupItem.status(): SetupRowStatus = when {
    unavailable -> SetupRowStatus.UNAVAILABLE
    optional && granted -> SetupRowStatus.DETECTED
    optional -> SetupRowStatus.OPTIONAL
    granted -> SetupRowStatus.READY
    else -> SetupRowStatus.NEEDS_SETUP
}

/** What sits at the end of a Setup row. */
internal enum class SetupRowControl { ACTION_BUTTON, REVIEW_CHEVRON, CHECK_MARK }

internal fun PermissionSetupItem.control(): SetupRowControl = when {
    !granted && action != PermissionAction.None -> SetupRowControl.ACTION_BUTTON
    // Turning power mode off changes state, so it can't sit behind a chevron labelled "Review app
    // settings". Tapping that chevron used to switch Shizuku off with no warning.
    granted && action is PermissionAction.ShizukuKillSwitch -> SetupRowControl.ACTION_BUTTON
    granted && allowActionWhenGranted -> SetupRowControl.REVIEW_CHEVRON
    else -> SetupRowControl.CHECK_MARK
}
