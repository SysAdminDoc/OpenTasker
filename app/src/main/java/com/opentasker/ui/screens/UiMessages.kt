package com.opentasker.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.snapshotFlow
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.CorruptStoredRecordException
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.transfer.ImportBudgetExceededException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.xml.sax.SAXException

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
    /** Stays on screen until dismissed, for a reason someone may need while they go fix the cause. */
    val stayUntilDismissed: Boolean = false,
) {
    @SuppressLint("ResourceType")
    fun resolve(context: Context): String = if (quantity == null) {
        context.getString(resId, *args.toTypedArray())
    } else {
        context.resources.getQuantityString(resId, quantity, *args.toTypedArray())
    }
}

/** A failure reason held on screen until dismissed or replaced. */
private class ReasonVisuals(override val message: String) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = true
    override val duration = SnackbarDuration.Indefinite

    /** Set when a direct message took its spot the moment it came up, before anyone read it. */
    var displacedUnseen = false
}

private class DirectVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean,
) : SnackbarVisuals {
    override val duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite
}

/**
 * Shows each message in turn and hands back the Undo someone taps. A failure reason waits for the
 * person, not the queue: it stays until dismissed or until the next message replaces it, and it
 * gets [STICKY_MESSAGE_MINIMUM_MS] on screen first even when that message is already queued. An Undo
 * answers something just tapped, so it replaces the reason at once, and a reason it jumps before
 * anyone saw it comes back once the Undo is done. The same goes for [showSnackbarOverReason].
 */
internal suspend fun SnackbarHostState.showUiMessages(
    messages: Flow<UiMessage>,
    context: Context,
    onUndo: (UiMessageAction.Undo) -> Unit,
) = coroutineScope {
    var sticky: Job? = null
    var stickyMinimum: Job? = null
    // Set while a reason waits behind another snackbar.
    var unshownReason: ReasonVisuals? = null
    var stickyReason: ReasonVisuals? = null

    fun holdReason(reason: ReasonVisuals) {
        // A reason held again after an Undo can still carry the flag from its last hold.
        reason.displacedUnseen = false
        unshownReason = reason
        stickyReason = reason
        // Counted from when the reason shows: it can wait behind a long snackbar first.
        val minimum = launch {
            snapshotFlow { currentSnackbarData?.visuals === reason }.first { it }
            unshownReason = null
            delay(STICKY_MESSAGE_MINIMUM_MS)
        }
        // Once the reason is gone, by Dismiss or by a direct message, nothing waits on it.
        sticky = launch {
            try {
                showSnackbar(reason)
                // Back in line, behind the direct message that jumped it. Held before the minimum ends,
                // since cancelling it can resume a waiting message on the spot.
                if (reason.displacedUnseen) holdReason(ReasonVisuals(reason.message))
            } finally {
                minimum.cancel()
                if (unshownReason === reason) unshownReason = null
            }
        }
        stickyMinimum = minimum
    }

    messages.collect { message ->
        if (message.action == null) {
            // A reason a direct message jumps is held again on its way out, so wait out that hold too.
            var waited: Job?
            do {
                waited = stickyMinimum
                waited?.join()
            } while (stickyMinimum !== waited)
        }
        // A reason displaced unseen whose own re-hold hasn't run yet is cancelled below, so it's jumped too.
        val jumpedReason = if (message.action != null) {
            unshownReason ?: stickyReason?.takeIf { it.displacedUnseen }
        } else {
            null
        }
        // Cancelling the shown snackbar's call takes it off screen and frees the host for this one.
        sticky?.cancelAndJoin()
        // An Undo keeps its button, so a message carrying one takes the path below.
        if (message.stayUntilDismissed && message.action == null) {
            holdReason(ReasonVisuals(message.resolve(context)))
            return@collect
        }
        val result = showSnackbar(
            message = message.resolve(context),
            actionLabel = message.action?.let { context.getString(R.string.action_undo) },
            withDismissAction = message.action != null,
            duration = when {
                message.action != null -> SnackbarDuration.Indefinite
                message.longDuration -> SnackbarDuration.Long
                else -> SnackbarDuration.Short
            },
        )
        val action = message.action
        if (result == SnackbarResult.ActionPerformed && action is UiMessageAction.Undo) onUndo(action)
        jumpedReason?.let { holdReason(it) }
    }
}

/**
 * What a snackbar's Undo runs. Deletes ask for no confirmation, so this is how one is taken back.
 * It skips the "Review what actually changed" dialog: the person just made that change and is
 * taking it back on the spot, so there's nothing to review. Undo from a task's menu still shows it.
 * Kept out of [ActiveAutomationViewModel], which sits against its line ceiling.
 */
internal fun ActiveAutomationViewModel.undo(action: UiMessageAction.Undo) =
    transitionEditAsync(action.entityType, action.entityId, redo = false, review = false)

/** As long as a short snackbar stays up, so a reason never flashes past unread. */
internal const val STICKY_MESSAGE_MINIMUM_MS = 4_000L

/**
 * For snackbars shown outside [showUiMessages]. A failure reason holds the host until dismissed, so
 * a message answering a tap takes its place rather than queueing behind it for good. A reason that
 * only comes up after this message was asked for hasn't been read yet, so it returns afterwards.
 */
internal suspend fun SnackbarHostState.showSnackbarOverReason(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
): SnackbarResult = coroutineScope {
    val visuals = DirectVisuals(message, actionLabel, withDismissAction)
    // Started on the spot, so "already up" means up when this was asked for, not a dispatch later.
    val clearing = launch(start = CoroutineStart.UNDISPATCHED) {
        var alreadyUp = true
        snapshotFlow { currentSnackbarData }.first { data ->
            val reason = data?.visuals as? ReasonVisuals
            if (reason != null) {
                if (!alreadyUp) reason.displacedUnseen = true
                data?.dismiss()
            }
            alreadyUp = false
            data?.visuals === visuals
        }
    }
    try {
        showSnackbar(visuals)
    } finally {
        clearing.cancel()
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
 * Why a picked or pasted file didn't open, kept up until dismissed. A damaged or cut-off file says
 * so; the parsers' own text stays in Diagnostics, which the fallback copy points to.
 */
internal fun importPreviewFailureMessage(error: Throwable, @StringRes fallbackRes: Int): UiMessage {
    val logged = loggedUiErrorMessage(error, fallbackRes)
    // A malformed Tasker file reaches here as the XML preflight's IllegalArgumentException.
    val damaged = error is SerializationException || error is SAXException || error.cause is SAXException
    return (if (damaged) UiMessage(R.string.ui_error_import_damaged) else logged).copy(stayUntilDismissed = true)
}

/**
 * The message shown for a failed operation. Corrupt-record failures keep their dedicated recovery
 * copy; a [UiRejection] states its own reason; anything else falls back to the caller's resource.
 */
internal fun uiErrorMessage(error: Throwable, @StringRes fallbackRes: Int): UiMessage = when (error) {
    is UiRejection -> UiMessage(error.copyRes, error.copyArgs)
    is ImportBudgetExceededException -> when (error.budgetName) {
        // The guard's own word. Someone importing macros has never seen an "entity".
        "entities" -> UiMessage(R.string.ui_error_import_budget_items, listOf(error.limit))
        else -> UiMessage(R.string.ui_error_import_budget, listOf(error.budgetName, error.limit))
    }
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
