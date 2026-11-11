package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tasker's Profile Status (159), laid out the way real Tasker exports write it. */
class TaskerProfileStatusTest {

    private fun parse(xml: String) = TaskerXmlImporter.parse(
        rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\" ve=\"7\">$xml</Action></Task></TaskerData>",
        appVersion = "test",
        importedAtEpochMs = 0L,
    )

    private fun action(xml: String): ActionSpec = parse(xml).bundle.tasks.single().actions.single()

    private fun export(action: ActionSpec) =
        TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Exported", actions = listOf(action))))

    @Test
    fun eachSetComesInAsTheSameSwitch() {
        mapOf(0 to "off", 1 to "on", 2 to "toggle").forEach { (set, state) ->
            val xml = """<code>159</code><label>Turn it</label><Str sr="arg0" ve="3">1. DB Launch</Str><Int sr="arg1" val="$set"/>"""
            val imported = action(xml)

            assertEquals("profile.set", imported.type)
            assertEquals(mapOf("profile" to "1. DB Launch", "state" to state), imported.args)
            assertTrue(parse(xml).lossyWarnings.toString(), parse(xml).lossyWarnings.isEmpty())
        }
    }

    @Test
    fun aProfileNamedByAVariableKeepsTheVariable() {
        val imported = action("""<code>159</code><Str sr="arg0" ve="3">%this_task</Str><Int sr="arg1" val="1"/>""")
        assertEquals(mapOf("profile" to "%this_task", "state" to "on"), imported.args)
    }

    @Test
    fun aSetHeldInAVariableOrNoProfileNameStaysAPlaceholder() {
        val inVariable = parse("""<code>159</code><Str sr="arg0" ve="3">Night</Str><Int sr="arg1"><var>%mode</var></Int>""")
        assertEquals("tasker.unsupported", inVariable.bundle.tasks.single().actions.single().type)
        assertTrue(inVariable.lossyWarnings.toString(), inVariable.lossyWarnings.any { "%mode" in it })

        val unnamed = parse("""<code>159</code><Str sr="arg0" ve="3"/><Int sr="arg1" val="1"/>""")
        assertEquals("tasker.unsupported", unnamed.bundle.tasks.single().actions.single().type)
        assertTrue(unnamed.lossyWarnings.toString(), unnamed.lossyWarnings.any { "names no profile" in it })
    }

    @Test
    fun exportWritesTheNameAndSetWhereTaskerReadsThem() {
        val xml = export(ActionSpec(type = "profile.set", args = mapOf("profile" to "Night & Day", "state" to "toggle"))).xml

        listOf("<code>159</code>", """<Str sr="arg0" ve="3">Night &amp; Day</Str>""", """<Int sr="arg1" val="2"/>""").forEach { part ->
            assertTrue("expected $part in:\n$xml", part in xml)
        }
        val back = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L).bundle.tasks.single().actions.single()
        assertEquals(ActionSpec(type = "profile.set", args = mapOf("profile" to "Night & Day", "state" to "toggle")).args, back.args)
        assertEquals("profile.set", back.type)
    }
}
