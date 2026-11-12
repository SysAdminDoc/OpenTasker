package com.opentasker.core.engine

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A reload that builds a profile's matcher again and starts it off clears the profile's old "on",
 * since that matcher only reports going on. Kept matchers and profiles that stay on keep theirs.
 */
class MatcherReloadStartsOffTest {
    private fun profile(id: Long, config: String) =
        Profile(id = id, name = "P$id", enterTaskId = 1, contexts = listOf(ContextSpec(type = ContextType.STATE, config = mapOf("k" to config))))

    @Test
    fun aHeldBackProfileWhoseRulesChangedStartsOff() {
        val heldBack = profile(1, "a")
        val handedOff = profile(2, "a")
        val untouched = profile(3, "a")
        val running = listOf(heldBack, handedOff, untouched).associateBy { it.id }
        val edited = listOf(profile(1, "b"), profile(2, "b"), untouched)

        val reload = planMatcherReload(running, edited, mutableMapOf(1L to heldBack, 2L to handedOff), mutableSetOf(2L))

        assertEquals(listOf(1L), reload.startsOff(edited))
    }

    @Test
    fun aNewProfileStartsOff() {
        val reload = planMatcherReload(emptyMap(), listOf(profile(4, "a")), mutableMapOf(), mutableSetOf())

        assertEquals(listOf(4L), reload.startsOff(listOf(profile(4, "a"))))
    }
}
