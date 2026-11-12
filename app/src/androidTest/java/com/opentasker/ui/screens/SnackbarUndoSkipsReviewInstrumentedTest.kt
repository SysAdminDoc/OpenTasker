package com.opentasker.ui.screens

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.R
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The snackbar's Undo takes back a delete someone just made, so it brings the item back without
 * the "Review what actually changed" dialog. Undo picked from a task's menu still shows it.
 */
@RunWith(AndroidJUnit4::class)
class SnackbarUndoSkipsReviewInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun undoFromTheSnackbarRestoresWithoutTheReviewAndTheMenuUndoStillShowsIt() = runBlocking {
        val fallbackTask = FallbackTaskSettings(context)
        val savedFallbackTaskId = fallbackTask.loadTaskId()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val viewModel = ViewModelProvider(store, ActiveAutomationViewModelFactory(db, context))[ActiveAutomationViewModel::class.java]
            val task = Task(id = TASK_ID, name = "Morning focus")
            db.taskDao().insert(task.toEntity())

            viewModel.deleteTask(task)
            val deleted = viewModel.nextMessage()
            viewModel.undo(deleted.action as UiMessageAction.Undo)
            assertEquals(R.string.ui_message_edit_undone, viewModel.nextMessage().resId)
            assertEquals("Morning focus", db.taskDao().getById(TASK_ID)?.name)
            assertNull(viewModel.semanticDiffReview.value)
            assertTrue(viewModel.highlightedFlowNodeKeys.value.isEmpty())

            // The same delete undone from the task's menu is the review's case.
            viewModel.deleteTask(task)
            assertEquals(R.string.ui_message_task_deleted, viewModel.nextMessage().resId)
            viewModel.undoLastTaskEdit(TASK_ID)
            assertEquals(R.string.ui_message_edit_undone, viewModel.nextMessage().resId)
            assertNotNull(viewModel.semanticDiffReview.value)
        } finally {
            store.clear()
            db.close()
            fallbackTask.saveTaskId(savedFallbackTaskId)
        }
    }

    private suspend fun ActiveAutomationViewModel.nextMessage(): UiMessage = withTimeout(10_000) { messages.first() }

    private companion object {
        // Far from anything the app on the device holds, since grants and widget state are keyed by id.
        const val TASK_ID = 9_100_011L
    }
}
