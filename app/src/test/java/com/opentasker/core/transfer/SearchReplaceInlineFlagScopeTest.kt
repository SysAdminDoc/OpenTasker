package com.opentasker.core.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tasker Search Replace's `$` warning reads an inline `m` flag only where the regex engines apply it. */
class SearchReplaceInlineFlagScopeTest {
    private fun warnsOfTheAnchor(pattern: String): Boolean {
        val xml = """<code>598</code><Str sr="arg0" ve="3">%stdout</Str><Str sr="arg1" ve="3">$pattern</Str>""" +
            """<Int sr="arg2" val="0"/><Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/>""" +
            """<Int sr="arg6" val="1"/><Str sr="arg7" ve="3"/>"""
        val result = TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$xml</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )
        return result.lossyWarnings.any { "final line break" in it }
    }

    @Test
    fun aQuotedOrClassBoundFlagLeavesTheAnchorAlone() {
        assertTrue(warnsOfTheAnchor("""\Q(?m)\E\d+$"""))
        assertTrue(warnsOfTheAnchor("""[(?m)]+$"""))
        assertTrue(warnsOfTheAnchor("""\(?m\)\d+$"""))
    }

    @Test
    fun aScopedFlagCoversOnlyItsOwnGroup() {
        assertTrue(warnsOfTheAnchor("""(?m:^#.*)|\d+$"""))
        assertFalse(warnsOfTheAnchor("""(?m:\d+$)"""))
        assertTrue(warnsOfTheAnchor("""(a(?m)b)c$"""))
        assertFalse(warnsOfTheAnchor("""((?m)a$)"""))
        assertFalse(warnsOfTheAnchor("""(?:x)(?m)\d+$"""))
    }

    @Test
    fun aFlagTurnedOffAgainBringsTheAnchorBack() {
        assertTrue(warnsOfTheAnchor("""(?m)a(?-m)b$"""))
        assertTrue(warnsOfTheAnchor("""(?m)a(?i-m:b$)"""))
        assertFalse(warnsOfTheAnchor("""(?-m)a(?m)b$"""))
        // A $ before the flag is still an end anchor.
        assertTrue(warnsOfTheAnchor("""a$|(?m)b"""))
    }
}
