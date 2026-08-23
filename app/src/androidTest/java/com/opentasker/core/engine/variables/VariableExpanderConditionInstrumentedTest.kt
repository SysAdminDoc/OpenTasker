package com.opentasker.core.engine.variables

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Matches/Doesn't Match (Tasker op codes 2/3) condition evaluator against Android's
 * real ICU-backed regex engine.
 *
 * Per CONTRIBUTING.md, this project has shipped desktop-JVM/Android ICU regex divergences three
 * times already; the JVM unit test suite runs on desktop `java.util.regex` and cannot see that
 * class of bug by construction. `VariableExpander.matchesGlob` builds its pattern at runtime from
 * imported Tasker wildcard data rather than from a fixed literal, so it isn't covered by the
 * generated `production-regex-patterns.txt` corpus either (that scanner finds compile-time regex
 * literals in source, and this pattern doesn't exist until a condition actually runs). This test
 * is the only place the real construct -- an `(?s)` inline dotall flag plus `Regex.escape()`
 * output, wired together by `String.split("*")` -- gets compiled by the engine devices actually
 * ship.
 */
@RunWith(AndroidJUnit4::class)
class VariableExpanderConditionInstrumentedTest {

    @Test
    fun matchesWildcardAgainstRealValueOnDevice() {
        val variables = VariableStore().apply { set("pa_do", "view_url") }
        assertTrue(variables.evaluateCondition("%pa_do ~ view_url"))
    }

    @Test
    fun matchesLeadingAndTrailingWildcardOnDevice() {
        // The shape actually produced by a real Tasker export for a JSON-substring guard
        // (verified against a live 6.6.20 backup): op=2, lhs a JSON blob, rhs `*"say":*`.
        val variables = VariableStore().apply { set("pa_json", "{\"say\":\"hello\"}") }
        assertTrue(variables.evaluateCondition("%pa_json ~ *\"say\":*"))
    }

    @Test
    fun doesNotMatchWildcardOnDevice() {
        val variables = VariableStore().apply { set("pa_do", "launch_app") }
        assertFalse(variables.evaluateCondition("%pa_do ~ view_url"))
    }

    @Test
    fun notMatchesOperatorNegatesCorrectlyOnDevice() {
        val variables = VariableStore().apply { set("pa_do", "launch_app") }
        assertTrue(variables.evaluateCondition("%pa_do !~ view_url"))
    }

    @Test
    fun wildcardValueContainingRegexMetacharactersIsTreatedLiterallyOnDevice() {
        // Everything except "*" must be escaped, not interpreted as regex syntax -- a value or
        // pattern containing ".", "(", "+", etc. (all realistic in imported %pa_json / %pa_url
        // condition data) must match literally.
        val variables = VariableStore().apply { set("pa_url", "https://example.com/a.b+c(d)") }
        assertTrue(variables.evaluateCondition("%pa_url ~ *example.com/a.b+c(d)*"))
        assertFalse(variables.evaluateCondition("%pa_url ~ *exampleXcom*"))
    }

    @Test
    fun isSetAndNotSetStillEvaluateCorrectlyOnDevice() {
        // Not regex-related, but cheap to confirm here too since this is the one place a real
        // ICU-backed VariableStore instance already exists for this evaluator.
        val unset = VariableStore()
        assertFalse(unset.evaluateCondition("%text is_set"))
        assertTrue(unset.evaluateCondition("%text not_set"))

        val set = VariableStore().apply { set("text", "set a timer") }
        assertTrue(set.evaluateCondition("%text is_set"))
        assertFalse(set.evaluateCondition("%text not_set"))
    }
}
