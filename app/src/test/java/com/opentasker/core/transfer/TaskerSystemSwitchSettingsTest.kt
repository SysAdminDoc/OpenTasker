package com.opentasker.core.transfer

import com.opentasker.core.actions.SettingsTable
import com.opentasker.core.actions.SettingsWriteRequest
import com.opentasker.core.actions.parseSettingsWrite
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The settings write step has to accept what Auto Brightness (808) and Display AutoRotate (822)
 * import as, or every imported switch would fail when it runs. The import report counts the steps
 * as mapped, and the placeholders left behind say which Set value they couldn't use.
 */
class TaskerSystemSwitchSettingsTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(code: String, set: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Switch</nme>
                        <Action sr="act0" ve="7"><code>$code</code>$set</Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun step(code: String, set: String): ActionSpec = parse(code, set).bundle.tasks.single().actions.single()

    @Test
    fun theSettingsWriteStepAcceptsBothSwitches() {
        listOf("808" to "screen_brightness_mode", "822" to "accelerometer_rotation").forEach { (code, key) ->
            listOf("0", "1").forEach { value ->
                val request = parseSettingsWrite(step(code, """<Int sr="arg0" val="$value"/>""").args)

                assertEquals("$code $value", SettingsWriteRequest.Valid(SettingsTable.SYSTEM, key, value), request)
            }
        }
    }

    @Test
    fun theReportCountsTheSwitchAsMapped() {
        val report = parse("808", """<Int sr="arg0" val="1"/>""")

        assertEquals(listOf("808" to "settings.write"), report.mappedActions.map { it.taskerCode to it.openTaskerActionId })
    }

    @Test
    fun aMissingOrUnknownSetValueIsNamedInTheNote() {
        val missing = step("808", "").args.getValue("summary")
        val unknown = step("822", """<Int sr="arg0" val="3"/>""").args.getValue("summary")

        assertTrue(missing, "Auto Brightness has no Set value" in missing)
        assertTrue(unknown, "Set value 3 isn't off, on or toggle" in unknown)
    }
}
