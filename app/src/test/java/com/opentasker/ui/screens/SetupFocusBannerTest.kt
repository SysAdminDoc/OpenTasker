package com.opentasker.ui.screens

import com.opentasker.core.capabilities.SetupRequirement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Finish setting up your new automation" banner said the template still needed access while
 * the same screen read 9 of 9 ready, because it showed whenever a template had set a focus.
 */
class SetupFocusBannerTest {
    private val focus = setOf(SetupRequirement.CALENDAR, SetupRequirement.WRITE_SETTINGS)

    @Test
    fun hiddenOnceEveryRowTheTemplateWaitsOnIsGranted() {
        val items = listOf(
            row(granted = true, SetupRequirement.CALENDAR),
            row(granted = true, SetupRequirement.WRITE_SETTINGS),
            row(granted = false, SetupRequirement.OVERLAY),
        )

        assertFalse(setupFocusBannerVisible(focus, items))
    }

    @Test
    fun shownWhileOneFocusedRowIsStillPending() {
        val items = listOf(
            row(granted = true, SetupRequirement.CALENDAR),
            row(granted = false, SetupRequirement.WRITE_SETTINGS),
        )

        assertTrue(setupFocusBannerVisible(focus, items))
    }

    @Test
    fun aPendingRowOutsideTheFocusDoesNotKeepTheBanner() {
        val items = listOf(
            row(granted = true, SetupRequirement.CALENDAR, SetupRequirement.WRITE_SETTINGS),
            row(granted = false, SetupRequirement.USAGE_ACCESS),
        )

        assertFalse(setupFocusBannerVisible(focus, items))
    }

    @Test
    fun shownWhileTheRowsAreStillLoading() {
        assertTrue(setupFocusBannerVisible(focus, loadedItems = null))
    }

    @Test
    fun neverShownWithoutAFocus() {
        assertFalse(setupFocusBannerVisible(emptySet(), listOf(row(granted = false, SetupRequirement.CALENDAR))))
        assertFalse(setupFocusBannerVisible(emptySet(), loadedItems = null))
    }

    private fun row(granted: Boolean, vararg requirements: SetupRequirement) = PermissionSetupItem(
        title = "Row",
        body = "",
        granted = granted,
        actionLabel = "Open",
        action = PermissionAction.None,
        requiredFor = "",
        section = SetupSection.NEEDED,
        requirements = requirements.toSet(),
    )
}
