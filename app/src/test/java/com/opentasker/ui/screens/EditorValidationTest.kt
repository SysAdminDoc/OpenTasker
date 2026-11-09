package com.opentasker.ui.screens

import com.opentasker.app.R
import com.opentasker.core.model.ProfileLifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorValidationTest {
    @Test
    fun aProfileTheReviewSwitchedOffDoesNotClaimAPlainSave() {
        val updated = R.string.ui_message_profile_updated
        assertEquals(R.string.ui_message_profile_left_off_for_review, profileSavedMessageRes(true, false, updated))
        assertEquals(updated, profileSavedMessageRes(true, true, updated))
        assertEquals(updated, profileSavedMessageRes(false, false, updated))
    }

    @Test
    fun cooldownParsesOnlyWhatTheSaveAccepts() {
        assertEquals(0, parseProfileCooldown("0"))
        assertEquals(3600, parseProfileCooldown("3600"))
        assertNull(parseProfileCooldown("3601"))
        assertNull(parseProfileCooldown("-1"))
        assertNull(parseProfileCooldown("soon"))
    }

    @Test
    fun taskEditorSaveRequiresNameAndBoundedPriority() {
        assertFalse(taskEditorCanSave("", 5))
        assertFalse(taskEditorCanSave("Task", null))
        assertFalse(taskEditorCanSave("Task", 11))
        assertTrue(taskEditorCanSave("Task", 0))
        assertTrue(taskEditorCanSave("Task", 10))
    }

    @Test
    fun profileEditorSaveRequiresExistingTasksAndValidCooldown() {
        assertFalse(profileEditorCanSave("Work", 1, false, true, "", null))
        assertFalse(profileEditorCanSave("Work", 1, true, true, "later", null))
        assertTrue(profileEditorCanSave("Work", 1, true, true, "", null))
        assertTrue(profileEditorCanSave("Work", 1, true, true, "15", 15))
    }

    @Test
    fun profileEditorSaveRequiresBoundedPriorityGraceAndDateLifetimeExpiry() {
        assertFalse(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                parsedPriority = 101,
            ),
        )
        assertFalse(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                lifetime = ProfileLifetime.UNTIL_DATE,
            ),
        )
        assertTrue(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                parsedPriority = -5,
                parsedGracePeriod = 20,
                lifetime = ProfileLifetime.UNTIL_DATE,
                parsedExpiryDate = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun profileEditorSaveRequiresBoundedConcurrencyOverridesWhenEntered() {
        assertFalse(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                maxActiveExecutions = "9",
                parsedMaxActiveExecutions = 9,
            ),
        )
        assertFalse(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                burstLimit = "33",
                parsedBurstLimit = 33,
            ),
        )
        assertTrue(
            profileEditorCanSave(
                "Work",
                1,
                true,
                true,
                "",
                null,
                maxActiveExecutions = "",
                parsedMaxActiveExecutions = null,
                burstLimit = "",
                parsedBurstLimit = null,
            ),
        )
    }
}
