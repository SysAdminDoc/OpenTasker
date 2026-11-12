package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker actions with no counterpart here stay placeholders, and their import notes say what's
 * missing instead of the generic line. Each step is copied from a real export, with Menu's list of
 * nine items cut to its first two.
 */
class TaskerNoCounterpartReasonTest {

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
    fun mediaButtonsStatusBarAndSystemDialogsSayWhatIsMissing() {
        val media = importNote("""<Action sr="act0" ve="7"><code>490</code><Int sr="arg0" val="0"/><Int sr="arg1" val="0"/></Action>""")
        val statusBar = importNote("""<Action sr="act0" ve="7"><code>512</code><Int sr="arg0" val="1"/></Action>""")
        val dialogs = importNote("""<Action sr="act9" ve="7"><code>513</code></Action>""")

        assertTrue(media, "Tasker's Media Button Events" in media && "no media button trigger" in media)
        assertTrue(statusBar, "Tasker's Status Bar" in statusBar && "notification shade" in statusBar)
        assertTrue(dialogs, "Tasker's Close System Dialogs" in dialogs && "no step that does" in dialogs)
    }

    @Test
    fun stepsWithNoMatchingStepSaySoByName() {
        listOf(
            Triple("Variable Randomize", "no step that does", """<Action sr="act3" ve="7"><code>545</code><Str sr="arg0" ve="3">%lll</Str><Int sr="arg1" val="1"/><Int sr="arg2"><var>%sss</var></Int></Action>"""),
            Triple("Call", "no phone call step", """<Action sr="act4" ve="7"><code>90</code><Str sr="arg0" ve="3">%numbers(1)</Str><Int sr="arg1" val="1"/><Str sr="arg2" ve="3"/></Action>"""),
            Triple("Vibrate Pattern", "takes one length", """<Action sr="act5" ve="7"><code>62</code><Str sr="arg0" ve="3">0,40,80,40</Str></Action>"""),
            Triple("Popup", "no step that shows one", """<Action sr="act0" ve="7"><code>550</code><Str sr="arg0" ve="3"/><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Str sr="arg3" ve="3">Popup</Str><Int sr="arg4" val="1"/><Int sr="arg5" val="1"/></Action>"""),
            Triple(
                "Menu",
                "no step that shows one",
                """
                <Action sr="act2" ve="7">
                    <code>551</code>
                    <Str sr="arg0" ve="3">Duration</Str>
                    <Str sr="arg1" ve="3"/>
                    <Str sr="arg2" ve="3">IconAndTextMenu</Str>
                    <Int sr="arg3" val="600"/>
                    <Int sr="arg4" val="1"/>
                    <ListElementItem sr="item0"><label>15 Min</label><Img sr="icon" ve="2"><nme>hd_device_access_data_usage</nme></Img></ListElementItem>
                    <ListElementItem sr="item1"><label>30 Min</label><Img sr="icon" ve="2"><nme>hd_device_access_data_usage</nme></Img></ListElementItem>
                </Action>
                """,
            ),
            Triple(
                "Set Alarm",
                "no alarm step",
                """
                <Action sr="act0" ve="7">
                    <code>566</code>
                    <label>Create Alarm</label>
                    <Int sr="arg0"><var>%http_request_body.alarmHours</var></Int>
                    <Int sr="arg1"><var>%http_request_body.alarmMinutes</var></Int>
                    <Str sr="arg2" ve="3">%http_request_body.alarmLabel</Str>
                    <Str sr="arg3" ve="3">%http_request_body.alarmSound</Str>
                    <Int sr="arg4" val="0"/>
                    <Int sr="arg5" val="0"/>
                </Action>
                """,
            ),
            Triple(
                "SQL Query",
                "content provider",
                """
                <Action sr="act2" ve="7">
                    <code>667</code>
                    <se>false</se>
                    <Int sr="arg0" val="2"/>
                    <Str sr="arg1" ve="3">content://com.android.contacts/contacts</Str>
                    <Int sr="arg10" val="1"/>
                    <Str sr="arg2" ve="3"/>
                    <Str sr="arg3" ve="3">_id</Str>
                    <Str sr="arg4" ve="3">display_name = '%par1'</Str>
                    <Str sr="arg5" ve="3"/>
                    <Str sr="arg6" ve="3"/>
                    <Str sr="arg7" ve="3"/>
                    <Str sr="arg8" ve="3">%ids</Str>
                    <Int sr="arg9" val="0"/>
                </Action>
                """,
            ),
        ).forEach { (name, phrase, action) ->
            val note = importNote(action)

            assertTrue(note, "Tasker's $name" in note && phrase in note)
        }
    }

    @Test
    fun getLocationPointsAtTheLocationCondition() {
        val note = importNote(
            """<Action sr="act0" ve="7"><code>902</code><Int sr="arg0" val="1"/><Int sr="arg1" val="17"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/></Action>""",
        )

        assertTrue(note, "Tasker's Get Location" in note && "profile condition" in note)
    }

    @Test
    fun fileStepsWithNoMatchSayWhatTheFileStepsHereDo() {
        val notes = listOf(
            "List Files" to """<Action sr="act2" ve="7"><code>412</code><Str sr="arg0" ve="3">WhatsApp/Media/WhatsApp Voice Notes</Str><Str sr="arg1" ve="3" /><Int sr="arg2" val="1" /><Int sr="arg3" val="0" /><Str sr="arg4" ve="3">%REPFOLDERS</Str></Action>""",
            "Read Line" to """<Action sr="act0" ve="7"><code>415</code><Str sr="arg0" ve="3">RJ/Tasker Logs/Battery</Str><Str sr="arg1" ve="3">1</Str><Str sr="arg2" ve="3">%line1</Str><Int sr="arg3" val="1"/></Action>""",
            "Test File" to """<Action sr="act13" ve="7"><code>342</code><Int sr="arg0" val="5"/><Str sr="arg1" ve="3">%filepathname</Str><Str sr="arg2" ve="3">%file_exists</Str><Int sr="arg3" val="0"/></Action>""",
            "Move" to """<Action sr="act9" ve="7"><code>400</code><Str sr="arg0" lb="" virt="0">Documents/VaultTemp.m4a</Str><Str sr="arg1" lb="" virt="0">Syncthing/VoiceDrop/%filename</Str><Int sr="arg2" val="0"/></Action>""",
            "Read Binary" to """<Action sr="act3" ve="7"><code>776</code><se>false</se><Str sr="arg0" ve="3">%par1</Str><Str sr="arg1" ve="3">%b64</Str></Action>""",
        ).associate { (name, action) -> name to importNote(action) }

        notes.forEach { (name, note) -> assertTrue(note, "Tasker's $name" in note) }
        val listFiles = notes.getValue("List Files")
        assertTrue(listFiles, "full paths" in listFiles && "one per line" in listFiles)
        val readLine = notes.getValue("Read Line")
        assertTrue(readLine, "reads the whole file" in readLine)
        val readBinary = notes.getValue("Read Binary")
        assertTrue(readBinary, "Base64" in readBinary)
    }
}
