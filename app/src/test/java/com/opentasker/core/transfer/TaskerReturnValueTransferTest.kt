package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.SUB_TASK_RETURN_KEY
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasker's Return and Perform Task's Return Value Variable come in and go back out. */
class TaskerReturnValueTransferTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(xml: String) = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L)

    private fun imported(action: String) = parse(
        "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$action</Action></Task></TaskerData>",
    )

    private fun export(vararg actions: ActionSpec) = TaskerXmlExporter.export(
        emptyList(),
        listOf(Task(id = 1, name = "Exported", actions = actions.toList()), Task(id = 2, name = "Restore", actions = emptyList())),
    )

    private fun exportedAction(xml: String, code: String) =
        xml.split("<Action").filter { "<code>$code</code>" in it }.single()

    @Test
    fun performTasksReturnValueVariableImportsWithNoWarning() {
        // From a real Tasker export.
        val result = imported(
            """<code>130</code><Str sr="arg0" ve="3">Restore</Str><Int sr="arg1" val="100"/><Str sr="arg2" ve="3"/><Str sr="arg3" ve="3"/><Str sr="arg4" ve="3">%restored</Str>""",
        )

        assertEquals(mapOf("task" to "Restore", SUB_TASK_RETURN_KEY to "%restored"), result.bundle.tasks.single().actions.single().args)
        assertTrue(result.lossyWarnings.toString(), result.lossyWarnings.none { "Return" in it || "%restored" in it })
    }

    @Test
    fun theOlderPerformTaskLayoutReadsItsReturnValueVariableToo() {
        val args = imported(
            """<code>130</code><Str sr="arg0" ve="3">Weather</Str><Int sr="arg1" val="100"/><Int sr="arg2" val="0"/><Str sr="arg3" ve="3">manual</Str><Str sr="arg4" ve="3">%city</Str><Str sr="arg5" ve="3">%forecast</Str>""",
        ).bundle.tasks.single().actions.single().args

        assertEquals("%city", args["par2"])
        assertEquals("%forecast", args[SUB_TASK_RETURN_KEY])
    }

    @Test
    fun returnImportsAsStopWithItsValue() {
        // From a real Tasker export.
        val xml = """<code>126</code><Str sr="arg0" ve="3">%par1</Str><Int sr="arg1" val="1"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/><Str sr="arg4" ve="3"/>"""
        val result = imported(xml)
        val stop = result.bundle.tasks.single().actions.single()

        assertEquals("flow.stop", stop.type)
        assertEquals(mapOf("value" to "%par1"), stop.args)
        assertEquals(emptyList<String>(), result.lossyWarnings)

        val passthrough = imported(xml.replace("""<Int sr="arg2" val="0"/>""", """<Int sr="arg2" val="1"/>"""))
        assertEquals("flow.stop", passthrough.bundle.tasks.single().actions.single().type)
        assertTrue(passthrough.lossyWarnings.toString(), passthrough.lossyWarnings.any { "Local Variable Passthrough" in it })

        val keepsRunning = imported(xml.replace("""<Int sr="arg1" val="1"/>""", """<Int sr="arg1" val="0"/>"""))
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, keepsRunning.bundle.tasks.single().actions.single().type)
    }

    @Test
    fun aStopWithAValueExportsAsReturnAndAPlainStopStaysStop() {
        val result = export(ActionSpec(type = "flow.stop", args = mapOf("value" to " %total ")), ActionSpec(type = "flow.stop"))
        val returned = exportedAction(result.xml, "126")

        assertTrue(returned, """<Str sr="arg0" ve="3">%total</Str>""" in returned)
        assertTrue(returned, """<Int sr="arg1" val="1"/>""" in returned)
        assertTrue(result.xml, "<code>137</code>" in result.xml)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())

        val actions = parse(result.xml).bundle.tasks.first { it.name == "Exported" }.actions
        assertEquals(listOf(mapOf("value" to "%total"), emptyMap()), actions.map { it.args })
    }

    @Test
    fun runTasksReturnVariableExportsAsPerformTasksAndReadsBack() {
        val result = export(ActionSpec(type = "task.run", args = mapOf("task" to "Restore", SUB_TASK_RETURN_KEY to "restored")))
        val perform = exportedAction(result.xml, "130")

        assertTrue(perform, """<Str sr="arg4" ve="3">%restored</Str>""" in perform)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        val args = parse(result.xml).bundle.tasks.first { it.name == "Exported" }.actions.single().args
        assertEquals("%restored", args[SUB_TASK_RETURN_KEY])
    }
}
