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
 * The overlap check behind the priority conflict reads a Wi-Fi name pattern (`~`) as something
 * that can match many names, and reads a predicate the way matching does.
 */
class AutomationLintWifiPatternTest {
    private val dim = Task(id = 10, name = "Dim", priority = 5, actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "20"))))
    private val bright = Task(id = 11, name = "Bright", priority = 5, actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("value" to "90"))))

    private fun profile(id: Long, task: Task, context: ContextSpec) =
        Profile(id = id, name = "P$id", enabled = true, contexts = listOf(context), enterTaskId = task.id)

    private fun wifi(operator: String, value: String) =
        ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "operator" to operator, "value" to value))

    private fun predicate(text: String) = ContextSpec(ContextType.STATE, mapOf("predicate" to text))

    private fun conflicts(left: ContextSpec, right: ContextSpec) =
        AutomationLint.analyze(listOf(profile(1, dim, left), profile(2, bright, right)), listOf(dim, bright))
            .findings.any { it.code == AutomationLintCode.PRIORITY_CONFLICT }

    @Test
    fun aPatternThatLooksLikeANumberCanStillMatchOtherNames() {
        // `+` is one or more of anything, so `+5` matches a network called 15.
        assertTrue(conflicts(wifi("~", "+5"), wifi("=", "15")))
        assertTrue(conflicts(predicate("wifi ~ +5"), predicate("wifi = 15")))
    }

    @Test
    fun twoPlainNamesStillCantOverlap() {
        assertFalse(conflicts(wifi("=", "Home"), wifi("=", "Office")))
        assertFalse(conflicts(predicate("battery >= 50"), predicate("battery <= 20")))
    }
}
