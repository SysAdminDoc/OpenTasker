package com.opentasker.core.engine

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which matchers a profile reload keeps, which it builds again, and which of those start out on.
 * A kept matcher doesn't read its contexts again, so a source's stand-in first event can't flip it.
 */
class ProfileReloadPlanTest {
    private val atHome = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Home")))
    private val atWork = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Work")))

    private fun profile(id: Long, contexts: List<ContextSpec> = atHome) =
        Profile(id = id, name = "profile $id", contexts = contexts, enterTaskId = id * 10)

    @Test
    fun anUneditedProfileKeepsItsMatcherWhetherOnOrOff() {
        val on = profile(1)
        val off = profile(2)
        val matched = mutableMapOf(1L to on)
        val dispatched = mutableSetOf(1L)

        val plan = planMatcherReload(mapOf(1L to on, 2L to off), listOf(on, off), matched, dispatched)

        assertEquals(MatcherReload(kept = setOf(1L, 2L), stillOn = emptySet()), plan)
        assertEquals(mapOf(1L to on), matched)
        assertEquals(setOf(1L), dispatched)
    }

    @Test
    fun editingWhatRunsAndNotWhenKeepsTheMatcherAndTheEditedProfile() {
        val before = profile(1)
        val edited = before.copy(
            name = "renamed",
            enterTaskId = 99,
            exitTaskId = 98,
            priority = 5,
            cooldownSec = 30,
            group = "evening",
        )
        val matched = mutableMapOf(1L to before)
        val dispatched = mutableSetOf(1L)

        val plan = planMatcherReload(mapOf(1L to before), listOf(edited), matched, dispatched)

        assertEquals(MatcherReload(kept = setOf(1L), stillOn = emptySet()), plan)
        assertEquals(mapOf(1L to edited), matched)
    }

    @Test
    fun editingTheContextsOfAProfileThatRanBuildsItAgainStillOn() {
        val before = profile(1)
        val edited = before.copy(contexts = atWork)
        val matched = mutableMapOf(1L to before)
        val dispatched = mutableSetOf(1L)

        val plan = planMatcherReload(mapOf(1L to before), listOf(edited), matched, dispatched)

        assertEquals(MatcherReload(kept = emptySet(), stillOn = setOf(1L)), plan)
        assertEquals(mapOf(1L to edited), matched)
    }

    @Test
    fun aGracePeriodEditBuildsItAgain() {
        val before = profile(1)

        val plan = planMatcherReload(mapOf(1L to before), listOf(before.copy(gracePeriodSec = 60)), mutableMapOf(), mutableSetOf())

        assertEquals(MatcherReload(kept = emptySet(), stillOn = emptySet()), plan)
    }

    private val winner = profile(1).copy(priority = 10)
    private val heldBack = profile(2)

    private fun reloadWithAProfileHeldBack(vararg profiles: Profile): Pair<MatcherReload, Map<Long, Profile>> {
        val matched = mutableMapOf(1L to winner, 2L to heldBack)
        val dispatched = mutableSetOf(1L)
        return planMatcherReload(mapOf(1L to winner, 2L to heldBack), profiles.asList(), matched, dispatched) to matched
    }

    @Test
    fun aProfileHeldBackByPriorityKeepsItsMatcherAndStaysHeldBack() {
        val (plan, matched) = reloadWithAProfileHeldBack(winner, heldBack)

        assertEquals(MatcherReload(kept = setOf(1L, 2L), stillOn = emptySet(), released = emptyList()), plan)
        assertEquals(mapOf(1L to winner, 2L to heldBack), matched)
    }

    @Test
    fun aPriorityEditThatLiftsTheHoldReleasesTheEditedProfile() {
        val raised = heldBack.copy(priority = 20)

        val (plan, _) = reloadWithAProfileHeldBack(winner, raised)

        assertEquals(MatcherReload(kept = setOf(1L, 2L), stillOn = emptySet(), released = listOf(raised)), plan)
    }

    @Test
    fun aHeldBackProfileIsReleasedWhenWhatOutranksItIsNoLongerEnabled() {
        val (plan, matched) = reloadWithAProfileHeldBack(heldBack)

        assertEquals(MatcherReload(kept = setOf(2L), stillOn = emptySet(), released = listOf(heldBack)), plan)
        assertEquals(mapOf(2L to heldBack), matched)
    }

    @Test
    fun aHeldBackProfileWhoseContextsWereEditedStartsOff() {
        val (plan, matched) = reloadWithAProfileHeldBack(winner, heldBack.copy(contexts = atWork))

        assertEquals(MatcherReload(kept = setOf(1L), stillOn = emptySet(), released = emptyList()), plan)
        assertEquals(mapOf(1L to winner), matched)
    }

    @Test
    fun aProfileNoLongerEnabledIsDropped() {
        val gone = profile(3)
        val stays = profile(4)
        val matched = mutableMapOf(3L to gone, 4L to stays)
        val dispatched = mutableSetOf(3L, 4L)

        val plan = planMatcherReload(mapOf(3L to gone, 4L to stays), listOf(stays), matched, dispatched)

        assertEquals(MatcherReload(kept = setOf(4L), stillOn = emptySet()), plan)
        assertEquals(mapOf(4L to stays), matched)
        assertEquals(setOf(4L), dispatched)
    }

    @Test
    fun aProfileThatRanWithoutARunningMatcherIsBuiltAgainStillOn() {
        val ran = profile(1)
        val matched = mutableMapOf(1L to ran)
        val dispatched = mutableSetOf(1L)

        val plan = planMatcherReload(emptyMap(), listOf(ran), matched, dispatched)

        assertEquals(MatcherReload(kept = emptySet(), stillOn = setOf(1L)), plan)
    }

    @Test
    fun aNewProfileStartsOff() {
        val plan = planMatcherReload(emptyMap(), listOf(profile(5)), mutableMapOf(), mutableSetOf())

        assertEquals(MatcherReload(kept = emptySet(), stillOn = emptySet()), plan)
    }
}
