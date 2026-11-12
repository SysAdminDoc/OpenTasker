package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Perform Task and Stop find their task by name. A step here can name it by id (pointing a
 * deleted task's steps at another task, or a bundle import, writes ids), so the export writes the
 * name Tasker needs.
 */
class TaskerTaskRefExportTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private val helper = Task(id = 2, name = "Helper")

    private fun export(vararg steps: ActionSpec, others: List<Task> = listOf(helper)) =
        TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Caller", actions = steps.toList())) + others)

    private fun run(ref: String) = ActionSpec(type = "task.run", args = mapOf("task" to ref))

    @Test
    fun aSubTaskNamedByIdGoesOutAsThatTasksName() {
        val result = export(run("2"))

        assertTrue(result.xml, """<Str sr="arg0" ve="3">Helper</Str>""" in result.xml)
        assertEquals(emptyList<String>(), result.warnings)
        val back = TaskerXmlImporter.parse(result.xml, appVersion = "test", importedAtEpochMs = 0L)
        val step = back.bundle.tasks.single { it.name == "Caller" }.actions.single()
        assertEquals("Helper", step.args["task"])
    }

    @Test
    fun aStopTaskNamedByIdGoesOutAsThatTasksName() {
        val result = export(ActionSpec(type = "task.stop", args = mapOf("task" to "2")))

        assertTrue(result.xml, """<Str sr="arg1" ve="3">Helper</Str>""" in result.xml)
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun anIdOfATaskLeftOutOfTheExportStaysAndSaysSo() {
        val result = export(run("99"))

        assertTrue(result.xml, """<Str sr="arg0" ve="3">99</Str>""" in result.xml)
        assertTrue(result.warnings.toString(), result.warnings.any { "task id 99" in it && "isn't in this export" in it })
    }

    @Test
    fun aNumberThatIsATasksNameStaysAsWritten() {
        val result = export(run("2024"), others = listOf(Task(id = 5, name = "2024")))

        assertTrue(result.xml, """<Str sr="arg0" ve="3">2024</Str>""" in result.xml)
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun anIdWinsOverATaskNamedWithThatNumberLikeItDoesInARun() {
        val result = export(run("2"), others = listOf(helper, Task(id = 5, name = "2")))

        assertTrue(result.xml, """<Str sr="arg0" ve="3">Helper</Str>""" in result.xml)
    }

    @Test
    fun aTaskNamedThroughAVariableStaysAsWritten() {
        val result = export(run("%target"))

        assertTrue(result.xml, """<Str sr="arg0" ve="3">%target</Str>""" in result.xml)
        assertEquals(emptyList<String>(), result.warnings)
    }
}
