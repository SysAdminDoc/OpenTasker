package com.opentasker.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAudioHardeningTest {
    @Test
    fun restrictionStartsAtAndroid17() {
        assertFalse(AndroidAudioHardening.isRestricted(36))
        assertTrue(AndroidAudioHardening.isRestricted(37))
        assertTrue(AndroidAudioHardening.isRestricted(38))
    }

    @Test
    fun lowerApisRetainExistingBehavior() {
        val decision = AndroidAudioHardening.evaluate(
            eligibility = AudioRuntimeEligibility(),
            sdkInt = 36,
            targetSdkInt = 36,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun visibleActivityIsEligibleOnAndroid17() {
        val decision = AndroidAudioHardening.evaluate(
            eligibility = AudioRuntimeEligibility(appVisible = true),
            sdkInt = 37,
        )

        assertTrue(decision.allowed)
        assertTrue(decision.reason.contains("visible activity"))
    }

    @Test
    fun whileInUseForegroundServiceIsEligibleOnAndroid17() {
        val decision = AndroidAudioHardening.evaluate(
            eligibility = AudioRuntimeEligibility(
                foregroundService = AudioForegroundServiceEligibility.WHILE_IN_USE,
            ),
            sdkInt = 37,
        )

        assertTrue(decision.allowed)
        assertTrue(decision.reason.contains("while-in-use"))
    }

    @Test
    fun backgroundStartedForegroundServiceFailsClosedForTarget37() {
        val decision = AndroidAudioHardening.evaluate(
            eligibility = AudioRuntimeEligibility(
                foregroundService = AudioForegroundServiceEligibility.BACKGROUND_STARTED,
            ),
            sdkInt = 37,
            targetSdkInt = 37,
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("started in the background"))
        assertTrue(decision.reason.contains("Open OpenTasker"))
    }

    @Test
    fun ordinaryForegroundServiceRemainsEligibleForPreTarget37Apps() {
        val decision = AndroidAudioHardening.evaluate(
            eligibility = AudioRuntimeEligibility(
                foregroundService = AudioForegroundServiceEligibility.BACKGROUND_STARTED,
            ),
            sdkInt = 37,
            targetSdkInt = 36,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun exactAlarmPermissionOnlyExemptsAlarmUsage() {
        val eligibility = AudioRuntimeEligibility(
            foregroundService = AudioForegroundServiceEligibility.BACKGROUND_STARTED,
            exactAlarmPermission = true,
        )

        val alarm = AndroidAudioHardening.evaluate(
            eligibility = eligibility,
            usage = AudioUsageEligibility.ALARM,
            sdkInt = 37,
        )
        val general = AndroidAudioHardening.evaluate(
            eligibility = eligibility,
            usage = AudioUsageEligibility.GENERAL,
            sdkInt = 37,
        )

        assertTrue(alarm.allowed)
        assertFalse(general.allowed)
    }

    @Test
    fun exactAlarmAccessDoesNotCoverAlarmAudioWithNoForegroundServiceRunning() {
        val decision = AndroidAudioHardening.evaluate(
            eligibility = AudioRuntimeEligibility(
                foregroundService = AudioForegroundServiceEligibility.NONE,
                exactAlarmPermission = true,
            ),
            usage = AudioUsageEligibility.ALARM,
            sdkInt = 37,
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason, decision.reason.contains("No eligible foreground service"))
    }

    @Test
    fun theAlarmStreamIsTheOneThingABackgroundStartCanPlay() {
        val backgroundStart = AudioRuntimeEligibility(
            foregroundService = AudioForegroundServiceEligibility.BACKGROUND_STARTED,
            exactAlarmPermission = true,
        )

        fun decide(stream: AudioOutputStream?) = AndroidAudioHardening.evaluate(
            eligibility = backgroundStart,
            usage = stream!!.usage,
            sdkInt = 37,
            offersAlarmStream = stream == AudioOutputStream.MUSIC,
        )

        assertTrue(decide(AudioOutputStream.parse("alarm")).allowed)
        val music = decide(AudioOutputStream.parse(null))
        assertFalse(music.allowed)
        assertTrue(music.reason, music.reason.contains("stream to Alarm"))
    }

    @Test
    fun theStreamArgumentKeepsSavedActionsOnMusicAndRefusesAnythingElse() {
        assertEquals(AudioOutputStream.MUSIC, AudioOutputStream.parse(null))
        assertEquals(AudioOutputStream.MUSIC, AudioOutputStream.parse(""))
        assertEquals(AudioOutputStream.MUSIC, AudioOutputStream.parse(" Music "))
        assertEquals(AudioOutputStream.ALARM, AudioOutputStream.parse("alarm"))
        assertEquals(null, AudioOutputStream.parse("voice"))
    }

    @Test
    fun missingExactAlarmAccessProvidesSpecificRecovery() {
        val decision = AndroidAudioHardening.evaluate(
            eligibility = AudioRuntimeEligibility(),
            usage = AudioUsageEligibility.ALARM,
            sdkInt = 37,
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("Grant exact-alarm access in Setup"))
    }
}
