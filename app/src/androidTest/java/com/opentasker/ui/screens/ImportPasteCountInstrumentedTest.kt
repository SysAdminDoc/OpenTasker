package com.opentasker.ui.screens

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.R
import com.opentasker.ui.theme.OpenTaskerTheme
import java.text.NumberFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A paste too long to edit by hand shows as a count with a Clear button instead of in the text
 * field, which took about 80 seconds to lay out a paste at the cap. Review still reads all of it.
 */
class ImportPasteCountInstrumentedTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun countLine(length: Int): String =
        resources.getQuantityString(
            R.plurals.import_paste_hidden,
            length,
            NumberFormat.getIntegerInstance().format(length),
        )

    @Test
    fun aPastePastTheEditableLengthShowsACountAndStillGoesToReview() {
        val length = MAX_EDITABLE_PASTE_CHARS + 1
        var text by mutableStateOf("x".repeat(length))
        var confirmed = 0
        composeTestRule.setContent {
            OpenTaskerTheme {
                OpenTaskerBundleTextImportDialog(
                    text = text,
                    busy = false,
                    onTextChanged = { text = it },
                    onDismiss = {},
                    onConfirm = { confirmed++ },
                )
            }
        }

        composeTestRule.onNodeWithText(countLine(length)).assertIsDisplayed()
        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeTestRule.onNodeWithText(resources.getString(R.string.import_paste_too_long)).assertDoesNotExist()
        composeTestRule.onNodeWithText(resources.getString(R.string.import_paste_json_review)).assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun clearEmptiesTheDraftAndBringsTheFieldBack() {
        var text by mutableStateOf("x".repeat(MAX_EDITABLE_PASTE_CHARS + 1))
        composeTestRule.setContent {
            OpenTaskerTheme {
                OpenTaskerBundleTextImportDialog(
                    text = text,
                    busy = false,
                    onTextChanged = { text = it },
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeTestRule.onNodeWithText(resources.getString(R.string.action_clear)).performClick()
        composeTestRule.waitForIdle()
        assertEquals("", text)
        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        composeTestRule.onNodeWithText(resources.getString(R.string.import_paste_json_review)).assertIsNotEnabled()
    }

    @Test
    fun aPasteAtTheCapShowsTheCountAndTheCutOffNoticeQuickly() {
        var text by mutableStateOf("")
        composeTestRule.setContent {
            OpenTaskerTheme {
                OpenTaskerBundleTextImportDialog(
                    text = text,
                    busy = false,
                    onTextChanged = { text = it },
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        val start = SystemClock.uptimeMillis()
        text = "x".repeat(MAX_PASTED_BUNDLE_CHARS)
        composeTestRule.onNodeWithText(countLine(MAX_PASTED_BUNDLE_CHARS)).assertIsDisplayed()
        composeTestRule.onNodeWithText(resources.getString(R.string.import_paste_too_long)).assertIsDisplayed()
        val took = SystemClock.uptimeMillis() - start
        // Laid out in the text field this took about 80 seconds on an API 35 emulator.
        assertTrue("showing the paste took $took ms", took < 10_000)
        composeTestRule.onNodeWithText(resources.getString(R.string.import_paste_json_review)).assertIsNotEnabled()
    }

    @Test
    fun aPasteAtTheEditableLengthStaysInTheField() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                OpenTaskerBundleTextImportDialog(
                    text = "x".repeat(MAX_EDITABLE_PASTE_CHARS),
                    busy = false,
                    onTextChanged = {},
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        composeTestRule.onNodeWithText(countLine(MAX_EDITABLE_PASTE_CHARS)).assertDoesNotExist()
    }
}
