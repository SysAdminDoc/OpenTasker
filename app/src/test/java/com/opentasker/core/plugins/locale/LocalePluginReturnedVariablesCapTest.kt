package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalePluginReturnedVariablesCapTest {
    @Test
    fun theRunLogNamesSixteenLeftOutKeysAndCountsTheRest() {
        val returned = LocalePluginReturnedVariables.from((0 until 20).associate { "k$it" to "x" })

        assertEquals(17, returned.refused.size)
        assertEquals("k0 (not a local variable name)", returned.refused.first())
        assertEquals("k15 (not a local variable name)", returned.refused[15])
        assertEquals("4 more", returned.refused.last())
    }

    @Test
    fun anArrayOfEmptyStringsStillCountsTowardTheTextCap() {
        val empties = List(LocalePluginContract.MAX_RETURNED_TEXT_CHARS + 1) { "" }
        val returned = LocalePluginReturnedVariables.from(linkedMapOf("%blank" to empties, "%ok" to "y"))

        assertEquals(listOf("%blank (too much text)"), returned.refused)
        assertTrue(returned.arrays.isEmpty())
        assertEquals("y", returned.values["ok"])
    }

    @Test
    fun aStringRightAtTheCapIsKept() {
        val full = "z".repeat(LocalePluginContract.MAX_RETURNED_TEXT_CHARS)
        val returned = LocalePluginReturnedVariables.from(mapOf("%full" to full))

        assertTrue(returned.refused.isEmpty())
        assertEquals(full, returned.values["full"])
    }
}
