package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `%x(/round)` used roundToLong, which throws on NaN. Expansion catches it and hands back the text
 * as typed, so "NaN" in one variable left every other variable in that text unfilled too.
 */
class VariableRoundNonFiniteTest {
    @Test
    fun aValueThatIsNotANumberComesOutLikeTheOtherOperationsLeaveIt() {
        val store = VariableStore().apply {
            set("x", "NaN")
            set("big", "Infinity")
            set("small", "-Infinity")
        }

        listOf("x", "big", "small").forEach { name ->
            assertEquals(name, store.expand("%$name(//)"), store.expand("%$name(/round)"))
        }
        assertEquals("NaN", store.expand("%x(/round)"))
    }

    @Test
    fun finiteValuesStillRoundHalfUp() {
        val store = VariableStore().apply {
            set("up", "2.5")
            set("down", "-2.5")
        }

        assertEquals("3", store.expand("%up(/round)"))
        assertEquals("-2", store.expand("%down(/round)"))
    }
}
