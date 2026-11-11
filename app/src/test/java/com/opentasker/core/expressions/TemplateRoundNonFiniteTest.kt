package com.opentasker.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Test

/** `round` on text that reads as a number but isn't finite used to throw out of the expansion. */
class TemplateRoundNonFiniteTest {
    private val engine = TemplateExpressionEngine()

    @Test
    fun roundLeavesNaNAndInfinityEmptyLikeTheOtherNumberFunctions() {
        listOf("NaN", "Infinity", "-Infinity").forEach { raw ->
            val rounded = engine.expand("{{ value | round }}", TemplateScope(task = mapOf("value" to raw)))
            val added = engine.expand("{{ value | add:1 }}", TemplateScope(task = mapOf("value" to raw)))

            assertEquals(raw, "", rounded.value)
            assertEquals(raw, added.value, rounded.value)
        }
    }

    @Test
    fun roundStillRoundsAFiniteValue() {
        assertEquals("-2", engine.expand("{{ value | round }}", TemplateScope(task = mapOf("value" to "-2.5"))).value)
        assertEquals("3", engine.expand("{{ value | round }}", TemplateScope(task = mapOf("value" to "2.5"))).value)
    }
}
