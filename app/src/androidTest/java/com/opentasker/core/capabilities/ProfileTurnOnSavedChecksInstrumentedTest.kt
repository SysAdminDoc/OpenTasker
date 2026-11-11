package com.opentasker.core.capabilities

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.TaskEntity
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The turn-on checks and the switch that read what's saved, which Profile Status and the broadcast use. */
@RunWith(AndroidJUnit4::class)
class ProfileTurnOnSavedChecksInstrumentedTest {

    private fun buildDb() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun profile(enterTaskId: Long) = Profile(
        name = "Reader",
        enabled = false,
        contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.reader"))),
        enterTaskId = enterTaskId,
    ).toEntity()

    private val plain = Task(name = "Plain", actions = listOf(ActionSpec(type = "notify.show", args = mapOf("title" to "Hi"))))

    @Test
    fun aSavedProfileTheAppWouldTurnOnPassesUntilAnotherSavedTaskCantBeRead() = runBlocking {
        val db = buildDb()
        try {
            val taskId = db.taskDao().insert(plain.toEntity())
            val profileId = db.profileDao().insert(profile(taskId))
            assertNull(ProfileTurnOnChecks.refusalForSaved(db, profileId, canWriteSettings = true))

            db.taskDao().insert(TaskEntity(name = "Corrupt", priority = 0, collisionMode = "ABORT_NEW", actionsJson = "{not json"))
            val message = ProfileTurnOnChecks.refusalForSaved(db, profileId, canWriteSettings = true)
            assertTrue(message.toString(), message!!.contains("couldn't be read"))
        } finally {
            db.close()
        }
    }

    @Test
    fun aSavedProfileWhoseTaskCallsItselfStaysOff() = runBlocking {
        val db = buildDb()
        try {
            val loopId = db.taskDao().insert(plain.toEntity())
            db.taskDao().update(
                Task(id = loopId, name = "Loop", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "$loopId"))))
                    .toEntity(),
            )
            val profileId = db.profileDao().insert(profile(loopId))

            val message = ProfileTurnOnChecks.refusalForSaved(db, profileId, canWriteSettings = true)
            assertTrue(message.toString(), message!!.contains("loop (Loop -> Loop)"))
        } finally {
            db.close()
        }
    }

    @Test
    fun switchingRefusesALoopingProfileButTurnsOnePointedElsewhereOnAndOff() = runBlocking {
        val db = buildDb()
        try {
            val plainId = db.taskDao().insert(plain.toEntity())
            val loopId = db.taskDao().insert(plain.toEntity())
            db.taskDao().update(
                Task(id = loopId, name = "Loop", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "$loopId"))))
                    .toEntity(),
            )
            val profileId = db.profileDao().insert(profile(loopId))

            val message = ProfileTurnOnChecks.switchSaved(db, profileId, enabled = true, canWriteSettings = true)
            assertTrue(message.toString(), message!!.contains("in a loop ("))
            assertFalse(db.profileDao().getById(profileId)!!.enabled)

            val pointed = db.profileDao().getById(profileId)!!.copy(enterTaskId = plainId)
            db.profileDao().update(pointed)
            assertNull(ProfileTurnOnChecks.switchSaved(db, profileId, enabled = true, canWriteSettings = true))
            assertEquals(pointed.copy(enabled = true), db.profileDao().getById(profileId))
            assertNull(ProfileTurnOnChecks.switchSaved(db, profileId, enabled = false, canWriteSettings = true))
            assertEquals(pointed, db.profileDao().getById(profileId))

            val gone = ProfileTurnOnChecks.switchSaved(db, profileId + 100, enabled = false, canWriteSettings = true)
            assertTrue(gone.toString(), gone!!.contains("was removed"))
        } finally {
            db.close()
        }
    }

    @Test
    fun aProfileThatIsNoLongerSavedIsRefused() = runBlocking {
        val db = buildDb()
        try {
            val taskId = db.taskDao().insert(plain.toEntity())
            val profileId = db.profileDao().insert(profile(taskId))

            val message = ProfileTurnOnChecks.refusalForSaved(db, profileId + 100, canWriteSettings = true)
            assertTrue(message.toString(), message!!.contains("was removed"))
        } finally {
            db.close()
        }
    }
}
