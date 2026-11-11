package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextEventObservation
import com.opentasker.core.contexts.ContextSourceSnapshot
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.contexts.inspectProfiles
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A context whose value is unknown never turns a profile on, through AND, OR and NOT alike. */
class UnknownContextLogicTest {
    private val daylight = ContextSpec(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "%Sunset"))
    private val steps = ContextSpec(ContextType.STATE, mapOf("key" to "health_steps", "operator" to ">=", "value" to "10000"))
    private val notFirst = ContextExpressionNode.group(ContextBooleanOperator.AND, listOf(ContextExpressionNode.leaf(0)), invert = true)
    private val read = mapOf(TimeContextBounds.START_VALUE to "06:42", TimeContextBounds.END_VALUE to "16:51")

    private fun night(vararg bounds: Pair<String, String>) = ContextEvent("time", true, mapOf("time" to "22:00") + bounds)

    @Test
    fun anExpressionFollowsKleeneLogic() {
        val and = ContextExpressionNode.group(ContextBooleanOperator.AND, listOf(ContextExpressionNode.leaf(0), ContextExpressionNode.leaf(1)))
        val or = and.copy(operator = ContextBooleanOperator.OR)

        assertEquals(false, and.evaluate(listOf(false, null)))
        assertNull(and.evaluate(listOf(true, null)))
        assertEquals(true, or.evaluate(listOf(true, null)))
        assertNull(or.evaluate(listOf(false, null)))
        assertNull(and.copy(invert = true).evaluate(listOf(true, null)))
        assertNull(ContextExpressionNode(contextIndex = 0, invert = true).evaluate(listOf(null)))
        assertEquals(true, and.copy(invert = true).evaluate(listOf(false, null)))
        assertEquals(true, or.copy(invert = true).evaluate(listOf(false, false)))
        assertEquals(false, and.evaluate(listOf(true)))
    }

    @Test
    fun theMatcherKeepsANotGroupOverAnUnsetTimeBoundOff() {
        val unset = contextMatchUpdate(daylight, night())
        val set = contextMatchUpdate(daylight, night(*read.toList().toTypedArray()))

        assertTrue(unset.unknown)
        assertFalse(unset.matched)
        assertFalse(evaluateContextExpression(arrayOf(unset), listOf(daylight), notFirst))
        assertFalse(set.unknown)
        assertTrue(evaluateContextExpression(arrayOf(set), listOf(daylight), notFirst))
    }

    @Test
    fun theMatcherKeepsANotGroupOverARefusedHealthReadOff() {
        val refused = contextMatchUpdate(steps, ContextEvent("state", true, mapOf("charging" to "true")))
        val few = contextMatchUpdate(steps, ContextEvent("state", true, mapOf("health_steps" to "500")))

        assertTrue(refused.unknown)
        assertFalse(evaluateContextExpression(arrayOf(refused), listOf(steps), notFirst))
        assertFalse(few.unknown)
        assertTrue(evaluateContextExpression(arrayOf(few), listOf(steps), notFirst))
    }

    @Test
    fun theMatcherLetsAKnownBranchDecideAroundAnUnknownOne() {
        val specs = listOf(daylight, ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "true")))
        val unknown = contextMatchUpdate(daylight, night())
        val charging = ContextMatchUpdate(matched = true, pulseContext = false, pulseSequence = 0)
        val notCharging = charging.copy(matched = false)
        fun expression(operator: ContextBooleanOperator) = ContextExpressionNode.group(
            operator,
            listOf(ContextExpressionNode.group(ContextBooleanOperator.AND, listOf(ContextExpressionNode.leaf(0)), invert = true), ContextExpressionNode.leaf(1)),
        )

        assertTrue(evaluateContextExpression(arrayOf(unknown, charging), specs, expression(ContextBooleanOperator.OR)))
        assertFalse(evaluateContextExpression(arrayOf(unknown, notCharging), specs, expression(ContextBooleanOperator.OR)))
        assertFalse(evaluateContextExpression(arrayOf(unknown, charging), specs, expression(ContextBooleanOperator.AND)))
    }

    @Test
    fun aStateNotReadYetIsUnknownWhileAnEventNotSeenYetIsNo() {
        assertTrue(ContextMatchUpdate.initial(pulseContext = false).unknown)
        assertFalse(ContextMatchUpdate.initial(pulseContext = true).unknown)
        assertFalse(evaluateContextExpression(arrayOf(ContextMatchUpdate.initial(pulseContext = false)), listOf(daylight), notFirst))
    }

    @Test
    fun theInspectorAgreesForAnUnsetTimeBound() {
        val profile = Profile(id = 1, name = "Dark", enterTaskId = 1, enabled = true, contexts = listOf(daylight), contextExpression = notFirst)
        val source = ContextSourceSnapshot(
            key = "time",
            label = "Time and day",
            registered = true,
            lastObservation = ContextEventObservation(night(), observedAtMs = 1000),
        )
        fun inspect(globals: Map<String, String>) = inspectProfiles(listOf(profile), listOf(source)) { _, _, spec, observation ->
            observation.copy(event = TimeContextBounds.withVariableValues(spec, observation.event, globals))
        }.single()

        val unset = inspect(emptyMap())
        assertFalse(unset.matching)
        assertTrue(unset.contexts.single().valueUnknown)
        assertEquals("NOT(ALL(Context 1=unknown))", unset.logicExplanation)
        assertTrue(inspect(mapOf("Sunrise" to "6.42", "Sunset" to "16.51")).matching)
    }

    @Test
    fun theInspectorAgreesForARefusedHealthReadAndAStateNotReadYet() {
        val profile = Profile(id = 2, name = "Lazy day", enterTaskId = 1, enabled = true, contexts = listOf(steps), contextExpression = notFirst)
        fun source(vararg state: Pair<String, String>) = ContextSourceSnapshot(
            key = "state",
            label = "Device state",
            registered = true,
            lastObservation = ContextEventObservation(ContextEvent("state", true, mapOf(*state)), observedAtMs = 1000),
        )

        assertFalse(inspectProfiles(listOf(profile), listOf(source("charging" to "true"))).single().matching)
        assertFalse(inspectProfiles(listOf(profile), listOf(source().copy(lastObservation = null))).single().matching)
        assertTrue(inspectProfiles(listOf(profile), listOf(source("health_steps" to "500"))).single().matching)
    }

    @Test
    fun theSimulationAgreesForAnUnsetTimeBoundAndARefusedHealthRead() {
        fun simulate(spec: ContextSpec, event: ContextEvent) = SyntheticTriggerSimulator.simulate(
            Profile(id = 3, name = "Simulated", enterTaskId = 1, contexts = listOf(spec), contextExpression = notFirst),
            nowMs = 1_000_000L,
            pinnedEvents = mapOf(0 to event),
        )

        val unsetTime = simulate(daylight, night())
        assertFalse(unsetTime.profileMatched)
        assertTrue(unsetTime.contexts.single().valueUnknown)
        assertTrue(unsetTime.contexts.single().explanation, unsetTime.contexts.single().explanation.contains("no value"))
        assertTrue(simulate(daylight, night(*read.toList().toTypedArray())).profileMatched)

        assertFalse(simulate(steps, ContextEvent("state", true, mapOf("charging" to "true"))).profileMatched)
        assertTrue(simulate(steps, ContextEvent("state", true, mapOf("health_steps" to "500"))).profileMatched)
    }
}
