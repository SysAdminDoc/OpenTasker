package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasker's Stop (137) takes the task to stop in arg1, so a Stop Task step goes out as Stop with that name. */
class TaskerStopTaskExportTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun aStopTaskStepExportsAsStopWithTheTaskNamedAndComesBack() {
        val xml = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Exported", actions = listOf(ActionSpec(type = "task.stop", args = mapOf("task" to "Background"))))),
        ).xml

        listOf("<code>137</code>", """<Int sr="arg0" val="0"/>""", """<Str sr="arg1" ve="3">Background</Str>""").forEach { part ->
            assertTrue("expected $part in:\n$xml", part in xml)
        }
        val back = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L)
        val step = back.bundle.tasks.single().actions.single()
        assertEquals("task.stop", step.type)
        assertEquals(mapOf("task" to "Background"), step.args)
    }

    @Test
    fun aStopTaskStepThatNamesNoTaskIsLeftOutInsteadOfBecomingAPlainStop() {
        val result = TaskerXmlExporter.export(
            emptyList(),
            listOf(
                Task(
                    id = 1,
                    name = "Exported",
                    actions = listOf(
                        ActionSpec(type = "task.stop", args = mapOf("task" to " ")),
                        ActionSpec(type = "var.set", args = mapOf("name" to "%after", "value" to "1")),
                    ),
                ),
            ),
        )

        assertFalse(result.xml, "<code>137</code>" in result.xml)
        val skipped = result.skippedActions.single()
        assertEquals("task.stop", skipped.actionType)
        assertTrue(skipped.reason, "plain Stop" in skipped.reason)
        // The step left out doesn't count, so the one after it is Tasker's first.
        assertTrue(result.xml, """<Action sr="act0" ve="7">""" in result.xml)
    }

    @Test
    fun aNamedStopWithErrorComesInAsAStopTaskStepAndSaysWithErrorWasLeftOut() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Stopper</nme>
                        <Action sr="act0" ve="7">
                            <code>137</code>
                            <Int sr="arg0" val="1"/>
                            <Str sr="arg1" ve="3">Background</Str>
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

        val step = report.bundle.tasks.single().actions.single()
        assertEquals("task.stop", step.type)
        assertEquals(mapOf("task" to "Background"), step.args)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { "With Error" in it })
    }
}
