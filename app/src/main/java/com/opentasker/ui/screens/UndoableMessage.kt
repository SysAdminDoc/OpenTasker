package com.opentasker.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shows a message with an Undo action and runs `onUndo` only if the user takes it. */
typealias UndoableMessage = (message: String, onUndo: () -> Unit) -> Unit

/**
 * The shell's [UndoableMessage]. It launches in the shell's [scope] on purpose: a card that raised
 * the message can scroll out of its list, and a snackbar tied to the card's own scope would vanish
 * with it and take the Undo along.
 */
internal fun undoableMessages(scope: CoroutineScope, host: SnackbarHostState, undoLabel: String): UndoableMessage =
    { message, onUndo ->
        scope.launch {
            val result = host.showSnackbarOverReason(message, actionLabel = undoLabel, withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }
