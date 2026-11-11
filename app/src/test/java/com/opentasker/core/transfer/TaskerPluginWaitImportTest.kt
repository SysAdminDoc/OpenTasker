package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long Tasker waits for a plugin step (arg3, in seconds) carries over as the step's wait. */
class TaskerPluginWaitImportTest {
    private fun import(arg3: String) = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData>
                <Task sr="task1">
                    <id>1</id>
                    <nme>Plugin task</nme>
                    <Action sr="act0" ve="7">
                        <code>703953103</code>
                        <Bundle sr="arg0">
                            <Vals sr="val">
                                <k>v</k>
                                <k-type>java.lang.String</k-type>
                            </Vals>
                        </Bundle>
                        <Str sr="arg1" ve="3">com.example.plugin</Str>
                        <Str sr="arg2" ve="3">com.example.plugin.EditActivity</Str>
                        $arg3
                    </Action>
                </Task>
            </TaskerData>
        """.trimIndent(),
        appVersion = "test",
        importedAtEpochMs = 123L,
    )

    @Test
    fun aWaitPastThirtyMinutesIsCappedAndSaysSo() {
        val report = import("""<Int sr="arg3" val="3600"/>""")

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("plugin.locale.fire", action.type)
        assertEquals("1800000", action.args["timeoutMs"])
        val warning = report.lossyWarnings.single { "com.example.plugin" in it }
        assertTrue(warning, "3600 seconds" in warning)
        assertTrue(warning, "30 minutes" in warning)
    }

    @Test
    fun aStepWithNoWaitKeepsTheDefault() {
        val report = import("")

        val action = report.bundle.tasks.single().actions.single()
        assertFalse(action.args.containsKey("timeoutMs"))
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.none { "com.example.plugin" in it })
    }

    @Test
    fun aWaitWithinThirtyMinutesCarriesOverExactly() {
        val action = import("""<Int sr="arg3" val="1800"/>""").bundle.tasks.single().actions.single()

        assertEquals("1800000", action.args["timeoutMs"])
    }
}
