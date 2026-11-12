package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tasker's Search Replace (code 598) as Tasker 6.7.8 runs it (`ExecuteService.V2`): arg0 the
 * variable, arg1 Search, arg2 Ignore Case, arg3 Multi-Line, arg4 One Match Only, arg5 Store Matches
 * In Array, arg6 Replace Matches and arg7 Replace With.
 */
class TaskerSearchReplaceTransferTest {

    private fun parse(xml: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$xml</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun action(xml: String): ActionSpec = parse(xml).bundle.tasks.single().actions.single()

    private fun searchReplace(store: String = "", oneMatch: Int = 0, replace: Int = 1, replaceWith: String = "#") =
        """<code>598</code><Str sr="arg0" ve="3">%text</Str><Str sr="arg1" ve="3">\d+</Str><Int sr="arg2" val="0"/>""" +
            """<Int sr="arg3" val="0"/><Int sr="arg4" val="$oneMatch"/><Str sr="arg5" ve="3">$store</Str>""" +
            """<Int sr="arg6" val="$replace"/><Str sr="arg7" ve="3">$replaceWith</Str>"""

    private val placeholder = TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID

    @Test
    fun storeMatchesInArrayAndOneMatchOnlyComeOver() {
        val step = searchReplace(store = "%found", oneMatch = 1)

        assertEquals(
            mapOf(
                "source" to "{{ text }}", "pattern" to "\\d+", "replacement" to "#", "var" to "%text",
                "first_only" to "true", "matches" to "%found",
            ),
            action(step).args,
        )
        assertEquals("text.replace", action(step).type)
        assertFalse(parse(step).lossyWarnings.toString(), parse(step).lossyWarnings.any { "Store Matches" in it })
    }

    @Test
    fun replaceMatchesOffOnlyCollectsTheMatches() {
        assertEquals(
            mapOf("source" to "{{ text }}", "pattern" to "\\d+", "search_only" to "true", "var" to "%text", "matches" to "%Found"),
            action(searchReplace(store = "%Found", replace = 0, replaceWith = "unused")).args,
        )
    }

    @Test
    fun aReplaceWithTheStepNeverUsesRaisesNoWarning() {
        // Replace With is only read with Replace Matches on, so its \% can't change anything.
        val off = searchReplace(store = "%found", replace = 0, replaceWith = "\\%x")

        assertEquals("text.replace", action(off).type)
        assertFalse(parse(off).lossyWarnings.toString(), parse(off).lossyWarnings.any { "\\%" in it })
    }

    @Test
    fun whatThisAppCantStoreStaysAPlaceholder() {
        listOf(
            // An array name built from another variable, or an element of one.
            searchReplace(store = "%list_%n"),
            searchReplace(store = "%arr(1)"),
            // A switch taken from a variable.
            searchReplace().replace("""<Int sr="arg4" val="0"/>""", """<Int sr="arg4"><var>%justOne</var></Int>"""),
            searchReplace().replace("""<Int sr="arg6" val="1"/>""", """<Int sr="arg6"><var>%doReplace</var></Int>"""),
        ).forEach { assertEquals(it, placeholder, action(it).type) }
    }
}
