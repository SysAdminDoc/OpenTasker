package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextEventObservation
import com.opentasker.core.contexts.ContextMatchEvaluator
import com.opentasker.core.contexts.ContextSourceSnapshot
import com.opentasker.core.contexts.inspectProfiles
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** For the same last event, the Inspector calls a profile on exactly when the engine does. */
class InspectorEngineParityTest {
    private val charging = ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "true"))
    private val pluginA = ContextSpec(ContextType.PLUGIN, mapOf("package" to "com.example.a", "bundleJson" to "{\"k\":\"a\"}"))
    private val notFirst = ContextExpressionNode.group(ContextBooleanOperator.AND, listOf(ContextExpressionNode.leaf(0)), invert = true)
    private val chargingEvent = ContextEvent("state", true, mapOf("charging" to "true"))

    private fun profile(spec: ContextSpec, expression: ContextExpressionNode?) =
        Profile(id = 1, name = "Parity", enterTaskId = 1, enabled = true, contexts = listOf(spec), contextExpression = expression)

    private fun pluginEvent(pkg: String, bundle: String, state: String) =
        ContextEvent("plugin", true, mapOf("package" to pkg, "bundleJson" to bundle, "state" to state))

    /** The matcher's verdict: a plugin result for another plugin or setting is skipped, as its scan does. */
    private fun engine(spec: ContextSpec, event: ContextEvent, expression: ContextExpressionNode?): Boolean {
        val skipped = spec.type == ContextType.PLUGIN && !ContextMatchEvaluator.pluginEventAddressesSpec(spec, event)
        val update = if (skipped) ContextMatchUpdate.initial(pulseContext = false) else contextMatchUpdate(spec, event)
        return evaluateContextExpression(arrayOf(update), listOf(spec), expression)
    }

    private fun inspector(spec: ContextSpec, source: ContextSourceSnapshot, expression: ContextExpressionNode?): Boolean =
        inspectProfiles(listOf(profile(spec, expression)), listOf(source)).single().matching

    @Test
    fun aSourceThatStoppedOrLostItsSetupKeepsItsLastValueInBoth() {
        val stale = ContextSourceSnapshot(
            key = "state",
            label = "Device state",
            registered = true,
            lastObservation = ContextEventObservation(chargingEvent, observedAtMs = 1000),
        )

        for (source in listOf(stale.copy(error = "source stopped"), stale.copy(setupReady = false, setupDetail = "Needs access."))) {
            for (expression in listOf(notFirst, null)) {
                assertEquals(
                    "${source.status} with ${if (expression == null) "charging" else "NOT(charging)"}",
                    engine(charging, chargingEvent, expression),
                    inspector(charging, source, expression),
                )
            }
        }
        assertFalse(inspector(charging, stale.copy(error = "source stopped"), notFirst))
        assertTrue(inspector(charging, stale.copy(error = "source stopped"), null))
    }

    @Test
    fun theReasonSaysAStoppedSourcesLastValueStillCounts() {
        val check = inspectProfiles(
            listOf(profile(charging, null)),
            listOf(
                ContextSourceSnapshot(
                    key = "state",
                    label = "Device state",
                    registered = true,
                    error = "source stopped",
                    lastObservation = ContextEventObservation(chargingEvent, observedAtMs = 1000),
                ),
            ),
        ).single().contexts.single()

        assertTrue(check.effectiveMatched)
        assertTrue(check.reason, check.reason.contains("stopped with an error"))
        assertTrue(check.reason, check.reason.contains("last value still counts"))
    }

    @Test
    fun anotherPluginsResultLeavesThisPluginUnknownInBoth() {
        val fromB = pluginEvent("com.example.b", "{}", "unsatisfied")
        val source = ContextSourceSnapshot(
            key = "plugin",
            label = "Plugin",
            registered = true,
            lastObservation = ContextEventObservation(fromB, observedAtMs = 1000),
        )

        assertFalse(engine(pluginA, fromB, notFirst))
        assertEquals(engine(pluginA, fromB, notFirst), inspector(pluginA, source, notFirst))
        val check = inspectProfiles(listOf(profile(pluginA, notFirst)), listOf(source)).single().contexts.single()
        assertTrue(check.valueUnknown)
        assertTrue(check.reason, check.reason.contains("another plugin"))
    }

    @Test
    fun theSamePluginWithAnotherSettingIsSkippedToo() {
        val otherSetting = pluginEvent("com.example.a", "{\"k\":\"b\"}", "unsatisfied")
        val source = ContextSourceSnapshot(
            key = "plugin",
            label = "Plugin",
            registered = true,
            lastObservation = ContextEventObservation(otherSetting, observedAtMs = 1000),
        )

        assertEquals(engine(pluginA, otherSetting, notFirst), inspector(pluginA, source, notFirst))
        assertFalse(inspector(pluginA, source, notFirst))
    }

    @Test
    fun thisPluginsOwnResultStillCountsInBoth() {
        val fromA = pluginEvent("com.example.a", "{\"k\":\"a\"}", "unsatisfied")
        val source = ContextSourceSnapshot(
            key = "plugin",
            label = "Plugin",
            registered = true,
            lastObservation = ContextEventObservation(fromA, observedAtMs = 1000),
        )

        assertTrue(engine(pluginA, fromA, notFirst))
        assertEquals(engine(pluginA, fromA, notFirst), inspector(pluginA, source, notFirst))
    }
}
