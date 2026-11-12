package com.opentasker.core.engine

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A reload clears only an old "on" from a profile whose matcher starts over. A profile the engine
 * has no state for yet, like a new one or any on the first start, stays unknown, so a Locale host
 * asking about it hears unknown instead of a made-up "off".
 */
class MatcherReloadUnknownStateTest {
    private fun profile(id: Long, config: String) =
        Profile(id = id, name = "P$id", enterTaskId = 1, contexts = listOf(ContextSpec(type = ContextType.STATE, config = mapOf("k" to config))))

    @Before
    @After
    fun reset() = AutomationLiveConditionState.clear()

    @Test
    fun aNewProfileStaysUnknown() {
        val fresh = listOf(profile(4, "a"))

        planMatcherReload(emptyMap(), fresh, mutableMapOf(), mutableSetOf()).dropOldOn(fresh)

        assertNull(AutomationLiveConditionState.profileState(4))
    }

    @Test
    fun anEditedProfileThatWasOnGoesOffAndOneThatWasOffStaysOff() {
        val wasOn = profile(1, "a")
        val wasOff = profile(2, "a")
        AutomationLiveConditionState.updateProfile(1, true)
        AutomationLiveConditionState.updateProfile(2, false)
        val edited = listOf(profile(1, "b"), profile(2, "b"))

        planMatcherReload(listOf(wasOn, wasOff).associateBy { it.id }, edited, mutableMapOf(1L to wasOn), mutableSetOf())
            .dropOldOn(edited)

        assertEquals(false, AutomationLiveConditionState.profileState(1))
        assertEquals(false, AutomationLiveConditionState.profileState(2))
        assertEquals(emptyList<Long>(), AutomationLiveConditionState.activeProfileIds())
    }

    @Test
    fun aProfileStillOnKeepsItsOn() {
        val handedOff = profile(3, "a")
        AutomationLiveConditionState.updateProfile(3, true)
        val edited = listOf(profile(3, "b"))

        planMatcherReload(mapOf(3L to handedOff), edited, mutableMapOf(3L to handedOff), mutableSetOf(3L)).dropOldOn(edited)

        assertEquals(true, AutomationLiveConditionState.profileState(3))
    }
}
