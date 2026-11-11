package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The switch Profile Status writes through: one column, and never past an import's review. */
@RunWith(AndroidJUnit4::class)
class ProfileSetEnabledInstrumentedTest {

    private fun buildDb() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun profile(name: String, enabled: Boolean, needsReview: Boolean = false) = ProfileEntity(
        name = name,
        enabled = enabled,
        enterTaskId = 1,
        exitTaskId = null,
        cooldownSec = 30,
        contextsJson = "[]",
        requiresRiskAcknowledgement = needsReview,
        priority = 7,
    )

    @Test
    fun turnsAProfileOnAndOffAndLeavesTheRestOfItsRowAlone() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.profileDao()
            val id = dao.insert(profile("Mine", enabled = false))
            val before = dao.getById(id)!!

            assertEquals(1, dao.setEnabled(id, true))
            assertEquals(before.copy(enabled = true), dao.getById(id))
            assertEquals(1, dao.setEnabled(id, false))
            assertEquals(before, dao.getById(id))
        } finally {
            db.close()
        }
    }

    @Test
    fun anImportStillWaitingForReviewCanBeTurnedOffButNotOn() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.profileDao()
            val id = dao.insert(profile("Imported", enabled = false, needsReview = true))

            assertEquals(0, dao.setEnabled(id, true))
            assertEquals(false, dao.getById(id)!!.enabled)
            assertEquals(1, dao.setEnabled(id, false))
            assertEquals(0, dao.setEnabled(id + 100, false))
        } finally {
            db.close()
        }
    }
}
