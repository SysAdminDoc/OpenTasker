package com.opentasker.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.CorruptStoredRecordException
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.transfer.ImportBudgetExceededException

data class UiMessage(
    // Deliberately unannotated: this is a string resource when [quantity] is null and a plurals
    // resource otherwise. The message() and pluralMessage() factories carry the precise type.
    val resId: Int,
    val args: List<Any> = emptyList(),
    /** Set when [resId] names a plurals resource rather than a string. */
    val quantity: Int? = null,
    val action: UiMessageAction? = null,
    /** For text someone has to read through, not just notice. */
    val longDuration: Boolean = false,
) {
    @SuppressLint("ResourceType")
    fun resolve(context: Context): String = if (quantity == null) {
        context.getString(resId, *args.toTypedArray())
    } else {
        context.resources.getQuantityString(resId, quantity, *args.toTypedArray())
    }
}

internal fun uiErrorResource(error: Throwable, @StringRes fallbackRes: Int): Int = when (error) {
    is CorruptRecordOverwriteException,
    is CorruptStoredRecordException -> R.string.ui_error_corrupt_record
    else -> fallbackRes
}

/**
 * A rejection that already carries user-facing, localized copy.
 *
 * Validation that only the view model can perform - automation lint, duplicate names, reference
 * guards - is the only thing that knows why a save was refused, and dropping that reason left the
 * user with "Operation failed" and an emptied form. Throwable messages are never rendered (they are
 * unlocalized internals), so a rejection carries a string resource and its arguments instead and is
 * resolved by the same collector as every other message.
 */
internal class UiRejection(
    @StringRes val copyRes: Int,
    val copyArgs: List<Any> = emptyList(),
    technical: String = "",
) : IllegalStateException(technical) {
    companion object {
        /** For copy already resolved from resources, such as an automation-lint finding. */
        fun ofResolved(text: String) = UiRejection(R.string.ui_error_reason, listOf(text), text)
    }
}

/** A snackbar message, shared by the view model and the lanes split out of it. */
internal fun uiMessage(@StringRes resId: Int, vararg args: Any): UiMessage = UiMessage(resId, args.toList())

internal fun uiPluralMessage(@PluralsRes resId: Int, quantity: Int, vararg args: Any): UiMessage =
    UiMessage(resId, args.toList(), quantity)

/** Logs the raw throwable for Diagnostics, then maps it to user-facing copy. */
internal fun loggedUiErrorMessage(error: Throwable, @StringRes fallbackRes: Int): UiMessage {
    AppLogger.error("OpenTasker.UI", "Operation failed", error)
    return uiErrorMessage(error, fallbackRes)
}

/**
 * The message shown for a failed operation. Corrupt-record failures keep their dedicated recovery
 * copy; a [UiRejection] states its own reason; anything else falls back to the caller's resource.
 */
internal fun uiErrorMessage(error: Throwable, @StringRes fallbackRes: Int): UiMessage = when (error) {
    is UiRejection -> UiMessage(error.copyRes, error.copyArgs)
    is ImportBudgetExceededException -> UiMessage(R.string.ui_error_import_budget, listOf(error.budgetName, error.limit))
    is CorruptRecordOverwriteException,
    is CorruptStoredRecordException -> UiMessage(R.string.ui_error_corrupt_record)
    else -> UiMessage(fallbackRes)
}

/**
 * Thrown when a normal editor save would overwrite a record whose stored payload currently fails
 * to decode. Blocking the write keeps the corrupt bytes intact for recovery instead of clobbering
 * them with an empty fallback (fail closed).
 */
internal class CorruptRecordOverwriteException(issue: StorageDecodeIssue) : IllegalStateException(
    "Can't save ${issue.recordType.label.lowercase()} \"${issue.recordName}\": its stored " +
        "${issue.fieldName} is corrupt. Recover it (undo or restore a backup) or delete it first.",
)
