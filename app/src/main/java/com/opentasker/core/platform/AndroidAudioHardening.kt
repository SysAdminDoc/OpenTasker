package com.opentasker.core.platform

import android.media.AudioAttributes
import android.os.Build
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.scheduling.ExactAlarmSupport

enum class AudioForegroundServiceEligibility {
    NONE,
    BACKGROUND_STARTED,
    WHILE_IN_USE,
}

enum class AudioUsageEligibility {
    GENERAL,
    ALARM,
}

/**
 * Where a played or spoken sound goes, and so which volume it follows. Alarm audio is the one kind
 * Android 17 lets a task started from the background make, so it's offered as a choice. Ringer and
 * Notifications are the streams Tasker's sounds most often use besides Music, and they go quiet
 * with the phone's ringer the way those sounds do.
 */
internal enum class AudioOutputStream(
    val value: String,
    val usage: AudioUsageEligibility,
    /** The AudioAttributes usage a sound on this stream plays with. */
    val attributeUsage: Int,
) {
    MUSIC("music", AudioUsageEligibility.GENERAL, AudioAttributes.USAGE_MEDIA),
    ALARM("alarm", AudioUsageEligibility.ALARM, AudioAttributes.USAGE_ALARM),
    RING("ring", AudioUsageEligibility.GENERAL, AudioAttributes.USAGE_NOTIFICATION_RINGTONE),
    NOTIFICATION("notification", AudioUsageEligibility.GENERAL, AudioAttributes.USAGE_NOTIFICATION),
    ;

    companion object {
        /** A blank value is the music stream every saved action has always played on. */
        fun parse(value: String?): AudioOutputStream? {
            val name = value.orEmpty().trim().lowercase()
            return if (name.isEmpty()) MUSIC else entries.firstOrNull { it.value == name }
        }
    }
}

/**
 * The automation service's current foreground-service audio eligibility, for code in the same
 * process that runs outside the service. The temporary-state restore runs from WorkManager and
 * builds its own action context; left at the default it claimed no eligibility at all, so on
 * Android 17 the restore of a volume or ringer change was refused even while the service held
 * while-in-use eligibility, leaving the device at the temporary value for good.
 */
object ServiceAudioEligibility {
    @Volatile
    var current: AudioForegroundServiceEligibility = AudioForegroundServiceEligibility.NONE
}

data class AudioRuntimeEligibility(
    val appVisible: Boolean = false,
    val foregroundService: AudioForegroundServiceEligibility = AudioForegroundServiceEligibility.NONE,
    val exactAlarmPermission: Boolean = false,
)

data class AudioHardeningDecision(
    val allowed: Boolean,
    val reason: String,
)

/** Runtime model for https://developer.android.com/about/versions/17/changes/bg-audio. */
internal object AndroidAudioHardening {
    const val ANDROID_17_API: Int = 37

    @Volatile
    internal var sdkIntOverrideForTests: Int? = null

    fun isRestricted(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= ANDROID_17_API

    fun evaluate(
        eligibility: AudioRuntimeEligibility,
        usage: AudioUsageEligibility = AudioUsageEligibility.GENERAL,
        sdkInt: Int = sdkIntOverrideForTests ?: Build.VERSION.SDK_INT,
        targetSdkInt: Int = ANDROID_17_API,
        offersAlarmStream: Boolean = false,
    ): AudioHardeningDecision {
        if (!isRestricted(sdkInt)) {
            return AudioHardeningDecision(allowed = true, reason = "Android audio hardening does not apply")
        }
        if (eligibility.appVisible) {
            return AudioHardeningDecision(allowed = true, reason = "OpenTasker has a visible activity")
        }
        if (targetSdkInt < ANDROID_17_API && eligibility.foregroundService != AudioForegroundServiceEligibility.NONE) {
            return AudioHardeningDecision(allowed = true, reason = "A foreground service is running")
        }
        if (eligibility.foregroundService == AudioForegroundServiceEligibility.WHILE_IN_USE) {
            return AudioHardeningDecision(
                allowed = true,
                reason = "The automation foreground service has while-in-use eligibility",
            )
        }
        // Exact-alarm access only lowers the enforcement to the level that wants a foreground service
        // of any kind. With no service running, Android still mutes alarm audio while the app is hidden.
        if (
            usage == AudioUsageEligibility.ALARM &&
            eligibility.exactAlarmPermission &&
            eligibility.foregroundService != AudioForegroundServiceEligibility.NONE
        ) {
            return AudioHardeningDecision(
                allowed = true,
                reason = "Exact-alarm permission permits alarm-usage audio from a foreground service",
            )
        }

        val recovery = when (eligibility.foregroundService) {
            AudioForegroundServiceEligibility.BACKGROUND_STARTED ->
                "The automation service was started in the background and lacks while-in-use eligibility. " +
                    "Open OpenTasker, then retry the task from the visible app."
            AudioForegroundServiceEligibility.NONE ->
                "No eligible foreground service or visible activity is active. " +
                    "Open OpenTasker, then retry the task from the visible app."
            AudioForegroundServiceEligibility.WHILE_IN_USE -> error("handled above")
        }
        val alarmHint = when {
            usage == AudioUsageEligibility.ALARM && !eligibility.exactAlarmPermission ->
                " Grant exact-alarm access in Setup to permit alarm-stream changes while backgrounded."
            offersAlarmStream ->
                " Or set this action's stream to Alarm, which can play in the background with exact-alarm access."
            else -> ""
        }
        return AudioHardeningDecision(allowed = false, reason = recovery + alarmHint)
    }

    fun failureIfIneligible(
        ctx: ActionContext,
        operation: String,
        usage: AudioUsageEligibility = AudioUsageEligibility.GENERAL,
        offersAlarmStream: Boolean = false,
    ): ActionResult.Failure? {
        val targetSdkInt = runCatching { ctx.app.applicationInfo.targetSdkVersion }
            .getOrDefault(ANDROID_17_API)
        val currentEligibility = ctx.audioEligibility.copy(
            appVisible = ctx.audioEligibility.appVisible || AppVisibilityTracker.isAppVisible,
            exactAlarmPermission = ctx.audioEligibility.exactAlarmPermission ||
                (sdkIntOverrideForTests == null && runCatching {
                    ExactAlarmSupport.canScheduleExactAlarms(ctx.app)
                }.getOrDefault(false)),
        )
        val decision = evaluate(
            eligibility = currentEligibility,
            usage = usage,
            targetSdkInt = targetSdkInt,
            offersAlarmStream = offersAlarmStream,
        )
        return if (decision.allowed) {
            null
        } else {
            ActionResult.Failure("Android 17+ blocked $operation before any audio side effect. ${decision.reason}")
        }
    }

    fun outputCapabilityReason(reason: String): String =
        "Android 17+ allows this only while OpenTasker is visible, from a while-in-use eligible foreground service, " +
            "or for alarm-usage audio with exact-alarm access. $reason"

    fun mediaKeyCapabilityReason(reason: String): String =
        "Android 17+ allows media-key dispatch only while OpenTasker is visible or from a while-in-use eligible " +
            "foreground service. $reason"

    fun volumeCapabilityReason(reason: String): String =
        "Android 17+ allows volume changes only while OpenTasker is visible, from a while-in-use eligible foreground " +
            "service, or on the alarm stream with exact-alarm access. $reason"
}
