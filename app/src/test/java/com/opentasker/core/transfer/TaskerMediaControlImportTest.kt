package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Media Control (443) picks its key with Cmd: 3 Toggle Pause, 5 Play, 6 Rewind and
 * 7 Fast Forward. Those four now import as the media key steps that send the same key, and export
 * back to the same command. The Toggle Pause, Play and Spotify steps are copied from real exports.
 * No real export has Rewind or Fast Forward, so those two carry only Cmd and Simulate Media Button.
 */
class TaskerMediaControlImportTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(fields: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Media</nme>
                        <Action sr="act0" ve="7"><code>443</code>$fields</Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun step(report: TaskerXmlImportReport): ActionSpec = report.bundle.tasks.single().actions.single()

    @Test
    fun togglePauseAndPlayImportAsTheirMediaKeys() {
        val toggle = parse("""<Int sr="arg0" val="3"/><Int sr="arg1" val="1"/><App sr="arg2"/><Int sr="arg3" val="1"/>""")
        val play = parse("""<Int sr="arg0" val="5"/><Int sr="arg1" val="1"/><App sr="arg2"/><Int sr="arg3" val="1"/>""")

        assertEquals("sound.toggle", step(toggle).type)
        assertEquals("sound.resume", step(play).type)
        assertTrue(toggle.unsupportedActions.isEmpty())
        assertTrue(play.unsupportedActions.isEmpty())
        assertTrue(toggle.lossyWarnings.toString(), toggle.lossyWarnings.isEmpty())
        assertTrue(play.lossyWarnings.toString(), play.lossyWarnings.isEmpty())
    }

    @Test
    fun playAimedAtAnAppSaysWhichAppGetsTheKey() {
        val report = parse(
            """<Int sr="arg0" val="5"/><Int sr="arg1" val="1"/>""" +
                """<App sr="arg2"><appClass>com.spotify.music.MainActivity</appClass><appPkg>com.spotify.music</appPkg><label>Spotify</label></App>""",
        )

        assertEquals("sound.resume", step(report).type)
        assertEquals(1, report.lossyWarnings.size)
        assertTrue(report.lossyWarnings.toString(), "com.spotify.music" in report.lossyWarnings.single())
        assertTrue(report.lossyWarnings.toString(), "the app that's playing or played last" in report.lossyWarnings.single())
    }

    @Test
    fun rewindAndFastForwardImportAsTheirMediaKeys() {
        assertEquals("track.rewind", step(parse("""<Int sr="arg0" val="6"/><Int sr="arg1" val="1"/>""")).type)
        assertEquals("track.fastforward", step(parse("""<Int sr="arg0" val="7"/><Int sr="arg1" val="1"/>""")).type)
    }

    @Test
    fun theNewMediaKeysExportBackToTheSameCommand() {
        mapOf("sound.toggle" to "3", "sound.resume" to "5", "track.rewind" to "6", "track.fastforward" to "7").forEach { (type, cmd) ->
            val xml = TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Exported", actions = listOf(ActionSpec(type = type))))).xml
            listOf("<code>443</code>", """<Int sr="arg0" val="$cmd"/>""", """<Int sr="arg1" val="1"/>""").forEach { part ->
                assertTrue("expected $part in:\n$xml", part in xml)
            }
            val back = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L)
            assertEquals(type, step(back).type)
        }
    }
}
