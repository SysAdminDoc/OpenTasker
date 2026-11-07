package com.opentasker.core.actions

import android.content.Context
import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.platform.AudioForegroundServiceEligibility
import com.opentasker.core.platform.AudioRuntimeEligibility
import com.opentasker.core.platform.ServiceAudioEligibility
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioActionEligibilityTest {
    private val noServicesContext = object : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    @Before
    fun enableAndroid17Hardening() {
        AndroidAudioHardening.sdkIntOverrideForTests = 37
    }

    @After
    fun clearAndroid17HardeningOverride() {
        AndroidAudioHardening.sdkIntOverrideForTests = null
        ServiceAudioEligibility.current = AudioForegroundServiceEligibility.NONE
    }

    @Test
    fun temporaryStateRestoreActsWithTheServicesWhileInUseEligibility() = runBlocking {
        // The original change ran inside the service with while-in-use eligibility. The restore
        // runs from WorkManager and used to claim none, so Android 17 refused it (A-322).
        ServiceAudioEligibility.current = AudioForegroundServiceEligibility.WHILE_IN_USE

        val result = VolumeAction().run(
            temporaryStateRestoreContext(noServicesContext),
            mapOf("stream" to "music", "level" to "5"),
        )

        assertAttempted(result)
    }

    @Test
    fun temporaryStateRestoreWithoutAnEligibleServiceIsRefusedByName() = runBlocking {
        ServiceAudioEligibility.current = AudioForegroundServiceEligibility.BACKGROUND_STARTED

        val result = VolumeAction().run(
            temporaryStateRestoreContext(noServicesContext),
            mapOf("stream" to "music", "level" to "5"),
        )

        assertBlocked(result, "volume control")
    }

    @Test
    fun ineligibleBackgroundVolumeFailsBeforePlatformLookup() = runBlocking {
        val result = VolumeAction().run(
            ctx(),
            mapOf("stream" to "music", "level" to "5"),
        )

        assertBlocked(result, "volume control")
    }

    @Test
    fun visibleVolumeAttemptsThePlatformOperation() = runBlocking {
        val result = VolumeAction().run(
            ctx(AudioRuntimeEligibility(appVisible = true)),
            mapOf("stream" to "music", "level" to "5"),
        )

        assertAttempted(result)
    }

    @Test
    fun whileInUseForegroundServiceAttemptsThePlatformOperation() = runBlocking {
        val result = MuteAction().run(
            ctx(
                AudioRuntimeEligibility(
                    foregroundService = AudioForegroundServiceEligibility.WHILE_IN_USE,
                ),
            ),
            mapOf("stream" to "music"),
        )

        assertAttempted(result)
    }

    @Test
    fun exactAlarmPermissionOnlyAttemptsAlarmStreamOperation() = runBlocking {
        // A boot-started service is a foreground service without while-in-use eligibility,
        // which is the case exact-alarm access exists for.
        val eligibility = AudioRuntimeEligibility(
            exactAlarmPermission = true,
            foregroundService = AudioForegroundServiceEligibility.BACKGROUND_STARTED,
        )

        val alarm = VolumeAction().run(
            ctx(eligibility),
            mapOf("stream" to "alarm", "level" to "5"),
        )
        val music = VolumeAction().run(
            ctx(eligibility),
            mapOf("stream" to "music", "level" to "5"),
        )

        assertAttempted(alarm)
        assertBlocked(music, "volume control")
    }

    @Test
    fun exactAlarmPermissionWithoutAForegroundServiceStillRefusesAlarmStream() = runBlocking {
        // Measured on API 37: with no foreground service running, Android mutes alarm audio
        // from a hidden app even with exact-alarm access.
        val result = VolumeAction().run(
            ctx(AudioRuntimeEligibility(exactAlarmPermission = true)),
            mapOf("stream" to "alarm", "level" to "5"),
        )

        assertBlocked(result, "volume control")
    }

    @Test
    fun genericBackgroundAudioActionsAllFailClosed() = runBlocking {
        val cases = listOf(
            PlaySoundAction().run(ctx(), mapOf("path" to "file:///tmp/test.mp3")) to "sound playback",
            SayAction().run(ctx(), mapOf("text" to "hello")) to "text-to-speech output",
            PauseSoundAction().run(ctx(), emptyMap()) to "media-key dispatch",
            RingerModeAction().run(ctx(), mapOf("mode" to "normal")) to "ringer-mode change",
        )

        cases.forEach { (result, operation) -> assertBlocked(result, operation) }
    }

    @Test
    fun lowerApiRetainsPlatformAttempt() = runBlocking {
        AndroidAudioHardening.sdkIntOverrideForTests = 36

        val result = VolumeAction().run(
            ctx(),
            mapOf("stream" to "music", "level" to "5"),
        )

        assertAttempted(result)
    }

    private fun ctx(eligibility: AudioRuntimeEligibility = AudioRuntimeEligibility()) =
        ActionContext(
            app = noServicesContext,
            variables = VariableStore(),
            audioEligibility = eligibility,
        )

    private fun assertBlocked(result: ActionResult, operation: String) {
        assertTrue(result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue(message.contains("blocked $operation before any audio side effect"))
        assertTrue(message.contains("Open OpenTasker"))
    }

    private fun assertAttempted(result: ActionResult) {
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("audio service not available"))
    }
}
