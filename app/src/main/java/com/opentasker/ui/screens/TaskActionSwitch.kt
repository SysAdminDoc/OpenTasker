package com.opentasker.ui.screens

import com.opentasker.app.R
import com.opentasker.core.model.Task
import com.opentasker.core.storage.EditHistoryDao

/** Turns one step of [task] off or back on, saved as an ordinary undoable task edit. */
fun ActiveAutomationViewModel.setTaskActionEnabled(task: Task, index: Int, enabled: Boolean) {
    require(index in task.actions.indices) { "Action index is out of range." }
    updateTask(
        task.copy(actions = task.actions.mapIndexed { actionIndex, action -> if (actionIndex == index) action.copy(enabled = enabled) else action }),
        if (enabled) R.string.ui_message_action_turned_on else R.string.ui_message_action_turned_off,
        UiMessageAction.Undo(EditHistoryDao.TYPE_TASK, task.id),
    )
}
