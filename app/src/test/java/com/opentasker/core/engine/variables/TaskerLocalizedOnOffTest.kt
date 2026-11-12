package com.opentasker.core.engine.variables

import com.opentasker.core.engine.VariableStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Tasker 6.7.8's Matches turns a pattern that is just its word for on or off in the phone's
 * language into that word or the English one (decompiled `Expr.a`, words from its `word_on` and
 * `word_off` strings), so a state variable's `on` matches `Ein` in German and `On` in English.
 */
class TaskerLocalizedOnOffTest {
    private lateinit var saved: Locale

    @Before
    fun saveLocale() {
        saved = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(saved)
    }

    private fun holds(condition: String, value: String): Boolean =
        VariableStore().apply { set("blue", value) }.evaluateCondition(condition)

    @Test
    fun germanWordsMatchTheEnglishValues() {
        Locale.setDefault(Locale.GERMANY)

        assertTrue(holds("%blue ~ Ein", "on"))
        assertTrue(holds("%blue ~ Ein", "Ein"))
        assertFalse(holds("%blue ~ Ein", "off"))
        assertTrue(holds("%blue ~ Aus", "off"))
        assertFalse(holds("%blue !~ Ein", "on"))
        assertTrue(holds("%blue !~ Aus", "on"))
    }

    @Test
    fun onlyTheWordAsTaskerWritesItCounts() {
        Locale.setDefault(Locale.GERMANY)

        // Tasker compares the whole pattern, case included, and the English word isn't German's.
        assertFalse(holds("%blue ~ ein", "on"))
        assertFalse(holds("%blue ~ Ein/Aus", "on"))
        assertFalse(holds("%blue ~ On", "on"))
    }

    @Test
    fun englishCapitalOnMatchesTheLowercaseValue() {
        Locale.setDefault(Locale.US)

        assertTrue(holds("%blue ~ On", "on"))
        assertTrue(holds("%blue ~ Off", "off"))
        assertFalse(holds("%blue ~ On", "off"))
        assertFalse(holds("%blue ~ ON", "on"))
    }

    @Test
    fun traditionalChineseUsesTaiwansWords() {
        Locale.setDefault(Locale.TAIWAN)
        assertTrue(holds("%blue ~ ${Char(TRADITIONAL_ON)}", "on"))

        Locale.setDefault(Locale.CHINA)
        assertFalse(holds("%blue ~ ${Char(TRADITIONAL_ON)}", "on"))
    }

    @Test
    fun regexComparisonsAreLeftAlone() {
        Locale.setDefault(Locale.GERMANY)

        assertFalse(holds("%blue ~R ^Ein\$", "on"))
    }

    private companion object {
        // Tasker's zh-rTW word_on.
        const val TRADITIONAL_ON = 0x958B
    }
}
