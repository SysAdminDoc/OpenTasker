package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Auto Brightness (808) and Display AutoRotate (822) each switch one system setting,
 * screen_brightness_mode and accelerometer_rotation, which is what Tasker's own action table names
 * for them. Off and on land on the settings write step. Toggle flips whatever the setting holds,
 * which a step that writes one value can't do, so it stays a placeholder. The steps are copied from
 * real exports.
 */
class TaskerSystemSwitchImportTest {

    // The app registers this at startup. Without it a field named "key" masks like a credential.
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(action: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Switch</nme>
                        $action
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun step(code: String, set: String): ActionSpec =
        parse(
            """
            <Action sr="act0" ve="7">
                <code>$code</code>
                $set
            </Action>
            """,
        ).bundle.tasks.single().actions.single()

    @Test
    fun autoBrightnessOnAndOffWriteTheBrightnessModeSetting() {
        val on = step("808", """<Int sr="arg0" val="1"/>""")
        val off = step("808", """<Int sr="arg0" val="0"/>""")

        assertEquals("settings.write", on.type)
        assertEquals(mapOf("table" to "system", "key" to "screen_brightness_mode", "value" to "1"), on.args)
        assertEquals(mapOf("table" to "system", "key" to "screen_brightness_mode", "value" to "0"), off.args)
    }

    @Test
    fun autoRotateOnAndOffWriteTheRotationSetting() {
        val on = step("822", """<Int sr="arg0" val="1"/>""")
        val off = step("822", """<Int sr="arg0" val="0"/>""")

        assertEquals("settings.write", off.type)
        assertEquals(mapOf("table" to "system", "key" to "accelerometer_rotation", "value" to "1"), on.args)
        assertEquals(mapOf("table" to "system", "key" to "accelerometer_rotation", "value" to "0"), off.args)
    }

    @Test
    fun anImportedSwitchAddsNoWarningAndCountsAsMapped() {
        val report = parse("""<Action sr="act4" ve="7"><code>822</code><Int sr="arg0" val="1"/></Action>""")

        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
        assertTrue(report.unsupportedActions.toString(), report.unsupportedActions.isEmpty())
    }

    @Test
    fun toggleStaysAPlaceholderThatSaysWhy() {
        val toggle = step("808", """<Int sr="arg0" val="2"/>""")

        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, toggle.type)
        val note = toggle.args.getValue("summary")
        assertTrue(note, "Toggle" in note && "Auto Brightness" in note)
    }

    @Test
    fun aSetValueFromAVariableOrMissingStaysAPlaceholder() {
        val fromVariable = step("822", """<Int sr="arg0"><var>%rotate</var></Int>""")
        val missing = step("822", "")

        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, fromVariable.type)
        assertTrue(fromVariable.args.getValue("summary"), "%rotate" in fromVariable.args.getValue("summary"))
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, missing.type)
    }

    @Test
    fun theWrittenSettingExportsAsCustomSettingAndComesBackTheSame() {
        val imported = step("808", """<Int sr="arg0" val="1"/>""")
        val report = TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Switch", actions = listOf(imported))))

        assertTrue(report.xml, "<code>235</code>" in report.xml)
        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
        assertEquals(imported.args, back.bundle.tasks.single().actions.single().args)
    }
}
