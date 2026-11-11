package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowStructureTest {

    private fun a(type: String) = ActionSpec(type = type)

    @Test
    fun pairsIfElseEndif() {
        val s = FlowStructure.analyze(
            listOf(a(FlowControl.IF), a("x"), a(FlowControl.ELSE), a("y"), a(FlowControl.ENDIF)),
        )
        assertNull(s.error)
        assertEquals(2, s.ifToElse[0])
        assertEquals(4, s.ifToEndif[0])
        assertEquals(4, s.elseToEndif[2])
    }

    @Test
    fun pairsForeachEndfor() {
        val s = FlowStructure.analyze(listOf(a(FlowControl.FOREACH), a("x"), a(FlowControl.ENDFOR)))
        assertNull(s.error)
        assertEquals(2, s.foreachToEndfor[0])
        assertEquals(0, s.endforToForeach[2])
    }

    @Test
    fun ifWithoutElseHasNoElseMapping() {
        val s = FlowStructure.analyze(listOf(a(FlowControl.IF), a("x"), a(FlowControl.ENDIF)))
        assertNull(s.error)
        assertNull(s.ifToElse[0])
        assertEquals(2, s.ifToEndif[0])
    }

    @Test
    fun detectsUnclosedIf() {
        val s = FlowStructure.analyze(listOf(a(FlowControl.IF), a("x")))
        assertNotNull(s.error)
    }

    @Test
    fun detectsStrayEndif() {
        val s = FlowStructure.analyze(listOf(a(FlowControl.ENDIF)))
        assertNotNull(s.error)
    }

    @Test
    fun detectsCrossedNesting() {
        // foreach ... if ... endfor ... endif is invalid nesting
        val s = FlowStructure.analyze(
            listOf(a(FlowControl.FOREACH), a(FlowControl.IF), a(FlowControl.ENDFOR), a(FlowControl.ENDIF)),
        )
        assertNotNull(s.error)
    }

    @Test
    fun pairsTryCatchEndtry() {
        val s = FlowStructure.analyze(
            listOf(a(FlowControl.TRY), a("x"), a(FlowControl.CATCH), a("y"), a(FlowControl.ENDTRY)),
        )
        assertNull(s.error)
        assertEquals(2, s.tryToCatch[0])
        assertEquals(4, s.tryToEndtry[0])
        assertEquals(4, s.catchToEndtry[2])
    }

    @Test
    fun rejectsInvalidTryBoundsAndDuplicateCatch() {
        assertNotNull(FlowStructure.analyze(listOf(ActionSpec(type = FlowControl.TRY, args = mapOf("max_attempts" to "6")), a(FlowControl.ENDTRY))).error)
        assertNotNull(FlowStructure.analyze(listOf(a(FlowControl.TRY), a(FlowControl.CATCH), a(FlowControl.CATCH), a(FlowControl.ENDTRY))).error)
    }

    @Test
    fun retryPlanSeparatesBodyActionsFromFlowMarkers() {
        val actions = listOf(
            a(FlowControl.TRY),
            a("safe.read"),
            a(FlowControl.IF),
            a("unsafe.send"),
            a(FlowControl.ENDIF),
            a(FlowControl.CATCH),
            a(FlowControl.ENDTRY),
        )

        val plan = tryRetryPlan(actions, tryIndex = 0) { action ->
            when (action.type) {
                "safe.read" -> ActionRetrySafety.IDEMPOTENT
                "unsafe.send" -> ActionRetrySafety.NEVER
                else -> null
            }
        }

        assertEquals(listOf("safe.read"), plan.retryableActionIds)
        assertEquals(listOf("unsafe.send"), plan.nonRetryableActionIds)
    }

    @Test
    fun gotoTargetReadsALabelFirstThenAWholeStepNumber() {
        val actions = listOf(a("x"), ActionSpec(type = FlowControl.ANCHOR, label = " Top "), a("y"))

        assertEquals(1, FlowControl.gotoTarget(actions, "Top", "3").index)
        assertEquals(2, FlowControl.gotoTarget(actions, null, "3").index)
        assertEquals(2, FlowControl.gotoTarget(actions, " ", " 3.0 ").index)
        listOf(
            Triple("top", null, "no step is labelled \"top\""),
            Triple(null, "4", "has no step 4 (it has 3)"),
            Triple(null, "-2.0", "has no step -2"),
            Triple(null, "2.5", "\"2.5\" isn't a step number"),
            Triple(null, "1e12", "\"1e12\" isn't a step number"),
            Triple(null, null, "needs a step number or a label"),
        ).forEach { (label, step, message) ->
            val target = FlowControl.gotoTarget(actions, label, step)
            assertNull(target.index)
            assertNotNull(target.error)
            assertTrue("$label/$step -> ${target.error}", message in target.error.orEmpty())
        }

        val emptied = FlowControl.gotoTarget(actions, mapOf("label" to "", "step" to ""), mapOf("label" to "%where", "step" to "%n"))
        assertEquals("goto: %where is empty, so there's no step to jump to", emptied.error)
        assertEquals(2, FlowControl.gotoTarget(actions, mapOf("label" to "", "step" to "3"), mapOf("label" to "%where", "step" to "3")).index)
    }
}
