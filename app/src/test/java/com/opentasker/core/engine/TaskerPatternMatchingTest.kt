package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Matches and Doesn't Match conditions (`~` and `!~` here) use its simple pattern
 * matching (https://tasker.joaoapps.com/userguide/en/matching.html): "a * will match any number of
 * any character", "a + will match one or more of any character", "/ means 'or'", "a ! at the very
 * start of a match means not", and matching is case-insensitive unless the pattern has a capital.
 * Its Matches Regex and Doesn't Match Regex (`~R` and `!~R`) find a regular expression anywhere in
 * the value. Each case is checked in a run and in Preflight.
 */
class TaskerPatternMatchingTest {
    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = RECORD
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("v")
                return ActionResult.Success
            }
        })
    }

    @After
    fun tearDown() {
        registerCoreRuntime()
    }

    /**
     * Sets `%Value` to [value], then has one step per pattern that runs only when
     * `%Value <operator> pattern` holds, and checks the run and the preview agree on which ran.
     */
    private fun assertRunsFor(value: String, operator: String, vararg cases: Pair<String, Boolean>) {
        val task = Task(
            name = "Patterns",
            actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%Value", "value" to value))) +
                cases.map { (pattern, _) -> ActionSpec(type = RECORD, args = mapOf("v" to pattern), condition = "%Value $operator $pattern") },
        )
        val expected = cases.filter { it.second }.map { it.first }

        recorded.clear()
        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(task) }
        assertTrue(report.results.toString(), report.success)
        assertEquals("run, %Value = $value", expected, recorded.toList())

        val previewed = PreflightRunner.preflightTask(task).tasks.single().steps
            .filter { it.actionType == RECORD && it.status != PreflightStepStatus.SKIPPED }
            .map { it.expandedArguments.getValue("v") }
        assertEquals("preview, %Value = $value", expected, previewed)
    }

    @Test
    fun aSlashSeparatesAlternatives() {
        assertRunsFor("work", "~", "home/work" to true, "home/office" to false, "/work/" to true)
        assertRunsFor("office", "~", "home/work" to false, "home/*fice" to true)
        assertRunsFor("work", "!~", "home/work" to false, "home/office" to true)
    }

    @Test
    fun aPlusIsOneOrMoreCharacters() {
        assertRunsFor("a", "~", "a+" to false, "a*" to true, "+" to true)
        assertRunsFor("ab", "~", "a+" to true, "+b" to true, "a+b" to false)
        assertRunsFor("axb", "~", "a+b" to true, "a*b" to true)
    }

    @Test
    fun aLeadingBangTurnsTheWholePatternAround() {
        assertRunsFor("com.android.chrome", "~", "!*chrome*" to false, "!*firefox*" to true)
        // The ! covers every alternative, not just the first one.
        assertRunsFor("work", "~", "!home/work" to false)
        assertRunsFor("cafe", "~", "!home/work" to true)
        // Only at the very start: anywhere else it's an ordinary character.
        assertRunsFor("hey!", "~", "hey!" to true, "*!" to true)
    }

    @Test
    fun aPatternWithNoCapitalIgnoresCase() {
        assertRunsFor("On", "~", "on" to true, "On" to true, "ON" to false)
        assertRunsFor("ON", "~", "on" to true, "o*" to true)
        assertRunsFor("work", "~", "Work" to false)
        // Each alternative decides for itself.
        assertRunsFor("WORK", "~", "Home/work" to true)
        assertRunsFor("home", "~", "Home/work" to false)
    }

    @Test
    fun theWholeValueHasToMatch() {
        assertRunsFor("my home", "~", "home" to false, "*home" to true, "my*" to true)
    }

    @Test
    fun aWildcardRunAtEitherEndCountsOnce() {
        // Tasker builds its pattern from the literals between wildcards, so at either end only the
        // outermost wildcard counts: *+a reads as *a, and a+* as a*.
        assertRunsFor("a", "~", "*+a" to true, "a+*" to true, "+*a" to false)
        assertRunsFor("ab", "~", "a**b" to true, "a+*b" to false)
        // A pattern of wildcards alone keeps both ends.
        assertRunsFor("a", "~", "++" to false, "**" to true)
        assertRunsFor("ab", "~", "++" to true)
    }

    @Test
    fun aBangWorksTheSameUnderDoesntMatch() {
        assertRunsFor("work", "!~", "!home/work" to true)
        assertRunsFor("cafe", "!~", "!home/work" to false)
    }

    @Test
    fun anEmptyValueMatchesNothingButANegatedPatternStillHolds() {
        // `~ *` stays false for an empty value, while `~ !foo` and `!~ foo` say the same thing.
        assertRunsFor("", "~", "*" to false, "!foo" to true, "!*" to false)
        assertRunsFor("", "!~", "foo" to true, "!*" to true, "*" to false, "a//b" to false)
    }

    @Test
    fun regexCharactersInAPatternAreJustCharacters() {
        assertRunsFor("a.b", "~", "a.b" to true)
        assertRunsFor("axb", "~", "a.b" to false)
        assertRunsFor("f(x)?\$y\\z", "~", "f(x)?\$y\\z" to true, "f*z" to true)
        assertRunsFor("fx\$y\\z", "~", "f(x)?\$y\\z" to false)
    }

    @Test
    fun aRegexConditionNeedsOnlyPartOfTheValueToMatch() {
        assertRunsFor(
            "Hello World", "~R",
            "lo W" to true, "^Hello" to true, "^World" to false, "hello" to false, "(?i)hello" to true, "o+ W" to true,
        )
        assertRunsFor("Hello World", "!~R", "^Hello" to false, "^World" to true)
    }

    @Test
    fun aMatchesPatternCanBeARegex() {
        // Tasker reads a pattern starting ~R as a regex, and !~R as one turned around.
        assertRunsFor("Hello World", "~", "~R^Hel" to true, "~R^World" to false, "!~R^Hel" to false, "!~R^World" to true)
        assertRunsFor("Hello World", "!~", "~R^Hel" to false)
    }

    @Test
    fun aRegexThisAppCantRunMatchesNothing() {
        // Tasker's Java engine runs a lookahead or a backreference, but RE2 doesn't, so here the
        // pattern matches nothing. That's a known difference, and the import notes such a pattern.
        assertRunsFor("abab", "~R", "(ab)\\1" to false, "a(?=b)" to false, "ab" to true)
        assertRunsFor("abab", "!~R", "(ab)\\1" to true)
    }

    private companion object {
        const val RECORD = "test.pattern.record"
    }
}
