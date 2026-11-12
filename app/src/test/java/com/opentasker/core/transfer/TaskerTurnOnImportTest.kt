package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Turn On (806) turns the screen on, which is what the wake step does. Its one field, Block
 * Time, has nothing to land on here, so the import says it was left out. The 500 ms step is copied
 * from a real export, and the other Block Time uses the same layout with arg0 changed.
 */
class TaskerTurnOnImportTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(blockTime: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Screen</nme>
                        <Action sr="act0" ve="7"><code>806</code>$blockTime</Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    @Test
    fun turnOnImportsAsTheWakeStepAndSaysTheBlockTimeWasLeftOut() {
        val report = parse("""<Int sr="arg0" val="500"/>""")

        assertEquals("wake", report.bundle.tasks.single().actions.single().type)
        assertEquals(listOf("806" to "wake"), report.mappedActions.map { it.taskerCode to it.openTaskerActionId })
        assertEquals(1, report.lossyWarnings.size)
        assertTrue(report.lossyWarnings.toString(), "Block Time" in report.lossyWarnings.toString())
        assertTrue(report.lossyWarnings.toString(), "500 ms" in report.lossyWarnings.toString())
    }

    @Test
    fun theWarningNamesTheBlockTimeTheExportHad() {
        val report = parse("""<Int sr="arg0" val="250"/>""")

        assertEquals("wake", report.bundle.tasks.single().actions.single().type)
        assertTrue(report.lossyWarnings.toString(), "250 ms" in report.lossyWarnings.toString())
    }

    @Test
    fun theWakeStepExportsAsTurnOnAndComesBack() {
        val report = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Screen", actions = listOf(ActionSpec(type = "wake")))),
        )

        assertTrue(report.xml, "<code>806</code>" in report.xml)
        assertTrue(report.xml, """<Int sr="arg0" val="500"/>""" in report.xml)
        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
        assertEquals("wake", back.bundle.tasks.single().actions.single().type)
    }
}
