package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker's Sound Mode (code 313). Tasker (decompiled 6.7.8, `bt.x`) hands the mode straight to
 * AudioManager.setRingerMode, 0 silent, 1 vibrate and 2 normal. With Ignore DND (arg1) off it saves
 * the Do Not Disturb filter first and puts it back afterwards. The layout is copied from a real
 * export, where every step but one ticks Ignore DND.
 */
class TaskerSoundModeImportTest {
    private fun parse(mode: String = """<Int sr="arg0" val="1"/>""", ignoreDnd: String = """<Int sr="arg1" val="1"/>"""): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Vibrate</nme>
                        <Action sr="act0" ve="7">
                            <code>313</code>
                            $mode
                            $ignoreDnd
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun step(mode: String = """<Int sr="arg0" val="1"/>""", ignoreDnd: String = """<Int sr="arg1" val="1"/>"""): ActionSpec =
        parse(mode, ignoreDnd).bundle.tasks.single().actions.single()

    @Test
    fun eachModeImportsAsARingerStep() {
        listOf(0 to "silent", 1 to "vibrate", 2 to "normal").forEach { (value, mode) ->
            val action = step(mode = """<Int sr="arg0" val="$value"/>""")

            assertEquals(action.toString(), "ringer.set", action.type)
            assertEquals(mapOf("mode" to mode), action.args)
        }
    }

    @Test
    fun withIgnoreDndTickedNothingIsLost() {
        assertEquals(emptyList<String>(), parse().lossyWarnings)
    }

    // One real export leaves Ignore DND out entirely, which Tasker reads as off.
    @Test
    fun withIgnoreDndOffOrLeftOutTheImportSaysDoNotDisturbMayChange() {
        listOf(
            parse(mode = """<Int sr="arg0" val="2"/>""", ignoreDnd = ""),
            parse(ignoreDnd = """<Int sr="arg1" val="0"/>"""),
        ).forEach { report ->
            assertEquals("ringer.set", report.bundle.tasks.single().actions.single().type)
            assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { "Do Not Disturb" in it && "Ignore DND" in it })
        }
    }

    @Test
    fun aVariableUnknownOrMissingModeStaysAPlaceholderThatSaysWhy() {
        mapOf(
            """<Int sr="arg0"><var>%mode</var></Int>""" to "Tasker Sound Mode takes its mode from %mode, which has no match here",
            """<Int sr="arg0" val="3"/>""" to "Tasker Sound Mode's mode 3 has no match here",
            "" to "Tasker Sound Mode has no mode",
        ).forEach { (mode, reason) ->
            val action = step(mode = mode)

            assertEquals(action.toString(), TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action.type)
            assertEquals(reason, action.args["summary"])
        }
    }

    @Test
    fun eachModeGoesBackOutAsSoundModeAndComesBackTheSame() {
        listOf(0, 1, 2).forEach { value ->
            val task = parse(mode = """<Int sr="arg0" val="$value"/>""").bundle.tasks.single()
            val report = TaskerXmlExporter.export(emptyList(), listOf(task))
            val xml = report.xml.replace(Regex(">\\s+<"), "><")

            assertTrue(xml, """<code>313</code><Int sr="arg0" val="$value"/><Int sr="arg1" val="1"/>""" in xml)
            assertEquals(emptyList<String>(), report.warnings)

            val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
            val action = back.bundle.tasks.single().actions.single()
            assertEquals(task.actions.single().type, action.type)
            assertEquals(task.actions.single().args, action.args)
            assertEquals(emptyList<String>(), back.lossyWarnings)
        }
    }

    @Test
    fun ringGoesOutAsNormalAndAModeTaskerHasNoneOfIsWarned() {
        fun export(mode: String) = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Ringer", actions = listOf(ActionSpec(type = "ringer.set", args = mapOf("mode" to mode))))),
        )

        val ring = export("Ring")
        assertTrue(ring.xml, """<Int sr="arg0" val="2"/>""" in ring.xml)
        assertEquals(emptyList<String>(), ring.warnings)

        val templated = export("{{ mode }}")
        assertTrue(templated.warnings.toString(), templated.warnings.any { "Sound Mode" in it && "{{ mode }}" in it })
    }
}
