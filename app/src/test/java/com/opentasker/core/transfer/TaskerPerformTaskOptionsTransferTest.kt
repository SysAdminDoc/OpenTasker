package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.SUB_TASK_RESET_RETURN_KEY
import com.opentasker.core.engine.SUB_TASK_RETURN_KEY
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Perform Task's options past Stop in Tasker's current layout: Reset Return Variable, passthrough and overwrite. */
class TaskerPerformTaskOptionsTransferTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(xml: String) = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L)

    /**
     * Tasker's current 11-field Perform Task, laid out the way its exports write it (args in sort
     * order, priority as a variable), with the return variable and the four options to fill in.
     */
    private fun imported(
        returnVariable: String = "%restored",
        passthrough: Int = 0,
        limit: String = "",
        reset: Int = 0,
        overwrite: Int = 0,
    ) = parse(
        "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\" ve=\"7\"><code>130</code>" +
            "<Str sr=\"arg0\" ve=\"3\">Restore</Str><Int sr=\"arg1\"><var>%priority</var></Int><Int sr=\"arg10\" val=\"1\"/>" +
            "<Str sr=\"arg2\" ve=\"3\"/><Str sr=\"arg3\" ve=\"3\"/>" +
            (if (returnVariable.isEmpty()) "<Str sr=\"arg4\" ve=\"3\"/>" else "<Str sr=\"arg4\" ve=\"3\">$returnVariable</Str>") +
            "<Int sr=\"arg5\" val=\"0\"/><Int sr=\"arg6\" val=\"$passthrough\"/>" +
            (if (limit.isEmpty()) "<Str sr=\"arg7\" ve=\"3\"/>" else "<Str sr=\"arg7\" ve=\"3\">$limit</Str>") +
            "<Int sr=\"arg8\" val=\"$reset\"/><Int sr=\"arg9\" val=\"$overwrite\"/></Action></Task></TaskerData>",
    )

    private fun export(vararg actions: ActionSpec) = TaskerXmlExporter.export(
        emptyList(),
        listOf(Task(id = 1, name = "Exported", actions = actions.toList()), Task(id = 2, name = "Restore", actions = emptyList())),
    )

    private fun exportedAction(xml: String, code: String) =
        xml.split("<Action").filter { "<code>$code</code>" in it }.single()

    @Test
    fun resetReturnVariableImportsAsTheSwitchWithNoNote() {
        val result = imported(reset = 1)

        assertEquals(
            mapOf("task" to "Restore", SUB_TASK_RETURN_KEY to "%restored", SUB_TASK_RESET_RETURN_KEY to "true"),
            result.bundle.tasks.single().actions.single().args,
        )
        assertEquals(emptyList<String>(), result.lossyWarnings)
    }

    @Test
    fun theDefaultsImportWithNoSwitchAndNoNote() {
        val result = imported()

        assertEquals(mapOf("task" to "Restore", SUB_TASK_RETURN_KEY to "%restored"), result.bundle.tasks.single().actions.single().args)
        assertEquals(emptyList<String>(), result.lossyWarnings)
    }

    @Test
    fun resetWithNoReturnVariableHasNothingToClear() {
        val result = imported(returnVariable = "", reset = 1)

        assertEquals(mapOf("task" to "Restore"), result.bundle.tasks.single().actions.single().args)
        assertEquals(emptyList<String>(), result.lossyWarnings)
    }

    @Test
    fun passthroughWithoutALimitMatchesAndOneWithALimitIsNoted() {
        assertEquals(emptyList<String>(), imported(passthrough = 1).lossyWarnings)

        val limited = imported(passthrough = 1, limit = "%city,%day").lossyWarnings
        assertTrue(limited.toString(), limited.any { "Limit Passthrough To" in it })
    }

    @Test
    fun allowOverwriteVariablesIsNoted() {
        val warnings = imported(overwrite = 1).lossyWarnings

        assertTrue(warnings.toString(), warnings.any { "Allow Overwrite Variables" in it })
    }

    @Test
    fun theSwitchExportsAsResetReturnVariableAndReadsBack() {
        val result = export(
            ActionSpec(type = "task.run", args = mapOf("task" to "Restore", SUB_TASK_RETURN_KEY to "restored", SUB_TASK_RESET_RETURN_KEY to "true")),
        )
        val perform = exportedAction(result.xml, "130")

        assertTrue(perform, """<Int sr="arg8" val="1"/>""" in perform)
        assertTrue(perform, """<Int sr="arg10" val="1"/>""" in perform)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        val args = parse(result.xml).bundle.tasks.first { it.name == "Exported" }.actions.single().args
        assertEquals(mapOf("task" to "Restore", SUB_TASK_RETURN_KEY to "%restored", SUB_TASK_RESET_RETURN_KEY to "true"), args)
    }

    @Test
    fun withoutTheSwitchResetReturnVariableExportsOff() {
        val result = export(
            ActionSpec(type = "task.run", args = mapOf("task" to "Restore", SUB_TASK_RETURN_KEY to "restored", SUB_TASK_RESET_RETURN_KEY to "false")),
        )

        assertTrue(result.xml, """<Int sr="arg8" val="0"/>""" in exportedAction(result.xml, "130"))
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }
}
