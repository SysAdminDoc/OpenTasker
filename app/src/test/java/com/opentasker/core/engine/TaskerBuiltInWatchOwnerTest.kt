package com.opentasker.core.engine

import com.opentasker.core.engine.variables.TaskerBuiltIns
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The engine stops watching its profiles when the service stops, and a reload still finishing then
 * can't list them again, while a newer engine's list survives the old one stopping.
 */
class TaskerBuiltInWatchOwnerTest {
    @Before
    fun setUp() = TaskerBuiltIns.resetForTests()

    @After
    fun tearDown() = TaskerBuiltIns.resetForTests()

    @Test
    fun aReloadFinishingAfterTheEngineStopsListsNothing() {
        val engine = Job()
        TaskerBuiltIns.watchProfiles(mapOf(1L to "Home"), owner = engine)

        engine.cancel()
        TaskerBuiltIns.stopWatching(engine)
        TaskerBuiltIns.watchProfiles(mapOf(1L to "Home"), owner = engine)

        assertNull(TaskerBuiltIns.value("PENABLED"))
    }

    @Test
    fun anOldEngineStoppingLeavesTheNewOnesList() {
        val old = Job()
        val new = Job()
        TaskerBuiltIns.watchProfiles(mapOf(1L to "Home"), owner = old)
        TaskerBuiltIns.watchProfiles(mapOf(2L to "Car"), owner = new)

        old.cancel()
        TaskerBuiltIns.stopWatching(old)

        assertEquals(",Car,", TaskerBuiltIns.value("PENABLED"))
    }
}
