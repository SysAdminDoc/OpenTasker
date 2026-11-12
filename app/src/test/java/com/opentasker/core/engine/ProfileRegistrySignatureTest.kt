package com.opentasker.core.engine

import com.opentasker.core.model.AutomationMode
import com.opentasker.core.storage.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileRegistrySignatureTest {
    private fun profile(
        id: Long,
        enabled: Boolean = true,
        name: String = "P$id",
        enterTaskId: Long = 1,
        exitTaskId: Long? = null,
        cooldownSec: Int = 0,
        contextsJson: String = "[]",
        automationMode: String = AutomationMode.SINGLE.name,
        group: String? = null,
    ) = ProfileEntity(
        id = id,
        name = name,
        enabled = enabled,
        enterTaskId = enterTaskId,
        exitTaskId = exitTaskId,
        cooldownSec = cooldownSec,
        contextsJson = contextsJson,
        automationMode = automationMode,
        profileGroup = group,
    )

    @Test
    fun disabledProfilesAreExcluded() {
        val enabledOnly = profileRegistrySignature(listOf(profile(1, enabled = true), profile(2, enabled = false)))
        assertEquals(profileRegistrySignature(listOf(profile(1, enabled = true))), enabledOnly)
    }

    @Test
    fun importedProfilesAwaitingReviewAreExcludedEvenIfEnabledBitIsSet() {
        val awaitingReview = profile(1, enabled = true).copy(requiresRiskAcknowledgement = true)

        assertEquals(emptyList<String>(), profileRegistrySignature(listOf(awaitingReview)))
    }

    @Test
    fun cosmeticEditsDoNotChangeSignature() {
        val before = profileRegistrySignature(listOf(profile(1, name = "Home", group = "A")))
        val after = profileRegistrySignature(listOf(profile(1, name = "Home", group = "B")))
        assertEquals(before, after)
    }

    @Test
    fun enableToggleChangesSignature() {
        val enabled = profileRegistrySignature(listOf(profile(1, enabled = true)))
        val disabled = profileRegistrySignature(listOf(profile(1, enabled = false)))
        assertNotEquals(enabled, disabled)
    }

    @Test
    fun engineRelevantEditsChangeSignature() {
        val base = profile(1, contextsJson = "[]", cooldownSec = 0, automationMode = AutomationMode.SINGLE.name, exitTaskId = null)
        val baseSig = profileRegistrySignature(listOf(base))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(contextsJson = "[{}]"))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(cooldownSec = 60))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(automationMode = AutomationMode.QUEUED.name))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(exitTaskId = 9))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(enterTaskId = 9))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(priority = 3))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(gracePeriodSec = 15))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(lifetime = "ONCE"))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(expiresAtMs = 1_800_000_000_000L))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(maxActiveExecutions = 3))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(burstLimit = 12))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(overflowPolicy = "SILENT"))))
        assertNotEquals(baseSig, profileRegistrySignature(listOf(base.copy(fallbackTaskId = 15))))
    }

    @Test
    fun addingOrRemovingProfileChangesSignature() {
        val one = profileRegistrySignature(listOf(profile(1)))
        val two = profileRegistrySignature(listOf(profile(1), profile(2)))
        assertNotEquals(one, two)
    }

    @Test
    fun orderIndependentForSameSet() {
        val a = profileRegistrySignature(listOf(profile(1), profile(2)))
        val b = profileRegistrySignature(listOf(profile(2), profile(1)))
        assertEquals(a, b)
    }
}
