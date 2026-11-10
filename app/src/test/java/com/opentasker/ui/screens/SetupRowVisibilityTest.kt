package com.opentasker.ui.screens

import com.opentasker.core.capabilities.SetupRequirement
import org.junit.Assert.assertEquals
import org.junit.Test

/** A NEEDED row like "Read NFC tags" only shows once a profile or the template being set up needs it. */
class SetupRowVisibilityTest {
    private val nfcRow = row("Read NFC tags", SetupSection.NEEDED, SetupRequirement.NFC_TAGS)
    private val engineRow = row("Notifications", SetupSection.ENGINE)

    @Test
    fun withNoNfcProfileTheNfcRowStaysHidden() {
        val visible = visibleSetupItems(listOf(engineRow, nfcRow), emptySet(), emptySet())

        assertEquals(listOf("Notifications"), visible.map { it.title })
    }

    @Test
    fun anNfcProfileShowsTheRow() {
        val visible = visibleSetupItems(listOf(engineRow, nfcRow), setOf(SetupRequirement.NFC_TAGS), emptySet())

        assertEquals(listOf("Notifications", "Read NFC tags"), visible.map { it.title })
    }

    @Test
    fun aTemplateThatNeedsNfcShowsTheRowBeforeItsProfileExists() {
        val visible = visibleSetupItems(listOf(nfcRow), emptySet(), setOf(SetupRequirement.NFC_TAGS))

        assertEquals(listOf("Read NFC tags"), visible.map { it.title })
    }

    @Test
    fun anotherProfilesNeedDoesNotShowTheNfcRow() {
        val visible = visibleSetupItems(listOf(nfcRow), setOf(SetupRequirement.CALENDAR), emptySet())

        assertEquals(emptyList<String>(), visible.map { it.title })
    }

    private fun row(title: String, section: SetupSection, vararg requirements: SetupRequirement) = PermissionSetupItem(
        title = title,
        body = "",
        granted = false,
        actionLabel = "",
        action = PermissionAction.None,
        requiredFor = "",
        section = section,
        requirements = requirements.toSet(),
    )
}
