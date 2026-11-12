package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.references.AutomationReferenceRewriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A task whose name is a number, like "5", is still the task a Perform Task or Stop calls after a
 * Tasker export and import, even though a run here reads a number as an id first. Also covers the
 * other keys and spellings a step can name its task with on the way out.
 */
class TaskerTaskRefRoundTripTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private val helper = Task(id = 2, name = "Helper")

    private fun export(vararg steps: ActionSpec, others: List<Task> = listOf(helper)) =
        TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Caller", actions = steps.toList())) + others)

    /** Imports [xml] the way a bundle import does, with every task under a fresh id 100 higher. */
    private fun importAsBundle(xml: String): List<Task> {
        val tasks = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L).bundle.tasks
        val idMap = tasks.associate { it.id to it.id + 100 }
        val inserted = tasks.map { it.copy(id = idMap.getValue(it.id)) }
        val remapped = AutomationReferenceRewriter.remapIds(idMap = idMap, tasks = inserted).tasks.associateBy { it.id }
        return inserted.map { remapped[it.id] ?: it }
    }

    @Test
    fun aCallToATaskNamedWithANumberStillReachesItAfterARoundTrip() {
        // Renaming "Five" to "5" pinned the call to its id, 3, and another task holds id 5.
        val result = export(
            ActionSpec(type = "task.run", args = mapOf("task" to "3")),
            others = listOf(Task(id = 3, name = "5"), Task(id = 5, name = "Other")),
        )
        assertTrue(result.xml, """<Str sr="arg0" ve="3">5</Str>""" in result.xml)

        val tasks = importAsBundle(result.xml)
        val target = tasks.single { it.name == "5" }
        val step = tasks.single { it.name == "Caller" }.actions.single()

        assertEquals(target.id.toString(), step.args["task"])
    }

    @Test
    fun aStopOfATaskNamedWithANumberStillReachesItAfterARoundTrip() {
        val result = export(
            ActionSpec(type = "task.stop", args = mapOf("task" to "3")),
            others = listOf(Task(id = 3, name = "5"), Task(id = 5, name = "Other")),
        )

        val tasks = importAsBundle(result.xml)
        val step = tasks.single { it.name == "Caller" }.actions.single()

        assertEquals("task.stop", step.type)
        assertEquals(tasks.single { it.name == "5" }.id.toString(), step.args["task"])
    }

    @Test
    fun aStopNamedByIdComesBackByName() {
        val result = export(ActionSpec(type = "task.stop", args = mapOf("task" to "2")))

        val step = importAsBundle(result.xml).single { it.name == "Caller" }.actions.single()

        assertEquals("task.stop", step.type)
        assertEquals("Helper", step.args["task"])
    }

    @Test
    fun aNameThatIsNotANumberIsLeftAsWritten() {
        val result = export(ActionSpec(type = "task.run", args = mapOf("task" to "Helper")), others = listOf(helper, Task(id = 5, name = "5")))

        val step = importAsBundle(result.xml).single { it.name == "Caller" }.actions.single()

        assertEquals("Helper", step.args["task"])
    }

    @Test
    fun theOtherKeysAndAPaddedIdNameTheTaskToo() {
        for (args in listOf(mapOf("name" to "2"), mapOf("id" to "2"), mapOf("task" to " 2 "), mapOf("task" to " ", "name" to "2"))) {
            val result = export(ActionSpec(type = "task.run", args = args))

            assertTrue("$args\n${result.xml}", """<Str sr="arg0" ve="3">Helper</Str>""" in result.xml)
            assertEquals(args.toString(), emptyList<String>(), result.warnings)
        }
    }

    @Test
    fun aStopOfATaskLeftOutOfTheExportSaysWhichTaskItIsIn() {
        val result = export(ActionSpec(type = "task.stop", args = mapOf("task" to "99")))

        assertTrue(result.xml, """<Str sr="arg1" ve="3">99</Str>""" in result.xml)
        assertTrue(result.warnings.toString(), result.warnings.any { "'Caller'" in it && "task id 99" in it })
    }
}
