package com.opentasker.core.engine

import com.opentasker.core.model.Profile
import com.opentasker.core.storage.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `%PENABLED` and `%PACTIVE` list profiles by name, so renaming one reloads the engine's profile
 * list. The reload keeps the profile's matcher, since the name has nothing to do with matching.
 */
class ProfileRenameReloadTest {
    private fun entity(name: String) =
        ProfileEntity(id = 1, name = name, enabled = true, enterTaskId = 1, exitTaskId = null, cooldownSec = 0, contextsJson = "[]")

    @Test
    fun aRenameChangesTheReloadSignature() {
        assertNotEquals(profileRegistrySignature(listOf(entity("Home"))), profileRegistrySignature(listOf(entity("House"))))
    }

    @Test
    fun aRenamedProfileKeepsItsMatcher() {
        val running = Profile(id = 1, name = "Home", enterTaskId = 1)
        val renamed = running.copy(name = "House")

        val reload = planMatcherReload(mapOf(1L to running), listOf(renamed), mutableMapOf(), mutableSetOf())

        assertEquals(setOf(1L), reload.kept)
    }
}
