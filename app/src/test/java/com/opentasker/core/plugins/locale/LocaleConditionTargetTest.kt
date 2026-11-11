package com.opentasker.core.plugins.locale

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleConditionTargetTest {
    @Test
    fun profileAndContextBundlesRoundTripAsTypedSpecs() {
        val profile = LocaleConditionTarget.parse(
            LocaleConditionTarget.profileActive(42, "Work", "profile-grant"),
        )
        val context = LocaleConditionTarget.parse(
            LocaleConditionTarget.contextSatisfied(42, "Work", 2, "Plugin condition", "context-grant"),
        )

        assertEquals(LocaleConditionKind.PROFILE_ACTIVE, profile.kind)
        assertEquals(42L, profile.profileId)
        assertEquals(LocaleConditionKind.CONTEXT_SATISFIED, context.kind)
        assertEquals(2, context.contextIndex)
        assertEquals("profile-grant", profile.grantToken)
        assertEquals("context-grant", context.grantToken)
    }

    @Test
    fun variableComparisonNeverIncludesExpectedValueInTheBlurb() {
        val values = LocaleConditionTarget.variableCompare(
            variableName = "Mode",
            projectId = 1,
            operator = LocaleConditionOperator.EQUALS,
            expectedValue = "private-value",
            grantToken = "grant-token",
        )
        val spec = LocaleConditionTarget.parse(values)

        assertEquals("private-value", spec.expectedValue)
        assertFalse(LocaleConditionTarget.buildBlurb(spec).contains("private-value"))
    }

    @Test
    fun malformedAndOversizedConditionValuesFailClosed() {
        val malformed = runCatching {
            LocaleConditionTarget.parse(
                mapOf(
                    LocaleConditionTarget.BUNDLE_KEY_SCHEMA to LocaleConditionTarget.SCHEMA_VERSION,
                    LocaleConditionTarget.BUNDLE_KEY_KIND to LocaleConditionKind.VARIABLE_COMPARE.wireName,
                    LocaleConditionTarget.BUNDLE_KEY_VARIABLE_NAME to "Mode",
                    LocaleConditionTarget.BUNDLE_KEY_OPERATOR to LocaleConditionOperator.EQUALS.wireName,
                ),
            )
        }.exceptionOrNull()
        val oversized = runCatching {
            LocaleConditionTarget.variableCompare(
                "Mode",
                1,
                LocaleConditionOperator.EQUALS,
                "x".repeat(LocaleConditionTarget.MAX_EXPECTED_VALUE_BYTES + 1),
                "grant-token",
            )
        }.exceptionOrNull()

        assertTrue(malformed is IllegalArgumentException || malformed is IllegalStateException)
        assertTrue(oversized is IllegalArgumentException)
    }

    @Test
    fun evaluatorDistinguishesSatisfiedUnsatisfiedAndUnknown() {
        val profileSpec = LocaleConditionTarget.parse(LocaleConditionTarget.profileActive(7, "Home", "profile-grant"))
        val contextSpec = LocaleConditionTarget.parse(
            LocaleConditionTarget.contextSatisfied(7, "Home", 0, "State", "context-grant"),
        )
        val variableSpec = LocaleConditionTarget.parse(
            LocaleConditionTarget.variableCompare("Mode", 1, LocaleConditionOperator.CONTAINS, "work", "grant-token"),
        )

        assertEquals(
            LocalePluginConditionState.Satisfied,
            LocaleConditionEvaluator.evaluate(profileSpec, LocaleConditionSnapshot(profileExists = true, profileEnabled = true, profileActive = true)),
        )
        assertEquals(
            LocalePluginConditionState.Unsatisfied,
            LocaleConditionEvaluator.evaluate(profileSpec, LocaleConditionSnapshot(profileExists = true, profileEnabled = true, profileActive = false)),
        )
        assertEquals(
            LocalePluginConditionState.Unknown,
            LocaleConditionEvaluator.evaluate(contextSpec, LocaleConditionSnapshot(profileExists = true, profileEnabled = true)),
        )
        assertEquals(
            LocalePluginConditionState.Satisfied,
            LocaleConditionEvaluator.evaluate(variableSpec, LocaleConditionSnapshot(variableExists = true, variableValue = "work-hours")),
        )
        assertEquals(
            LocalePluginConditionState.Unknown,
            LocaleConditionEvaluator.evaluate(variableSpec, LocaleConditionSnapshot(variableExists = true, variableSecret = true, variableValue = "work-hours")),
        )
    }

    /**
     * The query receiver is exported without a permission, so a bundle naming a variable the user
     * never exposed must not parse into a readable spec at all.
     */
    @Test
    fun variableComparisonWithoutAReadGrantIsRefused() {
        val grantless = runCatching {
            LocaleConditionTarget.parse(
                mapOf(
                    LocaleConditionTarget.BUNDLE_KEY_SCHEMA to LocaleConditionTarget.SCHEMA_VERSION,
                    LocaleConditionTarget.BUNDLE_KEY_KIND to LocaleConditionKind.VARIABLE_COMPARE.wireName,
                    LocaleConditionTarget.BUNDLE_KEY_VARIABLE_NAME to "ApiToken",
                    LocaleConditionTarget.BUNDLE_KEY_VARIABLE_PROJECT_ID to "1",
                    LocaleConditionTarget.BUNDLE_KEY_OPERATOR to LocaleConditionOperator.STARTS_WITH.wireName,
                    LocaleConditionTarget.BUNDLE_KEY_EXPECTED_VALUE to "sk-",
                ),
            )
        }.exceptionOrNull()

        assertTrue(grantless is IllegalArgumentException)
    }

    @Test
    fun aGrantCoversOnlyTheComparisonItWasIssuedFor() {
        val issued = LocaleConditionGrantStore.comparisonKey(1, "Mode", LocaleConditionOperator.EQUALS, "home")

        assertTrue(isConditionGrantValid(issued, "token", issued))
        assertFalse(
            "a host can't change the expected value",
            isConditionGrantValid(issued, "token", LocaleConditionGrantStore.comparisonKey(1, "Mode", LocaleConditionOperator.EQUALS, "h")),
        )
        assertFalse(
            "a host can't change the operator",
            isConditionGrantValid(issued, "token", LocaleConditionGrantStore.comparisonKey(1, "Mode", LocaleConditionOperator.STARTS_WITH, "home")),
        )
    }

    @Test
    fun anOlderVariableGrantIsPinnedToTheFirstComparisonAndNoOther() {
        val legacy = LocaleConditionGrantStore.variableKey(1, "Mode")
        val first = LocaleConditionGrantStore.comparisonKey(1, "Mode", LocaleConditionOperator.EQUALS, "home")
        val second = LocaleConditionGrantStore.comparisonKey(1, "Mode", LocaleConditionOperator.EQUALS, "h")

        assertTrue(shouldPinConditionGrant(legacy, first))
        assertFalse("once pinned it isn't a variable grant anymore", shouldPinConditionGrant(first, second))
        assertFalse(
            "a name that only starts the same is another variable",
            shouldPinConditionGrant(LocaleConditionGrantStore.variableKey(1, "Mo"), first),
        )
        assertFalse(shouldPinConditionGrant(null, first))
        assertFalse(
            "a profile grant is never rebound",
            shouldPinConditionGrant(LocaleConditionGrantStore.profileKey(5), LocaleConditionGrantStore.contextKey(5, 0)),
        )
    }

    @Test
    fun aContextGrantEndsWhenItsPositionHoldsAnotherContext() {
        val wifi = ContextSpec(ContextType.STATE, mapOf("key" to "wifi_connected"))
        val work = ContextSpec(ContextType.TIME, mapOf("from" to "09:00", "to" to "17:00"))
        val maps = ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.maps"))
        fun ended(previous: List<ContextSpec>, next: List<ContextSpec>) = LocaleConditionGrantStore.changedContextKeys(5, previous, next)
        fun at(vararg positions: Int) = positions.map { LocaleConditionGrantStore.contextKey(5, it) }

        assertEquals("a reorder", at(0, 1), ended(listOf(wifi, work), listOf(work, wifi)))
        assertEquals("removing the first moves the rest up", at(0, 1, 2), ended(listOf(wifi, work, maps), listOf(work, maps)))
        assertEquals("removing the last", at(2), ended(listOf(wifi, work, maps), listOf(wifi, work)))
        assertEquals("an undo that puts the first back", at(0, 1), ended(listOf(work, maps), listOf(wifi, work, maps)))
        assertEquals("an edit", at(1), ended(listOf(wifi, work), listOf(wifi, work.copy(invert = true))))
        assertEquals("one added at the end", emptyList<String>(), ended(listOf(wifi), listOf(wifi, work)))
        assertTrue(
            "every context grant of the profile shares one prefix, and another profile's don't",
            LocaleConditionGrantStore.contextKey(5, 12).startsWith(LocaleConditionGrantStore.profileContextsKey(5) + ":") &&
                !LocaleConditionGrantStore.contextKey(50, 0).startsWith(LocaleConditionGrantStore.profileContextsKey(5) + ":"),
        )
    }

    @Test
    fun aGrantAuthorizesOnlyTheVariableItWasIssuedFor() {
        val issued = LocaleConditionGrantStore.variableKey(1, "Mode")
        val other = LocaleConditionGrantStore.variableKey(1, "ApiToken")
        val crossProject = LocaleConditionGrantStore.variableKey(2, "Mode")

        assertTrue(isConditionGrantValid(issued, "token", issued))
        assertFalse(isConditionGrantValid(issued, "token", other))
        assertFalse(isConditionGrantValid(issued, "token", crossProject))
        assertFalse("a forged or revoked token has no stored binding", isConditionGrantValid(null, "token", issued))
        assertFalse("a bundle with no token is never authorized", isConditionGrantValid(issued, null, issued))
        assertFalse(isConditionGrantValid(issued, "  ", issued))
    }

    @Test
    fun everyConditionKindRequiresItsOwnGrantBinding() {
        val profile = LocaleConditionTarget.parse(
            LocaleConditionTarget.profileActive(7, "Home", "profile-grant"),
        )
        val context = LocaleConditionTarget.parse(
            LocaleConditionTarget.contextSatisfied(7, "Home", 0, "State", "context-grant"),
        )
        val variable = LocaleConditionTarget.parse(
            LocaleConditionTarget.variableCompare("Mode", 1, LocaleConditionOperator.EQUALS, "work", "variable-grant"),
        )

        assertTrue(
            isConditionGrantValid(
                LocaleConditionGrantStore.bindingKey(profile),
                profile.grantToken,
                LocaleConditionGrantStore.bindingKey(profile),
            ),
        )
        assertFalse(
            "An ungranted profile query must be unknown regardless of the profile state",
            isConditionGrantValid(null, profile.grantToken, LocaleConditionGrantStore.bindingKey(profile)),
        )
        assertTrue(
            isConditionGrantValid(
                LocaleConditionGrantStore.bindingKey(context),
                context.grantToken,
                LocaleConditionGrantStore.bindingKey(context),
            ),
        )
        assertFalse(
            isConditionGrantValid(
                LocaleConditionGrantStore.bindingKey(profile),
                context.grantToken,
                LocaleConditionGrantStore.bindingKey(context),
            ),
        )
        assertTrue(
            isConditionGrantValid(
                LocaleConditionGrantStore.bindingKey(variable),
                variable.grantToken,
                LocaleConditionGrantStore.bindingKey(variable),
            ),
        )
    }
}
