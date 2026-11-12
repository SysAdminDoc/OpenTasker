package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Search Replace whose Replace With reads one of the array's own elements stays a placeholder,
 * and only then. A Tasker variable name runs on through letters, digits and underscores, so
 * `%found1st` next to Store Matches In `%found` is another variable and imports.
 */
class TaskerSearchReplaceElementNameTest {

    private fun action(replaceWith: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">" +
                """<code>598</code><Str sr="arg0" ve="3">%text</Str><Str sr="arg1" ve="3">\d+</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3">%found</Str>""" +
                """<Int sr="arg6" val="1"/><Str sr="arg7" ve="3">$replaceWith</Str>""" +
                "</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        ).bundle.tasks.single().actions.single()

    @Test
    fun anotherVariableThatStartsLikeAnElementImports() {
        listOf("[%found1st]", "%found12_old", "%found2x").forEach { replaceWith ->
            assertEquals(replaceWith, "text.replace", action(replaceWith).type)
        }
    }

    @Test
    fun anElementStaysAPlaceholderWhereverItEnds() {
        // Behind two backslashes the element is filled in after one of them, and \% writes a
        // percent sign before it, so both still read the array.
        listOf("%found12", "[%found3].", "%found1 and more", "\\\\%found1", "\\%%found1").forEach { replaceWith ->
            assertEquals(replaceWith, TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action(replaceWith).type)
        }
    }
}
