package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Tasker Search Replace steps (code 598) that stay placeholders: a regex switch taken from a
 * variable, and a Replace With that reads the array the step fills. Tasker fills Replace With in
 * after it stores the matches (`ExecuteService.V2`), and here every field is filled in first.
 */
class TaskerSearchReplaceRefusalTest {

    private fun action(xml: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$xml</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        ).bundle.tasks.single().actions.single()

    private fun searchReplace(store: String = "%found", replace: Int = 1, replaceWith: String = "#") =
        """<code>598</code><Str sr="arg0" ve="3">%text</Str><Str sr="arg1" ve="3">\d+</Str><Int sr="arg2" val="0"/>""" +
            """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3">$store</Str>""" +
            """<Int sr="arg6" val="$replace"/><Str sr="arg7" ve="3">$replaceWith</Str>"""

    private val placeholder = TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID

    @Test
    fun ignoreCaseOrMultiLineFromAVariableStaysAPlaceholder() {
        listOf(
            searchReplace().replace("""<Int sr="arg2" val="0"/>""", """<Int sr="arg2"><var>%nocase</var></Int>"""),
            searchReplace().replace("""<Int sr="arg3" val="0"/>""", """<Int sr="arg3"><var>%lines</var></Int>"""),
        ).forEach { assertEquals(it, placeholder, action(it).type) }
    }

    @Test
    fun aReplaceWithThatReadsTheFilledArrayStaysAPlaceholder() {
        // Tasker writes < and > in Replace With as entities.
        listOf("[%found1]", "%found(#) of them", "&lt;%found(1)&gt;").forEach { replaceWith ->
            assertEquals(replaceWith, placeholder, action(searchReplace(replaceWith = replaceWith)).type)
        }
    }

    @Test
    fun aReplaceWithThatReadsSomethingElseStillComesOver() {
        listOf(
            searchReplace(replaceWith = "[%foundx]"),
            searchReplace(replaceWith = "[%Found1]"),
            searchReplace(store = "", replaceWith = "[%found1]"),
            // Replace With isn't read with Replace Matches off.
            searchReplace(replace = 0, replaceWith = "[%found1]"),
        ).forEach { assertEquals(it, "text.replace", action(it).type) }
    }
}
