package com.opentasker.core.engine

import com.opentasker.core.plugins.locale.LocaleConditionEvaluator
import com.opentasker.core.plugins.locale.LocaleConditionKind
import com.opentasker.core.plugins.locale.LocaleConditionSnapshot
import com.opentasker.core.plugins.locale.LocaleConditionSpec
import com.opentasker.core.plugins.locale.LocalePluginContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LiveContextStateUnknownTest {
    @Before
    fun setUp() {
        AutomationLiveConditionState.clear()
    }

    @After
    fun tearDown() {
        AutomationLiveConditionState.clear()
    }

    @Test
    fun aContextThatHasNotBeenReadAnswersUnknown() {
        publishLiveContextState(7, 0, ContextMatchUpdate.initial(pulseContext = false))

        assertEquals(LocalePluginContract.RESULT_CONDITION_UNKNOWN, hostHears(7, 0))
    }

    @Test
    fun aContextThatTurnsUnknownStopsAnsweringItsLastValue() {
        publishLiveContextState(7, 0, update(matched = true))
        assertEquals(LocalePluginContract.RESULT_CONDITION_SATISFIED, hostHears(7, 0))

        publishLiveContextState(7, 0, update(matched = false, unknown = true))

        assertEquals(LocalePluginContract.RESULT_CONDITION_UNKNOWN, hostHears(7, 0))
    }

    @Test
    fun aKnownFalseStillAnswersUnsatisfied() {
        publishLiveContextState(7, 0, update(matched = false))

        assertEquals(LocalePluginContract.RESULT_CONDITION_UNSATISFIED, hostHears(7, 0))
    }

    @Test
    fun anEventThatHasNotComeYetIsAKnownNo() {
        publishLiveContextState(7, 1, ContextMatchUpdate.initial(pulseContext = true))

        assertEquals(LocalePluginContract.RESULT_CONDITION_UNSATISFIED, hostHears(7, 1))
    }

    private fun update(matched: Boolean, unknown: Boolean = false) =
        ContextMatchUpdate(matched = matched, pulseContext = false, pulseSequence = 0L, unknown = unknown)

    /** What [LocaleConditionQueryReceiver] answers for an enabled profile's context. */
    private fun hostHears(profileId: Long, contextIndex: Int): Int = LocaleConditionEvaluator.evaluate(
        LocaleConditionSpec(LocaleConditionKind.CONTEXT_SATISFIED, profileId = profileId, contextIndex = contextIndex),
        LocaleConditionSnapshot(
            profileExists = true,
            profileEnabled = true,
            contextMatched = AutomationLiveConditionState.contextState(profileId, contextIndex),
        ),
    ).resultCode
}
