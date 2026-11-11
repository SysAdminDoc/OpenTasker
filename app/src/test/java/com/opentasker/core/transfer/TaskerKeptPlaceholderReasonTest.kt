package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tasker actions that stay placeholders on purpose say why in their import note (the
 * placeholder's summary), instead of the generic line every unknown code gets. Each step below is
 * copied from a real export.
 */
class TaskerKeptPlaceholderReasonTest {
    private fun parse(action: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
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

    private fun importNote(action: String): String {
        val report = parse(action)
        val step = report.bundle.tasks.single().actions.single()
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, step.type)
        return step.args.getValue("summary")
    }

    private val genericNote = importNote("""<Action sr="act0" ve="7"><code>9999</code></Action>""")

    @Test
    fun aKeyboardStepSaysOpenTaskerHasNoKeyboardToTypeWith() {
        val note = importNote(
            """
            <Action sr="act2" ve="7">
                <code>328</code>
                <Bundle sr="arg0">
                    <pref>ly</pref>
                    <Vals sr="val">
                        <net.dinglisch.android.tasker.RELEVANT_VARIABLES>&lt;StringArray sr=""&gt;&lt;_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;%kb_text
Text
Current text&lt;/_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;&lt;/StringArray&gt;</net.dinglisch.android.tasker.RELEVANT_VARIABLES>
                        <net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>[Ljava.lang.String;</net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>
                    </Vals>
                </Bundle>
                <Str sr="arg1" ve="3">wait(0)</Str>
                <Int sr="arg2">
                    <var>0</var>
                </Int>
                <Int sr="arg3" val="1"/>
            </Action>
            """,
        )

        assertTrue(note, "keyboard" in note)
    }

    @Test
    fun javaFunctionSaysImportedCodeDoesntRun() {
        val note = importNote(
            """
            <Action sr="act1" ve="7">
                <code>664</code>
                <Str sr="arg0" ve="3">%files_dir</Str>
                <Str sr="arg1" ve="3">CONTEXT</Str>
                <Str sr="arg2" ve="3">getFilesDir
{File} ()</Str>
                <Str sr="arg3" ve="3"/>
                <Str sr="arg4" ve="3"/>
                <Str sr="arg5" ve="3"/>
                <Str sr="arg6" ve="3"/>
                <Str sr="arg7" ve="3"/>
                <Str sr="arg8" ve="3"/>
                <Str sr="arg9" ve="3"/>
            </Action>
            """,
        )

        assertTrue(note, "Java" in note)
    }

    @Test
    fun runShellPointsAtAnApprovedShizukuScript() {
        val note = importNote(
            """
            <Action sr="act17" ve="5">
                <code>123</code>
                <Str sr="arg0" ve="3">input tap 500 500</Str>
                <Int sr="arg1" val="0"/>
                <Int sr="arg2" val="1"/>
                <Str sr="arg3" ve="3"/>
                <Str sr="arg4" ve="3"/>
                <Str sr="arg5" ve="3"/>
            </Action>
            """,
        )

        assertTrue(note, "Run Shizuku Script" in note)
        assertTrue(note, "approved" in note)
    }

    @Test
    fun showAndDestroySceneSayScenesArentImported() {
        val show = importNote(
            """
            <Action sr="act6" ve="7">
                <code>47</code>
                <Str sr="arg0" ve="3">DB Install Welcome Screen</Str>
                <Int sr="arg1" val="3"/>
                <Int sr="arg2" val="100"/>
                <Int sr="arg3" val="160"/>
                <Int sr="arg4" val="1"/>
                <Int sr="arg5" val="0"/>
                <Int sr="arg6" val="0"/>
                <Int sr="arg7" val="1"/>
            </Action>
            """,
        )
        val destroy = importNote(
            """
            <Action sr="act30" ve="7">
                <code>49</code>
                <se>false</se>
                <Str sr="arg0" ve="3">DB Install Welcome Screen</Str>
            </Action>
            """,
        )

        assertTrue(show, "scenes aren't imported" in show)
        assertEquals(show, destroy)
    }

    @Test
    fun javaScriptletSaysThereIsNoJavaScriptEngine() {
        val note = importNote(
            """
            <Action sr="act17" ve="7">
                <code>129</code>
                <se>false</se>
                <Str sr="arg0" ve="3">var testj = Number.isInteger(global('DB_Shake_Setting')*1)</Str>
                <Str sr="arg1" ve="3"/>
                <Int sr="arg2" val="1"/>
                <Int sr="arg3" val="45"/>
            </Action>
            """,
        )

        assertTrue(note, "JavaScript engine" in note)
    }

    @Test
    fun takePhotoSaysThereIsNoCameraStep() {
        val note = importNote(
            """
            <Action sr="act6" ve="7">
                <code>101</code>
                <Int sr="arg0" val="1"/>
                <Str sr="arg1" ve="3">potato</Str>
                <Int sr="arg2" val="2"/>
                <Int sr="arg3" val="0"/>
                <Int sr="arg4" val="1"/>
                <Str sr="arg5" ve="3">1280x720</Str>
                <Int sr="arg6" val="0"/>
                <Int sr="arg7" val="0"/>
                <Int sr="arg8" val="0"/>
                <Int sr="arg9" val="1"/>
            </Action>
            """,
        )

        assertTrue(note, "camera" in note)
    }

    @Test
    fun keptReasonsDontAddLossyWarningsAndUnknownCodesKeepTheGenericNote() {
        val report = parse("""<Action sr="act30" ve="7"><code>49</code><Str sr="arg0" ve="3">Scene</Str></Action>""")

        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
        assertEquals(listOf("49"), report.unsupportedActions.map { it.taskerCode })
        assertNotEquals(genericNote, importNote("""<Action sr="act0" ve="7"><code>49</code></Action>"""))
        assertTrue(genericNote, "preserved as an unsupported placeholder" in genericNote)
    }
}
