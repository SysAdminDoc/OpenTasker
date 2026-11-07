package com.opentasker.core.capabilities

import com.opentasker.core.contexts.AdvancedProtectionContextEvents
import com.opentasker.core.contexts.BluetoothContextEvents
import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.contexts.ScreenRecordingContextEvents
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import java.util.Locale

/**
 * Triggers that need a newer Android release than the phone runs.
 *
 * Each of these has no platform API at all on older releases, so the engine starts its monitor
 * as a no-op and the profile simply never fires. Nothing failed, so nothing was logged, and the
 * only symptom was a profile that sat there forever. This stays pure so the rule can be tested
 * for any release without a device.
 */
object PlatformSupport {
    /** One trigger in one enabled profile that this release can't deliver. */
    data class BlockedTrigger(
        val profileId: Long,
        val profileName: String,
        val trigger: BlockedFamily,
    )

    enum class BlockedFamily(val minimumApi: Int) {
        CAMERA_MIC(30),
        BROADCAST_SENDER(BroadcastContextEvents.SENDER_IDENTITY_API),
        SCREEN_RECORDING(35),
        ADVANCED_PROTECTION(36),
        BLUETOOTH_SECURITY(36),
    }

    /** The family [context] belongs to when that family has an API floor, else null. */
    fun familyOf(context: ContextSpec): BlockedFamily? {
        if (context.type != ContextType.EVENT) return null
        return when (context.config["event"].orEmpty().trim().lowercase(Locale.US)) {
            "camera", "mic", "microphone" -> BlockedFamily.CAMERA_MIC
            ScreenRecordingContextEvents.EVENT_SCREEN_RECORDING -> BlockedFamily.SCREEN_RECORDING
            AdvancedProtectionContextEvents.EVENT_ADVANCED_PROTECTION -> BlockedFamily.ADVANCED_PROTECTION
            BluetoothContextEvents.EVENT_KEY_MISSING,
            BluetoothContextEvents.EVENT_ENCRYPTION_CHANGE,
            -> BlockedFamily.BLUETOOTH_SECURITY
            // The filter refuses a sender it can't read, and before Android 14 there is none to read.
            BroadcastContextEvents.EVENT_BROADCAST ->
                BlockedFamily.BROADCAST_SENDER.takeIf { context.config["sender"].orEmpty().isNotBlank() }
            else -> null
        }
    }

    /**
     * Every trigger of an enabled profile that can't fire on [sdkInt], one entry per profile and
     * family, in profile order. Disabled profiles are left out: they aren't expected to fire.
     */
    fun blockedTriggers(profiles: List<Profile>, sdkInt: Int): List<BlockedTrigger> =
        profiles
            .filter { it.enabled }
            .flatMap { profile ->
                profile.contexts
                    .mapNotNull(::familyOf)
                    .filter { sdkInt < it.minimumApi }
                    .distinct()
                    .map { BlockedTrigger(profile.id, profile.name, it) }
            }

    /** The marketing release for an API level, for "needs Android 15". */
    fun androidRelease(api: Int): String = when (api) {
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        37 -> "17"
        else -> "API $api"
    }
}
