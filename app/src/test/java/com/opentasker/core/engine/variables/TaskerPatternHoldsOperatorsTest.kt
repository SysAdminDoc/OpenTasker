package com.opentasker.core.engine.variables

import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker keeps a Matches pattern in a field of its own, so a `<`, `>` or `==` in it is part of the
 * pattern. Here the first match sign starts the pattern and the rest of the condition is all pattern.
 */
class TaskerPatternHoldsOperatorsTest {

    @Test
    fun aPatternHoldingAComparisonSignIsStillOnePattern() {
        assertTrue(holds("a <b>bold</b> c", "%v ~ *<b>*"))
        assertFalse(holds("plain", "%v ~ *<b>*"))
        assertTrue(holds("plain", "%v !~ *<b>*"))
        assertTrue(holds("x == y", "%v ~ *==*"))
    }

    @Test
    fun aRegexHoldingAComparisonSignIsStillOneRegex() {
        assertTrue(holds("a<b", "%v ~R a<b"))
        assertFalse(holds("a<b", "%v !~R a<b"))
        assertTrue(holds("5", "%v ~R ^[0-9]>?$"))
    }

    @Test
    fun aComparisonBeforeAMatchSignStillReadsAsTwo() {
        assertFalse(holds("1", "%v == 1 ~ x"))
        assertFalse(holds("1 ~ x", "%v == 1 ~ x"))
    }

    private fun holds(value: String, condition: String) =
        VariableStore().apply { set("v", value) }.evaluateCondition(condition)
}
