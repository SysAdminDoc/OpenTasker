package com.opentasker.ui.screens

import com.opentasker.app.R
import com.opentasker.core.storage.CorruptStoredRecordException
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.StorageRecordType
import com.opentasker.core.transfer.ImportBudgetExceededException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A save the view model refuses must say why. The reason has to survive as a resource: presentation
 * code is forbidden from rendering throwable text, so a rejection that carried its reason only in
 * the exception message reached the user as "Operation failed".
 */
class UiErrorMessageTest {
    @Test
    fun aRejectionStatesItsOwnReasonInsteadOfTheGenericFallback() {
        val rejection = UiRejection(R.string.ui_error_project_name_duplicate)

        val message = uiErrorMessage(rejection, R.string.ui_error_generic)

        assertEquals(R.string.ui_error_project_name_duplicate, message.resId)
        assertTrue(message.args.isEmpty())
    }

    @Test
    fun aRejectionCarriesItsArgumentsThroughToTheMessage() {
        val rejection = UiRejection(
            R.string.ui_error_variable_referenced,
            listOf("%TOKEN", "Morning focus: Notify"),
        )

        val message = uiErrorMessage(rejection, R.string.ui_error_generic)

        assertEquals(R.string.ui_error_variable_referenced, message.resId)
        assertEquals(listOf("%TOKEN", "Morning focus: Notify"), message.args)
    }

    @Test
    fun copyAlreadyResolvedFromResourcesIsPassedThroughVerbatim() {
        // Automation-lint findings are localized before they are joined, so the reason travels as
        // an argument rather than being re-resolved.
        val rejection = UiRejection.ofResolved("Shadowed profile: a higher-priority profile wins.")

        val message = uiErrorMessage(rejection, R.string.ui_error_generic)

        assertEquals(R.string.ui_error_reason, message.resId)
        assertEquals(listOf("Shadowed profile: a higher-priority profile wins."), message.args)
    }

    @Test
    fun corruptRecordsKeepTheirRecoveryCopy() {
        val corrupt = CorruptStoredRecordException(
            StorageDecodeIssue(
                recordType = StorageRecordType.PROFILE,
                recordId = 4L,
                recordName = "Morning focus",
                fieldName = "contexts",
                message = "bad json",
            ),
        )

        assertEquals(
            R.string.ui_error_corrupt_record,
            uiErrorMessage(corrupt, R.string.ui_error_generic).resId,
        )
    }

    @Test
    fun anImportOverTheItemLimitSaysWhatItCountsInsteadOfEntities() {
        val message = uiErrorMessage(ImportBudgetExceededException("entities", observed = 5_002, limit = 5_000), R.string.ui_error_generic)

        assertEquals(R.string.ui_error_import_budget_items, message.resId)
        assertEquals(listOf<Any>(5_000L), message.args)
    }

    @Test
    fun anImportOverAnotherLimitStillNamesIt() {
        val message = uiErrorMessage(ImportBudgetExceededException("actions", observed = 20_001, limit = 20_000), R.string.ui_error_generic)

        assertEquals(R.string.ui_error_import_budget, message.resId)
        assertEquals(listOf<Any>("actions", 20_000L), message.args)
    }

    @Test
    fun anUnexpectedFailureFallsBackWithoutLeakingItsMessage() {
        val message = uiErrorMessage(IllegalStateException("SQLITE_BUSY: database is locked"), R.string.ui_error_generic)

        assertEquals(R.string.ui_error_generic, message.resId)
        assertTrue("an unexpected failure must not smuggle its text into the copy", message.args.isEmpty())
    }
}
