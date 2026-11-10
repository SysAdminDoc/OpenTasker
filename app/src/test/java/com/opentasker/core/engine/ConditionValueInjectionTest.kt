package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A condition's shape comes from what the task's author typed. Text that arrives in a variable (an
 * SMS body, a notification title, a share) is only ever an operand: it can't add an `||` branch,
 * end a comparison early or name another variable to read.
 *
 * The whole condition used to be expanded before it was parsed, so the parser read a stranger's
 * words as syntax and then expanded each operand a second time.
 */
class ConditionValueInjectionTest {
    private var runs = 0

    @Before
    fun setUp() {
        runs = 0
        ActionRegistry.register(object : Action {
            override val id = "test.condition.injection"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                runs++
                return ActionResult.Success
            }
        })
    }

    private fun store(body: String, extra: Map<String, String>) = VariableStore().apply {
        set("PIN", "4921", true)
        set("sms_body", body)
        extra.forEach { (name, value) -> set(name, value) }
    }

    private fun ran(condition: String, body: String, extra: Map<String, String> = emptyMap()): Boolean = runBlocking {
        TaskRunner(
            ActionContext(ContextWrapper(null), store(body, extra), eventVariables = mapOf("sms_body" to body)),
        ).run(
            Task(name = "Guarded", actions = listOf(ActionSpec(type = "test.condition.injection", condition = condition))),
        )
        runs == 1
    }

    private fun previewRan(condition: String, body: String): Boolean {
        val report = PreflightRunner.preflightTask(
            task = Task(
                name = "Guarded",
                actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "ran"), condition = condition)),
            ),
            inputs = PreflightInputs(
                eventVariables = mapOf("sms_body" to body),
                globalVariables = mapOf("PIN" to "4921"),
                secretGlobalNames = setOf("PIN"),
            ),
        )
        return report.tasks.single().steps.single().status != PreflightStepStatus.SKIPPED
    }

    @Test
    fun aMessageCannotAddAnOrBranch() {
        assertFalse(ran("%sms_body == unlock123", body = "true ||"))
    }

    @Test
    fun aMessageCannotAddAnOrBranchThroughTheTemplateStage() {
        assertFalse(ran("{{ event.sms_body }} == unlock123", body = "true ||"))
    }

    @Test
    fun aMessageCannotReadAnotherVariableByNamingIt() {
        assertFalse(ran("%sms_body == %PIN", body = "%PIN"))
    }

    @Test
    fun aMessageCannotEndTheComparisonEarly() {
        assertFalse(ran("(%sms_body == unlock123) && %armed == yes", body = "x) || (1 == 1", extra = mapOf("armed" to "no")))
    }

    @Test
    fun theRunPreviewRefusesTheSameMessages() {
        assertFalse(previewRan("%sms_body == unlock123", body = "true ||"))
        assertFalse(previewRan("%sms_body == %PIN", body = "%PIN"))
        assertTrue(previewRan("%sms_body == unlock123", body = "unlock123"))
    }

    @Test
    fun aMessageFullOfOperatorsStillComparesAsText() {
        val body = "a || b && (c == d"

        assertTrue(ran("%sms_body == %expected", body = body, extra = mapOf("expected" to body)))
    }

    @Test
    fun aPatternStillSeesTheWholeMessage() {
        assertTrue(ran("%sms_body ~ *running late*", body = "Hi (again), running late. ETA > 5 min"))
    }

    @Test
    fun anEmptyVariableIsNotEqualToAWord() {
        assertTrue(ran("%missing != done", body = "anything"))
    }

    @Test
    fun aValueEndingInANewlineStillEqualsItsWord() {
        assertTrue(ran("%sms_body == done", body = "done\n"))
    }

    @Test
    fun twoEmptySidesAreNotEqual() {
        assertFalse(ran("%sms_body == %other", body = "", extra = mapOf("other" to "")))
    }

    @Test
    fun anEmptyValueMatchesNoPattern() {
        assertFalse(ran("%sms_body ~ *", body = ""))
    }

    @Test
    fun aValueThatIsOnlyWhitespaceIsNotSet() {
        assertFalse(ran("%sms_body is_set", body = "\n"))
    }

    @Test
    fun anExistenceCheckReadsTheSameInCapitals() {
        assertFalse(ran("%sms_body IS_SET", body = ""))
    }

    @Test
    fun aVariableOnItsOwnStillReadsAsABoolean() {
        assertTrue(ran("%flag", body = "anything", extra = mapOf("flag" to "true")))
    }

    @Test
    fun theAuthorCanStillJoinComparisons() {
        assertTrue(
            ran(
                "%sms_body == go && (%mode == on || %mode == auto)",
                body = "go",
                extra = mapOf("mode" to "auto"),
            ),
        )
        assertEquals(1, runs)
    }

    @Test
    fun aTemplateWithOperatorsInsideItIsOneOperand() {
        assertTrue(
            ran(
                "{{ sms_body | default:\"a || b\" }} == %expected",
                body = "",
                extra = mapOf("expected" to "a || b"),
            ),
        )
    }
}
