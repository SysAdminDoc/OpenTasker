package com.opentasker.core.storage

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Whether a step is on lives in the task's saved steps, so the database needs no new column. */
class TurnedOffStepStorageTest {

    @Test
    fun stepsSavedBeforeStepsCouldBeTurnedOffReadAsOn() {
        val saved = TaskEntity(
            id = 4,
            name = "Older",
            priority = 5,
            collisionMode = CollisionMode.ABORT_NEW.name,
            actionsJson = """[{"id":1,"type":"notify.show","args":{"text":"hi"}}]""",
        )

        assertEquals(listOf(true), saved.toDomain().actions.map { it.enabled })
    }

    @Test
    fun aStepTurnedOffStaysOffAndAStepOnSavesAsBefore() {
        val task = Task(
            id = 5,
            name = "Mixed",
            actions = listOf(
                ActionSpec(id = 1, type = "notify.show"),
                ActionSpec(id = 2, type = "notify.show", enabled = false),
            ),
        )
        val entity = task.toEntity()

        assertEquals(task, entity.toDomain())
        assertFalse(entity.actionsJson, "\"enabled\":true" in entity.actionsJson)
    }
}
