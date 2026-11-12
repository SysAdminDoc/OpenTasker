package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * More Tasker actions that stay placeholders because nothing here does the same thing: pressing a
 * key, waiting for a condition, asking for Tasker's permissions and changing Tasker itself. Their
 * import notes say so instead of the generic line. Each step is copied from a real export.
 */
class TaskerNoMatchReasonTest {

    private fun importNote(action: String): String {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Kept</nme>
                        $action
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )
        val step = report.bundle.tasks.single().actions.single()
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, step.type)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
        return step.args.getValue("summary")
    }

    @Test
    fun buttonAndBackButtonSayThereIsNoStepThatPressesKeys() {
        val button = importNote("""<Action sr="act6" ve="5"><code>703</code><Int sr="arg0" val="0"/></Action>""")
        val back = importNote("""<Action sr="act2" ve="7"><code>245</code></Action>""")

        assertTrue(button, "no step that presses those keys" in button)
        assertTrue(back, "no step that presses Back" in back)
    }

    @Test
    fun waitUntilSaysTheWaitStepOnlyWaitsASetTime() {
        val note = importNote(
            """
            <Action sr="act7" ve="5">
                <code>35</code>
                <Int sr="arg0" val="129"/>
                <Int sr="arg1" val="0"/>
                <Int sr="arg2" val="0"/>
                <Int sr="arg3" val="0"/>
                <Int sr="arg4" val="0"/>
                <ConditionList sr="if">
                    <Condition sr="c0" ve="2"><lhs>%ON_STARTED</lhs><op>4</op><rhs>0</rhs></Condition>
                </ConditionList>
            </Action>
            """,
        )

        assertTrue(note, "If condition" in note && "set time" in note)
    }

    @Test
    fun askPermissionsPointsAtSetup() {
        val note = importNote(
            """
            <Action sr="act3" ve="7">
                <code>319</code>
                <Str sr="arg0" ve="3">android.permission.WRITE_EXTERNAL_STORAGE</Str>
            </Action>
            """,
        )

        assertTrue(note, "Setup" in note)
    }

    @Test
    fun stepsThatChangeTaskerItselfSaySoByName() {
        listOf(
            "Disable" to """<Action sr="act13" ve="7"><code>139</code><label>Stop Tasker / Monitor thread if self-standing app</label></Action>""",
            "Set Tasker Pref" to """<Action sr="act2" ve="7"><code>133</code><Int sr="arg0" val="22"/><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/></Action>""",
            "Set Tasker Icon" to """<Action sr="act0" ve="7"><code>138</code><Img sr="arg0" ve="2"/></Action>""",
        ).forEach { (name, action) ->
            val note = importNote(action)

            assertTrue(note, "Tasker's $name" in note && "nothing here for it to change" in note)
        }
    }
}
