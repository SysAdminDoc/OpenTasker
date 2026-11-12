package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlap check behind the priority conflict knows a Wi-Fi network's name means connected, so
 * "connected" and a name can hold at once while "disconnected" and a name can't.
 */
class AutomationLintWifiConnectedTest {
    private val dim = Task(id = 10, name = "Dim", priority = 5, actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "20"))))
    private val bright = Task(id = 11, name = "Bright", priority = 5, actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "90"))))

    private fun profile(id: Long, task: Task, context: ContextSpec) =
        Profile(id = id, name = "P$id", enabled = true, contexts = listOf(context), enterTaskId = task.id)

    private fun state(key: String, value: String) =
        ContextSpec(ContextType.STATE, mapOf("key" to key, "operator" to "=", "value" to value))

    private fun predicate(text: String) = ContextSpec(ContextType.STATE, mapOf("predicate" to text))

    private fun conflicts(left: ContextSpec, right: ContextSpec) =
        AutomationLint.analyze(listOf(profile(1, dim, left), profile(2, bright, right)), listOf(dim, bright))
            .findings.any { it.code == AutomationLintCode.PRIORITY_CONFLICT }

    @Test
    fun connectedCanHoldWhileOnANamedNetwork() {
        assertTrue(conflicts(state("wifi", "connected"), state("wifi", "Home")))
        assertTrue(conflicts(state("wifi_connected", "true"), state("wifi", "Home")))
        assertTrue(conflicts(state("wifi", "on"), state("wifi", "connected")))
    }

    @Test
    fun disconnectedCantHoldWhileOnAnyNetwork() {
        assertFalse(conflicts(state("wifi", "disconnected"), state("wifi", "Home")))
        assertFalse(conflicts(state("wifi_connected", "false"), state("wifi", "connected")))
        assertTrue(conflicts(state("wifi", "off"), state("wifi_connected", "false")))
    }

    @Test
    fun aPredicatePatternHoldingAnEqualsSignIsStillAPattern() {
        // Read as matching reads it: `a=*` is the pattern, not `wifi ~ a` equal to `*`.
        assertTrue(conflicts(predicate("wifi ~ a=*"), predicate("wifi ~ a=b")))
    }
}
