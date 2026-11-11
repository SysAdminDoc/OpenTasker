package com.opentasker.core.health

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Where the Setup row stands. MISSING covers a refused read permission and refused background reads. */
enum class HealthSetupState { UNAVAILABLE, NEEDS_UPDATE, MISSING, GRANTED }

object HealthSetup {
    private const val PERMISSION_READ_TIMEOUT_MS = 3_000L
    private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    /**
     * What Setup asks Health Connect for: the enabled profiles' metrics while the engine runs. With
     * the engine stopped nothing has synced a plan, so it asks for every metric a context can read.
     */
    fun requestedPermissions(): Set<String> =
        HealthPolling.requestedPermissions(
            HealthConnectPollWorker.currentPlan?.metrics ?: HealthMetric.entries.toSet(),
        )

    fun state(
        availability: HealthConnectAvailability,
        backgroundSupported: Boolean,
        granted: Set<String>,
        requested: Set<String>,
    ): HealthSetupState = when {
        availability == HealthConnectAvailability.UNAVAILABLE -> HealthSetupState.UNAVAILABLE
        // Polling runs in the background, so a build that can't read there is as good as missing.
        availability == HealthConnectAvailability.NEEDS_UPDATE || !backgroundSupported -> HealthSetupState.NEEDS_UPDATE
        granted.containsAll(requested) -> HealthSetupState.GRANTED
        else -> HealthSetupState.MISSING
    }

    /** Waits up to three seconds on Health Connect, so call it off the main thread. */
    fun read(context: Context): HealthSetupState {
        val availability = HealthConnectAccess.availability(context)
        if (availability != HealthConnectAvailability.AVAILABLE) {
            return state(availability, backgroundSupported = false, granted = emptySet(), requested = emptySet())
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = try {
            runBlocking {
                withTimeoutOrNull(PERMISSION_READ_TIMEOUT_MS) { client.permissionController.getGrantedPermissions() }
            }
        } catch (e: Exception) {
            // Setup's probes catch runtime exceptions, and a RemoteException isn't one.
            if (e is RuntimeException) throw e
            throw IllegalStateException("Couldn't read Health Connect permissions", e)
        } ?: throw IllegalStateException("Health Connect didn't answer within $PERMISSION_READ_TIMEOUT_MS ms")
        return state(availability, HealthConnectAccess.backgroundReadSupported(client), granted, requestedPermissions())
    }

    /** Health Connect's own page for this app's access, for after its dialog stops appearing. */
    fun manageIntent(context: Context): Intent = HealthConnectClient.getHealthConnectManageDataIntent(context)

    /** The store listing Google points apps at when Health Connect is missing or too old. */
    fun installIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        "market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri(),
    )
}
