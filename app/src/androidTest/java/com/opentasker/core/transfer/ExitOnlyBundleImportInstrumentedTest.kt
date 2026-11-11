package com.opentasker.core.transfer

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bundle import keeps a profile whose only task is its exit task, with the exit task remapped,
 * and skips one whose enter task id is broken (negative) instead of reading that as "no enter task".
 */
@RunWith(AndroidJUnit4::class)
class ExitOnlyBundleImportInstrumentedTest {
    @Test
    fun anExitOnlyProfileImportsAndBrokenOnesAreSkipped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // Takes id 1 so the imported task can't keep its bundle id by accident.
            db.taskDao().insert(Task(name = "Existing task").toEntity())
            val lightsOff = Task(id = 1, name = "Lights off", actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "off"))))
            val workHours = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "08:00", "end" to "17:30")))
            val bundle = OpenTaskerBundle(
                appVersion = "test",
                exportedAtEpochMs = 0L,
                tasks = listOf(lightsOff),
                profiles = listOf(
                    Profile(id = 1, name = "Leaving home", contexts = workHours, enterTaskId = 0, exitTaskId = 1),
                    Profile(id = 2, name = "Broken enter", contexts = workHours, enterTaskId = -1, exitTaskId = 1),
                    Profile(id = 3, name = "Exit gone", contexts = workHours, enterTaskId = 0, exitTaskId = 99),
                ),
            )

            val report = OpenTaskerBundleRepository(db).importBundle(bundle)

            assertEquals(report.lossyWarnings.toString(), 1, report.insertedProfiles)
            val imported = db.profileDao().getAll().single().toDomain()
            val newTaskId = db.taskDao().getAll().map { it.toDomain() }.single { it.name == "Lights off" }.id
            assertEquals("Leaving home", imported.name)
            assertEquals(0L, imported.enterTaskId)
            assertEquals(newTaskId, imported.exitTaskId)
            assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { "'Broken enter'" in it && "-1" in it })
            assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { "'Exit gone'" in it })
        } finally {
            db.close()
        }
    }
}
