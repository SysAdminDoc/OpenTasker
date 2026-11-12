package com.opentasker.core.actions

import com.opentasker.core.engine.SUB_TASK_RETURN_KEY
import org.junit.Assert.assertEquals
import org.junit.Test

/** Run Task's return variable is a setting of the step, never one of the inputs it passes on. */
class SubTaskInputsReturnVariableTest {
    @Test
    fun anInputCantTakeTheReturnVariablesName() {
        val issues = SubTaskInputs.validate(listOf(SubTaskInput("city", "Boston"), SubTaskInput("%$SUB_TASK_RETURN_KEY", "x")))

        assertEquals(mapOf(1 to SubTaskInputIssue.HoldsTheReturnVariable), issues)
    }

    @Test
    fun savingInputsLeavesTheReturnVariableAlone() {
        val saved = SubTaskInputs.applyTo(
            mapOf("task" to "2", SUB_TASK_RETURN_KEY to "%answer"),
            opened = emptyList(),
            edited = listOf(SubTaskInput(SUB_TASK_RETURN_KEY, "%other"), SubTaskInput("city", "Boston")),
        )

        assertEquals(mapOf("task" to "2", SUB_TASK_RETURN_KEY to "%answer", "city" to "Boston"), saved)
    }

    @Test
    fun theEditorShowsTheReturnVariableAsAFieldNotAnInput() {
        registerActionMetadata()
        val fieldKeys = ActionMetadataRegistry.get("task.run")!!.fields.mapTo(mutableSetOf()) { it.key }

        assertEquals(
            listOf(SubTaskInput("city", "Boston")),
            SubTaskInputs.from(mapOf("task" to "2", SUB_TASK_RETURN_KEY to "%answer", "city" to "Boston"), fieldKeys),
        )
    }
}
