package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveShellComposeTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun railStartsBelowTheHeaderAndReachesEveryDestination() {
        // On a tablet the rail drew from the top of the window, so the header covered Profiles
        // and Tasks and there was no way to open either.
        var selected by mutableStateOf(OpenTaskerScreen.Settings)
        setShell(selectedScreen = { selected }, onSelect = { selected = it }, usePadding = true)

        assertTrue(firstItemTop() >= headerBottom())
        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNode(railItem("Tasks")).performClick()
        composeTestRule.waitForIdle()
        assertEquals(OpenTaskerScreen.Tasks, selected)
    }

    @Test
    fun railMeasureCatchesARailUnderTheHeader() {
        // The measurement above has to see the overlap the bug produced.
        setShell(selectedScreen = { OpenTaskerScreen.Settings }, onSelect = {}, usePadding = false)

        assertTrue(firstItemTop() < headerBottom())
    }

    private fun setShell(selectedScreen: () -> OpenTaskerScreen, onSelect: (OpenTaskerScreen) -> Unit, usePadding: Boolean) {
        composeTestRule.setContent {
            OpenTaskerTheme {
                Scaffold(
                    topBar = {
                        Box(Modifier.testTag(HEADER_TAG)) {
                            OpenTaskerHeader(screen = selectedScreen(), detail = "", onOpenSearch = {})
                        }
                    },
                ) { padding ->
                    Row(Modifier.fillMaxSize()) {
                        OpenTaskerNavigationRail(
                            selectedScreen = selectedScreen(),
                            onSelect = onSelect,
                            scaffoldPadding = if (usePadding) padding else PaddingValues(),
                        )
                    }
                }
            }
        }
    }

    private fun headerBottom() = composeTestRule.onNodeWithTag(HEADER_TAG).getBoundsInRoot().bottom

    private fun firstItemTop() = composeTestRule.onNode(railItem("Profiles")).getBoundsInRoot().top

    private fun railItem(label: String): SemanticsMatcher =
        hasAnyAncestor(hasTestTag(NAVIGATION_RAIL_TAG)) and hasClickAction() and hasText(label)

    private companion object {
        const val HEADER_TAG = "header"
    }
}
