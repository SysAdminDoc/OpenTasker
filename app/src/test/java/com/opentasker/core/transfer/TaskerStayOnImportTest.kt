package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Stay On (820) picks one of eight choices in arg0: Never, With AC Power, With USB Power,
 * With AC or USB Power, With Wireless Power, With Wireless or AC Power, With Wireless or USB Power and
 * With Wireless, AC or USB Power. Android keeps the same choice in stay_on_while_plugged_in as a mask
 * of AC 1, USB 2 and wireless 4, so each index is already that mask. Mode 0 appears in a real export.
 * The other modes, the missing mode and the variable mode use the same layout with arg0 changed.
 */
class TaskerStayOnImportTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(mode: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Stay On</nme>
                        <Action sr="act0" ve="7"><code>820</code>$mode</Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun step(mode: String): ActionSpec = parse(mode).bundle.tasks.single().actions.single()

    @Test
    fun neverWritesZeroToTheGlobalStayOnSetting() {
        val report = parse("""<Int sr="arg0" val="0"/>""")
        val spec = report.bundle.tasks.single().actions.single()

        assertEquals("settings.write", spec.type)
        assertEquals(
            mapOf("table" to "global", "key" to "stay_on_while_plugged_in", "value" to "0"),
            spec.args,
        )
        assertEquals(listOf("820" to "settings.write"), report.mappedActions.map { it.taskerCode to it.openTaskerActionId })
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
    }

    @Test
    fun eachPowerChoiceWritesAndroidsPlugMask() {
        val ac = 1
        val usb = 2
        val wireless = 4
        listOf(
            1 to ac,
            2 to usb,
            3 to (ac or usb),
            4 to wireless,
            5 to (wireless or ac),
            6 to (wireless or usb),
            7 to (wireless or ac or usb),
        ).forEach { (mode, mask) ->
            val spec = step("""<Int sr="arg0" val="$mode"/>""")

            assertEquals("$mode", "settings.write", spec.type)
            assertEquals("$mode", mask.toString(), spec.args["value"])
        }
    }

    @Test
    fun aMissingVariableOrUnlistedModeIsNotGuessed() {
        val missing = step("")
        val variable = step("""<Int sr="arg0"><var>%plug</var></Int>""")
        val unlisted = step("""<Int sr="arg0" val="8"/>""")

        listOf(missing, variable, unlisted).forEach {
            assertEquals(it.toString(), TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, it.type)
        }
        assertTrue(missing.args.getValue("summary"), "no mode" in missing.args.getValue("summary"))
        assertTrue(variable.args.getValue("summary"), "%plug" in variable.args.getValue("summary"))
        assertTrue(unlisted.args.getValue("summary"), "mode 8" in unlisted.args.getValue("summary"))
    }

    @Test
    fun theImportedStepExportsAsACustomSettingThatComesBackTheSame() {
        val imported = step("""<Int sr="arg0" val="3"/>""")
        val report = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Stay On", actions = listOf(imported))),
        )

        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
            .bundle.tasks.single().actions.single()
        assertEquals("settings.write", back.type)
        assertEquals(imported.args, back.args)
    }
}
