package com.opentasker.ui.screens

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.R
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.ProjectEntity
import com.opentasker.core.storage.VariableEditHistoryIdentity
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deletes ask for no confirmation, so the snackbar's Undo is the way back. Each delete here runs
 * through the view model the screens call, and the Undo its message carries goes through
 * [ActiveAutomationViewModel.undo], the same as tapping Undo.
 */
@RunWith(AndroidJUnit4::class)
class DeleteThenUndoInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aDeletedProfileComesBackOnUndo() = withViewModel { db, viewModel ->
        db.taskDao().insert(Task(id = TASK_ID, name = "Morning focus").toEntity())
        val profile = Profile(id = PROFILE_ID, name = "Work hours", enterTaskId = TASK_ID)
        db.profileDao().insert(profile.toEntity())

        viewModel.deleteProfile(profile)
        val deleted = viewModel.nextMessage()

        assertEquals(R.string.ui_message_profile_deleted, deleted.resId)
        assertEquals(UiMessageAction.Undo(EditHistoryDao.TYPE_PROFILE, PROFILE_ID), deleted.action)
        assertNull(db.profileDao().getById(PROFILE_ID))
        undo(viewModel, deleted)
        assertEquals("Work hours", db.profileDao().getById(PROFILE_ID)?.name)
    }

    @Test
    fun aTaskNothingUsesIsDeletedOnTheTapAndComesBackOnUndo() = withViewModel { db, viewModel ->
        val task = Task(id = TASK_ID, name = "Morning focus")
        db.taskDao().insert(task.toEntity())

        // The screen only asks first when something still uses the task.
        assertFalse(viewModel.taskDeletionPreview(task).hasDependents)
        viewModel.deleteTask(task)
        val deleted = viewModel.nextMessage()

        assertEquals(R.string.ui_message_task_deleted, deleted.resId)
        assertEquals(UiMessageAction.Undo(EditHistoryDao.TYPE_TASK, TASK_ID), deleted.action)
        assertNull(db.taskDao().getById(TASK_ID))
        undo(viewModel, deleted)
        assertEquals("Morning focus", db.taskDao().getById(TASK_ID)?.name)
    }

    @Test
    fun aDeletedSceneComesBackOnUndo() = withViewModel { db, viewModel ->
        val scene = Scene(id = SCENE_ID, name = "Dashboard", widthDp = 240, heightDp = 160)
        db.sceneDao().insert(scene.toEntity())

        viewModel.deleteScene(scene)
        val deleted = viewModel.nextMessage()

        assertEquals(R.string.ui_message_scene_deleted, deleted.resId)
        assertEquals(UiMessageAction.Undo(EditHistoryDao.TYPE_SCENE, SCENE_ID), deleted.action)
        assertNull(db.sceneDao().getById(SCENE_ID))
        undo(viewModel, deleted)
        assertEquals("Dashboard", db.sceneDao().getById(SCENE_ID)?.name)
    }

    @Test
    fun aDeletedVariableComesBackOnUndo() = withViewModel { db, viewModel ->
        val variables = VariableRepository(db.variableDao())
        variables.upsert(Variable("Commute", "45", isGlobal = true, projectId = DEFAULT_PROJECT_ID))

        viewModel.deleteVariable("Commute", UiMessage(R.string.variables_deleted, listOf("Commute")), DEFAULT_PROJECT_ID)
        val deleted = viewModel.nextMessage()

        assertEquals(R.string.variables_deleted, deleted.resId)
        assertEquals(
            UiMessageAction.Undo(EditHistoryDao.TYPE_VARIABLE, VariableEditHistoryIdentity.entityId(DEFAULT_PROJECT_ID, "Commute")),
            deleted.action,
        )
        assertNull(variables.get("Commute", DEFAULT_PROJECT_ID))
        undo(viewModel, deleted)
        assertEquals("45", variables.get("Commute", DEFAULT_PROJECT_ID)?.value)
    }

    private suspend fun undo(viewModel: ActiveAutomationViewModel, deleted: UiMessage) {
        viewModel.undo(deleted.action as UiMessageAction.Undo)
        assertEquals(R.string.ui_message_edit_undone, viewModel.nextMessage().resId)
    }

    private suspend fun ActiveAutomationViewModel.nextMessage(): UiMessage = withTimeout(10_000) { messages.first() }

    /**
     * An in-memory database the way the app builds it, main-thread reads still refused. The view
     * model clears a stored fallback task that isn't in its database, so the app's own is put back.
     */
    private fun withViewModel(block: suspend (AppDatabase, ActiveAutomationViewModel) -> Unit) = runBlocking {
        val fallbackTask = FallbackTaskSettings(context)
        val savedFallbackTaskId = fallbackTask.loadTaskId()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            db.projectDao().insert(ProjectEntity(id = DEFAULT_PROJECT_ID, name = "Default", position = 0))
            val viewModel = ViewModelProvider(store, ActiveAutomationViewModelFactory(db, context))[ActiveAutomationViewModel::class.java]
            block(db, viewModel)
        } finally {
            store.clear()
            db.close()
            fallbackTask.saveTaskId(savedFallbackTaskId)
        }
    }

    private companion object {
        // Far from anything the app on the device holds, since grants and widget state are keyed by id.
        const val TASK_ID = 9_100_001L
        const val PROFILE_ID = 9_100_002L
        const val SCENE_ID = 9_100_003L
    }
}
