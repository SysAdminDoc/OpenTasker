package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Reboot (59) picks Normal, Recovery, Bootloader or Shutdown in arg0. The reboot step here
 * only restarts the phone normally, so Normal maps and the others stay placeholders that name their
 * mode. Types 0 and 3 appear in real exports. The other types, the missing type and the variable type
 * use the same layout with arg0 changed.
 */
class TaskerRebootImportTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(type: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Reboot</nme>
                        <Action sr="act0" ve="7"><code>59</code>$type</Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun step(type: String): ActionSpec = parse(type).bundle.tasks.single().actions.single()

    @Test
    fun aNormalRebootImportsAsTheRebootStep() {
        val report = parse("""<Int sr="arg0" val="0"/>""")

        assertEquals("reboot", report.bundle.tasks.single().actions.single().type)
        assertEquals(listOf("59" to "reboot"), report.mappedActions.map { it.taskerCode to it.openTaskerActionId })
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
    }

    @Test
    fun shutdownRecoveryAndBootloaderStayPlaceholdersThatNameTheirMode() {
        listOf("1" to "Recovery", "2" to "Bootloader", "3" to "Shutdown").forEach { (type, mode) ->
            val spec = step("""<Int sr="arg0" val="$type"/>""")

            assertEquals(type, TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, spec.type)
            assertTrue(spec.args.getValue("summary"), mode in spec.args.getValue("summary"))
        }
    }

    @Test
    fun aMissingOrVariableTypeIsNotGuessed() {
        val missing = step("")
        val variable = step("""<Int sr="arg0"><var>%mode</var></Int>""")

        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, missing.type)
        assertTrue(missing.args.getValue("summary"), "no type" in missing.args.getValue("summary"))
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, variable.type)
        assertTrue(variable.args.getValue("summary"), "%mode" in variable.args.getValue("summary"))
    }

    @Test
    fun aTypeTaskerDoesNotListStaysAPlaceholder() {
        listOf("4", "-1").forEach { type ->
            val spec = step("""<Int sr="arg0" val="$type"/>""")

            assertEquals(type, TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, spec.type)
            assertTrue(spec.args.getValue("summary"), "type $type isn't one Tasker lists" in spec.args.getValue("summary"))
        }
    }

    @Test
    fun theRebootStepExportsAsANormalRebootAndComesBack() {
        val report = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Reboot", actions = listOf(ActionSpec(type = "reboot")))),
        )

        assertTrue(report.xml, "<code>59</code>" in report.xml)
        assertTrue(report.xml, """<Int sr="arg0" val="0"/>""" in report.xml)
        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
        assertEquals("reboot", back.bundle.tasks.single().actions.single().type)
    }
}
