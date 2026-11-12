package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker's Display Brightness (810) writes only the brightness level, and Tasker's editor warns that
 * Auto Brightness gets in its way. The brightness step here switches to manual first, so an imported
 * Display Brightness says it turns Auto Brightness off. The task is copied from a real export that
 * turns Auto Brightness on and then sets a level.
 */
class TaskerDisplayBrightnessImportTest {

    private fun parse(actions: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Brightness</nme>
                        $actions
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    @Test
    fun aLevelAfterAutoBrightnessOnSaysItTurnsAutoBrightnessOff() {
        val report = parse(
            """
            <Action sr="act0" ve="7"><code>808</code><Int sr="arg0" val="1"/></Action>
            <Action sr="act1" ve="7"><code>810</code><Int sr="arg0" val="100"/><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/></Action>
            """,
        )

        assertEquals(listOf("settings.write", "brightness.set"), report.bundle.tasks.single().actions.map { it.type })
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { "leaves Auto Brightness as it is" in it })
    }

    @Test
    fun aLevelFromAVariableSaysTheSame() {
        val report = parse("""<Action sr="act0" ve="7"><code>810</code><Int sr="arg0"><var>%bri</var></Int><Int sr="arg1" val="1"/></Action>""")

        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { "leaves Auto Brightness as it is" in it })
    }

    @Test
    fun aStepWithNoLevelTurnsAutoBrightnessOnAndDoesNotClaimOtherwise() {
        val report = parse("""<Action sr="act0" ve="7"><code>810</code></Action>""")

        assertEquals("auto", report.bundle.tasks.single().actions.single().args["brightness"])
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.none { "Auto Brightness" in it })
    }
}
