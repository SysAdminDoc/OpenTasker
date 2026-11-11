package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An exported task has to mean the same thing to Tasker, and read back the same here. */
class TaskerExportLayoutTest {

    // The app registers this at startup. Without it a field named "key" masks like a credential.
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun export(vararg actions: ActionSpec) =
        TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Exported", actions = actions.toList())))

    private fun xml(type: String, vararg args: Pair<String, String>): String =
        export(ActionSpec(type = type, args = mapOf(*args))).xml

    private fun roundTrip(type: String, vararg args: Pair<String, String>): ActionSpec =
        TaskerXmlImporter.parse(xml(type, *args), appVersion = "test", importedAtEpochMs = 0L)
            .bundle.tasks.single().actions.single()

    private fun assertHas(xml: String, vararg parts: String) =
        parts.forEach { part -> assertTrue("expected $part in:\n$xml", part in xml) }

    @Test
    fun forWritesTheVariableFirstAndAnArrayItemsField() {
        assertHas(
            xml("flow.foreach", "list" to "input", "var" to "pair"),
            """<Str sr="arg0" ve="3">%pair</Str>""",
            """<Str sr="arg1" ve="3">%input()</Str>""",
        )
        assertHas(xml("flow.foreach", "list" to "{{ array.input }}", "var" to "%pair"), "%input()")

        val back = roundTrip("flow.foreach", "list" to "input", "var" to "%pair")
        assertEquals("input", back.args["list"])
        assertEquals("%pair", back.args["var"])
    }

    @Test
    fun displayTimeoutWritesSecondsMinutesAndHours() {
        assertHas(
            xml("screen.timeout", "millis" to "3725000"),
            """<Int sr="arg0" val="5"/>""",
            """<Int sr="arg1" val="2"/>""",
            """<Int sr="arg2" val="1"/>""",
        )
        assertEquals("90000", roundTrip("screen.timeout", "millis" to "90000").args["millis"])
    }

    @Test
    fun apkFieldsWriteTheAppElementTaskerReads() {
        assertHas(xml("app.launch", "package" to "com.foo"), """<App sr="arg0">""", "<appPkg>com.foo</appPkg>")
        assertEquals("com.foo", roundTrip("app.launch", "package" to "com.foo").args["package"])
        assertHas(xml("app.kill", "package" to "com.foo"), "<code>18</code>", "<appPkg>com.foo</appPkg>")
        assertEquals("com.foo", roundTrip("app.kill", "package" to "com.foo").args["package"])
    }

    @Test
    fun setFieldsWriteTaskersNumbers() {
        mapOf(
            "torch.set" to "511",
            "wifi.toggle" to "425",
            "bluetooth.toggle" to "294",
            "airplane.toggle" to "333",
            "hotspot.toggle" to "113",
        ).forEach { (type, code) ->
            mapOf("off" to "0", "on" to "1", "toggle" to "2").forEach { (state, value) ->
                assertHas(xml(type, "state" to state), "<code>$code</code>", """<Int sr="arg0" val="$value"/>""")
                val back = roundTrip(type, "state" to state)
                assertEquals(type, back.type)
                assertEquals(state, back.args["state"])
            }
        }
    }

    @Test
    fun aVariableInANumberFieldUsesTheVarElement() {
        assertHas(xml("brightness.set", "brightness" to "%b"), """<Int sr="arg0"><var>%b</var></Int>""")
        assertHas(xml("brightness.set", "brightness" to "120"), """<Int sr="arg0" val="120"/>""")
        assertHas(xml("volume.set", "stream" to "music", "level" to "%v"), """<Int sr="arg0"><var>%v</var></Int>""")
        assertHas(xml("vibrate", "millis" to "%m"), """<Int sr="arg0"><var>%m</var></Int>""")
        assertHas(xml("flow.wait", "millis" to "%w"), """<Int sr="arg0"><var>%w</var></Int>""")
        assertEquals("%b", roundTrip("brightness.set", "brightness" to "%b").args["brightness"])
        assertEquals("%w", roundTrip("flow.wait", "millis" to "%w").args["millis"])
    }

    @Test
    fun autoBrightnessIsCalledOutBecauseTaskerStoresThatElsewhere() {
        val report = export(ActionSpec(type = "brightness.set", args = mapOf("brightness" to "auto")))

        assertTrue(report.warnings.toString(), report.warnings.any { "auto" in it.lowercase() })
    }

    @Test
    fun aTimeoutHeldInAVariableIsCalledOutBecauseTaskerReadsSeconds() {
        val report = export(ActionSpec(type = "screen.timeout", args = mapOf("millis" to "%t")))

        assertHas(report.xml, """<Int sr="arg0"><var>%t</var></Int>""")
        assertTrue(report.warnings.toString(), report.warnings.any { "seconds" in it })
    }

    @Test
    fun customSettingWritesItsThreeFields() {
        assertHas(
            xml("settings.write", "table" to "secure", "key" to "doze_always_on", "value" to "1"),
            "<code>235</code>",
            """<Int sr="arg0" val="1"/>""",
            """<Str sr="arg1" ve="3">doze_always_on</Str>""",
            """<Str sr="arg2" ve="3">1</Str>""",
        )
        val back = roundTrip("settings.write", "table" to "system", "key" to "a", "value" to "1")
        assertEquals(mapOf("table" to "system", "key" to "a", "value" to "1"), back.args)
    }

    @Test
    fun sendSmsAndFilesAndClipboardReadBack() {
        assertEquals("%n", roundTrip("sms.send", "number" to "%n", "message" to "%m").args["number"])

        val read = roundTrip("file.read", "path" to "a.txt", "var" to "%v")
        assertEquals(mapOf("path" to "a.txt", "var" to "%v"), read.args)
        assertEquals("file.write", roundTrip("file.write", "path" to "a", "text" to "b").type)
        assertEquals("file.append", roundTrip("file.append", "path" to "a", "text" to "b").type)
    }

    @Test
    fun clipboardTextStaysOutOfTheExportAndTheReportSaysToReenterIt() {
        val report = export(ActionSpec(type = "clipboard.set", args = mapOf("text" to "hello")))

        assertHas(report.xml, "<code>105</code>")
        assertFalse(report.xml, "hello" in report.xml)
        assertTrue(report.warnings.toString(), report.warnings.any { "re-entered" in it })
    }

    @Test
    fun mediaKeysExportAsMediaControlBecauseThatIsWhatTheyDo() {
        mapOf("track.next" to "0", "sound.pause" to "1", "track.previous" to "2", "sound.stop" to "4").forEach { (type, cmd) ->
            assertHas(xml(type), "<code>443</code>", """<Int sr="arg0" val="$cmd"/>""", """<Int sr="arg1" val="1"/>""")
            assertEquals(type, roundTrip(type).type)
        }
    }

    @Test
    fun playingAFileExportsAsMusicPlay() {
        assertHas(xml("sound.play", "path" to "a.mp3"), "<code>445</code>", """<Str sr="arg0" ve="3">a.mp3</Str>""")
        assertEquals("a.mp3", roundTrip("sound.play", "path" to "a.mp3").args["path"])
    }

    @Test
    fun theAlarmStreamSurvivesARoundTrip() {
        assertHas(xml("sound.play", "path" to "a.mp3", "stream" to "alarm"), """<Int sr="arg3" val="4"/>""")
        assertHas(xml("tts.speak", "text" to "hi", "stream" to "alarm"), """<Int sr="arg2" val="4"/>""")
        assertEquals("alarm", roundTrip("sound.play", "path" to "a.mp3", "stream" to "alarm").args["stream"])
        assertEquals("alarm", roundTrip("tts.speak", "text" to "hi", "stream" to "alarm").args["stream"])
        assertEquals(null, roundTrip("sound.play", "path" to "a.mp3").args["stream"])
        assertEquals(null, roundTrip("tts.speak", "text" to "hi").args["stream"])
    }

    @Test
    fun everyVolumeStreamExportsAsItsOwnTaskerAction() {
        mapOf("alarm" to "303", "ring" to "304", "notification" to "305", "voice" to "306", "music" to "307", "system" to "308")
            .forEach { (stream, code) ->
                assertHas(xml("volume.set", "stream" to stream, "level" to "3"), "<code>$code</code>")
                val back = roundTrip("volume.set", "stream" to stream, "level" to "3")
                assertEquals(stream, back.args["stream"])
                assertEquals("3", back.args["level"])
            }
        assertHas(xml("volume.set", "level" to "3"), "<code>307</code>")
    }

    @Test
    fun lockAndScreenshotExport() {
        assertHas(xml("lock"), "<code>16</code>")
        assertEquals("lock", roundTrip("lock").type)
        assertEquals("%png", roundTrip("screenshot.take", "path" to "%png").args["path"])
    }

    @Test
    fun runTaskWritesItsTwoParametersAndNamesAnyOtherInput() {
        assertHas(
            xml("task.run", "task" to "Weather", "par1" to "a", "par2" to "b"),
            """<Str sr="arg2" ve="3">a</Str>""",
            """<Str sr="arg3" ve="3">b</Str>""",
        )
        val back = roundTrip("task.run", "task" to "Weather", "par1" to "a", "par2" to "b")
        assertEquals(mapOf("task" to "Weather", "par1" to "a", "par2" to "b"), back.args)

        val report = export(ActionSpec(type = "task.run", args = mapOf("task" to "Weather", "city" to "Oslo")))
        assertTrue(report.warnings.toString(), report.warnings.any { "city" in it })
    }

    @Test
    fun logIsSkippedBecauseItsOldCodeIsLocationModeInTasker() {
        val report = export(ActionSpec(type = "log", args = mapOf("message" to "hello")))

        assertFalse(report.xml, "<code>905</code>" in report.xml)
        assertEquals("log", report.skippedActions.single().actionType)
    }

    @Test
    fun arrayActionsWriteTaskersSplitJoinPushPopAndClear() {
        assertHas(
            xml("var.split", "name" to "words", "splitter" to " ", "regex" to "true"),
            "<code>590</code>",
            """<Str sr="arg0" ve="3">%words</Str>""",
            """<Str sr="arg1" ve="3"> </Str>""",
            """<Int sr="arg2" val="0"/>""",
            """<Int sr="arg3" val="1"/>""",
        )
        assertHas(xml("array.pop", "array" to "%arr", "position" to "%pos", "to_var" to "out"), "<code>356</code>", "<var>%pos</var>", ">%out</Str>")

        val cases = listOf(
            "var.split" to mapOf("name" to "%words", "splitter" to " ", "delete_base" to "true", "regex" to "false"),
            "var.join" to mapOf("name" to "%words", "joiner" to "|||", "delete_parts" to "false"),
            "array.push" to mapOf("array" to "%Log", "position" to "99", "value" to "%Entry", "fill_spaces" to "true"),
            "array.pop" to mapOf("array" to "%Log", "position" to "%pos", "to_var" to "%Out"),
            "array.clear" to mapOf("array" to "%Log"),
        )
        cases.forEach { (type, args) ->
            val back = roundTrip(type, *args.toList().toTypedArray())
            assertEquals(type, back.type)
            assertEquals(type, args, back.args)
        }
    }
}
