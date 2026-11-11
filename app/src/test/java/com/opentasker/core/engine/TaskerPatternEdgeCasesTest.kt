package com.opentasker.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matches and Doesn't Match with an empty pattern, and with long lists of choices. */
class TaskerPatternEdgeCasesTest {

    @Test
    fun anEmptyPatternMatchesNothing() {
        // Tasker checks for an empty pattern before it looks at the value or a leading !, so
        // Matches is false and Doesn't Match is true, even when the value is empty too.
        val variables = VariableStore().apply {
            set("value", " ")
            set("pattern", " ")
            set("word", "work")
        }
        // A blank value really comes out empty here, or `~ *` would be true.
        assertFalse(variables.evaluateCondition("%value ~ *"))

        assertFalse(variables.evaluateCondition("%value ~ %pattern"))
        assertTrue(variables.evaluateCondition("%value !~ %pattern"))
        assertFalse(variables.evaluateCondition("%word ~ %pattern"))
        assertTrue(variables.evaluateCondition("%word !~ %pattern"))
    }

    @Test
    fun aLongListOfShortChoicesStillMatches() {
        // The length limit applies to each choice, so a list of app packages far longer than the
        // limit still works.
        val apps = (1..40).joinToString("/") { "com.example.app$it" }
        val variables = VariableStore().apply { set("app", "com.example.app40") }
        assertTrue(variables.evaluateCondition("%app ~ $apps"))
        assertFalse(variables.evaluateCondition("%app !~ $apps"))
        assertFalse(variables.evaluateCondition("%app ~ ${apps.replace("app40", "app41")}"))
    }

    @Test
    fun aChoiceTooLongToCheckMatchesNothing() {
        val long = "a".repeat(300)
        val variables = VariableStore().apply { set("value", long) }
        assertFalse(variables.evaluateCondition("%value ~ $long"))
        assertTrue(variables.evaluateCondition("%value !~ $long"))
        // The other choices in the list still count.
        assertTrue(variables.evaluateCondition("%value ~ $long/a*"))
    }
}
